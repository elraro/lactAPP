package eu.elraro.babytracker.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.ui.auth.AuthNavHost
import eu.elraro.babytracker.ui.home.HomeScreen

/** Shows the sign-in flow or the app, depending on whether a session exists. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        when (val state = session) {
            null -> Unit
            is SessionState.SignedOut -> AuthNavHost()
            is SessionState.SignedIn -> {
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.syncWhileVisible() }
                }
                HomeScreen(account = state.account)
            }
        }
    }
}
