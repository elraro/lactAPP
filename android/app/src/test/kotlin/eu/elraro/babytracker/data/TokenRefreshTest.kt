package eu.elraro.babytracker.data

import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.sessionJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class TokenRefreshTest {
    private val backend = TestBackend()
    private val account = Account(backend.serverUrl, "user-1", "a@example.com", "Ana", "family-1", "Casa")

    @Before
    fun signIn() = runTest { backend.session.signIn(account, "access-1", "refresh-1") }

    @After
    fun tearDown() = backend.close()

    @Test
    fun `a 401 refreshes the token once and repeats the call`() =
        runTest {
            backend.enqueueError(401, "unauthenticated")
            backend.enqueueJson(200, sessionJson("access-2", "refresh-2"))
            backend.enqueueJson(204, "")

            backend.accountApi.logout()

            val first = backend.server.takeRequest()
            assertEquals("Bearer access-1", first.headers["Authorization"])
            val refresh = backend.server.takeRequest()
            assertEquals("/api/v1/auth/refresh", refresh.url.encodedPath)
            assertNull(refresh.headers["Authorization"])
            assertEquals(true, refresh.body!!.utf8().contains("\"refresh_token\":\"refresh-1\""))
            val retry = backend.server.takeRequest()
            assertEquals("/api/v1/auth/logout", retry.url.encodedPath)
            assertEquals("Bearer access-2", retry.headers["Authorization"])
            assertEquals("refresh-2", backend.session.refreshToken())
        }

    @Test
    fun `a rejected refresh token expires the session`() =
        runTest {
            backend.enqueueError(401, "unauthenticated")
            backend.enqueueError(401, "invalid_grant")

            assertThrows(HttpException::class.java) { kotlinx.coroutines.runBlocking { backend.accountApi.logout() } }

            assertEquals(SessionState.SignedOut(account), backend.session.state.first())
            assertEquals(2, backend.server.requestCount)
        }

    @Test
    fun `a network failure during refresh keeps the session`() =
        runTest {
            backend.enqueueError(401, "unauthenticated")
            backend.enqueueError(503, "internal")

            assertThrows(HttpException::class.java) { kotlinx.coroutines.runBlocking { backend.accountApi.logout() } }

            assertEquals(SessionState.SignedIn(account), backend.session.state.first())
            assertEquals("refresh-1", backend.session.refreshToken())
        }

    @Test
    fun `a second 401 after refreshing is not retried forever`() =
        runTest {
            backend.enqueueError(401, "unauthenticated")
            backend.enqueueJson(200, sessionJson("access-2", "refresh-2"))
            backend.enqueueError(401, "unauthenticated")

            assertThrows(HttpException::class.java) { kotlinx.coroutines.runBlocking { backend.accountApi.logout() } }
            assertEquals(3, backend.server.requestCount)
        }

    @Test
    fun `calls failing at the same time share one refresh`() =
        runTest {
            val refreshes = AtomicInteger()
            backend.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.url.encodedPath.endsWith("/auth/refresh") -> {
                                refreshes.incrementAndGet()
                                MockResponse(code = 200, body = sessionJson("access-2", "refresh-2"))
                            }
                            request.headers["Authorization"] == "Bearer access-2" -> MockResponse(code = 204)
                            else -> MockResponse(code = 401, body = """{"error":{"code":"unauthenticated"}}""")
                        }
                }

            (1..6).map { async { backend.accountApi.logout() } }.awaitAll()

            assertEquals(1, refreshes.get())
        }
}
