package eu.elraro.babytracker.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Who is signed in on this device. Contains no secrets. */
data class Account(
    val serverUrl: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val familyId: String,
    val familyName: String,
)

sealed interface SessionState {
    /** [lastAccount] is kept after the session expires so the login form can be pre-filled. */
    data class SignedOut(
        val lastAccount: Account?,
    ) : SessionState

    data class SignedIn(
        val account: Account,
    ) : SessionState
}

/**
 * Persists the account and its tokens. Tokens are stored encrypted; everything else is
 * plain because it is needed before the Keystore is touched and is not secret.
 */
@Singleton
class SessionStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val cipher: SecretCipher,
    ) {
        val state: Flow<SessionState> =
            dataStore.data
                .map { prefs ->
                    val account = prefs.account()
                    if (account != null && prefs[REFRESH_TOKEN] != null) {
                        SessionState.SignedIn(account)
                    } else {
                        SessionState.SignedOut(account)
                    }
                }.distinctUntilChanged()

        /** A stable random identifier for this installation, created on first use. */
        suspend fun deviceId(): String =
            dataStore
                .edit { prefs ->
                    if (prefs[DEVICE_ID] == null) prefs[DEVICE_ID] = UUID.randomUUID().toString()
                }[DEVICE_ID]!!

        suspend fun serverUrl(): String? = dataStore.data.first()[SERVER_URL]

        suspend fun setServerUrl(url: String) {
            dataStore.edit { it[SERVER_URL] = url }
        }

        suspend fun account(): Account? = dataStore.data.first().account()

        suspend fun accessToken(): String? = dataStore.data.first()[ACCESS_TOKEN]?.let(cipher::decrypt)

        suspend fun refreshToken(): String? = dataStore.data.first()[REFRESH_TOKEN]?.let(cipher::decrypt)

        suspend fun signIn(
            account: Account,
            accessToken: String,
            refreshToken: String,
        ) {
            dataStore.edit { prefs ->
                prefs[SERVER_URL] = account.serverUrl
                prefs[USER_ID] = account.userId
                prefs[EMAIL] = account.email
                prefs[DISPLAY_NAME] = account.displayName
                prefs[FAMILY_ID] = account.familyId
                prefs[FAMILY_NAME] = account.familyName
                prefs[ACCESS_TOKEN] = cipher.encrypt(accessToken)
                prefs[REFRESH_TOKEN] = cipher.encrypt(refreshToken)
            }
        }

        suspend fun updateTokens(
            accessToken: String,
            refreshToken: String,
        ) {
            dataStore.edit { prefs ->
                prefs[ACCESS_TOKEN] = cipher.encrypt(accessToken)
                prefs[REFRESH_TOKEN] = cipher.encrypt(refreshToken)
            }
        }

        /** The server no longer accepts this session. Local data and the account identity stay. */
        suspend fun expire() {
            dataStore.edit { prefs ->
                prefs.remove(ACCESS_TOKEN)
                prefs.remove(REFRESH_TOKEN)
            }
        }

        /** Explicit sign-out: forget the account. The server URL and device ID are kept. */
        suspend fun signOut() {
            dataStore.edit { prefs ->
                listOf(USER_ID, EMAIL, DISPLAY_NAME, FAMILY_ID, FAMILY_NAME, ACCESS_TOKEN, REFRESH_TOKEN)
                    .forEach(prefs::remove)
            }
        }

        private fun Preferences.account(): Account? {
            return Account(
                serverUrl = this[SERVER_URL] ?: return null,
                userId = this[USER_ID] ?: return null,
                email = this[EMAIL] ?: return null,
                displayName = this[DISPLAY_NAME] ?: return null,
                familyId = this[FAMILY_ID] ?: return null,
                familyName = this[FAMILY_NAME] ?: return null,
            )
        }

        private companion object {
            val DEVICE_ID = stringPreferencesKey("device_id")
            val SERVER_URL = stringPreferencesKey("server_url")
            val USER_ID = stringPreferencesKey("user_id")
            val EMAIL = stringPreferencesKey("email")
            val DISPLAY_NAME = stringPreferencesKey("display_name")
            val FAMILY_ID = stringPreferencesKey("family_id")
            val FAMILY_NAME = stringPreferencesKey("family_name")
            val ACCESS_TOKEN = stringPreferencesKey("access_token")
            val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        }
    }
