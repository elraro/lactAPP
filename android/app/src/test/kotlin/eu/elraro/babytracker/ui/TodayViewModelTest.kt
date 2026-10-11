package eu.elraro.babytracker.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.SyncStateEntity
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.data.repository.toEntity
import eu.elraro.babytracker.data.sync.SyncMonitor
import eu.elraro.babytracker.data.sync.SyncOutcome
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.SleepKind
import eu.elraro.babytracker.domain.TimelineKind
import eu.elraro.babytracker.ui.today.TodayMessage
import eu.elraro.babytracker.ui.today.TodayUiState
import eu.elraro.babytracker.ui.today.TodayViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    // 10:15 on 8 October 2026 in Madrid.
    private val time = FakeTime(now = Instant.parse("2026-10-08T08:15:00Z"))
    private val babies = BabyRepository(db, time) {}

    /** Stands in for the engine: a "sync" succeeds or fails as the test says, and is counted. */
    private val runner =
        object : SyncRunner {
            var outcome = SyncOutcome.SUCCESS
            var runs = 0

            override suspend fun sync(): SyncOutcome {
                runs++
                if (outcome == SyncOutcome.SUCCESS) {
                    db.syncStateDao().upsert(SyncStateEntity(cursor = "c", lastSuccessAt = 1, serverTimeOffsetMs = 0))
                }
                return outcome
            }

            override suspend fun discardFailed() = Unit
        }

    // Created in setUp: a view model binds its scope to Dispatchers.Main when constructed.
    private lateinit var vm: TodayViewModel

    // Real time and real threads (runBlocking, not runTest): Room delivers its flows from its
    // own threads, and the view model's periodic day check would make virtual time spin forever.
    private val collector = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        vm = TodayViewModel(babies, EventRepository(db, time) {}, time, SyncMonitor(db), runner)
    }

    @After
    fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    /** Keeps the state subscribed, like the screen does, and waits for the baby to exist. */
    private suspend fun startWithBaby() {
        collector.launch { vm.state.collect {} }
        babies.create("Vera", LocalDate.of(2026, 7, 1))
        await { it.baby != null }
    }

    private suspend fun await(predicate: (TodayUiState) -> Boolean): TodayUiState =
        withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first(predicate) }

    @Test
    fun `after signing in the screen waits for the first download before asking for a baby`() =
        runBlocking {
            runner.outcome = SyncOutcome.RETRY
            vm = TodayViewModel(babies, EventRepository(db, time) {}, time, SyncMonitor(db), runner)

            // Offline: we cannot know whether the other parent already added the baby.
            val waiting = await { it.firstSyncFailed }
            assertTrue(waiting.awaitingFirstSync)

            runner.outcome = SyncOutcome.SUCCESS
            vm.firstSync()
            val ready = await { !it.loading && !it.awaitingFirstSync }
            assertNull(ready.baby)
            assertFalse(ready.firstSyncFailed)
        }

    @Test
    fun `pull to refresh syncs and reports when the server cannot be reached`() =
        runBlocking {
            startWithBaby()
            val before = runner.runs

            vm.refresh()
            await { !it.refreshing && runner.runs == before + 1 }
            assertNull(vm.state.value.message)

            runner.outcome = SyncOutcome.RETRY
            vm.refresh()
            assertEquals(TodayMessage.Error(R.string.error_network), await { it.message != null }.message)
        }

    @Test
    fun `changes waiting to upload are counted`() =
        runBlocking {
            startWithBaby()
            vm.logBottle(120)
            vm.logDiaper(DiaperKind.WET)
            // The baby itself, the bottle and the diaper.
            assertEquals(3, await { it.sync.pending == 3 }.sync.pending)
        }

    @Test
    fun `the next sleep is estimated from past days and disappears while asleep`() =
        runBlocking {
            startWithBaby()
            val babyId =
                vm.state.value.baby!!
                    .id
            val repo = EventRepository(db, time) {}
            // Six regular days: up at 07:00 local, a nap 90 minutes later, bedtime at 19:00.
            for (day in 2..7) {
                val wake = Instant.parse("2026-10-0${day}T05:00:00Z")
                val night = repo.newEvent(babyId, EventType.SLEEP).copy(sleepKind = SleepKind.NIGHT)
                repo.create(night.copy(startedAt = wake.minus(Duration.ofHours(12)), endedAt = wake))
                val nap = repo.newEvent(babyId, EventType.SLEEP).copy(sleepKind = SleepKind.NAP)
                val napStart = wake.plus(Duration.ofMinutes(90))
                repo.create(nap.copy(startedAt = napStart, endedAt = napStart.plus(Duration.ofHours(1))))
            }
            // This morning: woke at 07:00; the test clock says 10:15, well past the usual nap time.
            val lastNight = repo.newEvent(babyId, EventType.SLEEP).copy(sleepKind = SleepKind.NIGHT)
            repo.create(
                lastNight.copy(
                    startedAt = Instant.parse("2026-10-07T17:00:00Z"),
                    endedAt = Instant.parse("2026-10-08T05:00:00Z"),
                ),
            )

            val estimate = await { it.nextSleep?.status == "ok" }.nextSleep!!
            // 07:00 + 90 min, widened to twenty minutes: 08:20–08:40 local.
            assertEquals("2026-10-08T06:20:00Z", estimate.windowStart)
            assertEquals("2026-10-08T06:40:00Z", estimate.windowEnd)
            assertEquals("nap", estimate.kind)
            assertEquals(true, estimate.overdue)
            assertEquals(6, estimate.basis?.sampleSize)

            vm.startSleep()
            assertEquals("asleep", await { it.nextSleep?.status == "asleep" }.nextSleep?.status)
        }

    @Test
    fun `without a baby the screen asks for one`() =
        runBlocking {
            val empty = await { !it.loading && !it.awaitingFirstSync }
            assertNull(empty.baby)

            vm.createBaby("Vera", LocalDate.of(2026, 7, 1))

            val state = await { it.baby != null }
            assertEquals("Vera", state.baby?.name)
            assertEquals(LocalDate.of(2026, 10, 8), state.day)
            assertTrue(state.isToday)
        }

    @Test
    fun `one-tap actions update the summary and the timeline`() =
        runBlocking {
            startWithBaby()

            vm.logBottle(120)
            time.advance(Duration.ofMinutes(20))
            vm.logDiaper(DiaperKind.WET)
            time.advance(Duration.ofMinutes(10))
            vm.startSleep()

            var state = await { it.openSleep != null && it.rows.size == 3 }
            assertEquals(120, state.lastBottle?.amountMl)
            assertEquals(DiaperKind.WET, state.lastDiaper?.diaperKind)
            assertEquals(SleepKind.NAP, state.openSleep?.sleepKind)
            assertEquals(
                listOf(TimelineKind.SLEEP_START, TimelineKind.DIAPER, TimelineKind.BOTTLE),
                state.rows.map { it.kind },
            )

            time.advance(Duration.ofMinutes(45))
            vm.endSleep()

            state = await { it.openSleep == null && it.rows.size == 4 && it.lastSleep?.endedAt != null }
            assertEquals(TimelineKind.SLEEP_END, state.rows.first().kind)
            assertEquals(Duration.ofMinutes(45), state.lastSleep?.let { Duration.between(it.startedAt, it.endedAt) })

            vm.setFilter(EventType.SLEEP)
            assertEquals(2, await { it.filter == EventType.SLEEP }.rows.size)
            vm.setFilter(null)
            assertEquals(4, await { it.filter == null }.rows.size)
        }

    @Test
    fun `days can be browsed but never past today`() =
        runBlocking {
            startWithBaby()
            vm.logBottle(90)
            await { it.rows.size == 1 }

            vm.previousDay()
            val yesterday = await { !it.isToday }
            assertEquals(LocalDate.of(2026, 10, 7), yesterday.day)
            assertTrue(yesterday.rows.isEmpty())
            // The summary is about now, not about the day being browsed.
            assertEquals(90, yesterday.lastBottle?.amountMl)

            vm.nextDay()
            assertEquals(1, await { it.isToday }.rows.size)
            vm.nextDay()
            assertEquals(LocalDate.of(2026, 10, 8), await { it.isToday }.day)
        }

    @Test
    fun `editing validates, saves and can be undone after deleting`() =
        runBlocking {
            startWithBaby()
            vm.logBottle(120)
            val bottle = await { it.rows.size == 1 }.rows.single().event

            vm.openEdit(bottle)
            vm.updateEditor { it.withAmount("") }
            vm.saveEditor()
            assertTrue(await { it.editor?.amountInvalid == true }.editor!!.amountInvalid)

            vm.updateEditor { it.withAmount("5000") }
            vm.saveEditor()
            await { it.editor?.amountInvalid == true }

            vm.updateEditor { it.withAmount("15a0").withNotes("  spat up a little ") }
            assertEquals(
                "150",
                vm.state.value.editor
                    ?.amount,
            )
            assertFalse(
                vm.state.value.editor!!
                    .amountInvalid,
            )
            vm.saveEditor()

            val saved = await { it.editor == null && it.lastBottle?.amountMl == 150 }
            assertEquals("spat up a little", saved.lastBottle?.notes)
            assertEquals(bottle.startedAt, saved.lastBottle?.startedAt)

            vm.openEdit(saved.lastBottle!!)
            vm.deleteEdited()
            // The timeline and the summary come from separate queries; wait for both.
            val deleted = await { it.message is TodayMessage.Deleted && it.rows.isEmpty() && it.lastBottle == null }

            val message = deleted.message as TodayMessage.Deleted
            vm.undoDelete(message.eventId)
            vm.messageShown(message)
            val restored = await { it.rows.size == 1 && it.message == null && it.lastBottle != null }
            assertEquals(150, restored.lastBottle?.amountMl)
        }

    @Test
    fun `a past sleep session is pre-filled, checked and saved`() =
        runBlocking {
            startWithBaby()

            vm.openNew(EventType.SLEEP)
            val form = await { it.editor != null }.editor!!
            assertTrue(form.isNew)
            assertEquals(LocalDateTime.of(2026, 10, 8, 9, 15), form.start)
            assertEquals(LocalDateTime.of(2026, 10, 8, 10, 15), form.end)
            assertEquals(SleepKind.NAP, form.sleepKind)

            vm.updateEditor { it.copy(end = it.start.minusMinutes(1)) }
            vm.saveEditor()
            assertEquals(R.string.error_end_before_start, await { it.editor?.timeError != null }.editor?.timeError)

            // Changing the form clears the error; a valid session is saved.
            vm.updateEditor { it.copy(end = it.start.plusMinutes(40)) }
            assertNull(
                vm.state.value.editor
                    ?.timeError,
            )
            vm.saveEditor()
            val saved = await { it.editor == null && it.rows.size == 2 }
            assertNull(saved.openSleep)

            // A second session over the same time is refused with an explanation.
            vm.openNew(EventType.SLEEP)
            await { it.editor != null }
            vm.updateEditor { it.copy(start = form.start.plusMinutes(10), end = form.start.plusMinutes(20)) }
            vm.saveEditor()
            assertEquals(R.string.error_sleep_overlap, await { it.editor?.timeError != null }.editor?.timeError)
            vm.dismissEditor()
            assertEquals(2, await { it.editor == null }.rows.size)
        }

    @Test
    fun `waking when no session is open reports a problem instead of failing silently`() =
        runBlocking {
            startWithBaby()
            vm.endSleep()
            val message = await { it.message != null }.message
            assertEquals(TodayMessage.Error(R.string.error_not_saved), message)
        }

    @Test
    fun `a measurement typed with a comma or a point is stored in grams and millimetres`() =
        runBlocking {
            startWithBaby()
            vm.openNew(EventType.MEASUREMENT)
            await { it.editor != null }

            vm.saveEditor() // nothing filled in
            assertTrue(await { it.editor?.measurementInvalid == true }.editor!!.measurementInvalid)

            vm.updateEditor { it.withWeight("5,25").withHeight("58.5") }
            assertFalse(
                vm.state.value.editor!!
                    .measurementInvalid,
            )
            vm.saveEditor()
            val saved = await { it.editor == null && it.rows.size == 1 }.rows.single().event
            assertEquals(5250, saved.weightG)
            assertEquals(585, saved.heightMm)
            assertNull(saved.headMm)

            // Editing shows the values back the way they were typed, minus the locale.
            vm.openEdit(saved)
            val form = await { it.editor != null }.editor!!
            assertEquals("5.25", form.weightKg)
            assertEquals("58.5", form.heightCm)

            vm.updateEditor { it.withWeight("0,02") } // 20 g is not a baby
            vm.saveEditor()
            await { it.editor?.measurementInvalid == true }
            vm.updateEditor { it.withWeight("5..2") } // not a number
            vm.saveEditor()
            await { it.editor?.measurementInvalid == true }
            vm.dismissEditor()
        }

    @Test
    fun `a note needs text`() =
        runBlocking {
            startWithBaby()
            vm.openNew(EventType.NOTE)
            await { it.editor != null }
            vm.saveEditor()
            assertTrue(await { it.editor?.notesMissing == true }.editor!!.notesMissing)

            vm.updateEditor { it.withNotes("First laugh") }
            assertFalse(
                vm.state.value.editor!!
                    .notesMissing,
            )
            vm.saveEditor()
            assertEquals(
                "First laugh",
                await { it.editor == null && it.rows.size == 1 }
                    .rows
                    .single()
                    .event.notes,
            )
        }

    @Test
    fun `sleep recorded on two phones is flagged for review`() =
        runBlocking {
            startWithBaby()
            val babyId =
                vm.state.value.baby!!
                    .id
            vm.startSleep()
            val mine = await { it.openSleep != null }.openSleep!!
            time.advance(Duration.ofMinutes(10))
            // The other parent's record of the same nap arrives through sync, bypassing the
            // check that prevents overlaps on one phone.
            val theirs = mine.copy(id = "from-the-other-phone", startedAt = mine.startedAt.minusSeconds(120))
            db.eventDao().upsert(theirs.toEntity().copy(version = 1))

            val state = await { it.overlapping.size == 2 }
            assertEquals(setOf(mine.id, theirs.id), state.overlapping)
            assertEquals(babyId, theirs.babyId)

            // Correcting one of them clears the flag.
            vm.openEdit(theirs)
            vm.deleteEdited()
            assertTrue(await { it.overlapping.isEmpty() && it.rows.size == 1 }.overlapping.isEmpty())
        }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
    }
}
