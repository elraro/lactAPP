package eu.elraro.babytracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    @Upsert
    suspend fun upsert(baby: BabyEntity)
}

@Dao
interface EventDao {
    /** Events that start in [from, to), newest first. Sleep that began earlier is not included. */
    @Query(
        """
        SELECT * FROM events
        WHERE baby_id = :babyId AND deleted_at IS NULL AND started_at >= :from AND started_at < :to
        ORDER BY started_at DESC, id DESC
        """,
    )
    fun observeStartedBetween(
        babyId: String,
        from: Long,
        to: Long,
    ): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

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
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}
