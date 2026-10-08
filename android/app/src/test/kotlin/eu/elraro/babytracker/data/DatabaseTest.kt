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
    fun `events of a day are newest first, half-open, without deleted ones`() =
        runTest {
            val dao = db.eventDao()
            // A day in Europe/Madrid (UTC+2): 2026-10-07T22:00Z until 2026-10-08T22:00Z.
            val from = millis("2026-10-07T22:00:00Z")
            val to = millis("2026-10-08T22:00:00Z")
            dao.upsert(event("before", "2026-10-07T21:59:59Z"))
            dao.upsert(event("first", "2026-10-07T22:00:00Z"))
            dao.upsert(event("noon", "2026-10-08T10:00:00Z"))
            dao.upsert(event("deleted", "2026-10-08T11:00:00Z", deletedAt = 1))
            dao.upsert(event("night", "2026-10-08T19:30:00Z", type = "sleep"))
            dao.upsert(event("next-day", "2026-10-08T22:00:00Z"))

            dao.observeStartedBetween("baby-1", from, to).test {
                assertEquals(listOf("night", "noon", "first"), awaitItem().map { it.id })

                // The UI is driven by the table: a new row re-emits.
                dao.upsert(event("late", "2026-10-08T21:00:00Z"))
                assertEquals(listOf("late", "night", "noon", "first"), awaitItem().map { it.id })
            }
            assertNull(dao.get("missing"))
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
