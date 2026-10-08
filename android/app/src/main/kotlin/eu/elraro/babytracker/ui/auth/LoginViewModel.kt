package eu.elraro.babytracker.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.BuildConfig
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.remote.ServerUrl
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.ui.common.messageRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val server: String = "",
    val email: String = "",
    val password: String = "",
    @param:StringRes val serverError: Int? = null,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val formError: Int? = null,
    val loading: Boolean = false,
    /** The previous session ended on the server; explain why the login screen is back. */
    val sessionExpired: Boolean = false,
)

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(LoginUiState())
        val state: StateFlow<LoginUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val last = (auth.sessionState.first() as? SessionState.SignedOut)?.lastAccount ?: return@launch
                _state.update {
                    // Do not overwrite what the user has already started typing.
                    if (it.server.isEmpty() && it.email.isEmpty()) {
                        it.copy(server = last.serverUrl, email = last.email, sessionExpired = true)
                    } else {
                        it
                    }
                }
            }
        }

        fun onServerChange(value: String) =
            _state.update { it.copy(server = value, serverError = null, formError = null) }

        fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }

        fun onPasswordChange(value: String) =
            _state.update {
                it.copy(password = value, passwordError = null, formError = null)
            }

        fun submit() {
            val current = _state.value
            if (current.loading) return
            val server = ServerUrl.parse(current.server, allowCleartext = BuildConfig.DEBUG)
            val checked =
                current.copy(
                    serverError = server.errorRes(current.server),
                    emailError = if (current.email.isBlank()) R.string.error_required else null,
                    passwordError = if (current.password.isEmpty()) R.string.error_required else null,
                    formError = null,
                )
            if (server !is ServerUrl.Parsed.Valid || checked.emailError != null || checked.passwordError != null) {
                _state.value = checked
                return
            }
            _state.value = checked.copy(loading = true)
            viewModelScope.launch {
                val result = auth.login(server.url, current.email, current.password)
                _state.update {
                    when (result) {
                        // On success the session state changes and this screen is replaced.
                        is ApiResult.Ok -> it.copy(loading = false, password = "")
                        is ApiResult.Failure -> it.copy(loading = false, formError = result.error.messageRes())
                    }
                }
            }
        }
    }

@StringRes
internal fun ServerUrl.Parsed.errorRes(input: String): Int? =
    when (this) {
        is ServerUrl.Parsed.Valid -> null
        ServerUrl.Parsed.Insecure -> R.string.error_server_insecure
        ServerUrl.Parsed.Invalid -> if (input.isBlank()) R.string.error_required else R.string.error_server_invalid
    }
