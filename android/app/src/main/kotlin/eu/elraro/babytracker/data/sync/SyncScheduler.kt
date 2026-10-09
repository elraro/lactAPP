package eu.elraro.babytracker.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for a synchronization "soon". Implemented by WorkManager; a no-op in tests. */
fun interface SyncRequester {
    fun requestSync()
}

/**
 * Background synchronization through WorkManager: it waits for a connection, retries with
 * exponential backoff and survives the app being closed (docs/ARCHITECTURE.md §5).
 */
@Singleton
class SyncScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SyncRequester {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Called after every local change. Several changes in a row result in one run. */
        override fun requestSync() {
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(online)
                    .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Keeps the device up to date while the app is not open. Safe to call repeatedly. */
        fun schedulePeriodic() {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(online)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        }

        private companion object {
            const val WORK_NOW = "sync-now"
            const val WORK_PERIODIC = "sync-periodic"
            const val DEBOUNCE_SECONDS = 2L
            const val BACKOFF_SECONDS = 30L
            const val PERIOD_MINUTES = 15L
        }
    }

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncEngine(): SyncEngine
    }

    override suspend fun doWork(): Result {
        val engine = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).syncEngine()
        return when (engine.sync()) {
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            // Nothing to do until the user signs in; signing in requests a sync again.
            SyncOutcome.SIGNED_OUT -> Result.success()
        }
    }
}
