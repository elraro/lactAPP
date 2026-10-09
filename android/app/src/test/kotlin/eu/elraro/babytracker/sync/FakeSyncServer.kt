package eu.elraro.babytracker.sync

import eu.elraro.babytracker.sessionJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * An in-memory server that follows docs/SYNC.md closely enough to test the client: recorded
 * mutation IDs, per-row versions, a change feed with cursors and pages, tombstones, and
 * delete-wins. The Go server's own behaviour is covered by its API tests.
 */
class FakeSyncServer : AutoCloseable {
    private class Row(
        val entity: String,
        val fields: MutableMap<String, JsonElement>,
        var version: Int,
        var seq: Long,
        var deletedAt: String?,
    )

    class Call(
        val path: String,
        val body: JsonObject?,
        val cursor: String?,
    )

    private val rows = LinkedHashMap<String, Row>()
    private val recorded = HashMap<String, Pair<String, String?>>() // mutation id -> status, error code
    private var seq = 1L
    private var epoch = "epoch-1"
    private val failures = HashMap<String, ArrayDeque<Int>>()

    val calls = mutableListOf<Call>()
    var pageSize = 500

    /** Return a reason to reject an upsert permanently, as the real server's validation would. */
    var rejectFields: (JsonObject) -> String? = { null }
    var refreshTokenAccepted = true

    val web =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        synchronized(this@FakeSyncServer) { handle(request) }
                }
            start()
        }
    val url: String = web.url("/").toString().trimEnd('/')

    /**
     * The next requests to a path ending in [path] are processed and then answered with these
     * statuses, one per request; [PASS] lets a request through normally.
     */
    fun failAfterProcessing(
        path: String,
        vararg statuses: Int,
    ) = synchronized(this) { failures.getOrPut(path) { ArrayDeque() }.addAll(statuses.toList()) }

    /** Simulates a server restored from an old backup: history is gone and cursors are invalid. */
    fun loseEverything() =
        synchronized(this) {
            rows.clear()
            recorded.clear()
            seq = 1
            epoch = "epoch-${System.nanoTime()}"
        }

    fun liveRows(entity: String): List<JsonObject> =
        synchronized(this) {
            rows
                .filter {
                    it.value.entity == entity &&
                        it.value.deletedAt == null
                }.map { data(it.key, it.value) }
        }

    fun pushes(): List<List<JsonObject>> =
        synchronized(this) {
            calls
                .filter {
                    it.path.endsWith("/sync/push")
                }.map { call -> call.body!!["mutations"]!!.jsonArray.map { it.jsonObject } }
        }

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val body =
            request.body
                ?.utf8()
                ?.takeIf { it.isNotBlank() }
                ?.let { Json.parseToJsonElement(it).jsonObject }
        calls += Call(path, body, request.url.queryParameter("cursor"))
        val response =
            when {
                path.endsWith("/auth/refresh") ->
                    if (refreshTokenAccepted) {
                        json(
                            200,
                            sessionJson("access-new", "refresh-new"),
                        )
                    } else {
                        error(401, "invalid_grant")
                    }
                request.headers["Authorization"] == null -> error(401, "unauthenticated")
                path.endsWith("/sync/push") -> push(body!!)
                path.endsWith("/sync") -> pull(request.url.queryParameter("cursor"))
                else -> error(404, "not_found")
            }
        val forced =
            failures.entries
                .firstOrNull {
                    path.endsWith(
                        it.key,
                    ) &&
                        it.value.isNotEmpty()
                }?.value
                ?.removeFirst()
        return when (forced) {
            null, PASS -> response
            401 -> error(401, "unauthenticated")
            else -> error(forced, "internal")
        }
    }

    private fun push(body: JsonObject): MockResponse {
        val results =
            buildJsonArray {
                for (element in body["mutations"]!!.jsonArray) {
                    val m = element.jsonObject
                    val mutationId = m["mutation_id"]!!.jsonPrimitive.content
                    val entityId = m["entity_id"]!!.jsonPrimitive.content
                    val (status, code) = recorded.getOrPut(mutationId) { apply(m, entityId) }
                    add(
                        buildJsonObject {
                            put("mutation_id", mutationId)
                            put("status", status)
                            code?.let { put("error_code", it) }
                            put("merged", false)
                            if (status == "applied") rows[entityId]?.let { put("data", data(entityId, it)) }
                        },
                    )
                }
            }
        return json(
            200,
            buildJsonObject {
                put("results", results)
                put("server_time", NOW)
            }.toString(),
        )
    }

    private fun apply(
        m: JsonObject,
        entityId: String,
    ): Pair<String, String?> {
        val row = rows[entityId]
        val fields = m["fields"]?.jsonObject ?: JsonObject(emptyMap())
        when (m["op"]!!.jsonPrimitive.content) {
            "upsert" -> upsert(m, entityId, row, fields)?.let { return "rejected" to it }
            "delete" -> {
                row ?: return "rejected" to "not_found"
                if (row.deletedAt == null) {
                    row.deletedAt = NOW
                    touch(row)
                }
            }
            "restore" -> {
                row ?: return "rejected" to "not_found"
                if (row.deletedAt != null) {
                    row.deletedAt = null
                    touch(row)
                }
            }
        }
        return "applied" to null
    }

    /** Creates or patches a row; returns an error code if the server would reject it. */
    private fun upsert(
        m: JsonObject,
        entityId: String,
        row: Row?,
        fields: JsonObject,
    ): String? {
        rejectFields(fields)?.let { return it }
        val entity = m["entity"]!!.jsonPrimitive.content
        if (row == null) {
            // Like the real server: a new row needs its required fields and an existing baby.
            val required = if (entity == "baby") "name" else "type"
            if (required !in fields) return "validation_failed"
            if (entity == "event" && fields["baby_id"]?.jsonPrimitive?.content !in rows) return "not_found"
            rows[entityId] = Row(entity, fields.toMutableMap(), 1, ++seq, null)
            return null
        }
        if (row.deletedAt != null) return "entity_deleted"
        row.fields.putAll(fields)
        touch(row)
        return null
    }

    private fun touch(row: Row) {
        row.version += 1
        row.seq = ++seq
    }

    private fun pull(cursor: String?): MockResponse {
        var after = 0L
        if (cursor != null) {
            val (cursorEpoch, cursorSeq) = cursor.split(":")
            if (cursorEpoch != epoch) return error(410, "cursor_expired")
            after = cursorSeq.toLong()
        }
        val family = if (after < 1) listOf(1L to change("family", FAMILY)) else emptyList()
        val changed =
            rows.filter { it.value.seq > after }.entries.sortedBy { it.value.seq }.map {
                it.value.seq to
                    change(it.value.entity, data(it.key, it.value))
            }
        val all = family + changed
        val page = all.take(pageSize)
        val next = page.lastOrNull()?.first ?: after
        return json(
            200,
            buildJsonObject {
                put("changes", buildJsonArray { page.forEach { add(it.second) } })
                put("next_cursor", "$epoch:$next")
                put("has_more", all.size > page.size)
                put("server_time", NOW)
            }.toString(),
        )
    }

    private fun change(
        entity: String,
        data: JsonObject,
    ) = buildJsonObject {
        put("entity", entity)
        put("data", data)
    }

    private fun data(
        id: String,
        row: Row,
    ) = JsonObject(
        row.fields +
            mapOf(
                "id" to JsonPrimitive(id),
                "version" to JsonPrimitive(row.version),
                "seq" to JsonPrimitive(row.seq),
                "deleted_at" to (row.deletedAt?.let(::JsonPrimitive) ?: JsonNull),
            ),
    )

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse(code = code, body = body)

    private fun error(
        code: Int,
        errorCode: String,
    ) = json(code, """{"error":{"code":"$errorCode","message":"x"}}""")

    override fun close() = web.close()

    companion object {
        /** Placeholder status for [failAfterProcessing]: answer this request normally. */
        const val PASS = 0
        private const val NOW = "2026-10-08T12:30:00Z"
        private val FAMILY =
            buildJsonObject {
                put("id", "family-1")
                put("name", "Casa")
                put("timezone", "Europe/Madrid")
                put("ai_enabled", false)
                put("version", 1)
            }
    }
}

fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content
