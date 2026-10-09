package eu.elraro.babytracker.data.sync

import eu.elraro.babytracker.data.local.PendingMutationEntity
import kotlinx.serialization.json.JsonObject

/** What to do to the queue before sending: rows to remove and rows to rewrite. */
data class CoalescePlan(
    val delete: List<Long> = emptyList(),
    val update: List<PendingMutationEntity> = emptyList(),
) {
    operator fun plus(other: CoalescePlan) = CoalescePlan(delete + other.delete, update + other.update)
}

/** What the coalescer needs to know about the entity a group of mutations belongs to. */
data class EntitySnapshot(
    /** The server has never been told about this entity (version 0, nothing already sent). */
    val unknownToServer: Boolean,
    /** The local row is currently deleted, or does not exist. */
    val deleted: Boolean,
    /** All fields of the local row as a create payload; null if the row does not exist. */
    val createFields: JsonObject?,
)

/**
 * Shrinks the queue of mutations that have never been sent (docs/SYNC.md §6). A mutation
 * that was sent — even if the answer was lost — is never touched: its content is what the
 * server may have recorded under that mutation ID.
 */
object Coalescer {
    suspend fun plan(
        unsent: List<PendingMutationEntity>,
        snapshot: suspend (PendingMutationEntity) -> EntitySnapshot,
    ): CoalescePlan =
        unsent
            .groupBy { it.entity to it.entityId }
            .values
            .map { group ->
                val entity = snapshot(group.first())
                if (entity.unknownToServer) collapseUnknown(group, entity) else merge(group)
            }.fold(CoalescePlan(), CoalescePlan::plus)

    /**
     * The server has never seen the entity, so only its current state matters: either it
     * still exists and is created in one mutation, or it was deleted again and nothing is
     * sent at all.
     */
    private fun collapseUnknown(
        group: List<PendingMutationEntity>,
        entity: EntitySnapshot,
    ): CoalescePlan {
        val fields = entity.createFields
        if (entity.deleted || fields == null) return CoalescePlan(delete = group.map { it.sequence })
        val create = group.first().copy(op = OP_UPSERT, fieldsJson = fields.toString(), baseVersion = 0)
        return CoalescePlan(
            delete = group.drop(1).map { it.sequence },
            update = listOfNotNull(create.takeIf { it != group.first() }),
        )
    }

    /** For an entity the server knows: delete+restore cancel out, consecutive edits become one. */
    private fun merge(group: List<PendingMutationEntity>): CoalescePlan {
        val kept = ArrayDeque<PendingMutationEntity>()
        val delete = mutableListOf<Long>()
        val changed = mutableSetOf<Long>()
        for (mutation in group) {
            val top = kept.lastOrNull()
            when {
                mutation.op == OP_RESTORE && top?.op == OP_DELETE -> {
                    kept.removeLast()
                    delete += listOf(top.sequence, mutation.sequence)
                    changed -= top.sequence
                }
                mutation.op == OP_UPSERT && top?.op == OP_UPSERT -> {
                    // Later values win; the earliest base version is the one both edits started from.
                    val fields = JsonObject(top.fields() + mutation.fields())
                    kept[kept.lastIndex] = top.copy(fieldsJson = fields.toString())
                    delete += mutation.sequence
                    changed += top.sequence
                }
                else -> kept += mutation
            }
        }
        return CoalescePlan(delete = delete, update = kept.filter { it.sequence in changed })
    }
}
