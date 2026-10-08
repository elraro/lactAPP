package eu.elraro.babytracker.data.repository

import eu.elraro.babytracker.BuildConfig
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.remote.AccountApi
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.remote.AuthApi
import eu.elraro.babytracker.data.remote.DeviceDto
import eu.elraro.babytracker.data.remote.LoginRequest
import eu.elraro.babytracker.data.remote.RedeemRequest
import eu.elraro.babytracker.data.remote.SessionResponse
import eu.elraro.babytracker.data.remote.apiCall
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.data.session.SessionStore
import eu.elraro.babytracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Name and e-mail of a parent joining a family with an invitation. */
data class NewMember(
    val email: String,
    val displayName: String,
)

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val authApi: AuthApi,
        private val accountApi: AccountApi,
        private val session: SessionStore,
        private val database: AppDatabase,
        private val json: Json,
        private val deviceName: DeviceName,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) {
        val sessionState: Flow<SessionState> = session.state

        /** [serverUrl] must already be validated with ServerUrl.parse. */
        suspend fun login(
            serverUrl: String,
            email: String,
            password: String,
        ): ApiResult<Unit> =
            signIn(serverUrl) { device ->
                authApi.login(LoginRequest(email.trim(), password, device))
            }

        /**
         * Redeems a password-setup code, or — when [newMember] is given — an invitation
         * to join an existing family.
         */
        suspend fun redeemCode(
            serverUrl: String,
            code: String,
            password: String,
            newMember: NewMember?,
        ): ApiResult<Unit> =
            signIn(serverUrl) { device ->
                authApi.redeem(
                    RedeemRequest(
                        code = code.trim(),
                        password = password,
                        device = device,
                        email = newMember?.email?.trim(),
                        displayName = newMember?.displayName?.trim(),
                    ),
                )
            }

        private suspend fun signIn(
            serverUrl: String,
            call: suspend (DeviceDto) -> SessionResponse,
        ): ApiResult<Unit> =
            withContext(io) {
                session.setServerUrl(serverUrl)
                val device =
                    DeviceDto(
                        id = session.deviceId(),
                        name = deviceName.get(),
                        platform = "android",
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                when (val result = apiCall(json) { call(device) }) {
                    is ApiResult.Failure -> result
                    is ApiResult.Ok -> {
                        val response = result.value
                        // Data of a previously signed-in family must never be shown to another one.
                        if (session.account()?.familyId != response.family.id) database.clearAllTables()
                        session.signIn(
                            Account(
                                serverUrl = serverUrl,
                                userId = response.user.id,
                                email = response.user.email,
                                displayName = response.user.displayName,
                                familyId = response.family.id,
                                familyName = response.family.name,
                            ),
                            accessToken = response.accessToken,
                            refreshToken = response.refreshToken,
                        )
                        ApiResult.Ok(Unit)
                    }
                }
            }

        /**
         * Signs out and removes all local data. Telling the server is best effort: the device
         * may be offline, and the local sign-out must not depend on it.
         */
        suspend fun logout() =
            withContext(io) {
                apiCall(json) { accountApi.logout() }
                session.signOut()
                database.clearAllTables()
            }
    }

/** Human-readable device name shown in the family's device list. */
fun interface DeviceName {
    fun get(): String
}
