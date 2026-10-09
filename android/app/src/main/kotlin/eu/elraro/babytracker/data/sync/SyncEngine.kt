package eu.elraro.babytracker.data.sync

import androidx.room.withTransaction
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.data.local.SyncStateEntity
import eu.elraro.babytracker.data.remote.ApiError
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.remote.ChangeDto
import eu.elraro.babytracker.data.remote.PullResponse
import eu.elraro.babytracker.data.remote.PushRequest
import eu.elraro.babytracker.data.remote.PushResultDto
import eu.elraro.babytracker.data.remote.RemoteBaby
import eu.elraro.babytracker.data.remote.RemoteEvent
import eu.elraro.babytracker.data.remote.RemoteFamily
import eu.elraro.babytracker.data.remote.SyncApi
import eu.elraro.babytracker.data.remote.apiCall
import eu.elraro.babytracker.data.repository.createFields
import eu.elraro.babytracker.data.repository.pendingMutation
import eu.elraro.babytracker.data.session.SessionState
import eu.elraro.babytracker.data.session.SessionStore
import eu.elraro.babytracker.di.IoDispatcher
import eu.elraro.babytracker.domain.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncOutcome {
    /** Everything queued was uploaded and everything new was downloaded. */
    SUCCESS,

    /** Could not finish (offline, server trouble). Nothing is lost; try again later. */
    RETRY,

    /** There is no valid session. The queue is kept until the user signs in again. */
    SIGNED_OUT,
}

/**
 * One synchronization cycle: push the queue, then pull changes (docs/SYNC.md §6).
 * Only one cycle runs at a time; a second caller waits for the first and then runs its own,
 * so a change made during a cycle is never left behind.
 */
@Singleton
class SyncEngine
    @Inject
    constructor(
        private val db: AppDatabase,
        private val api: SyncApi,
        private val json: Json,
        private val session: SessionStore,
        private val time: TimeSource,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : SyncRunner {
        private val mutex = Mutex()
        private val pending get() = db.pendingMutationDao()

        private class Stop(
            val outcome: SyncOutcome,
        ) : Exception()

        override suspend fun sync(): SyncOutcome =
            withContext(io) {
                mutex.withLock {
                    if (session.state.first() !is SessionState.SignedIn) return@withLock SyncOutcome.SIGNED_OUT
                    try {
                        push()
                        pull()
                        SyncOutcome.SUCCESS
                    } catch (stop: Stop) {
                        stop.outcome
                    }
                }
            }

        // ---- push ----

        private suspend fun push() {
            while (true) {
                val batch =
                    db.withTransaction {
                        coalesce()
                        pending.nextBatch(PUSH_BATCH).also { batch ->
                            // From here on these mutations are frozen: the request may reach the
                            // server even if we never see the answer.
                            pending.markSent(batch.map { it.sequence })
                        }
                    }
                if (batch.isEmpty()) return
                val request = PushRequest(batch.map { it.toWire() })
                when (val result = apiCall(json) { api.push(request) }) {
                    is ApiResult.Ok ->
                        db.withTransaction {
                            val byId = batch.associateBy { it.mutationId }
                            result.value.results.forEach { res -> byId[res.mutationId]?.let { applyResult(it, res) } }
                            recordServerTime(result.value.serverTime)
                        }
                    is ApiResult.Failure -> onPushFailure(result.error, batch)
                }
            }
        }

        private suspend fun coalesce() {
            val plan =
                Coalescer.plan(pending.unsent()) { mutation ->
                    val nothingSent = pending.sentCount(mutation.entityId) == 0
                    if (mutation.entity == ENTITY_BABY) {
                        val row = db.babyDao().get(mutation.entityId)
                        EntitySnapshot(row?.version == 0 && nothingSent, row?.deletedAt != null, row?.createFields())
                    } else {
                        val row = db.eventDao().get(mutation.entityId)
                        EntitySnapshot(row?.version == 0 && nothingSent, row?.deletedAt != null, row?.createFields())
                    }
                }
            plan.update.forEach { pending.update(it) }
            if (plan.delete.isNotEmpty()) pending.deleteSequences(plan.delete)
        }

        private suspend fun applyResult(
            mutation: PendingMutationEntity,
            result: PushResultDto,
        ) {
            when {
                result.status == STATUS_APPLIED -> {
                    pending.delete(mutation.mutationId)
                    result.data?.let { applyChange(ChangeDto(mutation.entity, it)) }
                }
                result.errorCode == ERROR_ENTITY_DELETED -> {
                    // Delete wins (docs/SYNC.md §5): another device removed it; drop our edit.
                    pending.delete(mutation.mutationId)
                    markDeletedLocally(mutation)
                }
                else -> pending.markRejected(mutation.mutationId, result.errorCode ?: ERROR_UNKNOWN)
            }
        }

        private suspend fun markDeletedLocally(mutation: PendingMutationEntity) {
            val now = time.now().toEpochMilli()
            if (mutation.entity == ENTITY_BABY) {
                db
                    .babyDao()
                    .get(
                        mutation.entityId,
                    )?.let { db.babyDao().upsert(it.copy(deletedAt = it.deletedAt ?: now)) }
            } else {
                db.eventDao().get(mutation.entityId)?.let {
                    db.eventDao().upsert(it.copy(deletedAt = it.deletedAt ?: now))
                }
            }
        }

        private suspend fun onPushFailure(
            error: ApiError,
            batch: List<PendingMutationEntity>,
        ): Nothing {
            if (error is ApiError.Server && error.status in PERMANENT_REQUEST_ERRORS) {
                // The server refuses the request itself, so retrying it unchanged can never
                // work. Park the batch as failed instead of blocking the queue forever.
                db.withTransaction { batch.forEach { pending.markRejected(it.mutationId, error.code) } }
            }
            throw Stop(outcomeAfterFailure())
        }

        /** Every failure is retried later, unless the authenticator had to expire the session. */
        private suspend fun outcomeAfterFailure(): SyncOutcome =
            if (session.state.first() is SessionState.SignedIn) SyncOutcome.RETRY else SyncOutcome.SIGNED_OUT

        // ---- pull ----

        private suspend fun pull() {
            // Non-null while recovering from a server that lost history: IDs seen in the fresh snapshot.
            var snapshot: MutableSet<String>? = null
            var more = true
            while (more) {
                val cursor = db.syncStateDao().get()?.cursor
                when (val result = apiCall(json) { api.pull(cursor, PULL_PAGE) }) {
                    is ApiResult.Ok -> {
                        storePage(result.value, snapshot)
                        more = result.value.hasMore
                    }
                    is ApiResult.Failure -> {
                        // A server that lost its history refuses our cursor: start over, once.
                        val expired = (result.error as? ApiError.Server)?.code == ERROR_CURSOR_EXPIRED
                        if (!expired || snapshot != null) throw Stop(outcomeAfterFailure())
                        snapshot = mutableSetOf()
                        saveState { it.copy(cursor = null) }
                    }
                }
            }
            // Rows queued again by the reconciliation are uploaded right away.
            if (snapshot != null) push()
        }

        /** Stores one page and moves the cursor, in the same transaction. */
        private suspend fun storePage(
            page: PullResponse,
            snapshot: MutableSet<String>?,
        ) = db.withTransaction {
            for (change in page.changes) {
                val id = applyChange(change)
                if (id != null) snapshot?.add(id)
            }
            saveState { it.copy(cursor = page.nextCursor) }
            recordServerTime(page.serverTime)
            if (!page.hasMore) {
                snapshot?.let { reconcileWithSnapshot(it) }
                saveState { it.copy(lastSuccessAt = time.now().toEpochMilli()) }
            }
        }

        /** Applies one change; returns the ID of the row, or null if it was not understood. */
        private suspend fun applyChange(change: ChangeDto): String? =
            try {
                applyRemote(change.entity, change.data)
            } catch (_: SerializationException) {
                null // a shape from a newer server version; skip it rather than block the feed
            } catch (_: DateTimeParseException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        /**
         * Stores a row sent by the server unless the local copy is already at that version,
         * then re-applies local changes that are still waiting to be uploaded.
         */
        internal suspend fun applyRemote(
            entity: String,
            data: JsonObject,
        ): String? {
            val now = time.now().toEpochMilli()
            return when (entity) {
                ENTITY_EVENT -> {
                    val remote = json.decodeFromJsonElement<RemoteEvent>(data).toEntity()
                    val local = db.eventDao().get(remote.id)
                    if (local == null || local.version < remote.version) {
                        db.eventDao().upsert(
                            pending.waitingFor(remote.id).fold(remote) { row, m -> row.rebase(m, now) },
                        )
                    }
                    remote.id
                }
                ENTITY_BABY -> {
                    val remote = json.decodeFromJsonElement<RemoteBaby>(data).toEntity()
                    val local = db.babyDao().get(remote.id)
                    if (local == null || local.version < remote.version) {
                        db.babyDao().upsert(pending.waitingFor(remote.id).fold(remote) { row, m -> row.rebase(m, now) })
                    }
                    remote.id
                }
                ENTITY_FAMILY -> {
                    val remote = json.decodeFromJsonElement<RemoteFamily>(data).toEntity()
                    db.familyDao().upsert(remote)
                    remote.id
                }
                else -> null
            }
        }

        /**
         * After the server lost history (restored from a backup): rows it once confirmed but
         * no longer has are removed from the phone — unless they carry work the server never
         * confirmed. Those are queued again as new rows in their current state, babies before
         * their events, so nothing recorded on this phone disappears.
         */
        private suspend fun reconcileWithSnapshot(seen: Set<String>) {
            val now = time.now()
            val events = db.eventDao()
            val babies = db.babyDao()

            val lostEvents = (events.syncedIds() - seen).mapNotNull { events.get(it) }
            val keptEvents = lostEvents.filter { it.deletedAt == null && pending.countFor(it.id) > 0 }
            for (event in lostEvents - keptEvents.toSet()) {
                pending.deleteFor(event.id)
                events.purge(event.id)
            }

            for (baby in (babies.syncedIds() - seen).mapNotNull { babies.get(it) }) {
                val neverUploaded = events.neverUploaded(baby.id)
                val inUse =
                    pending.countFor(baby.id) > 0 ||
                        neverUploaded.isNotEmpty() ||
                        keptEvents.any { it.babyId == baby.id }
                pending.deleteFor(baby.id)
                if (!inUse || baby.deletedAt != null) {
                    babies.purge(baby.id)
                    continue
                }
                babies.upsert(baby.copy(version = 0))
                pending.insert(pendingMutation(ENTITY_BABY, baby.id, OP_UPSERT, baby.createFields(), 0, now))
                // Their creations may already have been refused for want of the baby: queue them afresh.
                for (event in neverUploaded) {
                    pending.deleteFor(event.id)
                    pending.insert(pendingMutation(ENTITY_EVENT, event.id, OP_UPSERT, event.createFields(), 0, now))
                }
            }

            for (event in keptEvents) {
                pending.deleteFor(event.id)
                events.upsert(event.copy(version = 0))
                pending.insert(pendingMutation(ENTITY_EVENT, event.id, OP_UPSERT, event.createFields(), 0, now))
            }
        }

        // ---- failed changes ----

        /**
         * Gives up on the changes the server rejected: the affected rows go back to what the
         * server has (or disappear if it never had them).
         */
        override suspend fun discardFailed() {
            withContext(io) {
                mutex.withLock {
                    db.withTransaction {
                        for (mutation in pending.failed()) {
                            pending.deleteFor(mutation.entityId)
                            forgetLocalChanges(mutation)
                        }
                        // Start the feed again so the server's copies are downloaded.
                        saveState { it.copy(cursor = null) }
                    }
                }
            }
        }

        /** Makes the next pull overwrite the row, or removes it if the server never had it. */
        private suspend fun forgetLocalChanges(mutation: PendingMutationEntity) {
            if (mutation.entity == ENTITY_BABY) {
                val row = db.babyDao().get(mutation.entityId) ?: return
                if (row.version == 0) db.babyDao().purge(row.id) else db.babyDao().upsert(row.copy(version = 0))
            } else {
                val row = db.eventDao().get(mutation.entityId) ?: return
                if (row.version == 0) db.eventDao().purge(row.id) else db.eventDao().upsert(row.copy(version = 0))
            }
        }

        // ---- state ----

        private suspend fun saveState(change: (SyncStateEntity) -> SyncStateEntity) {
            val current =
                db.syncStateDao().get()
                    ?: SyncStateEntity(cursor = null, lastSuccessAt = null, serverTimeOffsetMs = null)
            db.syncStateDao().upsert(change(current))
        }

        private suspend fun recordServerTime(serverTime: String) {
            val server = runCatching { Instant.parse(serverTime) }.getOrNull() ?: return
            saveState { it.copy(serverTimeOffsetMs = server.toEpochMilli() - time.now().toEpochMilli()) }
        }

        private companion object {
            const val PUSH_BATCH = 200
            const val PULL_PAGE = 500
            const val STATUS_APPLIED = "applied"
            const val ERROR_ENTITY_DELETED = "entity_deleted"
            const val ERROR_CURSOR_EXPIRED = "cursor_expired"
            const val ERROR_UNKNOWN = "unknown"

            // 400 and 413: the request body itself is unacceptable. 401/403/404/429 and 5xx are
            // about the session, the address or the moment, and are retried.
            val PERMANENT_REQUEST_ERRORS = setOf(400, 413)
        }
    }
