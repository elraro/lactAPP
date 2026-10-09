package eu.elraro.babytracker.sync

import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.data.sync.Coalescer
import eu.elraro.babytracker.data.sync.EntitySnapshot
import eu.elraro.babytracker.data.sync.fields
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescerTest {
    private var next = 1L

    private fun mutation(
        op: String,
        fields: String? = null,
        entityId: String = "e1",
        baseVersion: Int = 0,
    ) = PendingMutationEntity(
        sequence = next,
        mutationId = "m${next++}",
        entity = "event",
        entityId = entityId,
        op = op,
        fieldsJson = fields,
        baseVersion = baseVersion,
        createdAt = 0,
    )

    private val known = EntitySnapshot(unknownToServer = false, deleted = false, createFields = null)

    private fun unknown(
        current: String?,
        deleted: Boolean = false,
    ) = EntitySnapshot(true, deleted, current?.let { Json.parseToJsonElement(it).jsonObject })

    @Test
    fun `a new event edited several times becomes one create with its current state`() =
        runTest {
            val create = mutation("upsert", """{"type":"bottle","amount_ml":120}""")
            val edit1 = mutation("upsert", """{"amount_ml":150}""")
            val edit2 = mutation("upsert", """{"notes":"x"}""")

            val plan =
                Coalescer.plan(
                    listOf(create, edit1, edit2),
                ) { unknown("""{"type":"bottle","amount_ml":150,"notes":"x"}""") }

            assertEquals(listOf(edit1.sequence, edit2.sequence), plan.delete)
            val merged = plan.update.single()
            // The first mutation's identity is kept; its content is the row as it is now.
            assertEquals(create.mutationId, merged.mutationId)
            assertEquals(setOf("type", "amount_ml", "notes"), merged.fields().keys)
            assertEquals(0, merged.baseVersion)
        }

    @Test
    fun `a new event that was deleted again is never sent`() =
        runTest {
            val group =
                listOf(
                    mutation("upsert", """{"type":"bottle"}"""),
                    mutation("upsert", """{"amount_ml":1}"""),
                    mutation("delete"),
                )
            val plan = Coalescer.plan(group) { unknown("""{"type":"bottle"}""", deleted = true) }
            assertEquals(group.map { it.sequence }, plan.delete)
            assertTrue(plan.update.isEmpty())
        }

    @Test
    fun `undoing the deletion of a new event sends a single create`() =
        runTest {
            // create and delete were already dropped by an earlier cycle; only the restore is left.
            val restore = mutation("restore")
            val plan = Coalescer.plan(listOf(restore)) { unknown("""{"type":"bottle","amount_ml":90}""") }
            val create = plan.update.single()
            assertEquals("upsert", create.op)
            assertEquals(setOf("type", "amount_ml"), create.fields().keys)
            assertTrue(plan.delete.isEmpty())
        }

    @Test
    fun `edits of a synced event merge into one with the earliest base version`() =
        runTest {
            val first = mutation("upsert", """{"amount_ml":150,"notes":"a"}""", baseVersion = 3)
            val second = mutation("upsert", """{"notes":null}""", baseVersion = 3)

            val plan = Coalescer.plan(listOf(first, second)) { known }

            assertEquals(listOf(second.sequence), plan.delete)
            val merged = plan.update.single()
            assertEquals("""{"amount_ml":150,"notes":null}""", merged.fieldsJson)
            assertEquals(3, merged.baseVersion)
            assertEquals(first.mutationId, merged.mutationId)
        }

    @Test
    fun `delete followed by undo cancels out, with edits around it kept`() =
        runTest {
            val edit = mutation("upsert", """{"amount_ml":150}""", baseVersion = 2)
            val delete = mutation("delete", baseVersion = 2)
            val restore = mutation("restore", baseVersion = 2)
            val later = mutation("upsert", """{"notes":"y"}""", baseVersion = 2)

            val plan = Coalescer.plan(listOf(edit, delete, restore, later)) { known }

            assertEquals(setOf(delete.sequence, restore.sequence, later.sequence), plan.delete.toSet())
            assertEquals("""{"amount_ml":150,"notes":"y"}""", plan.update.single().fieldsJson)
        }

    @Test
    fun `an edit followed by a delete of a synced event is sent as both`() =
        runTest {
            val plan =
                Coalescer.plan(
                    listOf(
                        mutation("upsert", """{"amount_ml":1}""", baseVersion = 1),
                        mutation("delete", baseVersion = 1),
                    ),
                ) {
                    known
                }
            assertTrue(plan.delete.isEmpty() && plan.update.isEmpty())
        }

    @Test
    fun `entities are coalesced independently`() =
        runTest {
            val a1 = mutation("upsert", """{"amount_ml":1}""", entityId = "a", baseVersion = 1)
            val b1 = mutation("upsert", """{"amount_ml":2}""", entityId = "b", baseVersion = 1)
            val a2 = mutation("upsert", """{"amount_ml":3}""", entityId = "a", baseVersion = 1)

            val plan = Coalescer.plan(listOf(a1, b1, a2)) { known }

            assertEquals(listOf(a2.sequence), plan.delete)
            assertEquals("""{"amount_ml":3}""", plan.update.single().fieldsJson)
        }
}
