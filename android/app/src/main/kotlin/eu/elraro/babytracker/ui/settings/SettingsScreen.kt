package eu.elraro.babytracker.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.ui.common.PrimaryActionHeight

@Composable
fun SettingsScreen(
    account: Account,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var language by remember { mutableStateOf(AppLanguage.current()) }
    SettingsContent(
        account = account,
        language = language,
        onLanguageChange = {
            language = it
            it.apply()
        },
        onSignOut = viewModel::signOut,
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    account: Account,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle(R.string.settings_language)
        Column(Modifier.selectableGroup()) {
            AppLanguage.entries.forEach { option ->
                LanguageRow(option = option, selected = option == language, onSelect = { onLanguageChange(option) })
            }
        }
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
            text = { Text(stringResource(R.string.sign_out_confirm_body)) },
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
