package eu.elraro.babytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.data.sync.SyncRunner
import eu.elraro.babytracker.data.sync.SyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        auth: AuthRepository,
        private val sync: SyncRunner,
        private val scheduler: SyncScheduler,
    ) : ViewModel() {
        /** null until the stored session has been read. */
        val session: StateFlow<SessionState?> =
            auth.sessionState
                .onEach { state ->
                    // Background sync exists exactly while someone is signed in.
                    if (state is SessionState.SignedIn) scheduler.schedulePeriodic() else scheduler.cancelAll()
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /**
         * Keeps the open app current: the other parent's changes appear within
         * [FOREGROUND_INTERVAL_MS]. Runs until cancelled; the caller ties it to the screen
         * being visible. (docs/ARCHITECTURE.md §5, "Sync triggers")
         */
        suspend fun syncWhileVisible() {
            while (true) {
                sync.sync()
                delay(FOREGROUND_INTERVAL_MS)
            }
        }

        private companion object {
            const val FOREGROUND_INTERVAL_MS = 30_000L
        }
    }
