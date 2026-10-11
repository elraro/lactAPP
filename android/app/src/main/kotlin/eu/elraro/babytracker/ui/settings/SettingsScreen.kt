package eu.elraro.babytracker.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.remote.AiStatusDto
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.sync.SyncStatus
import eu.elraro.babytracker.domain.Baby
import eu.elraro.babytracker.domain.MAX_NOTES_LENGTH
import eu.elraro.babytracker.ui.common.DatePickerDialog
import eu.elraro.babytracker.ui.common.FormTextField
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.rememberFormatters
import java.time.LocalDate

@Composable
fun SettingsScreen(
    account: Account,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var language by remember { mutableStateOf(AppLanguage.current()) }
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()
    val baby by viewModel.baby.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    SettingsContent(
        account = account,
        language = language,
        sync = sync,
        syncing = syncing,
        aiStatus = aiStatus,
        baby = baby,
        actions =
            SettingsActions(
                onLanguageChange = {
                    language = it
                    it.apply()
                },
                onSyncNow = viewModel::syncNow,
                onDiscardFailed = viewModel::discardFailed,
                onAiEnabled = viewModel::setAiEnabled,
                onSaveBaby = viewModel::saveBaby,
                onInvite = viewModel::createInvite,
                onSignOut = viewModel::signOut,
            ),
        modifier = modifier,
    )
    invite?.let { InviteDialog(it, onDismiss = viewModel::dismissInvite) }
}

data class SettingsActions(
    val onLanguageChange: (AppLanguage) -> Unit,
    val onSyncNow: () -> Unit,
    val onDiscardFailed: () -> Unit,
    val onAiEnabled: (Boolean) -> Unit,
    val onSaveBaby: (Baby) -> Unit,
    val onInvite: () -> Unit,
    val onSignOut: () -> Unit,
)

@Composable
fun SettingsContent(
    account: Account,
    language: AppLanguage,
    sync: SyncStatus,
    syncing: Boolean,
    aiStatus: AiStatusDto?,
    baby: Baby?,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val onLanguageChange = actions.onLanguageChange
    val onSignOut = actions.onSignOut

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(R.string.settings_language)
        Column(Modifier.selectableGroup()) {
            AppLanguage.entries.forEach { option ->
                LanguageRow(option = option, selected = option == language, onSelect = { onLanguageChange(option) })
            }
        }
        HorizontalDivider()

        SectionTitle(R.string.settings_sync)
        SyncSection(sync, syncing, onSyncNow = actions.onSyncNow, onDiscard = { confirmDiscard = true })
        HorizontalDivider()

        if (baby != null) {
            SectionTitle(R.string.settings_baby)
            BabySection(baby, actions.onSaveBaby)
            HorizontalDivider()
        }

        SectionTitle(R.string.settings_ai)
        AiSection(aiStatus, actions.onAiEnabled)
        HorizontalDivider()

        SectionTitle(R.string.settings_account)
        ListItem(
            headlineContent = { Text(account.displayName) },
            supportingContent = { Text(account.email) },
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.settings_account_family)) },
            headlineContent = { Text(account.familyName) },
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.settings_account_server)) },
            headlineContent = { Text(account.serverUrl) },
        )
        OutlinedButton(
            onClick = actions.onInvite,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = PrimaryActionHeight),
        ) {
            Text(stringResource(R.string.action_invite))
        }
        OutlinedButton(
            onClick = { confirmSignOut = true },
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = PrimaryActionHeight),
        ) {
            Text(stringResource(R.string.action_sign_out))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.sign_out_confirm_body))
                    if (sync.pending > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.sign_out_pending, sync.pending, sync.pending),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) {
                    Text(stringResource(R.string.action_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_confirm_title)) },
            text = { Text(stringResource(R.string.discard_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        actions.onDiscardFailed()
                    },
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Shows the single-use code the other parent types into "I have a code" on their phone. */
@Composable
private fun InviteDialog(
    invite: InviteUiState,
    onDismiss: () -> Unit,
) {
    val formatters = rememberFormatters()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.invite_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    invite.loading -> CircularProgressIndicator()
                    invite.error != null -> Text(stringResource(invite.error), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text(stringResource(R.string.invite_body))
                        // Selectable so that it can be copied into a message.
                        SelectionContainer {
                            Text(invite.code.orEmpty(), style = MaterialTheme.typography.headlineMedium)
                        }
                        invite.expiresAt?.let {
                            Text(
                                text = stringResource(R.string.invite_expires, formatters.dateTime(it)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

/** The baby's profile: shown as a row, edited in a dialog. */
@Composable
private fun BabySection(
    baby: Baby,
    onSave: (Baby) -> Unit,
) {
    val formatters = rememberFormatters()
    var editing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(baby.name) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.baby_born, formatters.fullDate(baby.dateOfBirth)))
                baby.notes?.let { Text(it) }
            }
        },
        trailingContent = { TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_edit)) } },
    )
    if (editing) {
        BabyDialog(baby, onDismiss = { editing = false }) {
            editing = false
            onSave(it)
        }
    }
}

@Composable
private fun BabyDialog(
    baby: Baby,
    onDismiss: () -> Unit,
    onSave: (Baby) -> Unit,
) {
    val formatters = rememberFormatters()
    var name by rememberSaveable { mutableStateOf(baby.name) }
    var notes by rememberSaveable { mutableStateOf(baby.notes.orEmpty()) }
    // Saved as ISO text because LocalDate is not saveable by itself.
    var birth by rememberSaveable { mutableStateOf(baby.dateOfBirth.toString()) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val birthDate = LocalDate.parse(birth)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baby_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    label = R.string.field_baby_name,
                    error = if (name.isBlank()) R.string.error_required else null,
                    capitalization = KeyboardCapitalization.Words,
                )
                Text(stringResource(R.string.field_date_of_birth), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { pickingDate = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryActionHeight),
                ) {
                    Text(formatters.fullDate(birthDate))
                }
                FormTextField(
                    value = notes,
                    onValueChange = { notes = it.take(MAX_NOTES_LENGTH) },
                    label = R.string.field_notes,
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(baby.copy(name = name, dateOfBirth = birthDate, notes = notes)) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pickingDate) {
        DatePickerDialog(
            initial = birthDate,
            onDismiss = { pickingDate = false },
            onConfirm = {
                pickingDate = false
                // A birth date in the future is a slip of the finger.
                birth = minOf(it, LocalDate.now()).toString()
            },
        )
    }
}

private const val MAX_NAME_LENGTH = 100

/**
 * The family's consent to AI explanations. Turning it on shows exactly what is sent and to
 * whom; turning it off needs no confirmation.
 */
@Composable
private fun AiSection(
    status: AiStatusDto?,
    onEnabled: (Boolean) -> Unit,
) {
    var askConsent by rememberSaveable { mutableStateOf(false) }
    if (status == null || !status.configured) {
        Text(
            text = stringResource(if (status == null) R.string.ai_status_unknown else R.string.ai_not_configured),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PrimaryActionHeight)
                .toggleable(
                    value = status.enabled,
                    role = Role.Switch,
                    onValueChange = { on -> if (on) askConsent = true else onEnabled(false) },
                ).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_switch), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.ai_switch_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = status.enabled, onCheckedChange = null)
    }
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text(stringResource(R.string.ai_consent_title)) },
            text = { Text(stringResource(R.string.ai_consent_body, status.provider.orEmpty())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askConsent = false
                        onEnabled(true)
                    },
                ) {
                    Text(stringResource(R.string.action_turn_on))
                }
            },
            dismissButton = {
                TextButton(onClick = { askConsent = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SyncSection(
    sync: SyncStatus,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    onDiscard: () -> Unit,
) {
    val formatters = rememberFormatters()
    ListItem(
        overlineContent = { Text(stringResource(R.string.sync_last)) },
        headlineContent = {
            Text(sync.lastSuccessAt?.let(formatters::dateTime) ?: stringResource(R.string.sync_never))
        },
        supportingContent = {
            Text(
                if (sync.pending > 0) {
                    pluralStringResource(R.plurals.sync_pending, sync.pending, sync.pending)
                } else {
                    stringResource(R.string.sync_up_to_date)
                },
            )
        },
    )
    if (sync.clockSkewed) {
        Text(
            text = stringResource(R.string.sync_clock_warning),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (sync.failed > 0) {
        Text(
            text = pluralStringResource(R.plurals.sync_failed, sync.failed, sync.failed),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onDiscard, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_discard_failed))
        }
    }
    OutlinedButton(
        onClick = onSyncNow,
        enabled = !syncing,
        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = PrimaryActionHeight),
    ) {
        Text(stringResource(R.string.action_sync_now))
    }
}

@Composable
private fun SectionTitle(
    @StringRes title: Int,
) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LanguageRow(
    option: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PrimaryActionHeight)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = stringResource(option.label),
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
