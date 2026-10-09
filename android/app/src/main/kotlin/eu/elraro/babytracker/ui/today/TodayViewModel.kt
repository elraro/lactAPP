package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.data.repository.SaveResult
import eu.elraro.babytracker.data.sync.SyncMonitor
import eu.elraro.babytracker.data.sync.SyncOutcome
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.data.sync.SyncStatus
import eu.elraro.babytracker.domain.Baby
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventProblem
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.SleepRules
import eu.elraro.babytracker.domain.TimeSource
import eu.elraro.babytracker.domain.Timeline
import eu.elraro.babytracker.domain.TimelineRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

sealed interface TodayMessage {
    /** An event was deleted; the snackbar offers to undo it. */
    data class Deleted(
        val eventId: String,
    ) : TodayMessage

    data class Error(
        @param:StringRes val text: Int,
    ) : TodayMessage
}

data class TodayUiState(
    val loading: Boolean = true,
    val baby: Baby? = null,
    val day: LocalDate = LocalDate.MIN,
    val isToday: Boolean = true,
    val lastBottle: Event? = null,
    val lastDiaper: Event? = null,
    /** The session in progress, if the baby is asleep. */
    val openSleep: Event? = null,
    val lastSleep: Event? = null,
    val rows: List<TimelineRow> = emptyList(),
    val filter: EventType? = null,
    val editor: EditorState? = null,
    val message: TodayMessage? = null,
    val sync: SyncStatus = SyncStatus(),
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
    /** The first download after signing in could not be completed. */
    val firstSyncFailed: Boolean = false,
) {
    /**
     * Nothing has been downloaded yet, so we cannot know whether the family already has a
     * baby. Offering "add your baby" now could create a duplicate of the other parent's.
     */
    val awaitingFirstSync: Boolean get() = !loading && baby == null && sync.lastSuccessAt == null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val babies: BabyRepository,
        private val events: EventRepository,
        private val time: TimeSource,
        monitor: SyncMonitor,
        private val sync: SyncRunner,
    ) : ViewModel() {
        /** null follows the calendar: the screen moves to the new day at midnight. */
        private val selectedDay = MutableStateFlow<LocalDate?>(null)
        private val filter = MutableStateFlow<EventType?>(null)
        private val editor = MutableStateFlow<EditorState?>(null)
        private val message = MutableStateFlow<TodayMessage?>(null)
        private val refreshing = MutableStateFlow(false)
        private val firstSyncFailed = MutableStateFlow(false)

        private val today: Flow<LocalDate> =
            flow {
                while (true) {
                    emit(today())
                    delay(DAY_CHECK_INTERVAL_MS)
                }
            }.distinctUntilChanged()

        private val tracking: Flow<TodayUiState> =
            combine(babies.observeAll().map { it.firstOrNull() }, selectedDay, today) { baby, selected, today ->
                Triple(baby, selected ?: today, today)
            }.flatMapLatest { (baby, day, today) ->
                val base = TodayUiState(loading = false, baby = baby, day = day, isToday = day == today)
                if (baby == null) return@flatMapLatest flowOf(base)
                val from = day.atStartOfDay(time.zone()).toInstant()
                val to = day.plusDays(1).atStartOfDay(time.zone()).toInstant()
                combine(
                    events.observeTouching(baby.id, from, to),
                    events.observeLatest(baby.id, EventType.BOTTLE),
                    events.observeLatest(baby.id, EventType.DIAPER),
                    events.observeOpenSleep(baby.id),
                    events.observeLatest(baby.id, EventType.SLEEP),
                ) { dayEvents, bottle, diaper, openSleep, lastSleep ->
                    base.copy(
                        lastBottle = bottle,
                        lastDiaper = diaper,
                        openSleep = openSleep,
                        lastSleep = lastSleep,
                        // Unfiltered here; the filter is applied below without re-querying.
                        rows = Timeline.build(dayEvents, from, to),
                    )
                }
            }

        private val screen: Flow<TodayUiState> =
            combine(tracking, filter, editor, message) { tracking, filter, editor, message ->
                tracking.copy(
                    rows = tracking.rows.filter { filter == null || it.event.type == filter },
                    filter = filter,
                    editor = editor,
                    message = message,
                )
            }

        val state: StateFlow<TodayUiState> =
            combine(screen, monitor.status, refreshing, firstSyncFailed) { screen, status, refreshing, failed ->
                screen.copy(sync = status, refreshing = refreshing, firstSyncFailed = failed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState())

        init {
            viewModelScope.launch {
                if (monitor.status.first().lastSuccessAt == null) firstSync()
            }
        }

        /** The first download after signing in; the screen waits for it. */
        fun firstSync() {
            viewModelScope.launch {
                firstSyncFailed.value = false
                firstSyncFailed.value = sync.sync() != SyncOutcome.SUCCESS
            }
        }

        /** Pull-to-refresh. */
        fun refresh() {
            if (refreshing.value) return
            refreshing.value = true
            viewModelScope.launch {
                if (sync.sync() == SyncOutcome.RETRY) message.value = TodayMessage.Error(R.string.error_network)
                refreshing.value = false
            }
        }

        private fun today(): LocalDate = time.now().atZone(time.zone()).toLocalDate()

        fun previousDay() = selectedDay.update { (it ?: today()).minusDays(1) }

        /** Moves forward one day, never past today. */
        fun nextDay() =
            selectedDay.update { current ->
                val next = (current ?: today()).plusDays(1)
                if (next >= today()) null else next
            }

        fun setFilter(type: EventType?) {
            filter.value = type
        }

        fun createBaby(
            name: String,
            dateOfBirth: LocalDate,
        ) {
            viewModelScope.launch { babies.create(name, dateOfBirth) }
        }

        fun logBottle(amountMl: Int) = track { events.logBottle(it, amountMl) }

        fun logDiaper(kind: DiaperKind) = track { events.logDiaper(it, kind) }

        fun startSleep() = track { events.startSleep(it) }

        fun endSleep() = track { events.endSleep(it) }

        /** Runs a one-tap action for the current baby and reports a failure. */
        private fun track(action: suspend (babyId: String) -> SaveResult) {
            val babyId = state.value.baby?.id ?: return
            viewModelScope.launch {
                if (action(babyId) !is SaveResult.Saved) message.value = TodayMessage.Error(R.string.error_not_saved)
            }
        }

        /** Opens the form for a new event of [type], pre-filled with the current time. */
        fun openNew(type: EventType) {
            val babyId = state.value.baby?.id ?: return
            var event = events.newEvent(babyId, type)
            if (type == EventType.SLEEP) {
                // "Add past sleep": a finished session ending now is the likeliest starting point.
                val start = event.startedAt.minus(DEFAULT_PAST_SLEEP)
                event =
                    event.copy(
                        startedAt = start,
                        endedAt = event.startedAt,
                        sleepKind = SleepRules.suggestKind(start, time.zone()),
                    )
            }
            editor.value = EditorState.of(event, isNew = true, zone = time.zone())
        }

        fun openEdit(event: Event) {
            editor.value = EditorState.of(event, isNew = false, zone = time.zone())
        }

        fun updateEditor(transform: (EditorState) -> EditorState) {
            editor.update { it?.let(transform)?.copy(timeError = null) }
        }

        fun dismissEditor() {
            editor.value = null
        }

        fun saveEditor() {
            val form = editor.value ?: return
            val event = form.toEvent(time.zone())
            if (event == null) {
                editor.value = form.copy(amountInvalid = true)
                return
            }
            viewModelScope.launch {
                val result = if (form.isNew) events.create(event) else events.update(event)
                editor.value =
                    when (result) {
                        is SaveResult.Saved, SaveResult.NotFound -> null
                        is SaveResult.OverlapsSleep -> form.copy(timeError = R.string.error_sleep_overlap)
                        is SaveResult.Invalid ->
                            when (result.problem) {
                                EventProblem.END_BEFORE_START -> form.copy(timeError = R.string.error_end_before_start)
                                else -> form.copy(amountInvalid = true)
                            }
                    }
                if (result == SaveResult.NotFound) message.value = TodayMessage.Error(R.string.error_not_saved)
            }
        }

        fun deleteEdited() {
            val form = editor.value?.takeIf { !it.isNew } ?: return
            editor.value = null
            viewModelScope.launch {
                events.delete(form.base.id)
                message.value = TodayMessage.Deleted(form.base.id)
            }
        }

        fun undoDelete(eventId: String) {
            viewModelScope.launch {
                when (events.restore(eventId)) {
                    is SaveResult.OverlapsSleep -> message.value = TodayMessage.Error(R.string.error_sleep_overlap)
                    is SaveResult.Saved -> Unit
                    else -> message.value = TodayMessage.Error(R.string.error_not_saved)
                }
            }
        }

        fun messageShown(shown: TodayMessage) {
            message.compareAndSet(shown, null)
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val DAY_CHECK_INTERVAL_MS = 30_000L
            val DEFAULT_PAST_SLEEP: Duration = Duration.ofHours(1)
        }
    }
