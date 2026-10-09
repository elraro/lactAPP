package eu.elraro.babytracker.data.repository

import eu.elraro.babytracker.data.remote.AiApi
import eu.elraro.babytracker.data.remote.AiStatusDto
import eu.elraro.babytracker.data.remote.AnalysisDto
import eu.elraro.babytracker.data.remote.AnalysisRequest
import eu.elraro.babytracker.data.remote.ApiResult
import eu.elraro.babytracker.data.remote.FamilyPatchRequest
import eu.elraro.babytracker.data.remote.apiCall
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional AI explanations. The phone never talks to an AI provider and holds no
 * key: it asks the family's server, which builds the context from statistics and applies
 * the consent rules (docs/AI.md).
 */
@Singleton
class AiRepository
    @Inject
    constructor(
        private val api: AiApi,
        private val json: Json,
    ) {
        suspend fun status(): ApiResult<AiStatusDto> = apiCall(json) { api.status() }

        /** Switches AI on or off for the whole family; takes effect on the server at once. */
        suspend fun setEnabled(enabled: Boolean): ApiResult<Unit> =
            apiCall(json) { api.setFamilyAi(FamilyPatchRequest(enabled)) }

        suspend fun analyze(
            babyId: String,
            kind: String,
            days: Int,
            locale: String,
        ): ApiResult<AnalysisDto> = apiCall(json) { api.analyze(AnalysisRequest(babyId, kind, days, locale)) }
    }
