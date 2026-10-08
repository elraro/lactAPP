package eu.elraro.babytracker.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val error: ApiError,
    ) : ApiResult<Nothing>
}

sealed interface ApiError {
    /** The server answered with one of the stable codes of docs/API.md. */
    data class Server(
        val status: Int,
        val code: String,
        val fields: Map<String, String> = emptyMap(),
    ) : ApiError

    /** No answer: offline, timeout, DNS, TLS. Always safe to retry. */
    data object Network : ApiError

    /** An answer we do not understand (wrong server, proxy error page). */
    data object Unexpected : ApiError
}

/** Runs a Retrofit call and turns every failure into a value. */
suspend fun <T> apiCall(
    json: Json,
    block: suspend () -> T,
): ApiResult<T> =
    try {
        ApiResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        ApiResult.Failure(e.toApiError(json))
    } catch (_: IOException) {
        ApiResult.Failure(ApiError.Network)
    } catch (_: SerializationException) {
        ApiResult.Failure(ApiError.Unexpected)
    }

private fun HttpException.toApiError(json: Json): ApiError =
    parseApiError(json, code(), response()?.errorBody()?.string().orEmpty())

/** Reads the `{"error":{"code":…}}` body of a non-2xx response. */
fun parseApiError(
    json: Json,
    status: Int,
    body: String,
): ApiError =
    try {
        val error = json.decodeFromString<ErrorResponse>(body).error
        ApiError.Server(status, error.code, error.fields)
    } catch (_: SerializationException) {
        ApiError.Unexpected
    } catch (_: IllegalArgumentException) {
        ApiError.Unexpected
    }
