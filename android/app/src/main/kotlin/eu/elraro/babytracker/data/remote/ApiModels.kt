package eu.elraro.babytracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models of docs/API.md §2. Field names follow the server's snake_case.

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val device: DeviceDto,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class RedeemRequest(
    val code: String,
    val password: String,
    val device: DeviceDto,
    // Only sent when joining a family with an invitation.
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class FamilyDto(
    val id: String,
    val name: String,
    val timezone: String,
    @SerialName("ai_enabled") val aiEnabled: Boolean,
)

@Serializable
data class SessionResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val user: UserDto,
    val family: FamilyDto,
)

@Serializable
data class ErrorResponse(
    val error: ErrorBody,
)

@Serializable
data class ErrorBody(
    val code: String,
    val fields: Map<String, String> = emptyMap(),
)
