package eu.elraro.babytracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire models of docs/SYNC.md §3–4 and the entity payloads of docs/API.md §4.

@Serializable
data class PullResponse(
    val changes: List<ChangeDto>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class ChangeDto(
    /** family | baby | event — or something newer, which this version ignores. */
    val entity: String,
    val data: JsonObject,
)

@Serializable
data class PushRequest(
    /** Built by hand so that `fields` is sent byte-for-byte as it was queued. */
    val mutations: List<JsonObject>,
)

@Serializable
data class PushResponse(
    val results: List<PushResultDto>,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class PushResultDto(
    @SerialName("mutation_id") val mutationId: String,
    /** applied | rejected */
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
    val data: JsonObject? = null,
)

@Serializable
data class RemoteFamily(
    val id: String,
    val name: String,
    val timezone: String,
    @SerialName("ai_enabled") val aiEnabled: Boolean,
    val version: Int,
)

@Serializable
data class RemoteBaby(
    val id: String,
    val name: String,
    @SerialName("date_of_birth") val dateOfBirth: String,
    val sex: String? = null,
    val notes: String? = null,
    val version: Int,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class RemoteEvent(
    val id: String,
    @SerialName("baby_id") val babyId: String,
    val type: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val timezone: String,
    @SerialName("amount_ml") val amountMl: Int? = null,
    @SerialName("milk_kind") val milkKind: String? = null,
    @SerialName("diaper_kind") val diaperKind: String? = null,
    @SerialName("sleep_kind") val sleepKind: String? = null,
    @SerialName("weight_g") val weightG: Int? = null,
    @SerialName("height_mm") val heightMm: Int? = null,
    @SerialName("head_mm") val headMm: Int? = null,
    val notes: String? = null,
    val version: Int,
    @SerialName("deleted_at") val deletedAt: String? = null,
)
