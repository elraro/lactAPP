package eu.elraro.babytracker.ui.common

import androidx.annotation.StringRes
import eu.elraro.babytracker.R
import eu.elraro.babytracker.data.remote.ApiError

/** Maps the server's stable error codes (docs/API.md §1) to localized text. */
@StringRes
fun ApiError.messageRes(): Int =
    when (this) {
        ApiError.Network -> R.string.error_network
        ApiError.Unexpected -> R.string.error_unexpected
        is ApiError.Server ->
            when (code) {
                "invalid_credentials" -> R.string.error_invalid_credentials
                "rate_limited" -> R.string.error_rate_limited
                "invite_expired" -> R.string.error_invite_expired
                "email_taken" -> R.string.error_email_taken
                "ai_disabled", "ai_not_configured" -> R.string.error_ai_disabled
                "ai_provider_error" -> R.string.error_ai_provider
                "validation_failed", "invalid_request" -> R.string.error_validation
                else -> R.string.error_unexpected
            }
    }
