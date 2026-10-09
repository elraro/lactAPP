package eu.elraro.babytracker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.remote.AccessTokenInterceptor
import eu.elraro.babytracker.data.remote.ServerUrlInterceptor
import eu.elraro.babytracker.data.remote.TokenAuthenticator
import eu.elraro.babytracker.data.repository.AiRepository
import eu.elraro.babytracker.data.repository.AuthRepository
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.session.SecretCipher
import eu.elraro.babytracker.data.session.SessionStore
import eu.elraro.babytracker.di.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files

/** Reversible stand-in for the Keystore cipher, which only exists on a device. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String): String = "enc:" + plain.reversed()

    override fun decrypt(encrypted: String): String? =
        if (encrypted.startsWith("enc:")) encrypted.removePrefix("enc:").reversed() else null
}

/** A [SessionStore] backed by a real DataStore file in a temporary directory. */
class TestSession : AutoCloseable {
    private val dir: File = Files.createTempDirectory("session").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store =
        SessionStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "session.preferences_pb") },
            FakeCipher(),
        )

    override fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}

/**
 * The real data layer — session store, interceptors, authenticator, Retrofit, Room and
 * repository — wired exactly like the Hilt modules, talking to a [MockWebServer].
 * Needs Robolectric for the in-memory database.
 */
class TestBackend : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val serverUrl: String = server.url("/").toString().trimEnd('/')
    private val testSession = TestSession()
    val session: SessionStore = testSession.store
    val json = NetworkModule.json()
    val database: AppDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private val publicClient = NetworkModule.publicClient(ServerUrlInterceptor(session))
    private val authApi = NetworkModule.authApi(publicClient, json)
    private val authenticatedClient =
        NetworkModule.authenticatedClient(
            publicClient,
            AccessTokenInterceptor(session),
            TokenAuthenticator(session, { authApi }, json),
        )
    val accountApi = NetworkModule.accountApi(authenticatedClient, json)

    val ai = AiRepository(NetworkModule.aiApi(authenticatedClient, json), json)

    /** Signs in directly, for tests that are not about signing in. */
    suspend fun signedIn() {
        session.signIn(
            Account(serverUrl, "user-1", "a@example.com", "Ana", "family-1", "Casa"),
            "access-1",
            "refresh-1",
        )
    }

    /** How often the code under test asked for a background sync. */
    var syncRequests = 0
        private set

    val auth =
        AuthRepository(
            authApi = authApi,
            accountApi = accountApi,
            session = session,
            database = database,
            json = json,
            deviceName = { "Test phone" },
            sync = { syncRequests++ },
            io = Dispatchers.Unconfined,
        )

    fun enqueueJson(
        code: Int,
        body: String,
    ) {
        server.enqueue(MockResponse(code = code, body = body))
    }

    fun enqueueSession(
        access: String = "access-1",
        refresh: String = "refresh-1",
        familyId: String = "family-1",
        email: String = "a@example.com",
    ) = enqueueJson(200, sessionJson(access, refresh, familyId, email))

    fun enqueueError(
        code: Int,
        errorCode: String,
    ) = enqueueJson(code, """{"error":{"code":"$errorCode","message":"x"}}""")

    override fun close() {
        database.close()
        server.close()
        testSession.close()
    }
}

fun sessionJson(
    access: String,
    refresh: String,
    familyId: String = "family-1",
    email: String = "a@example.com",
): String =
    """
    {"access_token":"$access","expires_in":900,"refresh_token":"$refresh",
     "user":{"id":"user-1","email":"$email","display_name":"Ana"},
     "family":{"id":"$familyId","name":"Casa","timezone":"Europe/Madrid","ai_enabled":false,"version":1,"seq":1,
               "updated_at":"2026-10-08T12:00:00Z"},
     "server_time":"2026-10-08T12:00:00Z"}
    """.trimIndent()

/** A clock and zone the test controls. */
class FakeTime(
    var now: java.time.Instant = java.time.Instant.parse("2026-10-08T12:30:00Z"),
    var zone: java.time.ZoneId = java.time.ZoneId.of("Europe/Madrid"),
) : eu.elraro.babytracker.domain.TimeSource() {
    override fun now(): java.time.Instant = now

    override fun zone(): java.time.ZoneId = zone

    fun advance(duration: java.time.Duration) {
        now = now.plus(duration)
    }
}
