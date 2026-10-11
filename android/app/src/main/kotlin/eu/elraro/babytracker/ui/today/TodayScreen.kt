package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.BabyChangingStation
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.sync.SyncStatus
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.TimelineKind
import eu.elraro.babytracker.domain.TimelineRow
import eu.elraro.babytracker.stats.KIND_NIGHT
import eu.elraro.babytracker.stats.NextSleepEstimate
import eu.elraro.babytracker.ui.common.EmptyState
import eu.elraro.babytracker.ui.common.Formatters
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.durationText
import eu.elraro.babytracker.ui.common.rememberFormatters
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private enum class QuickSheet { NONE, BOTTLE, DIAPER }

@Composable
fun TodayScreen(
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheet by rememberSaveable { mutableStateOf(QuickSheet.NONE) }

    Messages(state.message, snackbar, onUndo = viewModel::undoDelete, onShown = viewModel::messageShown)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        // The outer scaffold (bottom bar) already consumed the system insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val content = Modifier.padding(padding)
        when {
            state.loading -> Box(content.fillMaxSize())
            state.awaitingFirstSync -> FirstSync(state.firstSyncFailed, viewModel::firstSync, content)
            state.baby == null -> AddBabyScreen(today = state.day, onSave = viewModel::createBaby, modifier = content)
            else ->
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = content,
                ) {
                    TodayContent(
                        state = state,
                        actions =
                            TodayActions(
                                onPreviousDay = viewModel::previousDay,
                                onNextDay = viewModel::nextDay,
                                onAddBottle = { sheet = QuickSheet.BOTTLE },
                                onAddDiaper = { sheet = QuickSheet.DIAPER },
                                onSleep = viewModel::startSleep,
                                onWake = viewModel::endSleep,
                                onAddPastSleep = { viewModel.openNew(EventType.SLEEP) },
                                onAddMeasurement = { viewModel.openNew(EventType.MEASUREMENT) },
                                onAddNote = { viewModel.openNew(EventType.NOTE) },
                                onFilter = viewModel::setFilter,
                                onEdit = viewModel::openEdit,
                            ),
                    )
                }
        }
    }

    when (sheet) {
        QuickSheet.NONE -> Unit
        QuickSheet.BOTTLE ->
            BottleSheet(
                onAmount = {
                    sheet = QuickSheet.NONE
                    viewModel.logBottle(it)
                },
                onOtherAmount = {
                    sheet = QuickSheet.NONE
                    viewModel.openNew(EventType.BOTTLE)
                },
                onDismiss = { sheet = QuickSheet.NONE },
            )
        QuickSheet.DIAPER ->
            DiaperSheet(
                onKind = {
                    sheet = QuickSheet.NONE
                    viewModel.logDiaper(it)
                },
                onDismiss = { sheet = QuickSheet.NONE },
            )
    }
    state.editor?.let { editor ->
        EventEditorSheet(
            state = editor,
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDelete = viewModel::deleteEdited,
            onDismiss = viewModel::dismissEditor,
        )
    }
}

@Composable
private fun Messages(
    message: TodayMessage?,
    snackbar: SnackbarHostState,
    onUndo: (String) -> Unit,
    onShown: (TodayMessage) -> Unit,
) {
    val deleted = stringResource(R.string.message_deleted)
    val undo = stringResource(R.string.action_undo)
    val errorText = (message as? TodayMessage.Error)?.let { stringResource(it.text) }
    LaunchedEffect(message) {
        when (message) {
            null -> Unit
            is TodayMessage.Error -> {
                snackbar.showSnackbar(errorText.orEmpty())
                onShown(message)
            }
            is TodayMessage.Deleted -> {
                val result = snackbar.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) onUndo(message.eventId)
                onShown(message)
            }
        }
    }
}

/** Waiting for the first download after signing in, or offering to retry it. */
@Composable
private fun FirstSync(
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            Text(
                text = stringResource(R.string.error_network),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = PrimaryActionHeight)) {
                Text(stringResource(R.string.action_retry))
            }
        } else {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.sync_first_title),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A quiet line about changes that are not on the server yet; nothing when all is uploaded. */
@Composable
private fun SyncNotice(status: SyncStatus) {
    if (status.failed > 0) {
        Text(
            text = pluralStringResource(R.plurals.sync_failed, status.failed, status.failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else if (status.pending > 0) {
        Text(
            text = pluralStringResource(R.plurals.sync_pending, status.pending, status.pending),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

data class TodayActions(
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onAddBottle: () -> Unit,
    val onAddDiaper: () -> Unit,
    val onSleep: () -> Unit,
    val onWake: () -> Unit,
    val onAddPastSleep: () -> Unit,
    val onAddMeasurement: () -> Unit,
    val onAddNote: () -> Unit,
    val onFilter: (EventType?) -> Unit,
    val onEdit: (Event) -> Unit,
)

@Composable
fun TodayContent(
    state: TodayUiState,
    actions: TodayActions,
    modifier: Modifier = Modifier,
) {
    val formatters = rememberFormatters()
    val today = remember(state.day, state.isToday) { if (state.isToday) state.day else LocalDate.MAX }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "summary") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.baby?.name.orEmpty(),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                SyncNotice(state.sync)
                FeedCard(state.lastBottle, formatters, today, actions.onAddBottle)
                DiaperCard(state.lastDiaper, formatters, today, actions.onAddDiaper)
                SleepCard(state, formatters, today, actions)
                NextSleepCard(state.nextSleep, formatters, today)
                // Less frequent records: smaller, but still comfortable to hit.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions.onAddMeasurement,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.row_measurement))
                    }
                    OutlinedButton(onClick = actions.onAddNote, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.row_note))
                    }
                }
            }
        }
        item(key = "day") {
            DayHeader(state.day, state.isToday, formatters, actions.onPreviousDay, actions.onNextDay)
            FilterRow(state.filter, actions.onFilter)
        }
        if (state.rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Outlined.ChildCare,
                    title = R.string.today_empty_title,
                    body = R.string.today_empty_body,
                    modifier = Modifier.fillParentMaxHeight(0.4f),
                )
            }
        }
        items(state.rows, key = { "${it.event.id}-${it.kind}" }) { row ->
            TimelineItem(
                row = row,
                formatters = formatters,
                overlaps = row.event.id in state.overlapping,
                onClick = { actions.onEdit(row.event) },
                // Rows slide into place when something is added, edited or removed.
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** Latest value on the left, the one-tap action on the right; [extra] sits under the value. */
@Composable
private fun SummaryCard(
    @StringRes title: Int,
    value: String,
    extra: @Composable () -> Unit = {},
    action: @Composable () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.titleLarge)
                extra()
            }
            action()
        }
    }
}

@Composable
private fun ActionButton(
    @StringRes text: Int,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.widthIn(min = 132.dp).heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        // Decorative: the label says what the button does.
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun FeedCard(
    last: Event?,
    formatters: Formatters,
    today: LocalDate,
    onAdd: () -> Unit,
) {
    val value =
        if (last == null) {
            stringResource(R.string.card_no_feed)
        } else {
            stringResource(
                R.string.value_time_and_detail,
                formatters.timeOn(last.startedAt, today),
                stringResource(R.string.amount_ml, last.amountMl ?: 0),
            )
        }
    SummaryCard(R.string.card_last_feed, value) {
        ActionButton(R.string.action_add_bottle, Icons.Outlined.LocalDrink, onAdd)
    }
}

@Composable
private fun DiaperCard(
    last: Event?,
    formatters: Formatters,
    today: LocalDate,
    onAdd: () -> Unit,
) {
    val kind = last?.diaperKind
    val value =
        if (last == null || kind == null) {
            stringResource(R.string.card_no_diaper)
        } else {
            stringResource(
                R.string.value_time_and_detail,
                formatters.timeOn(last.startedAt, today),
                stringResource(kind.label),
            )
        }
    SummaryCard(R.string.card_last_diaper, value) {
        ActionButton(R.string.action_add_diaper, Icons.Outlined.BabyChangingStation, onAdd)
    }
}

@Composable
private fun SleepCard(
    state: TodayUiState,
    formatters: Formatters,
    today: LocalDate,
    actions: TodayActions,
) {
    val open = state.openSleep
    val lastEnd = state.lastSleep?.endedAt
    val value =
        when {
            open != null -> stringResource(R.string.sleep_sleeping_since, formatters.timeOn(open.startedAt, today))
            lastEnd != null -> stringResource(R.string.sleep_awake_since, formatters.timeOn(lastEnd, today))
            else -> stringResource(R.string.card_no_sleep)
        }
    SummaryCard(
        title = R.string.card_sleep,
        value = value,
        extra = {
            if (open == null) {
                TextButton(
                    onClick = actions.onAddPastSleep,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(stringResource(R.string.action_add_past_sleep))
                }
            }
        },
    ) {
        if (open != null) {
            ActionButton(R.string.action_wake, Icons.Outlined.WbSunny, actions.onWake)
        } else {
            ActionButton(R.string.action_sleep, Icons.Outlined.Bedtime, actions.onSleep)
        }
    }
}

/**
 * The estimate of when the baby will next fall asleep. Shown only when there is one: while
 * asleep, during a night waking or without enough records the card is simply absent.
 */
@Composable
private fun NextSleepCard(
    estimate: NextSleepEstimate?,
    formatters: Formatters,
    today: LocalDate,
) {
    val start = estimate?.windowStart?.let(Instant::parse) ?: return
    val end = estimate.windowEnd?.let(Instant::parse) ?: return
    val basis = estimate.basis ?: return
    val kind = stringResource(if (estimate.kind == KIND_NIGHT) R.string.prediction_bedtime else R.string.sleep_nap)
    val confidence =
        stringResource(
            when (estimate.confidence) {
                "high" -> R.string.confidence_high
                "moderate" -> R.string.confidence_moderate
                else -> R.string.confidence_low
            },
        )
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.prediction_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    stringResource(
                        R.string.prediction_window,
                        formatters.timeOn(start, today),
                        formatters.time(end),
                    ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.prediction_kind_and_confidence, kind, confidence),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (estimate.overdue == true) {
                Text(
                    text = stringResource(R.string.prediction_overdue),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // What the estimate rests on, and what it is not.
            Text(
                text = pluralStringResource(R.plurals.prediction_basis, basis.sampleSize, basis.sampleSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.prediction_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayHeader(
    day: LocalDate,
    isToday: Boolean,
    formatters: Formatters,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(PrimaryActionHeight)) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.action_previous_day))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (isToday) {
                Text(
                    text = stringResource(R.string.nav_today),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = formatters.date(day),
                style = if (isToday) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = !isToday, modifier = Modifier.size(PrimaryActionHeight)) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.action_next_day))
        }
    }
}

@Composable
private fun FilterRow(
    selected: EventType?,
    onSelect: (EventType?) -> Unit,
) {
    val options =
        listOf(
            null to R.string.filter_all,
            EventType.BOTTLE to R.string.filter_bottles,
            EventType.DIAPER to R.string.filter_diapers,
            EventType.SLEEP to R.string.filter_sleep,
        )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun TimelineItem(
    row: TimelineRow,
    formatters: Formatters,
    overlaps: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = row.event
    val (icon, text) =
        when (row.kind) {
            TimelineKind.BOTTLE -> Icons.Outlined.LocalDrink to stringResource(R.string.amount_ml, event.amountMl ?: 0)
            TimelineKind.DIAPER ->
                Icons.Outlined.BabyChangingStation to
                    stringResource(event.diaperKind?.label ?: R.string.action_add_diaper)
            TimelineKind.SLEEP_START ->
                Icons.Outlined.Bedtime to
                    stringResource(
                        R.string.value_time_and_detail,
                        stringResource(R.string.row_sleep_start),
                        stringResource(event.sleepKind?.label ?: R.string.card_sleep),
                    )
            TimelineKind.SLEEP_END ->
                Icons.Outlined.WbSunny to
                    stringResource(
                        R.string.value_time_and_detail,
                        stringResource(R.string.row_sleep_end),
                        durationText(Duration.between(event.startedAt, event.endedAt ?: event.startedAt)),
                    )
            TimelineKind.MEASUREMENT -> Icons.Outlined.Straighten to measurementText(event, formatters)
            TimelineKind.NOTE -> Icons.AutoMirrored.Outlined.Notes to stringResource(R.string.row_note)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = PrimaryActionHeight)
                .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatters.time(row.at),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(88.dp),
        )
        // Decorative: the text next to it names the event.
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (overlaps) {
                Text(
                    text = stringResource(R.string.row_overlap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            event.notes?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** "5.25 kg · 58 cm" from whichever measurements were taken. */
@Composable
private fun measurementText(
    event: Event,
    formatters: Formatters,
): String {
    val parts =
        listOfNotNull(
            event.weightG?.let { stringResource(R.string.value_weight_kg, formatters.kilograms(it)) },
            event.heightMm?.let { stringResource(R.string.value_length_cm, formatters.centimetres(it)) },
            event.headMm?.let { stringResource(R.string.value_length_cm, formatters.centimetres(it)) },
        )
    if (parts.isEmpty()) return stringResource(R.string.row_measurement)
    var text = parts.first()
    for (next in parts.drop(1)) text = stringResource(R.string.value_two_parts, text, next)
    return text
}
