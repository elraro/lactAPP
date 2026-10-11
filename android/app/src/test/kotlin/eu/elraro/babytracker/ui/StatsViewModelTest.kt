package eu.elraro.babytracker.ui

import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.R
import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.ui.stats.AiUiState
import eu.elraro.babytracker.ui.stats.StatsUiState
import eu.elraro.babytracker.ui.stats.StatsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {
    private val backend = TestBackend()
    private val db = backend.database

    // 10:15 on 8 October 2026 in Madrid.
    private val time = FakeTime(now = Instant.parse("2026-10-08T08:15:00Z"))
    private val babies = BabyRepository(db, time) {}
    private val events = EventRepository(db, time) {}
    private lateinit var vm: StatsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        vm = StatsViewModel(babies, events, time, backend.ai)
        runBlocking { backend.signedIn() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
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

    private suspend fun awaitAi(predicate: (AiUiState) -> Boolean): AiUiState =
        withTimeout(10_000) {
            vm.ai.first(predicate)
        }

    private val statusOn =
        """{"configured":true,"enabled":true,"provider":"anthropic","model":"m","remaining_today":5}"""
    private val analysis =
        """
        {"id":"an-1","baby_id":"b","kind":"sleep","days":7,"locale":"es","provider":"anthropic","model":"m",
         "created_at":"2026-10-08T12:00:00Z","cached":false,
         "result":{"observations":[{"text":"Las siestas duran una hora.","refs":["calculated.sleep"]}],
                   "interpretation":[{"text":"Podría indicar un ritmo regular."}],"suggestions":[]}}
        """.trimIndent()

    @Test
    fun `the AI card stays unknown offline and reflects the server otherwise`() =
        runBlocking {
            backend.enqueueJson(200, """{"configured":false,"enabled":false,"remaining_today":0}""")
            vm.refreshAiStatus()
            assertFalse(awaitAi { it.status != null }.status!!.configured)

            backend.enqueueJson(200, statusOn)
            vm.refreshAiStatus()
            assertTrue(awaitAi { it.status?.enabled == true }.status!!.configured)
        }

    @Test
    fun `an explanation is requested for the baby, range, kind and language on screen`() =
        runBlocking {
            val baby = babies.create("Vera", LocalDate.of(2026, 7, 1))
            events.logBottle(baby.id, 120)
            await { it.babyId == baby.id }
            vm.setRange(14)
            vm.setAiKind("sleep")
            vm.setAiKind("horoscope") // not an offered kind
            await { it.days == 14 }

            backend.enqueueJson(200, analysis)
            backend.enqueueJson(200, statusOn)
            vm.explain("es")

            val shown = awaitAi { it.analysis != null && !it.working }
            assertEquals(
                "Las siestas duran una hora.",
                shown.analysis!!
                    .result.observations
                    .single()
                    .text,
            )
            assertNull(shown.error)
            val request = backend.server.takeRequest()
            assertEquals("/api/v1/ai/analyses", request.url.encodedPath)
            val body =
                Json
                    .parseToJsonElement(
                        request.body!!.utf8(),
                    ).jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
            assertEquals(mapOf("baby_id" to baby.id, "kind" to "sleep", "days" to "14", "locale" to "es"), body)
            // Nothing else leaves the phone: no events, no statistics, no names.

            // Changing the range discards an explanation written for another one.
            vm.setRange(7)
            assertNull(awaitAi { it.analysis == null }.analysis)
        }

    @Test
    fun `AI failures are explained in words`() =
        runBlocking {
            val baby = babies.create("Vera", LocalDate.of(2026, 7, 1))
            events.logBottle(baby.id, 120)
            await { it.babyId == baby.id }

            // Answer by path: the status refresh after each attempt must not race with the next attempt.
            var next = 200 to analysis
            backend.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.url.encodedPath.endsWith("/ai/status")) {
                            MockResponse(code = 200, body = statusOn)
                        } else {
                            MockResponse(code = next.first, body = next.second)
                        }
                }

            suspend fun failure(
                status: Int,
                code: String,
            ): Int? {
                next = status to """{"error":{"code":"$code","message":"x"}}"""
                vm.explain("en")
                val error = awaitAi { it.error != null && !it.working }.error
                vm.setAiKind(vm.ai.value.kind) // clears the error for the next attempt
                return error
            }
            assertEquals(R.string.error_ai_limit, failure(429, "rate_limited"))
            assertEquals(R.string.error_ai_provider, failure(502, "ai_provider_error"))
            assertEquals(R.string.error_ai_disabled, failure(403, "ai_disabled"))
        }

    @Test
    fun `growth is shown even when nothing else has been recorded`() =
        runBlocking {
            val baby = babies.create("Vera", LocalDate.of(2026, 7, 1))
            val weighing = events.newEvent(baby.id, EventType.MEASUREMENT).copy(weightG = 5200)
            events.create(weighing)

            val state = await { it.growth.isNotEmpty() }
            assertEquals(5200, state.growth.single().weightG)
            // A weighing is not feeding, sleep or diaper data: no statistics are made up from it.
            assertNull(state.stats)
        }
}
