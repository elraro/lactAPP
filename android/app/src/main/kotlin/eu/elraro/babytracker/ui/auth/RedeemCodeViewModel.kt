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
import eu.elraro.babytracker.data.repository.NewMember
import eu.elraro.babytracker.ui.common.messageRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Same rule as the server (docs/API.md §2). */
const val MIN_PASSWORD_LENGTH = 10

data class RedeemUiState(
    val server: String = "",
    val code: String = "",
    /** True for an invitation from the other parent; false for a password-setup code. */
    val joining: Boolean = false,
    val email: String = "",
    val displayName: String = "",
    val password: String = "",
    @param:StringRes val serverError: Int? = null,
    @param:StringRes val codeError: Int? = null,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val displayNameError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val formError: Int? = null,
    val loading: Boolean = false,
) {
    val hasFieldErrors: Boolean
        get() = listOfNotNull(serverError, codeError, emailError, displayNameError, passwordError).isNotEmpty()
}

@HiltViewModel
class RedeemCodeViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(RedeemUiState())
        val state: StateFlow<RedeemUiState> = _state.asStateFlow()

        fun onServerChange(value: String) =
            _state.update { it.copy(server = value, serverError = null, formError = null) }

        fun onCodeChange(value: String) = _state.update { it.copy(code = value, codeError = null, formError = null) }

        fun onJoiningChange(value: Boolean) =
            _state.update { it.copy(joining = value, emailError = null, displayNameError = null, formError = null) }

        fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }

        fun onDisplayNameChange(value: String) =
            _state.update {
                it.copy(displayName = value, displayNameError = null, formError = null)
            }

        fun onPasswordChange(value: String) =
            _state.update {
                it.copy(password = value, passwordError = null, formError = null)
            }

        fun submit() {
            val current = _state.value
            if (current.loading) return
            val server = ServerUrl.parse(current.server, allowCleartext = BuildConfig.ALLOW_CLEARTEXT)
            val checked =
                current.copy(
                    serverError = server.errorRes(current.server),
                    codeError = if (current.code.isBlank()) R.string.error_required else null,
                    emailError = if (current.joining) emailError(current.email) else null,
                    displayNameError =
                        if (current.joining && current.displayName.isBlank()) R.string.error_required else null,
                    passwordError = passwordError(current.password),
                    formError = null,
                )
            if (server !is ServerUrl.Parsed.Valid || checked.hasFieldErrors) {
                _state.value = checked
                return
            }
            _state.value = checked.copy(loading = true)
            viewModelScope.launch {
                val member = if (current.joining) NewMember(current.email, current.displayName) else null
                val result = auth.redeemCode(server.url, current.code, current.password, member)
                _state.update {
                    when (result) {
                        is ApiResult.Ok -> it.copy(loading = false, password = "")
                        is ApiResult.Failure -> it.copy(loading = false, formError = result.error.messageRes())
                    }
                }
            }
        }

        @StringRes
        private fun emailError(email: String): Int? =
            when {
                email.isBlank() -> R.string.error_required
                '@' !in email.trim().drop(1).dropLast(1) -> R.string.error_email_invalid
                else -> null
            }

        @StringRes
        private fun passwordError(password: String): Int? =
            when {
                password.isEmpty() -> R.string.error_required
                password.length < MIN_PASSWORD_LENGTH -> R.string.error_password_short
                else -> null
            }
    }
