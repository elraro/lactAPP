package eu.elraro.babytracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.elraro.babytracker.data.repository.AuthRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
    ) : ViewModel() {
        fun signOut() {
            viewModelScope.launch { auth.logout() }
        }
    }
