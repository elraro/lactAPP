package eu.elraro.babytracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyDao {
    @Query("SELECT * FROM family LIMIT 1")
    fun observe(): Flow<FamilyEntity?>

    @Upsert
    suspend fun upsert(family: FamilyEntity)
}

@Dao
interface BabyDao {
    @Query("SELECT * FROM babies WHERE deleted_at IS NULL ORDER BY date_of_birth, id")
    fun observeAll(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM babies WHERE id = :id")
    suspend fun get(id: String): BabyEntity?

    /** Rows the server has acknowledged at some point (used when the server lost history). */
    @Query("SELECT id FROM babies WHERE version > 0")
    suspend fun syncedIds(): List<String>

    @Query("DELETE FROM babies WHERE id = :id")
    suspend fun purge(id: String)

    @Upsert
    suspend fun upsert(baby: BabyEntity)
}

@Dao
interface EventDao {
    /**
     * Everything that touches [from, to): events that start in the range, plus sleep that
     * started earlier and was still going on (ended later or not yet ended).
     */
    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND deleted_at IS NULL AND started_at < :to
          AND (started_at >= :from OR (type = 'sleep' AND (ended_at IS NULL OR ended_at >= :from)))
        ORDER BY started_at DESC, id DESC
        """,
    )
    fun observeTouching(
        babyId: String,
        from: Long,
        to: Long,
    ): Flow<List<EventEntity>>

    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND type = :type AND deleted_at IS NULL
        ORDER BY started_at DESC, id DESC LIMIT 1
        """,
    )
    fun observeLatest(
        babyId: String,
        type: String,
    ): Flow<EventEntity?>

    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND type = 'sleep' AND ended_at IS NULL AND deleted_at IS NULL
        ORDER BY started_at DESC, id DESC LIMIT 1
        """,
    )
    fun observeOpenSleep(babyId: String): Flow<EventEntity?>

    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND type = 'sleep' AND ended_at IS NULL AND deleted_at IS NULL
        ORDER BY started_at DESC, id DESC LIMIT 1
        """,
    )
    suspend fun openSleep(babyId: String): EventEntity?

    /**
     * Another sleep session whose interval intersects [start, end). An open session counts
     * as never ending; pass Long.MAX_VALUE as [end] for a session that is itself open.
     */
    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND type = 'sleep' AND deleted_at IS NULL AND id != :excludeId
          AND started_at < :end AND COALESCE(ended_at, 9223372036854775807) > :start
        ORDER BY started_at LIMIT 1
        """,
    )
    suspend fun findOverlappingSleep(
        babyId: String,
        excludeId: String,
        start: Long,
        end: Long,
    ): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT id FROM events WHERE version > 0")
    suspend fun syncedIds(): List<String>

    /** Every measurement of a baby, oldest first: the growth history. */
    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND type = 'measurement' AND deleted_at IS NULL
        ORDER BY started_at, id
        """,
    )
    fun observeMeasurements(babyId: String): Flow<List<EventEntity>>

    /** Live events of [babyId] that the server has never confirmed. */
    @Query("SELECT * FROM events WHERE baby_id = :babyId AND version = 0 AND deleted_at IS NULL")
    suspend fun neverUploaded(babyId: String): List<EventEntity>

    /** Physical removal. Only for rows the server does not have; user deletions are tombstones. */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun purge(id: String)

    @Upsert
    suspend fun upsert(event: EventEntity)
}

@Dao
interface PendingMutationDao {
    @Insert
    suspend fun insert(mutation: PendingMutationEntity): Long

    @Query("SELECT * FROM pending_mutations WHERE error_code IS NULL ORDER BY sequence LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<PendingMutationEntity>

    @Query("UPDATE pending_mutations SET sent = 1 WHERE sequence IN (:sequences)")
    suspend fun markSent(sequences: List<Long>)

    @Query("UPDATE pending_mutations SET error_code = :errorCode WHERE mutation_id = :mutationId")
    suspend fun markRejected(
        mutationId: String,
        errorCode: String,
    )

    @Query("DELETE FROM pending_mutations WHERE mutation_id = :mutationId")
    suspend fun delete(mutationId: String)

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE error_code IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE error_code IS NOT NULL")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM pending_mutations WHERE error_code IS NOT NULL ORDER BY sequence")
    suspend fun failed(): List<PendingMutationEntity>

    /** Never sent: these may still be merged or dropped (docs/SYNC.md §6). */
    @Query("SELECT * FROM pending_mutations WHERE sent = 0 AND error_code IS NULL ORDER BY sequence")
    suspend fun unsent(): List<PendingMutationEntity>

    /** Everything still waiting for [entityId], oldest first. */
    @Query("SELECT * FROM pending_mutations WHERE entity_id = :entityId AND error_code IS NULL ORDER BY sequence")
    suspend fun waitingFor(entityId: String): List<PendingMutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE entity_id = :entityId AND sent = 1 AND error_code IS NULL")
    suspend fun sentCount(entityId: String): Int

    /** Waiting or rejected: anything that shows the user changed this entity locally. */
    @Query("SELECT COUNT(*) FROM pending_mutations WHERE entity_id = :entityId")
    suspend fun countFor(entityId: String): Int

    @Update
    suspend fun update(mutation: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE sequence IN (:sequences)")
    suspend fun deleteSequences(sequences: List<Long>)

    @Query("DELETE FROM pending_mutations WHERE entity_id = :entityId")
    suspend fun deleteFor(entityId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}
