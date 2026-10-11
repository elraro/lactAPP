package eu.elraro.babytracker.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.remote.AiStatusDto
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.repository.AiRepository
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.sync.SyncMonitor
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.data.sync.SyncStatus
import eu.elraro.babytracker.domain.Baby
import eu.elraro.babytracker.ui.common.messageRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class InviteUiState(
    val loading: Boolean = false,
    val code: String? = null,
    val expiresAt: Instant? = null,
    @param:StringRes val error: Int? = null,
)

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
        monitor: SyncMonitor,
        private val sync: SyncRunner,
        private val aiRepository: AiRepository,
        private val babies: BabyRepository,
    ) : ViewModel() {
        val baby: StateFlow<Baby?> =
            babies
                .observeAll()
                .map { it.firstOrNull() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        fun saveBaby(baby: Baby) {
            viewModelScope.launch { babies.update(baby) }
        }

        /** null until the server has answered (or while offline). */
        private val _aiStatus = MutableStateFlow<AiStatusDto?>(null)
        val aiStatus: StateFlow<AiStatusDto?> = _aiStatus.asStateFlow()

        init {
            refreshAiStatus()
        }

        private fun refreshAiStatus() {
            viewModelScope.launch {
                val result = aiRepository.status()
                if (result is ApiResult.Ok) _aiStatus.value = result.value
            }
        }

        /**
         * Switches AI on or off for the family. Online only on purpose: consent is never
         * queued for later, and the switch shows what the server actually stored.
         */
        fun setAiEnabled(enabled: Boolean) {
            viewModelScope.launch {
                aiRepository.setEnabled(enabled)
                refreshAiStatus()
            }
        }

        val syncStatus: StateFlow<SyncStatus> =
            monitor.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncStatus())

        private val _syncing = MutableStateFlow(false)
        val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

        fun syncNow() {
            if (_syncing.value) return
            _syncing.value = true
            viewModelScope.launch {
                sync.sync()
                _syncing.value = false
            }
        }

        /** Drops the changes the server refused and downloads the server's version of those records. */
        fun discardFailed() {
            viewModelScope.launch {
                sync.discardFailed()
                sync.sync()
            }
        }

        private val _invite = MutableStateFlow<InviteUiState?>(null)

        /** The invitation being shown, or why it could not be created; null when the dialog is closed. */
        val invite: StateFlow<InviteUiState?> = _invite.asStateFlow()

        fun createInvite() {
            _invite.value = InviteUiState(loading = true)
            viewModelScope.launch {
                _invite.value =
                    when (val result = auth.createInvite()) {
                        is ApiResult.Ok ->
                            InviteUiState(
                                code = result.value.code,
                                expiresAt = Instant.parse(result.value.expiresAt),
                            )
                        is ApiResult.Failure -> InviteUiState(error = result.error.messageRes())
                    }
            }
        }

        fun dismissInvite() {
            _invite.value = null
        }

        fun signOut() {
            viewModelScope.launch { auth.logout() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
