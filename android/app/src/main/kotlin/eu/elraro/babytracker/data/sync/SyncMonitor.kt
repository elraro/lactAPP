package eu.elraro.babytracker.data.sync

import eu.elraro.babytracker.data.local.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SyncStatus(
    /** Changes recorded on this phone that the server has not confirmed yet. */
    val pending: Int = 0,
    /** Changes the server refused; they need the user's attention (Settings). */
    val failed: Int = 0,
    /** null until the first complete synchronization. */
    val lastSuccessAt: Instant? = null,
    /** The phone's clock differs from the server's by more than the tolerated skew. */
    val clockSkewed: Boolean = false,
)

/** Runs synchronization on demand. An interface so view-model tests need no server. */
interface SyncRunner {
    suspend fun sync(): SyncOutcome

    suspend fun discardFailed()
}

@Singleton
class SyncMonitor
    @Inject
    constructor(
        db: AppDatabase,
    ) {
        val status: Flow<SyncStatus> =
            combine(
                db.pendingMutationDao().observePendingCount(),
                db.pendingMutationDao().observeFailedCount(),
                db.syncStateDao().observe(),
            ) { pending, failed, state ->
                SyncStatus(
                    pending = pending,
                    failed = failed,
                    lastSuccessAt = state?.lastSuccessAt?.let(Instant::ofEpochMilli),
                    clockSkewed = abs(state?.serverTimeOffsetMs ?: 0) > MAX_CLOCK_SKEW.toMillis(),
                )
            }

        private companion object {
            // docs/ARCHITECTURE.md §7
            val MAX_CLOCK_SKEW: Duration = Duration.ofMinutes(2)
        }
    }
