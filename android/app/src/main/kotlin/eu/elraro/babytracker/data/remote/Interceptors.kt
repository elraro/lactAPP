package eu.elraro.babytracker.data.remote

import eu.elraro.babytracker.data.session.SessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Retrofit is built against this placeholder; [ServerUrlInterceptor] swaps in the real server. */
const val PLACEHOLDER_BASE_URL = "http://server.invalid/"

/**
 * The server address is chosen by the user at sign-in, so it is resolved per request
 * instead of being baked into Retrofit.
 */
@Singleton
class ServerUrlInterceptor
    @Inject
    constructor(
        private val session: SessionStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val server =
                runBlocking { session.serverUrl() }?.let(ServerUrl::apiBase)
                    ?: throw IOException("No server configured")
            val original = chain.request().url
            val target =
                server
                    .newBuilder()
                    .addEncodedPathSegments(original.encodedPath.trimStart('/'))
                    .encodedQuery(original.encodedQuery)
                    .build()
            return chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .url(target)
                    .build(),
            )
        }
    }

@Singleton
class AccessTokenInterceptor
    @Inject
    constructor(
        private val session: SessionStore,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = runBlocking { session.accessToken() }
            val request = if (token == null) chain.request() else chain.request().withBearer(token)
            return chain.proceed(request)
        }
    }

/**
 * On 401, rotates the refresh token once and retries. Calls that fail at the same time
 * share a single refresh. If the server rejects the refresh token the session is expired,
 * which sends the user to the login screen; local data is kept.
 */
@Singleton
class TokenAuthenticator
    @Inject
    constructor(
        private val session: SessionStore,
        // Provider: AuthApi's client must not depend on this authenticator.
        private val authApi: Provider<AuthApi>,
        private val json: Json,
    ) : Authenticator {
        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            if (response.priorResponse != null) return null // already retried once
            val sentToken = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)
            val token =
                synchronized(this) {
                    val current = runBlocking { session.accessToken() }
                    // If the token changed, another call refreshed while this one was in flight.
                    if (current != null && current != sentToken) current else refresh()
                }
            return token?.let { response.request.withBearer(it) }
        }

        private fun refresh(): String? {
            val request =
                runBlocking {
                    session.refreshToken()?.let { RefreshRequest(it, session.deviceId()) }
                } ?: return null
            val result =
                try {
                    authApi.get().refresh(request).execute()
                } catch (_: IOException) {
                    return null // offline: keep the session and let the caller fail normally
                } catch (_: SerializationException) {
                    return null
                }
            val tokens = result.body()
            if (result.isSuccessful && tokens != null) {
                runBlocking { session.updateTokens(tokens.accessToken, tokens.refreshToken) }
                return tokens.accessToken
            }
            val error = parseApiError(json, result.code(), result.errorBody()?.string().orEmpty())
            if (error is ApiError.Server && error.code == "invalid_grant") runBlocking { session.expire() }
            return null
        }
    }

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

private fun Request.withBearer(token: String): Request = newBuilder().header(AUTHORIZATION, BEARER + token).build()
