package eu.elraro.babytracker.ui.stats

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.remote.AiStatusDto
import eu.elraro.babytracker.data.remote.AnalysisDto
import eu.elraro.babytracker.data.remote.ApiError
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.repository.AiRepository
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.TimeSource
import eu.elraro.babytracker.stats.Stats
import eu.elraro.babytracker.stats.StatsInput
import eu.elraro.babytracker.stats.StatsOutput
import eu.elraro.babytracker.ui.common.messageRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val days: Int = StatsViewModel.RANGES.first(),
    /** null when there is no baby yet or nothing has been recorded in the range. */
    val stats: StatsOutput? = null,
    val babyId: String? = null,
    /** Every measurement ever recorded, oldest first; independent of the selected range. */
    val growth: List<Event> = emptyList(),
)

/** The optional AI explanation shown under the statistics. */
data class AiUiState(
    /** null until the server has answered; the card stays hidden while unknown or not configured. */
    val status: AiStatusDto? = null,
    val kind: String = StatsViewModel.AI_KINDS.first(),
    val working: Boolean = false,
    val analysis: AnalysisDto? = null,
    @param:StringRes val error: Int? = null,
)

/**
 * Statistics are computed on the phone from the local database, so they work offline and
 * always include what has just been recorded (docs/ARCHITECTURE.md, decision D1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        babies: BabyRepository,
        private val events: EventRepository,
        private val time: TimeSource,
        private val aiRepository: AiRepository,
    ) : ViewModel() {
        private val days = MutableStateFlow(RANGES.first())

        private val _ai = MutableStateFlow(AiUiState())
        val ai: StateFlow<AiUiState> = _ai.asStateFlow()

        /** Asks the server whether AI is available and switched on. Silent when offline. */
        fun refreshAiStatus() {
            viewModelScope.launch {
                val result = aiRepository.status()
                if (result is ApiResult.Ok) _ai.update { it.copy(status = result.value) }
            }
        }

        fun setAiKind(kind: String) {
            if (kind in AI_KINDS) _ai.update { it.copy(kind = kind, analysis = null, error = null) }
        }

        /** Requests an explanation of the statistics on screen, written in [locale] ("en" or "es"). */
        fun explain(locale: String) {
            val babyId = state.value.babyId ?: return
            val request = _ai.value
            if (request.working) return
            _ai.update { it.copy(working = true, error = null, analysis = null) }
            viewModelScope.launch {
                val result = aiRepository.analyze(babyId, request.kind, state.value.days, locale)
                _ai.update {
                    when (result) {
                        is ApiResult.Ok -> it.copy(working = false, analysis = result.value)
                        is ApiResult.Failure -> it.copy(working = false, error = aiError(result.error))
                    }
                }
                // The remaining allowance changed, and consent may have been withdrawn elsewhere.
                refreshAiStatus()
            }
        }

        @StringRes
        private fun aiError(error: ApiError): Int =
            if (error is ApiError.Server &&
                error.code == "rate_limited"
            ) {
                R.string.error_ai_limit
            } else {
                error.messageRes()
            }

        val state: StateFlow<StatsUiState> =
            combine(babies.observeAll().map { it.firstOrNull() }, days) { baby, days -> baby to days }
                .flatMapLatest { (baby, days) ->
                    if (baby == null) return@flatMapLatest flowOf(StatsUiState(loading = false, days = days))
                    val babyId = baby.id
                    val zone = time.zone()
                    val today = time.now().atZone(zone).toLocalDate()
                    val from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant()
                    val to = today.plusDays(1).atStartOfDay(zone).toInstant()
                    combine(
                        events.observeForStats(baby.id, from.minus(LOOKBACK), to),
                        events.observeMeasurements(baby.id),
                    ) { rows, growth ->
                        // Measurements alone are not "statistics": they have their own card.
                        val tracked =
                            rows.filter {
                                it.type != EventType.MEASUREMENT.wire &&
                                    it.type != EventType.NOTE.wire
                            }
                        val stats =
                            if (tracked.isEmpty()) {
                                null
                            } else {
                                Stats.compute(StatsInput(zone.id, today.toString(), days, time.now(), rows))
                            }
                        StatsUiState(loading = false, days = days, stats = stats, babyId = babyId, growth = growth)
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatsUiState())

        fun setRange(days: Int) {
            if (days !in RANGES) return
            this.days.value = days
            // An explanation belongs to the range it was written for.
            _ai.update { it.copy(analysis = null, error = null) }
        }

        companion object {
            val RANGES = listOf(7, 14, 30)
            val AI_KINDS = listOf("overview", "sleep", "feeding")
            private const val STOP_TIMEOUT_MS = 5_000L

            // Sleep and wake windows that straddle the first day need the events before it.
            private val LOOKBACK: Duration = Duration.ofHours(48)
        }
    }
