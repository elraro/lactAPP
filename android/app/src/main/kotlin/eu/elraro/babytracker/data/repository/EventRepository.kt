package eu.elraro.babytracker.data.repository

import androidx.room.withTransaction
import eu.elraro.babytracker.data.local.AppDatabase
import eu.elraro.babytracker.data.local.EventEntity
import eu.elraro.babytracker.data.sync.SyncRequester
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventProblem
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.Ids
import eu.elraro.babytracker.domain.SleepRules
import eu.elraro.babytracker.domain.TimeSource
import eu.elraro.babytracker.domain.problem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveResult {
    data class Saved(
        val event: Event,
    ) : SaveResult

    data class Invalid(
        val problem: EventProblem,
    ) : SaveResult

    /** The sleep session would overlap [other]; nothing was saved. */
    data class OverlapsSleep(
        val other: Event,
    ) : SaveResult

    /** The event no longer exists on this device. */
    data object NotFound : SaveResult
}

/**
 * The only way the app changes events. Every change is written to Room together with a
 * queued mutation in one transaction; the network is never involved (AGENTS.md §8).
 */
@Singleton
class EventRepository
    @Inject
    constructor(
        private val db: AppDatabase,
        private val time: TimeSource,
        private val sync: SyncRequester,
    ) {
        private val events get() = db.eventDao()

        /** Events touching [from, to), including sleep that began before [from]. */
        fun observeTouching(
            babyId: String,
            from: Instant,
            to: Instant,
        ): Flow<List<Event>> =
            events
                .observeTouching(babyId, from.toEpochMilli(), to.toEpochMilli())
                .map { rows -> rows.mapNotNull { it.toDomain() } }

        fun observeLatest(
            babyId: String,
            type: EventType,
        ): Flow<Event?> = events.observeLatest(babyId, type.wire).map { it?.toDomain() }

        fun observeOpenSleep(babyId: String): Flow<Event?> = events.observeOpenSleep(babyId).map { it?.toDomain() }

        suspend fun logBottle(
            babyId: String,
            amountMl: Int,
        ): SaveResult = create(newEvent(babyId, EventType.BOTTLE).copy(amountMl = amountMl))

        suspend fun logDiaper(
            babyId: String,
            kind: DiaperKind,
        ): SaveResult = create(newEvent(babyId, EventType.DIAPER).copy(diaperKind = kind))

        /** Opens a sleep session now. Fails with [SaveResult.OverlapsSleep] if one is already open. */
        suspend fun startSleep(babyId: String): SaveResult {
            val event = newEvent(babyId, EventType.SLEEP)
            return create(event.copy(sleepKind = SleepRules.suggestKind(event.startedAt, time.zone())))
        }

        /** Closes the open session now; returns [SaveResult.NotFound] if the baby is not asleep. */
        suspend fun endSleep(babyId: String): SaveResult =
            db
                .withTransaction {
                    val open = events.openSleep(babyId) ?: return@withTransaction SaveResult.NotFound
                    // A start time in the future (edited, or a clock that moved) must not produce
                    // a negative duration.
                    val end = maxOf(time.now(), Instant.ofEpochMilli(open.startedAt))
                    val closed = open.copy(endedAt = end.toEpochMilli())
                    write(closed, open)
                    SaveResult.Saved(closed.toDomain()!!)
                }.also(::requestSyncIfSaved)

        /** A blank event for [type] at the current time and zone; not saved yet. */
        fun newEvent(
            babyId: String,
            type: EventType,
        ): Event {
            val now = time.now()
            return Event(
                id = Ids.uuidV7(now),
                babyId = babyId,
                type = type,
                startedAt = now,
                timezone = time.zone().id,
            )
        }

        suspend fun create(event: Event): SaveResult = save(event, mustExist = false)

        suspend fun update(event: Event): SaveResult = save(event, mustExist = true)

        private suspend fun save(
            event: Event,
            mustExist: Boolean,
        ): SaveResult {
            event.problem()?.let { return SaveResult.Invalid(it) }
            return db
                .withTransaction {
                    val old = events.get(event.id)?.takeIf { it.deletedAt == null }
                    if (mustExist && old == null) return@withTransaction SaveResult.NotFound
                    // The version always comes from the stored row, never from a stale UI copy.
                    val new = event.toEntity().copy(version = old?.version ?: 0)
                    overlappingSleep(new)?.let { return@withTransaction SaveResult.OverlapsSleep(it) }
                    write(new, old)
                    SaveResult.Saved(new.toDomain()!!)
                }.also(::requestSyncIfSaved)
        }

        /** Marks the event deleted. It stays on the device so the deletion can be undone. */
        suspend fun delete(id: String) {
            val deleted =
                db.withTransaction {
                    val old = events.get(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction false
                    val now = time.now()
                    events.upsert(old.copy(deletedAt = now.toEpochMilli()))
                    db.pendingMutationDao().insert(pendingMutation("event", id, "delete", null, old.version, now))
                    true
                }
            if (deleted) sync.requestSync()
        }

        private fun requestSyncIfSaved(result: SaveResult) {
            if (result is SaveResult.Saved) sync.requestSync()
        }

        /** Undoes [delete]. */
        suspend fun restore(id: String): SaveResult =
            db
                .withTransaction {
                    val deleted = events.get(id) ?: return@withTransaction SaveResult.NotFound
                    val restored = deleted.copy(deletedAt = null)
                    if (deleted.deletedAt != null) {
                        overlappingSleep(restored)?.let { return@withTransaction SaveResult.OverlapsSleep(it) }
                        events.upsert(restored)
                        db.pendingMutationDao().insert(
                            pendingMutation("event", id, "restore", null, deleted.version, time.now()),
                        )
                    }
                    SaveResult.Saved(restored.toDomain()!!)
                }.also(::requestSyncIfSaved)

        private suspend fun overlappingSleep(event: EventEntity): Event? {
            if (event.type != EventType.SLEEP.wire) return null
            return events
                .findOverlappingSleep(
                    babyId = event.babyId,
                    excludeId = event.id,
                    start = event.startedAt,
                    end = event.endedAt ?: Long.MAX_VALUE,
                )?.toDomain()
        }

        /** Stores [new] and queues what changed relative to [old] (null = creation). */
        private suspend fun write(
            new: EventEntity,
            old: EventEntity?,
        ) {
            val fields = if (old == null) new.createFields() else new.changedFields(old)
            if (old != null && fields.isEmpty()) return
            events.upsert(new)
            db.pendingMutationDao().insert(
                pendingMutation("event", new.id, "upsert", fields, baseVersion = old?.version ?: 0, now = time.now()),
            )
        }
    }
