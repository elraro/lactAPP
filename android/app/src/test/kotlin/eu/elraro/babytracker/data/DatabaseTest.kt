package eu.elraro.babytracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.data.local.EventEntity
import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.data.local.SyncStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() = db.close()

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun event(
        id: String,
        startedAt: String,
        type: String = "bottle",
        deletedAt: Long? = null,
    ) = EventEntity(
        id = id,
        babyId = "baby-1",
        type = type,
        startedAt = millis(startedAt),
        endedAt = null,
        timezone = "Europe/Madrid",
        amountMl = if (type == "bottle") 120 else null,
        milkKind = null,
        diaperKind = null,
        sleepKind = if (type == "sleep") "night" else null,
        weightG = null,
        heightMm = null,
        headMm = null,
        notes = null,
        version = 0,
        deletedAt = deletedAt,
    )

    @Test
    fun `babies are listed without tombstones and updated in place`() =
        runTest {
            val dao = db.babyDao()
            dao.upsert(BabyEntity("b1", "Vera", "2026-07-01", null, null, 1, null))
            dao.upsert(BabyEntity("b2", "Gone", "2025-01-01", null, null, 2, deletedAt = 5))
            dao.upsert(BabyEntity("b1", "Vera", "2026-07-01", "female", "note", 2, null))

            val babies = dao.observeAll().first()
            assertEquals(listOf("b1"), babies.map { it.id })
            assertEquals(2, babies.single().version)
            assertEquals("Gone", dao.get("b2")?.name)
        }

    @Test
    fun `a day lists its events newest first plus sleep still going on, without deleted ones`() =
        runTest {
            val dao = db.eventDao()
            // A day in Europe/Madrid (UTC+2): 2026-10-07T22:00Z until 2026-10-08T22:00Z.
            val from = millis("2026-10-07T22:00:00Z")
            val to = millis("2026-10-08T22:00:00Z")
            dao.upsert(event("before", "2026-10-07T21:59:59Z"))
            dao.upsert(event("first", "2026-10-07T22:00:00Z"))
            dao.upsert(event("noon", "2026-10-08T10:00:00Z"))
            dao.upsert(event("deleted", "2026-10-08T11:00:00Z", deletedAt = 1))
            dao.upsert(event("next-day", "2026-10-08T22:00:00Z"))
            // Night sleep that began the evening before and ended this morning.
            dao.upsert(
                event("night", "2026-10-07T19:30:00Z", type = "sleep").copy(endedAt = millis("2026-10-08T05:00:00Z")),
            )
            // Sleep that ended before the day began does not belong to it.
            dao.upsert(
                event("old-nap", "2026-10-07T15:00:00Z", type = "sleep").copy(endedAt = millis("2026-10-07T16:00:00Z")),
            )

            dao.observeTouching("baby-1", from, to).test {
                assertEquals(listOf("noon", "first", "night"), awaitItem().map { it.id })

                // The UI is driven by the table: a new row re-emits.
                dao.upsert(event("late", "2026-10-08T21:00:00Z"))
                assertEquals(listOf("late", "noon", "first", "night"), awaitItem().map { it.id })
            }
            assertNull(dao.get("missing"))
            // "Latest" is not limited to the day.
            assertEquals("next-day", dao.observeLatest("baby-1", "bottle").first()?.id)
        }

    @Test
    fun `overlap detection treats an open session as never ending`() =
        runTest {
            val dao = db.eventDao()
            dao.upsert(
                event("nap", "2026-10-08T10:00:00Z", type = "sleep").copy(endedAt = millis("2026-10-08T11:00:00Z")),
            )
            dao.upsert(event("open", "2026-10-08T19:00:00Z", type = "sleep"))

            suspend fun overlap(
                start: String,
                end: String?,
                exclude: String = "",
            ) = dao.findOverlappingSleep("baby-1", exclude, millis(start), end?.let(::millis) ?: Long.MAX_VALUE)?.id

            assertEquals("nap", overlap("2026-10-08T10:30:00Z", "2026-10-08T12:00:00Z"))
            // Touching end-to-start is not an overlap.
            assertNull(overlap("2026-10-08T11:00:00Z", "2026-10-08T12:00:00Z"))
            assertNull(overlap("2026-10-08T09:00:00Z", "2026-10-08T10:00:00Z"))
            assertEquals("open", overlap("2026-10-08T23:00:00Z", "2026-10-08T23:30:00Z"))
            assertEquals("nap", overlap("2026-10-08T09:00:00Z", null))
            // A session does not overlap itself when it is being edited.
            assertNull(overlap("2026-10-08T10:15:00Z", "2026-10-08T10:45:00Z", exclude = "nap"))
            assertEquals("open", dao.openSleep("baby-1")?.id)
        }

    @Test
    fun `pending mutations are sent in the order they were created`() =
        runTest {
            val dao = db.pendingMutationDao()

            fun mutation(id: String) =
                PendingMutationEntity(
                    mutationId = id,
                    entity = "event",
                    entityId = "e-$id",
                    op = "upsert",
                    fieldsJson = "{}",
                    baseVersion = 0,
                    createdAt = 0,
                )
            listOf("m1", "m2", "m3").forEach { dao.insert(mutation(it)) }

            val batch = dao.nextBatch(limit = 2)
            assertEquals(listOf("m1", "m2"), batch.map { it.mutationId })

            dao.markSent(batch.map { it.sequence })
            dao.delete("m1")
            dao.markRejected("m2", "validation_failed")

            // A rejected mutation stays for the user to see but is not sent again.
            assertEquals(listOf("m3"), dao.nextBatch(limit = 10).map { it.mutationId })
            assertEquals(1, dao.observePendingCount().first())
        }

    @Test
    fun `sync state is a single row`() =
        runTest {
            val dao = db.syncStateDao()
            assertNull(dao.get())
            dao.upsert(SyncStateEntity(cursor = "c1", lastSuccessAt = 1, serverTimeOffsetMs = 0))
            dao.upsert(SyncStateEntity(cursor = "c2", lastSuccessAt = 2, serverTimeOffsetMs = -40))
            assertEquals("c2", dao.get()?.cursor)
        }
}
