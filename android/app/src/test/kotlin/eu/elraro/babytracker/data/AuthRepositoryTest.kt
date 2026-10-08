package eu.elraro.babytracker.data

import eu.elraro.babytracker.TestBackend
import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.data.remote.ApiError
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.repository.NewMember
import eu.elraro.babytracker.data.session.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private val backend = TestBackend()
    private val auth = backend.auth
    private val baby = BabyEntity("baby-1", "Vera", "2026-07-01", null, null, 1, null)

    @After
    fun tearDown() = backend.close()

    @Test
    fun `login sends credentials and device and stores the session`() =
        runTest {
            backend.enqueueSession()

            val result = auth.login(backend.serverUrl, " a@example.com ", "secret-password")

            assertEquals(ApiResult.Ok(Unit), result)
            val request = backend.server.takeRequest()
            assertEquals("/api/v1/auth/login", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("a@example.com", body["email"]!!.jsonPrimitive.content)
            val device = body["device"]!!.jsonObject
            assertEquals(backend.session.deviceId(), device["id"]!!.jsonPrimitive.content)
            assertEquals("android", device["platform"]!!.jsonPrimitive.content)

            val state = auth.sessionState.first() as SessionState.SignedIn
            assertEquals("Casa", state.account.familyName)
            assertEquals(backend.serverUrl, state.account.serverUrl)
            assertEquals("access-1", backend.session.accessToken())
        }

    @Test
    fun `server error codes are returned and nothing is stored`() =
        runTest {
            backend.enqueueError(401, "invalid_credentials")

            val result = auth.login(backend.serverUrl, "a@example.com", "wrong")

            assertEquals(ApiResult.Failure(ApiError.Server(401, "invalid_credentials")), result)
            assertEquals(SessionState.SignedOut(null), auth.sessionState.first())
        }

    @Test
    fun `an unreachable server is a network error`() =
        runTest {
            backend.server.close()
            assertEquals(ApiResult.Failure(ApiError.Network), auth.login(backend.serverUrl, "a@example.com", "x"))
        }

    @Test
    fun `a response that is not from our server is unexpected`() =
        runTest {
            backend.enqueueJson(502, "<html>Bad gateway</html>")
            assertEquals(ApiResult.Failure(ApiError.Unexpected), auth.login(backend.serverUrl, "a@example.com", "x"))
            backend.enqueueJson(200, "<html>Welcome to nginx</html>")
            assertEquals(ApiResult.Failure(ApiError.Unexpected), auth.login(backend.serverUrl, "a@example.com", "x"))
        }

    @Test
    fun `a setup code is redeemed without e-mail and an invitation with it`() =
        runTest {
            backend.enqueueSession()
            auth.redeemCode(backend.serverUrl, " K7QF-2M9X-PD4T ", "a-long-password", newMember = null)
            var body =
                Json
                    .parseToJsonElement(
                        backend.server
                            .takeRequest()
                            .body!!
                            .utf8(),
                    ).jsonObject
            assertEquals("K7QF-2M9X-PD4T", body["code"]!!.jsonPrimitive.content)
            assertFalse("email" in body)
            assertFalse("display_name" in body)

            backend.enqueueSession()
            auth.redeemCode(backend.serverUrl, "CODE", "a-long-password", NewMember("b@example.com", " Bea "))
            val request = backend.server.takeRequest()
            assertEquals("/api/v1/auth/invites/redeem", request.url.encodedPath)
            body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("b@example.com", body["email"]!!.jsonPrimitive.content)
            assertEquals("Bea", body["display_name"]!!.jsonPrimitive.content)
        }

    @Test
    fun `logout clears session and local data even when the server cannot be reached`() =
        runTest {
            backend.enqueueSession()
            auth.login(backend.serverUrl, "a@example.com", "secret-password")
            backend.database.babyDao().upsert(baby)
            backend.server.close()

            auth.logout()

            assertEquals(SessionState.SignedOut(null), auth.sessionState.first())
            assertTrue(
                backend.database
                    .babyDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun `signing in again as the same family keeps local data`() =
        runTest {
            backend.enqueueSession()
            auth.login(backend.serverUrl, "a@example.com", "secret-password")
            backend.database.babyDao().upsert(baby)
            backend.session.expire()

            backend.enqueueSession(access = "access-2", refresh = "refresh-2")
            auth.login(backend.serverUrl, "a@example.com", "secret-password")

            assertEquals(
                listOf(baby),
                backend.database
                    .babyDao()
                    .observeAll()
                    .first(),
            )
        }

    @Test
    fun `signing in as another family removes the previous family's data`() =
        runTest {
            backend.enqueueSession()
            auth.login(backend.serverUrl, "a@example.com", "secret-password")
            backend.database.babyDao().upsert(baby)
            backend.session.expire()

            backend.enqueueSession(familyId = "family-2", email = "z@example.com")
            auth.login(backend.serverUrl, "z@example.com", "secret-password")

            assertTrue(
                backend.database
                    .babyDao()
                    .observeAll()
                    .first()
                    .isEmpty(),
            )
        }
}
