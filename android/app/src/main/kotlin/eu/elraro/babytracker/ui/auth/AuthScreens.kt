package eu.elraro.babytracker.ui.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import eu.elraro.babytracker.R
import eu.elraro.babytracker.ui.common.ErrorText
import eu.elraro.babytracker.ui.common.FormTextField
import eu.elraro.babytracker.ui.common.PasswordField
import eu.elraro.babytracker.ui.common.PrimaryActionHeight
import eu.elraro.babytracker.ui.common.PrimaryButton
import kotlinx.serialization.Serializable

@Serializable
private data object LoginRoute

@Serializable
private data object RedeemCodeRoute

@Composable
fun AuthNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = LoginRoute) {
        composable<LoginRoute> {
            val viewModel: LoginViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            LoginScreen(
                state = state,
                onServerChange = viewModel::onServerChange,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSubmit = viewModel::submit,
                onHaveCode = { navController.navigate(RedeemCodeRoute) },
            )
        }
        composable<RedeemCodeRoute> {
            val viewModel: RedeemCodeViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            RedeemCodeScreen(
                state = state,
                actions =
                    RedeemActions(
                        onServerChange = viewModel::onServerChange,
                        onCodeChange = viewModel::onCodeChange,
                        onJoiningChange = viewModel::onJoiningChange,
                        onEmailChange = viewModel::onEmailChange,
                        onDisplayNameChange = viewModel::onDisplayNameChange,
                        onPasswordChange = viewModel::onPasswordChange,
                        onSubmit = viewModel::submit,
                        onBack = { navController.popBackStack() },
                    ),
            )
        }
    }
}

@Composable
fun LoginScreen(
    state: LoginUiState,
    onServerChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHaveCode: () -> Unit,
) {
    Scaffold { padding ->
        FormColumn(modifier = Modifier.padding(padding)) {
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.sessionExpired) {
                Text(stringResource(R.string.login_session_expired), style = MaterialTheme.typography.bodyMedium)
            }
            ServerField(state.server, onServerChange, state.serverError, enabled = !state.loading)
            FormTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = R.string.field_email,
                error = state.emailError,
                enabled = !state.loading,
                keyboardType = KeyboardType.Email,
            )
            PasswordField(
                value = state.password,
                onValueChange = onPasswordChange,
                error = state.passwordError,
                enabled = !state.loading,
            )
            state.formError?.let { ErrorText(it) }
            PrimaryButton(text = R.string.action_sign_in, onClick = onSubmit, loading = state.loading)
            TextButton(
                onClick = onHaveCode,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = PrimaryActionHeight),
            ) {
                Text(stringResource(R.string.action_have_code))
            }
        }
    }
}

data class RedeemActions(
    val onServerChange: (String) -> Unit,
    val onCodeChange: (String) -> Unit,
    val onJoiningChange: (Boolean) -> Unit,
    val onEmailChange: (String) -> Unit,
    val onDisplayNameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedeemCodeScreen(
    state: RedeemUiState,
    actions: RedeemActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.redeem_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        FormColumn(modifier = Modifier.padding(padding)) {
            Text(
                text = stringResource(R.string.redeem_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerField(state.server, actions.onServerChange, state.serverError, enabled = !state.loading)
            FormTextField(
                value = state.code,
                onValueChange = actions.onCodeChange,
                label = R.string.field_code,
                error = state.codeError,
                enabled = !state.loading,
                keyboardType = KeyboardType.Ascii,
            )
            JoiningSwitch(checked = state.joining, onCheckedChange = actions.onJoiningChange, enabled = !state.loading)
            if (state.joining) {
                FormTextField(
                    value = state.displayName,
                    onValueChange = actions.onDisplayNameChange,
                    label = R.string.field_display_name,
                    error = state.displayNameError,
                    enabled = !state.loading,
                )
                FormTextField(
                    value = state.email,
                    onValueChange = actions.onEmailChange,
                    label = R.string.field_email,
                    error = state.emailError,
                    enabled = !state.loading,
                    keyboardType = KeyboardType.Email,
                )
            }
            PasswordField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                error = state.passwordError,
                supportingText =
                    pluralStringResource(
                        R.plurals.redeem_password_help,
                        MIN_PASSWORD_LENGTH,
                        MIN_PASSWORD_LENGTH,
                    ),
                enabled = !state.loading,
            )
            state.formError?.let { ErrorText(it) }
            PrimaryButton(text = R.string.action_continue, onClick = actions.onSubmit, loading = state.loading)
        }
    }
}

@Composable
private fun ServerField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes error: Int?,
    enabled: Boolean,
) {
    FormTextField(
        value = value,
        onValueChange = onValueChange,
        label = R.string.field_server,
        error = error,
        placeholder = stringResource(R.string.field_server_placeholder),
        enabled = enabled,
        keyboardType = KeyboardType.Uri,
    )
}

@Composable
private fun JoiningSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    // The whole row is the touch target; the Switch itself is not separately focusable.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PrimaryActionHeight)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.redeem_joining_switch), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.redeem_joining_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Scrollable, keyboard-aware column, centred and width-limited on tablets. */
@Composable
private fun FormColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}
