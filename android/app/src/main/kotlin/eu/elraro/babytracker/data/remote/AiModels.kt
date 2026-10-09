package eu.elraro.babytracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models of docs/API.md §6.

@Serializable
data class AiStatusDto(
    /** The server operator set up a provider. */
    val configured: Boolean,
    /** The family switched AI on. */
    val enabled: Boolean,
    val provider: String? = null,
    @SerialName("remaining_today") val remainingToday: Int = 0,
)

@Serializable
data class FamilyPatchRequest(
    @SerialName("ai_enabled") val aiEnabled: Boolean,
)

@Serializable
data class AnalysisRequest(
    @SerialName("baby_id") val babyId: String,
    /** overview | sleep | feeding */
    val kind: String,
    val days: Int,
    /** en | es — the language the explanation is written in. */
    val locale: String,
)

@Serializable
data class AnalysisDto(
    val id: String,
    val kind: String,
    val provider: String,
    val result: AnalysisResultDto,
)

@Serializable
data class AnalysisResultDto(
    val observations: List<AnalysisTextDto>,
    val interpretation: List<AnalysisTextDto>,
    val suggestions: List<AnalysisTextDto>,
)

@Serializable
data class AnalysisTextDto(
    val text: String,
)
