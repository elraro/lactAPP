package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.elraro.babytracker.R
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.MAX_AMOUNT_ML
import eu.elraro.babytracker.domain.MIN_AMOUNT_ML
import eu.elraro.babytracker.domain.MilkKind
import eu.elraro.babytracker.domain.SleepKind
import eu.elraro.babytracker.ui.common.DateTimeField
import eu.elraro.babytracker.ui.common.ErrorText
import eu.elraro.babytracker.ui.common.FormTextField
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.PrimaryButton
import java.time.LocalDateTime

/** Amounts offered for one-tap entry, in millilitres. */
val QuickBottleAmounts = listOf(60, 90, 120, 150, 180, 210, 240)

private val QuickButtonHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BottleSheet(
    onAmount: (Int) -> Unit,
    onOtherAmount: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetColumn(title = R.string.bottle_sheet_title) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                QuickBottleAmounts.forEach { amount ->
                    FilledTonalButton(
                        onClick = { onAmount(amount) },
                        modifier = Modifier.weight(1f).heightIn(min = QuickButtonHeight),
                    ) {
                        Text(stringResource(R.string.amount_ml, amount), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            TextButton(
                onClick = onOtherAmount,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryActionHeight),
            ) {
                Text(stringResource(R.string.bottle_other_amount))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaperSheet(
    onKind: (DiaperKind) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetColumn(title = R.string.diaper_sheet_title) {
            DiaperKind.entries.forEach { kind ->
                FilledTonalButton(
                    onClick = { onKind(kind) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = QuickButtonHeight),
                ) {
                    Text(stringResource(kind.label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Create or edit one event: time, details by type, notes; delete for existing events. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    state: EditorState,
    onChange: ((EditorState) -> EditorState) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully expanded: a half-open form hides the Save button behind the keyboard.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn(title = state.type.title) {
            when (state.type) {
                EventType.BOTTLE -> BottleFields(state, onChange)
                EventType.DIAPER ->
                    ChoiceChips(DiaperKind.entries, state.diaperKind, { it.label }) { kind ->
                        onChange { it.copy(diaperKind = kind) }
                    }
                EventType.SLEEP ->
                    ChoiceChips(SleepKind.entries, state.sleepKind, { it.label }) { kind ->
                        onChange { it.copy(sleepKind = kind) }
                    }
                EventType.MEASUREMENT, EventType.NOTE -> Unit
            }
            val hasEnd = state.type == EventType.SLEEP || state.type == EventType.BOTTLE
            DateTimeField(
                label = if (hasEnd) R.string.field_start else R.string.field_time,
                value = state.start,
                onValueChange = { start -> onChange { it.copy(start = start) } },
            )
            if (hasEnd) {
                EndField(state = state, onChange = onChange)
            }
            state.timeError?.let { ErrorText(it) }
            FormTextField(
                value = state.notes,
                onValueChange = { notes -> onChange { it.withNotes(notes) } },
                label = R.string.field_notes,
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
                singleLine = false,
            )
            PrimaryButton(text = R.string.action_save, onClick = onSave)
            if (!state.isNew) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryActionHeight),
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BottleFields(
    state: EditorState,
    onChange: ((EditorState) -> EditorState) -> Unit,
) {
    FormTextField(
        value = state.amount,
        onValueChange = { amount -> onChange { it.withAmount(amount) } },
        label = R.string.field_amount_ml,
        supportingText =
            if (state.amountInvalid) {
                stringResource(
                    R.string.error_amount_range,
                    MIN_AMOUNT_ML,
                    MAX_AMOUNT_ML,
                )
            } else {
                null
            },
        isError = state.amountInvalid,
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
    )
    Text(stringResource(R.string.field_milk), style = MaterialTheme.typography.labelLarge)
    // Optional: tapping the selected kind clears it.
    ChoiceChips(MilkKind.entries, state.milkKind, { it.label }) { kind ->
        onChange { form -> form.copy(milkKind = if (kind == form.milkKind) null else kind) }
    }
}

/** The optional end of a sleep session or a feed. */
@Composable
private fun EndField(
    state: EditorState,
    onChange: ((EditorState) -> EditorState) -> Unit,
) {
    val end = state.end
    if (end != null) {
        DateTimeField(
            label = R.string.field_end,
            value = end,
            onValueChange = { value -> onChange { it.copy(end = value) } },
        )
        TextButton(onClick = { onChange { it.copy(end = null) } }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_clear_end))
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.field_end), style = MaterialTheme.typography.labelLarge)
                Text(
                    text =
                        stringResource(
                            if (state.type == EventType.SLEEP) R.string.sleep_still_asleep else R.string.end_not_set,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { onChange { it.copy(end = defaultEnd(it.start)) } },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_set_end))
            }
        }
    }
}

/** "Now" when that is after the start, otherwise the start itself. */
private fun defaultEnd(start: LocalDateTime): LocalDateTime =
    maxOf(start, LocalDateTime.now().withSecond(0).withNano(0))

@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T?,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(stringResource(label(option))) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun SheetColumn(
    @StringRes title: Int,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@get:StringRes
private val EventType.title: Int
    get() =
        when (this) {
            EventType.BOTTLE -> R.string.action_add_bottle
            EventType.DIAPER -> R.string.action_add_diaper
            EventType.SLEEP -> R.string.card_sleep
            EventType.MEASUREMENT -> R.string.row_measurement
            EventType.NOTE -> R.string.row_note
        }
