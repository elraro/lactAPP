package eu.elraro.babytracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.remote.AiStatusDto
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.repository.AiRepository
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.sync.SyncMonitor
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
        monitor: SyncMonitor,
        private val sync: SyncRunner,
        private val aiRepository: AiRepository,
    ) : ViewModel() {
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

        fun signOut() {
            viewModelScope.launch { auth.logout() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
