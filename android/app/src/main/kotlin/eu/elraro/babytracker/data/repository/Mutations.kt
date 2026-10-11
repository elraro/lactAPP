package eu.elraro.babytracker.data.repository

import eu.elraro.babytracker.data.local.BabyEntity
import eu.elraro.babytracker.data.local.EventEntity
import eu.elraro.babytracker.data.local.PendingMutationEntity
import eu.elraro.babytracker.domain.Baby
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.Ids
import eu.elraro.babytracker.domain.MilkKind
import eu.elraro.babytracker.domain.SleepKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

// Conversions between Room rows, domain models and the `fields` object of a sync
// mutation (docs/SYNC.md §4). Field names are the server's.

fun EventEntity.toDomain(): Event? {
    return Event(
        id = id,
        babyId = babyId,
        // A type added by a newer app version: not shown, and never modified, by this one.
        type = EventType.fromWire(type) ?: return null,
        startedAt = Instant.ofEpochMilli(startedAt),
        endedAt = endedAt?.let(Instant::ofEpochMilli),
        timezone = timezone,
        amountMl = amountMl,
        milkKind = MilkKind.fromWire(milkKind),
        diaperKind = DiaperKind.fromWire(diaperKind),
        sleepKind = SleepKind.fromWire(sleepKind),
        weightG = weightG,
        heightMm = heightMm,
        headMm = headMm,
        notes = notes,
        version = version,
    )
}

fun Event.toEntity(deletedAt: Long? = null): EventEntity =
    EventEntity(
        id = id,
        babyId = babyId,
        type = type.wire,
        startedAt = startedAt.toEpochMilli(),
        endedAt = endedAt?.toEpochMilli(),
        timezone = timezone,
        amountMl = amountMl,
        milkKind = milkKind?.wire,
        diaperKind = diaperKind?.wire,
        sleepKind = sleepKind?.wire,
        weightG = weightG,
        heightMm = heightMm,
        headMm = headMm,
        notes = notes?.takeIf { it.isNotBlank() },
        version = version,
        deletedAt = deletedAt,
    )

fun BabyEntity.toDomain(): Baby = Baby(id, name, LocalDate.parse(dateOfBirth), notes)

private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun number(value: Int?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun instant(millis: Long?): JsonElement =
    millis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull

/** Every writable field of an event, as sent to the server. */
private fun EventEntity.fields(): Map<String, JsonElement> =
    mapOf(
        "baby_id" to text(babyId),
        "type" to text(type),
        "started_at" to instant(startedAt),
        "ended_at" to instant(endedAt),
        "timezone" to text(timezone),
        "amount_ml" to number(amountMl),
        "milk_kind" to text(milkKind),
        "diaper_kind" to text(diaperKind),
        "sleep_kind" to text(sleepKind),
        "weight_g" to number(weightG),
        "height_mm" to number(heightMm),
        "head_mm" to number(headMm),
        "notes" to text(notes),
    )

/** Fields for creating the event: everything that has a value. */
fun EventEntity.createFields(): JsonObject = JsonObject(fields().filterValues { it != JsonNull })

/** Only the fields that differ from [old] — this is what makes edits merge per field. */
fun EventEntity.changedFields(old: EventEntity): JsonObject {
    val before = old.fields()
    return JsonObject(fields().filter { (key, value) -> before[key] != value })
}

/** Every writable field of a baby, as sent to the server. */
private fun BabyEntity.fields(): Map<String, JsonElement> =
    mapOf("name" to text(name), "date_of_birth" to text(dateOfBirth), "sex" to text(sex), "notes" to text(notes))

fun BabyEntity.createFields(): JsonObject = JsonObject(fields().filterValues { it != JsonNull })

fun BabyEntity.changedFields(old: BabyEntity): JsonObject {
    val before = old.fields()
    return JsonObject(fields().filter { (key, value) -> before[key] != value })
}

fun pendingMutation(
    entity: String,
    entityId: String,
    op: String,
    fields: JsonObject?,
    baseVersion: Int,
    now: Instant,
) = PendingMutationEntity(
    mutationId = Ids.uuidV7(now),
    entity = entity,
    entityId = entityId,
    op = op,
    fieldsJson = fields?.toString(),
    baseVersion = baseVersion,
    createdAt = now.toEpochMilli(),
)
