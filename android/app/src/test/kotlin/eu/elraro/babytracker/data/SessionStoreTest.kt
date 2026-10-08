package eu.elraro.babytracker.data

import eu.elraro.babytracker.TestSession
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.session.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
    private val session = TestSession()
    private val store = session.store
    private val account = Account("https://example.com", "u1", "a@example.com", "Ana", "f1", "Casa")

    @After
    fun tearDown() = session.close()

    @Test
    fun `starts signed out with a stable device id`() =
        runTest {
            assertEquals(SessionState.SignedOut(null), store.state.first())
            val id = store.deviceId()
            assertEquals(id, store.deviceId())
            assertEquals(36, id.length)
        }

    @Test
    fun `sign in stores the account and returns the tokens`() =
        runTest {
            store.signIn(account, "access", "refresh")
            assertEquals(SessionState.SignedIn(account), store.state.first())
            assertEquals("access", store.accessToken())
            assertEquals("refresh", store.refreshToken())

            store.updateTokens("access-2", "refresh-2")
            assertEquals("access-2", store.accessToken())
            assertEquals("refresh-2", store.refreshToken())
        }

    @Test
    fun `an expired session keeps the account for the login form but no tokens`() =
        runTest {
            store.signIn(account, "access", "refresh")
            store.expire()
            assertEquals(SessionState.SignedOut(account), store.state.first())
            assertNull(store.accessToken())
            assertNull(store.refreshToken())
        }

    @Test
    fun `sign out forgets the account but keeps server and device`() =
        runTest {
            val device = store.deviceId()
            store.signIn(account, "access", "refresh")
            store.signOut()
            assertEquals(SessionState.SignedOut(null), store.state.first())
            assertNull(store.refreshToken())
            assertEquals("https://example.com", store.serverUrl())
            assertEquals(device, store.deviceId())
        }
}
