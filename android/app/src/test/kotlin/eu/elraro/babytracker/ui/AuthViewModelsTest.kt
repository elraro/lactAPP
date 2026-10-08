package eu.elraro.babytracker.ui

import eu.elraro.babytracker.R
import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.ui.auth.LoginViewModel
import eu.elraro.babytracker.ui.auth.RedeemCodeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelsTest {
    private val backend = TestBackend()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        backend.close()
    }

    @Test
    fun `login validates fields before calling the server`() =
        runTest {
            val vm = LoginViewModel(backend.auth)

            vm.submit()

            val state = vm.state.value
            assertEquals(R.string.error_required, state.serverError)
            assertEquals(R.string.error_required, state.emailError)
            assertEquals(R.string.error_required, state.passwordError)
            assertFalse(state.loading)
            assertEquals(0, backend.server.requestCount)

            vm.onServerChange("not a url")
            vm.submit()
            assertEquals(R.string.error_server_invalid, vm.state.value.serverError)

            // Typing clears the error of that field.
            vm.onServerChange(backend.serverUrl)
            assertNull(vm.state.value.serverError)
        }

    @Test
    fun `login shows the localized reason when the server refuses`() =
        runTest {
            val vm = LoginViewModel(backend.auth)
            vm.onServerChange(backend.serverUrl)
            vm.onEmailChange("a@example.com")
            vm.onPasswordChange("wrong")

            backend.enqueueError(401, "invalid_credentials")
            vm.submit()
            assertEquals(R.string.error_invalid_credentials, vm.state.first { !it.loading }.formError)

            backend.enqueueError(429, "rate_limited")
            vm.submit()
            assertEquals(R.string.error_rate_limited, vm.state.first { !it.loading }.formError)

            backend.enqueueError(500, "some_future_code")
            vm.submit()
            assertEquals(R.string.error_unexpected, vm.state.first { !it.loading }.formError)

            // The password is kept so the user can correct a typo.
            assertEquals("wrong", vm.state.value.password)
        }

    @Test
    fun `successful login signs in and clears the password from memory`() =
        runTest {
            val vm = LoginViewModel(backend.auth)
            vm.onServerChange(backend.serverUrl)
            vm.onEmailChange("a@example.com")
            vm.onPasswordChange("secret-password")
            backend.enqueueSession()

            vm.submit()

            val state = vm.state.first { !it.loading }
            assertNull(state.formError)
            assertEquals("", state.password)
            assertTrue(backend.auth.sessionState.first() is SessionState.SignedIn)
        }

    @Test
    fun `login is pre-filled and explains itself after the session expired`() =
        runTest {
            backend.session.signIn(Account(backend.serverUrl, "u", "a@example.com", "Ana", "f", "Casa"), "a", "r")
            backend.session.expire()

            val state = LoginViewModel(backend.auth).state.first { it.sessionExpired }

            assertEquals(backend.serverUrl, state.server)
            assertEquals("a@example.com", state.email)
        }

    @Test
    fun `a setup code needs only server, code and a long enough password`() =
        runTest {
            val vm = RedeemCodeViewModel(backend.auth)
            vm.onServerChange(backend.serverUrl)
            vm.onCodeChange("K7QF-2M9X-PD4T")
            vm.onPasswordChange("short")

            vm.submit()
            assertEquals(R.string.error_password_short, vm.state.value.passwordError)
            assertNull(vm.state.value.emailError)
            assertEquals(0, backend.server.requestCount)

            vm.onPasswordChange("long-enough-password")
            backend.enqueueError(410, "invite_expired")
            vm.submit()
            assertEquals(R.string.error_invite_expired, vm.state.first { !it.loading }.formError)

            backend.enqueueSession()
            vm.submit()
            vm.state.first { !it.loading && it.formError == null }
            assertTrue(backend.auth.sessionState.first() is SessionState.SignedIn)
        }

    @Test
    fun `an invitation also needs name and a valid e-mail`() =
        runTest {
            val vm = RedeemCodeViewModel(backend.auth)
            vm.onServerChange(backend.serverUrl)
            vm.onCodeChange("CODE")
            vm.onPasswordChange("long-enough-password")
            vm.onJoiningChange(true)

            vm.submit()
            assertEquals(R.string.error_required, vm.state.value.emailError)
            assertEquals(R.string.error_required, vm.state.value.displayNameError)

            vm.onDisplayNameChange("Bea")
            vm.onEmailChange("bea@")
            vm.submit()
            assertEquals(R.string.error_email_invalid, vm.state.value.emailError)
            assertEquals(0, backend.server.requestCount)

            vm.onEmailChange("bea@example.com")
            backend.enqueueError(409, "email_taken")
            vm.submit()
            assertEquals(R.string.error_email_taken, vm.state.first { !it.loading }.formError)
        }
}
