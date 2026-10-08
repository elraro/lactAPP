package eu.elraro.babytracker.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    /** Path under which every endpoint lives. */
    const val API_PATH = "api/v1/"

    sealed interface Parsed {
        data class Valid(
            val url: String,
        ) : Parsed

        data object Invalid : Parsed

        /** Plain HTTP where only HTTPS is acceptable. */
        data object Insecure : Parsed
    }

    /**
     * Turns what a person typed ("baby.example.com", "https://host/ ") into a canonical
     * base URL without trailing slash. HTTPS is assumed when no scheme is given.
     */
    fun parse(
        input: String,
        allowCleartext: Boolean,
    ): Parsed {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Parsed.Invalid
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return Parsed.Invalid
        if (url.query != null || url.fragment != null || url.username.isNotEmpty()) return Parsed.Invalid
        if (!url.isHttps && !allowCleartext) return Parsed.Insecure
        return Parsed.Valid(url.toString().trimEnd('/'))
    }

    /** Base URL for Retrofit: the server URL plus the API path. */
    fun apiBase(serverUrl: String): HttpUrl? = "$serverUrl/$API_PATH".toHttpUrlOrNull()
}
