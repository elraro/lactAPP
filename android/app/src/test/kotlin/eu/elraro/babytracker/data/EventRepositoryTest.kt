package eu.elraro.babytracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.data.repository.SaveResult
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventProblem
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.SleepKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class EventRepositoryTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val time = FakeTime()
    private val repo = EventRepository(db, time)
    private val baby = "baby-1"

    @After
    fun tearDown() = db.close()

    private suspend fun queue(): List<PendingMutationEntity> = db.pendingMutationDao().nextBatch(1000)

    private fun PendingMutationEntity.fields(): JsonObject = Json.parseToJsonElement(fieldsJson!!).jsonObject

    private fun SaveResult.event(): Event = (this as SaveResult.Saved).event

    @Test
    fun `a bottle is stored and queued with exactly the fields the server needs`() =
        runTest {
            val saved = repo.logBottle(baby, 120).event()

            assertEquals(saved, repo.observeLatest(baby, EventType.BOTTLE).first())
            assertEquals(time.now, saved.startedAt)
            assertEquals("Europe/Madrid", saved.timezone)
            assertEquals(0, saved.version)

            val mutation = queue().single()
            assertEquals(
                listOf("event", saved.id, "upsert", 0),
                mutation.run { listOf(entity, entityId, op, baseVersion) },
            )
            assertEquals(false, mutation.sent)
            assertEquals(
                mapOf(
                    "baby_id" to baby,
                    "type" to "bottle",
                    "started_at" to "2026-10-08T12:30:00Z",
                    "timezone" to "Europe/Madrid",
                    "amount_ml" to "120",
                ),
                mutation.fields().mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun `an edit queues only the changed fields and a cleared field as null`() =
        runTest {
            val created =
                repo
                    .create(
                        repo.newEvent(baby, EventType.BOTTLE).copy(amountMl = 120, notes = "sleepy"),
                    ).event()
            // Pretend the server has acknowledged it as version 3.
            db.eventDao().upsert(db.eventDao().get(created.id)!!.copy(version = 3))

            // The UI copy still says version 0; the stored version must win.
            repo.update(created.copy(amountMl = 150, notes = " ")).event()

            val edit = queue().last()
            assertEquals(3, edit.baseVersion)
            assertEquals(setOf("amount_ml", "notes"), edit.fields().keys)
            assertEquals("150", edit.fields()["amount_ml"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, edit.fields()["notes"])
            assertEquals(3, db.eventDao().get(created.id)!!.version)

            // Saving without changes queues nothing.
            repo.update(repo.observeLatest(baby, EventType.BOTTLE).first()!!)
            assertEquals(2, queue().size)
        }

    @Test
    fun `invalid events are refused and leave no trace`() =
        runTest {
            assertEquals(SaveResult.Invalid(EventProblem.AMOUNT_OUT_OF_RANGE), repo.logBottle(baby, 0))
            assertEquals(
                SaveResult.NotFound,
                repo.update(repo.newEvent(baby, EventType.DIAPER).copy(diaperKind = DiaperKind.WET)),
            )
            assertTrue(queue().isEmpty())
            assertNull(repo.observeLatest(baby, EventType.BOTTLE).first())
        }

    @Test
    fun `delete hides the event and restore brings it back, each queued`() =
        runTest {
            val diaper = repo.logDiaper(baby, DiaperKind.BOTH).event()

            repo.delete(diaper.id)
            assertNull(repo.observeLatest(baby, EventType.DIAPER).first())
            repo.delete(diaper.id) // a second delete is a no-op
            assertEquals(SaveResult.NotFound, repo.update(diaper.copy(diaperKind = DiaperKind.WET)))

            assertEquals(diaper, repo.restore(diaper.id).event())
            assertEquals(diaper, repo.observeLatest(baby, EventType.DIAPER).first())
            assertEquals(listOf("upsert", "delete", "restore"), queue().map { it.op })
            assertEquals(SaveResult.NotFound, repo.restore("missing"))
        }

    @Test
    fun `sleep and wake open and close one session`() =
        runTest {
            time.now = Instant.parse("2026-10-08T08:15:00Z") // 10:15 in Madrid
            val started = repo.startSleep(baby).event()
            assertEquals(SleepKind.NAP, started.sleepKind)
            assertEquals(started, repo.observeOpenSleep(baby).first())

            // SLEEP while already asleep is refused.
            assertEquals(SaveResult.OverlapsSleep(started), repo.startSleep(baby))

            time.advance(Duration.ofMinutes(50))
            val ended = repo.endSleep(baby).event()
            assertEquals(started.id, ended.id)
            assertEquals(Instant.parse("2026-10-08T09:05:00Z"), ended.endedAt)
            assertNull(repo.observeOpenSleep(baby).first())
            assertEquals(SaveResult.NotFound, repo.endSleep(baby))

            val wake = queue().last()
            assertEquals(
                mapOf("ended_at" to "2026-10-08T09:05:00Z"),
                wake.fields().mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun `bedtime is suggested as night and may cross midnight`() =
        runTest {
            time.now = Instant.parse("2026-10-08T19:40:00Z") // 21:40 in Madrid
            assertEquals(SleepKind.NIGHT, repo.startSleep(baby).event().sleepKind)
            time.now = Instant.parse("2026-10-09T05:10:00Z")
            val night = repo.endSleep(baby).event()

            val zone = ZoneId.of("Europe/Madrid")
            val day = LocalDate.of(2026, 10, 9)
            val from = day.atStartOfDay(zone).toInstant()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant()
            // The night belongs to the 9th as well, although it started on the 8th.
            assertEquals(listOf(night), repo.observeTouching(baby, from, to).first())
        }

    @Test
    fun `waking never produces a negative duration`() =
        runTest {
            val started = repo.startSleep(baby).event()
            time.advance(Duration.ofMinutes(-5)) // the device clock was moved back
            assertEquals(started.startedAt, repo.endSleep(baby).event().endedAt)
        }

    @Test
    fun `a sleep session that overlaps another is not saved`() =
        runTest {
            fun nap(
                start: String,
                end: String?,
            ) = repo.newEvent(baby, EventType.SLEEP).copy(
                startedAt = Instant.parse(start),
                endedAt = end?.let(Instant::parse),
                sleepKind = SleepKind.NAP,
            )
            val morning = repo.create(nap("2026-10-08T08:00:00Z", "2026-10-08T09:00:00Z")).event()
            val before = queue().size

            assertEquals(
                SaveResult.OverlapsSleep(morning),
                repo.create(nap("2026-10-08T08:30:00Z", "2026-10-08T09:30:00Z")),
            )
            assertEquals(SaveResult.OverlapsSleep(morning), repo.create(nap("2026-10-08T07:00:00Z", null)))
            assertEquals(before, queue().size)

            // Back-to-back sessions are fine, and a session may be edited within its own slot.
            val second = repo.create(nap("2026-10-08T09:00:00Z", "2026-10-08T10:00:00Z")).event()
            assertTrue(repo.update(morning.copy(startedAt = Instant.parse("2026-10-08T08:10:00Z"))) is SaveResult.Saved)
            assertEquals(
                SaveResult.OverlapsSleep(second),
                repo.update(morning.copy(endedAt = Instant.parse("2026-10-08T09:30:00Z"))),
            )

            // Undoing a deletion must not create an overlap either.
            repo.delete(second.id)
            val replacement = repo.create(nap("2026-10-08T09:15:00Z", "2026-10-08T09:45:00Z")).event()
            assertEquals(SaveResult.OverlapsSleep(replacement), repo.restore(second.id))
        }

    @Test
    fun `days follow the zone rules when the clocks change`() =
        runTest {
            val zone = ZoneId.of("Europe/Madrid")

            suspend fun bottlesOn(day: LocalDate): Int {
                val from = day.atStartOfDay(zone).toInstant()
                val to = day.plusDays(1).atStartOfDay(zone).toInstant()
                return repo.observeTouching(baby, from, to).first().size
            }

            suspend fun bottleAt(utc: String) {
                time.now = Instant.parse(utc)
                repo.logBottle(baby, 100)
            }
            // 25 October 2026 has 25 hours in Madrid: it runs from 22:00Z to 23:00Z.
            bottleAt("2026-10-24T22:00:00Z") // 00:00 on the 25th
            bottleAt("2026-10-25T00:30:00Z") // 02:30, first time
            bottleAt("2026-10-25T01:30:00Z") // 02:30, second time
            bottleAt("2026-10-25T22:30:00Z") // 23:30, still the 25th
            bottleAt("2026-10-25T23:00:00Z") // 00:00 on the 26th
            assertEquals(4, bottlesOn(LocalDate.of(2026, 10, 25)))
            assertEquals(1, bottlesOn(LocalDate.of(2026, 10, 26)))

            // 29 March 2026 has 23 hours: it runs from 23:00Z to 22:00Z.
            bottleAt("2026-03-28T23:00:00Z") // 00:00 on the 29th
            bottleAt("2026-03-29T21:59:00Z") // 23:59 on the 29th
            bottleAt("2026-03-29T22:00:00Z") // 00:00 on the 30th
            assertEquals(2, bottlesOn(LocalDate.of(2026, 3, 29)))
        }

    @Test
    fun `a baby is created locally and queued`() =
        runTest {
            val babies = BabyRepository(db, time)
            val created = babies.create(" Vera ", LocalDate.of(2026, 7, 1))

            assertEquals(listOf(created), babies.observeAll().first())
            assertEquals("Vera", created.name)
            val mutation = queue().single()
            assertEquals(listOf("baby", created.id, "upsert"), mutation.run { listOf(entity, entityId, op) })
            assertEquals(
                mapOf("name" to "Vera", "date_of_birth" to "2026-07-01"),
                mutation.fields().mapValues { it.value.jsonPrimitive.content },
            )
        }
}
