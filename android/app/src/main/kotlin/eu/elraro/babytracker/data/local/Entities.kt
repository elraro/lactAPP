package eu.elraro.babytracker.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Local mirror of the syncable server tables (docs/DATABASE.md §5).
// Instants are epoch milliseconds in UTC. `version` 0 means "not yet known to the server".

@Entity(tableName = "family")
data class FamilyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val timezone: String,
    @ColumnInfo(name = "ai_enabled") val aiEnabled: Boolean,
    val version: Int,
)

@Entity(tableName = "babies")
data class BabyEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ISO local date, e.g. 2026-07-01. */
    @ColumnInfo(name = "date_of_birth") val dateOfBirth: String,
    val sex: String?,
    val notes: String?,
    val version: Int,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

@Entity(
    tableName = "events",
    indices = [Index("baby_id", "started_at")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "baby_id") val babyId: String,
    /** bottle | diaper | sleep | measurement | note */
    val type: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    /** IANA zone of the device that recorded the event. */
    val timezone: String,
    @ColumnInfo(name = "amount_ml") val amountMl: Int?,
    @ColumnInfo(name = "milk_kind") val milkKind: String?,
    @ColumnInfo(name = "diaper_kind") val diaperKind: String?,
    @ColumnInfo(name = "sleep_kind") val sleepKind: String?,
    @ColumnInfo(name = "weight_g") val weightG: Int?,
    @ColumnInfo(name = "height_mm") val heightMm: Int?,
    @ColumnInfo(name = "head_mm") val headMm: Int?,
    val notes: String?,
    val version: Int,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** One queued change waiting to be pushed (docs/SYNC.md §6). Rows are sent in [sequence] order. */
@Entity(
    tableName = "pending_mutations",
    indices = [Index("entity_id"), Index(value = ["mutation_id"], unique = true)],
)
data class PendingMutationEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    @ColumnInfo(name = "mutation_id") val mutationId: String,
    /** event | baby */
    val entity: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    /** upsert | delete | restore */
    val op: String,
    /** JSON object with only the changed fields, exactly as it will be sent. */
    @ColumnInfo(name = "fields_json") val fieldsJson: String?,
    @ColumnInfo(name = "base_version") val baseVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** True once the mutation may have reached the server; it must then never change. */
    val sent: Boolean = false,
    /** Set when the server rejected the mutation permanently. */
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)

/** Single row (id = 1) holding the pull cursor. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val cursor: String?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "server_time_offset_ms") val serverTimeOffsetMs: Long?,
)
