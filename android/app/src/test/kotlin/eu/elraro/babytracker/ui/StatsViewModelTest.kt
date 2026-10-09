package eu.elraro.babytracker.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.ui.stats.StatsUiState
import eu.elraro.babytracker.ui.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    // 10:15 on 8 October 2026 in Madrid.
    private val time = FakeTime(now = Instant.parse("2026-10-08T08:15:00Z"))
    private val babies = BabyRepository(db, time) {}
    private val events = EventRepository(db, time) {}
    private lateinit var vm: StatsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        vm = StatsViewModel(babies, events, time)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun await(predicate: (StatsUiState) -> Boolean): StatsUiState =
        withTimeout(10_000) { vm.state.first(predicate) }

    @Test
    fun `nothing recorded shows the empty state, not zeros`() =
        runBlocking {
            assertNull(await { !it.loading }.stats)
            babies.create("Vera", LocalDate.of(2026, 7, 1))
            assertNull(await { !it.loading }.stats)
        }

    @Test
    fun `statistics follow what is recorded on the phone and the chosen range`() =
        runBlocking {
            val baby = babies.create("Vera", LocalDate.of(2026, 7, 1))
            events.logBottle(baby.id, 120)
            time.advance(Duration.ofHours(3))
            events.logBottle(baby.id, 150)
            events.logDiaper(baby.id, DiaperKind.BOTH)

            val week = await { it.stats?.feeding?.todayFeeds == 2 }.stats!!
            assertEquals(270, week.feeding.todayMl)
            assertEquals(1, week.diapers.today.both)
            assertEquals("2026-10-02", week.range.start)
            assertEquals("Europe/Madrid", week.range.timezone)
            // Two feeds are below the minimum sample: reported as unknown, not as a number.
            assertNull(week.feeding.intervalS.median)
            assertEquals(1, week.feeding.intervalS.sampleSize)

            vm.setRange(30)
            assertEquals("2026-09-09", await { it.days == 30 && it.stats != null }.stats!!.range.start)
            vm.setRange(5) // not an offered range
            assertEquals(30, vm.state.value.days)
        }
}
