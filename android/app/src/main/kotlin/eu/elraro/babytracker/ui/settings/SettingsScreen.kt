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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.sync.SyncStatus
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.rememberFormatters

@Composable
fun SettingsScreen(
    account: Account,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var language by remember { mutableStateOf(AppLanguage.current()) }
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    SettingsContent(
        account = account,
        language = language,
        sync = sync,
        syncing = syncing,
        actions =
            SettingsActions(
                onLanguageChange = {
                    language = it
                    it.apply()
                },
                onSyncNow = viewModel::syncNow,
                onDiscardFailed = viewModel::discardFailed,
                onSignOut = viewModel::signOut,
            ),
        modifier = modifier,
    )
}

data class SettingsActions(
    val onLanguageChange: (AppLanguage) -> Unit,
    val onSyncNow: () -> Unit,
    val onDiscardFailed: () -> Unit,
    val onSignOut: () -> Unit,
)

@Composable
fun SettingsContent(
    account: Account,
    language: AppLanguage,
    sync: SyncStatus,
    syncing: Boolean,
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
