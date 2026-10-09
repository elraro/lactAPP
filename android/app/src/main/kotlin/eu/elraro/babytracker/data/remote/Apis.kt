package eu.elraro.babytracker.data.remote

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

/** Endpoints that do not need an access token. */
interface AuthApi {
    @POST("auth/login")
    suspend fun login(
        @Body body: LoginRequest,
    ): SessionResponse

    /**
     * Not a suspend function on purpose: the authenticator executes it synchronously on the
     * thread of the failed call. An enqueued call would wait for one of OkHttp's per-host
     * slots, which are all held by the very calls waiting for this refresh.
     */
    @POST("auth/refresh")
    fun refresh(
        @Body body: RefreshRequest,
    ): Call<SessionResponse>

    @POST("auth/invites/redeem")
    suspend fun redeem(
        @Body body: RedeemRequest,
    ): SessionResponse
}

/** Endpoints called with the access token; a 401 triggers one transparent refresh. */
interface AccountApi {
    @POST("auth/logout")
    suspend fun logout()
}

/** Optional AI explanations (docs/AI.md). Online only: consent is never queued. */
interface AiApi {
    @GET("ai/status")
    suspend fun status(): AiStatusDto

    @PATCH("family")
    suspend fun setFamilyAi(
        @Body body: FamilyPatchRequest,
    )

    @POST("ai/analyses")
    suspend fun analyze(
        @Body body: AnalysisRequest,
    ): AnalysisDto
}

/** The synchronization protocol of docs/SYNC.md. */
interface SyncApi {
    @GET("sync")
    suspend fun pull(
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): PullResponse

    @POST("sync/push")
    suspend fun push(
        @Body body: PushRequest,
    ): PushResponse
}
