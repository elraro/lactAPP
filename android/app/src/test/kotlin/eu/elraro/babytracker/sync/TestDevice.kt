package eu.elraro.babytracker.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import eu.elraro.babytracker.FakeTime
import eu.elraro.babytracker.TestSession
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.EventEntity
import eu.elraro.babytracker.data.remote.AccessTokenInterceptor
import eu.elraro.babytracker.data.remote.ServerUrlInterceptor
import eu.elraro.babytracker.data.remote.TokenAuthenticator
import eu.elraro.babytracker.data.repository.BabyRepository
import eu.elraro.babytracker.data.repository.EventRepository
import eu.elraro.babytracker.data.session.Account
import eu.elraro.babytracker.data.sync.SyncEngine
import eu.elraro.babytracker.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** One phone: its own database, session and sync engine, talking to a shared [FakeSyncServer]. */
class TestDevice(
    server: FakeSyncServer,
    val time: FakeTime = FakeTime(),
) : AutoCloseable {
    private val testSession = TestSession()
    val session = testSession.store
    val db: AppDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val json = NetworkModule.json()
    private val publicClient = NetworkModule.publicClient(ServerUrlInterceptor(session))
    private val authApi = NetworkModule.authApi(publicClient, json)
    private val client =
        NetworkModule.authenticatedClient(
            publicClient,
            AccessTokenInterceptor(session),
            TokenAuthenticator(session, { authApi }, json),
        )

    var syncRequests = 0
        private set
    val events = EventRepository(db, time) { syncRequests++ }
    val babies = BabyRepository(db, time) { syncRequests++ }
    val engine = SyncEngine(db, NetworkModule.syncApi(client, json), json, session, time, Dispatchers.Unconfined)

    init {
        runBlocking {
            session.signIn(
                Account(server.url, "user-1", "a@example.com", "Ana", "family-1", "Casa"),
                "access-1",
                "refresh-1",
            )
        }
    }

    suspend fun queue() = db.pendingMutationDao().nextBatch(1000)

    suspend fun event(id: String): EventEntity? = db.eventDao().get(id)

    /** Every event row, for comparing two devices. */
    suspend fun allEvents(): List<EventEntity> =
        db
            .eventDao()
            .syncedIds()
            .mapNotNull {
                db.eventDao().get(it)
            }.sortedBy { it.id }

    override fun close() {
        db.close()
        testSession.close()
    }
}
