package eu.elraro.babytracker.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.domain.TimeSource
import eu.elraro.babytracker.stats.Stats
import eu.elraro.babytracker.stats.StatsInput
import eu.elraro.babytracker.stats.StatsOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val days: Int = StatsViewModel.RANGES.first(),
    /** null when there is no baby yet or nothing has been recorded in the range. */
    val stats: StatsOutput? = null,
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
    ) : ViewModel() {
        private val days = MutableStateFlow(RANGES.first())

        val state: StateFlow<StatsUiState> =
            combine(babies.observeAll().map { it.firstOrNull() }, days) { baby, days -> baby to days }
                .flatMapLatest { (baby, days) ->
                    if (baby == null) return@flatMapLatest flowOf(StatsUiState(loading = false, days = days))
                    val zone = time.zone()
                    val today = time.now().atZone(zone).toLocalDate()
                    val from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant()
                    val to = today.plusDays(1).atStartOfDay(zone).toInstant()
                    events.observeForStats(baby.id, from.minus(LOOKBACK), to).map { rows ->
                        val stats =
                            if (rows.isEmpty()) {
                                null
                            } else {
                                Stats.compute(StatsInput(zone.id, today.toString(), days, time.now(), rows))
                            }
                        StatsUiState(loading = false, days = days, stats = stats)
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatsUiState())

        fun setRange(days: Int) {
            if (days in RANGES) this.days.value = days
        }

        companion object {
            val RANGES = listOf(7, 14, 30)
            private const val STOP_TIMEOUT_MS = 5_000L

            // Sleep and wake windows that straddle the first day need the events before it.
            private val LOOKBACK: Duration = Duration.ofHours(48)
        }
    }
