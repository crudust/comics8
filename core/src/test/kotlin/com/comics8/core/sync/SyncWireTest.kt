package com.comics8.core.sync

import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class SyncWireTest {
    @Test
    fun mockV1ServerOmitsHitomiAndKeepsEleven() {
        val captured = mutableListOf<String>()
        val http = mockHealth(404, body = "not found") { request ->
            captured.add(request.url.toString())
            assertThat(request.header(SyncConstants.SCHEMA_HEADER)).isEqualTo("2")
        }
        val schema = SyncWire.probeServerSchema(http, "https://old.example/api/comics8/sync", "KEY")
        assertThat(schema).isEqualTo(1)
        assertThat(captured.single()).isEqualTo("https://old.example/api/comics8/health")

        val filtered = SyncWire.filterOutbound(mixedChanges(), schema)
        assertThat(filtered.omittedHitomi).isTrue()

        val favs = filtered.json.getJSONArray("favorites")
        assertThat(favs.length()).isEqualTo(1)
        assertThat(favs.getJSONObject(0).getString("id")).isEqualTo("11")
        assertThat(favs.getJSONObject(0).getString("sourceId")).isEqualTo("eleven")
        assertThat(favs.getJSONObject(0).has("toonId")).isFalse()

        val hist = filtered.json.getJSONArray("history")
        assertThat(hist.length()).isEqualTo(1)
        assertThat(hist.getJSONObject(0).getString("toonId")).isEqualTo("11")

        val eps = filtered.json.getJSONArray("readEpisodes")
        assertThat(eps.length()).isEqualTo(1)
        assertThat(eps.getJSONObject(0).getString("toonId")).isEqualTo("11")

        val tombs = filtered.json.getJSONArray("tombstones")
        assertThat(tombs.length()).isEqualTo(1)
        assertThat(tombs.getJSONObject(0).getString("entityId")).isEqualTo("11")
    }

    @Test
    fun v2EmitUsesFavoriteIdNotToonIdAndPrefixesTombstones() {
        val http = mockHealth(
            200,
            body = """{"status":"ok","syncSchema":2}""",
            headerSchema = "2",
        )
        val schema = SyncWire.probeServerSchema(http, "https://new.example/sync", "KEY")
        assertThat(schema).isEqualTo(2)

        val filtered = SyncWire.filterOutbound(mixedChanges(), schema)
        assertThat(filtered.omittedHitomi).isFalse()

        val favs = filtered.json.getJSONArray("favorites")
        assertThat(favs.length()).isEqualTo(2)
        val ids = (0 until favs.length()).map { i ->
            val fav = favs.getJSONObject(i)
            assertThat(fav.has("toonId")).isFalse()
            assertThat(fav.has("id")).isTrue()
            fav.getString("sourceId") to fav.getString("id")
        }
        assertThat(ids).containsExactly("eleven" to "11", "hitomi" to "99")

        val tombs = filtered.json.getJSONArray("tombstones")
        val tombIds = (0 until tombs.length()).map { tombs.getJSONObject(it).getString("entityId") }
        assertThat(tombIds).containsExactly("eleven:11", "hitomi:99")
    }

    @Test
    fun oldElevenDeltasStillApplyWithoutSourceId() {
        val fav = JSONObject().put("id", "12345").put("title", "원피스")
        assertThat(SyncWire.workId(fav, preferId = true)).isEqualTo(WorkId.eleven("12345"))

        val hist = JSONObject()
            .put("toonId", "12345")
            .put("toonTitle", "원피스")
            .put("lastWrId", "9")
        assertThat(SyncWire.workId(hist, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val ep = JSONObject().put("toonId", "12345").put("wrId", "9")
        assertThat(SyncWire.workId(ep, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val setting = JSONObject().put("toonId", "12345").put("viewMode", "SCROLL")
        assertThat(SyncWire.workId(setting, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val unprefixedTomb = WorkId.parse("12345")
        assertThat(unprefixedTomb).isEqualTo(WorkId.eleven("12345"))
    }

    @Test
    fun missingSchemaDefaultsToOne() {
        assertThat(SyncWire.resolveServerSchema(null, null)).isEqualTo(1)
        assertThat(SyncWire.resolveServerSchema("{}", null)).isEqualTo(1)
        assertThat(SyncWire.resolveServerSchema("""{"status":"ok"}""", "")).isEqualTo(1)
    }

    @Test
    fun headerEchoWinsOverMissingBodyField() {
        assertThat(SyncWire.resolveServerSchema("""{"status":"ok"}""", "2")).isEqualTo(2)
    }

    private fun mixedChanges(): JSONObject {
        return JSONObject()
            .put(
                "favorites",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "11")
                            .put("sourceId", "eleven")
                            .put("title", "Eleven"),
                    )
                    .put(
                        JSONObject()
                            .put("toonId", "99")
                            .put("sourceId", "hitomi")
                            .put("title", "Hitomi"),
                    ),
            )
            .put(
                "history",
                JSONArray()
                    .put(JSONObject().put("toonId", "11").put("sourceId", "eleven").put("toonTitle", "E"))
                    .put(JSONObject().put("toonId", "99").put("sourceId", "hitomi").put("toonTitle", "H")),
            )
            .put(
                "readEpisodes",
                JSONArray()
                    .put(JSONObject().put("toonId", "11").put("sourceId", "eleven").put("wrId", "a"))
                    .put(JSONObject().put("toonId", "99").put("sourceId", "hitomi").put("wrId", "b")),
            )
            .put(
                "tombstones",
                JSONArray()
                    .put(JSONObject().put("entityType", "FAVORITE").put("entityId", "eleven:11").put("deletedAt", 1))
                    .put(JSONObject().put("entityType", "FAVORITE").put("entityId", "hitomi:99").put("deletedAt", 1)),
            )
    }

    private fun mockHealth(
        code: Int,
        body: String,
        headerSchema: String? = null,
        inspect: ((okhttp3.Request) -> Unit)? = null,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                inspect?.invoke(chain.request())
                val builder = Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toByteArray().toResponseBody("application/json".toMediaType()))
                if (headerSchema != null) {
                    builder.header(SyncConstants.SCHEMA_HEADER, headerSchema)
                }
                builder.build()
            })
            .build()
    }
}
