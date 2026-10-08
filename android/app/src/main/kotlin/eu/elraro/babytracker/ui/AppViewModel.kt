package eu.elraro.babytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.session.SessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        auth: AuthRepository,
    ) : ViewModel() {
        /** null until the stored session has been read. */
        val session: StateFlow<SessionState?> =
            auth.sessionState.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
