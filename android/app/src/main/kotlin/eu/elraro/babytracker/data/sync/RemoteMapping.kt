package eu.elraro.babytracker.data.sync

import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.data.local.EventEntity
import eu.elraro.babytracker.data.local.FamilyEntity
import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.data.remote.RemoteBaby
import eu.elraro.babytracker.data.remote.RemoteEvent
import eu.elraro.babytracker.data.remote.RemoteFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

const val ENTITY_EVENT = "event"
const val ENTITY_BABY = "baby"
const val ENTITY_FAMILY = "family"

const val OP_UPSERT = "upsert"
const val OP_DELETE = "delete"
const val OP_RESTORE = "restore"

private fun millis(instant: String?): Long? = instant?.let { Instant.parse(it).toEpochMilli() }

fun RemoteFamily.toEntity() = FamilyEntity(id, name, timezone, aiEnabled, version)

fun RemoteBaby.toEntity() = BabyEntity(id, name, dateOfBirth, sex, notes, version, millis(deletedAt))

fun RemoteEvent.toEntity() =
    EventEntity(
        id = id,
        babyId = babyId,
        type = type,
        startedAt = Instant.parse(startedAt).toEpochMilli(),
        endedAt = millis(endedAt),
        timezone = timezone,
        amountMl = amountMl,
        milkKind = milkKind,
        diaperKind = diaperKind,
        sleepKind = sleepKind,
        weightG = weightG,
        heightMm = heightMm,
        headMm = headMm,
        notes = notes,
        version = version,
        deletedAt = millis(deletedAt),
    )

fun PendingMutationEntity.fields(): JsonObject =
    fieldsJson?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())

/** The JSON object sent to the server for this mutation. */
fun PendingMutationEntity.toWire(): JsonObject =
    buildJsonObject {
        put("mutation_id", mutationId)
        put("entity", entity)
        put("entity_id", entityId)
        put("op", op)
        put("base_version", baseVersion)
        fieldsJson?.let { put("fields", Json.parseToJsonElement(it)) }
    }

private fun JsonElement.text(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull

private fun JsonElement.int(): Int? = if (this is JsonNull) null else jsonPrimitive.intOrNull

/**
 * Re-applies a local change that the server has not confirmed yet on top of the row the
 * server just sent, so the user's edit stays visible (docs/SYNC.md §6, "rebase on pull").
 */
fun EventEntity.rebase(
    mutation: PendingMutationEntity,
    now: Long,
): EventEntity =
    when (mutation.op) {
        OP_DELETE -> copy(deletedAt = deletedAt ?: now)
        OP_RESTORE -> copy(deletedAt = null)
        else -> mutation.fields().entries.fold(this) { row, (key, value) -> row.withField(key, value) }
    }

private fun EventEntity.withField(
    key: String,
    value: JsonElement,
): EventEntity =
    when (key) {
        "started_at" -> copy(startedAt = millis(value.text()) ?: startedAt)
        "ended_at" -> copy(endedAt = millis(value.text()))
        "timezone" -> copy(timezone = value.text() ?: timezone)
        "amount_ml" -> copy(amountMl = value.int())
        "milk_kind" -> copy(milkKind = value.text())
        "diaper_kind" -> copy(diaperKind = value.text())
        "sleep_kind" -> copy(sleepKind = value.text())
        "weight_g" -> copy(weightG = value.int())
        "height_mm" -> copy(heightMm = value.int())
        "head_mm" -> copy(headMm = value.int())
        "notes" -> copy(notes = value.text())
        // baby_id and type cannot change after creation.
        else -> this
    }

fun BabyEntity.rebase(
    mutation: PendingMutationEntity,
    now: Long,
): BabyEntity =
    when (mutation.op) {
        OP_DELETE -> copy(deletedAt = deletedAt ?: now)
        OP_RESTORE -> copy(deletedAt = null)
        else ->
            mutation.fields().entries.fold(this) { row, (key, value) ->
                when (key) {
                    "name" -> row.copy(name = value.text() ?: row.name)
                    "date_of_birth" -> row.copy(dateOfBirth = value.text() ?: row.dateOfBirth)
                    "sex" -> row.copy(sex = value.text())
                    "notes" -> row.copy(notes = value.text())
                    else -> row
                }
            }
    }
