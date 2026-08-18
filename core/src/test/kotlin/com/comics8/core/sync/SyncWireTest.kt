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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SyncWireTest {
    @Before
    fun resetCache() {
        SyncWire.clearProbeCache()
    }

    @Test
    fun mockV1ServerOmitsForeignAndKeepsEleven() {
        val captured = mutableListOf<String>()
        val http = mockHealth(200, body = """{"status":"ok","syncSchema":1}""") { request ->
            captured.add(request.url.toString())
            assertThat(request.header(SyncConstants.SCHEMA_HEADER)).isEqualTo("2")
        }
        val schema = SyncWire.probeServerSchema(http, "https://old.example/api/comics8/sync", "KEY")
        assertThat(schema).isEqualTo(ServerSchema.Known(1))
        assertThat(schema.commitForeign).isFalse()
        assertThat(captured.single()).isEqualTo("https://old.example/api/comics8/health")

        val filtered = SyncWire.filterOutbound(mixedChanges(), schema)
        assertThat(filtered.omittedForeignSource).isTrue()
        assertThat(SyncWire.shouldCommitForeign(schema, filtered.omittedForeignSource)).isFalse()

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

        val settings = filtered.json.getJSONArray("readerSettings")
        assertThat(settings.length()).isEqualTo(1)
        assertThat(settings.getJSONObject(0).getString("toonId")).isEqualTo("11")
        assertThat(settings.getJSONObject(0).getString("sourceId")).isEqualTo("eleven")

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
        assertThat(schema).isEqualTo(ServerSchema.Known(2))
        assertThat(schema.commitForeign).isTrue()

        val filtered = SyncWire.filterOutbound(mixedChanges(), schema)
        assertThat(filtered.omittedForeignSource).isFalse()
        assertThat(SyncWire.shouldCommitForeign(schema, filtered.omittedForeignSource)).isTrue()

        val favs = filtered.json.getJSONArray("favorites")
        assertThat(favs.length()).isEqualTo(2)
        val ids = (0 until favs.length()).map { i ->
            val fav = favs.getJSONObject(i)
            assertThat(fav.has("toonId")).isFalse()
            assertThat(fav.has("id")).isTrue()
            fav.getString("sourceId") to fav.getString("id")
        }
        assertThat(ids).containsExactly("eleven" to "11", "hitomi" to "99")

        val settings = filtered.json.getJSONArray("readerSettings")
        assertThat(settings.length()).isEqualTo(2)

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
    fun schema2InboundAppliesHitomiWorkId() {
        val hitomi = JSONObject().put("id", "99").put("sourceId", "hitomi")
        assertThat(SyncWire.workId(hitomi, preferId = true)).isEqualTo(WorkId("hitomi", "99"))
    }

    @Test
    fun omittedForeignStaysPendingUntilSchema2() {
        val elevenSince = 100L
        val foreignSince = 0L
        val v1 = ServerSchema.Known(1)
        assertThat(SyncWire.includeRow("hitomi", 50, elevenSince, foreignSince, v1)).isTrue()
        assertThat(SyncWire.includeRow("eleven", 150, elevenSince, foreignSince, v1)).isTrue()
        assertThat(SyncWire.localQuerySince(elevenSince, foreignSince, v1)).isEqualTo(0L)
        assertThat(SyncWire.requestSince(elevenSince, foreignSince, v1)).isEqualTo(100L)
        val filteredV1 = SyncWire.filterOutbound(mixedChanges(), v1)
        assertThat(filteredV1.omittedForeignSource).isTrue()
        assertThat(SyncWire.shouldCommitForeign(v1, filteredV1.omittedForeignSource)).isFalse()
        assertThat(SyncWire.withOmittedHint("ok", filteredV1.omittedForeignSource, v1))
            .contains(SyncWire.FOREIGN_OMITTED_SCHEMA1_MESSAGE)

        val v2 = ServerSchema.Known(2)
        assertThat(SyncWire.includeRow("hitomi", 50, elevenSince, foreignSince, v2)).isTrue()
        assertThat(SyncWire.includeRow("eleven", 50, elevenSince, foreignSince, v2)).isFalse()
        assertThat(SyncWire.localQuerySince(elevenSince, foreignSince, v2)).isEqualTo(0L)
        assertThat(SyncWire.requestSince(elevenSince, foreignSince, v2)).isEqualTo(0L)
        assertThat(SyncWire.includeTombstone("hitomi:99", 1, elevenSince, foreignSince, v2)).isTrue()
        val filteredV2 = SyncWire.filterOutbound(mixedChanges(), v2)
        assertThat(filteredV2.omittedForeignSource).isFalse()
        assertThat(SyncWire.shouldCommitForeign(v2, filteredV2.omittedForeignSource)).isTrue()
    }

    @Test
    fun transportFailureIsUnknownAndDoesNotCommit() {
        val http = mockHealth(500, body = "err", headerSchema = "2")
        val schema = SyncWire.probeServerSchema(http, "https://flaky.example/sync", "KEY")
        assertThat(schema).isEqualTo(ServerSchema.Unknown)
        assertThat(schema.omitForeign).isTrue()
        assertThat(schema.commitForeign).isFalse()
        assertThat(SyncWire.omittedMessage(schema)).isEqualTo(SyncWire.FOREIGN_OMITTED_UNKNOWN_MESSAGE)
    }

    @Test
    fun missingHealthJsonOn200IsSchema1() {
        assertThat(SyncWire.resolveSuccessfulHealth("""{"status":"ok"}""", null))
            .isEqualTo(ServerSchema.Known(1))
        assertThat(SyncWire.resolveSuccessfulHealth(null, null)).isEqualTo(ServerSchema.Known(1))
    }

    @Test
    fun bodySyncSchemaPreferredOverHeaderOnSuccess() {
        assertThat(SyncWire.resolveSuccessfulHealth("""{"syncSchema":1}""", "2"))
            .isEqualTo(ServerSchema.Known(1))
        assertThat(SyncWire.resolveSuccessfulHealth("""{"status":"ok"}""", "2"))
            .isEqualTo(ServerSchema.Known(2))
    }

    @Test
    fun probeCacheSkipsRepeatHealthAndFallsBackToLastKnown() {
        val hits = AtomicInteger(0)
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val n = hits.incrementAndGet()
                val (code, body) = if (n == 1) {
                    200 to """{"syncSchema":2}"""
                } else {
                    500 to "down"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toByteArray().toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val first = SyncWire.probeServerSchema(http, "https://cache.example/sync", "KEY", nowMs = 1_000L)
        val cached = SyncWire.probeServerSchema(http, "https://cache.example/sync", "KEY", nowMs = 2_000L)
        val afterTtlFail = SyncWire.probeServerSchema(
            http,
            "https://cache.example/sync",
            "KEY",
            nowMs = 1_000L + SyncConstants.SCHEMA_PROBE_TTL_MS + 1,
        )
        assertThat(first).isEqualTo(ServerSchema.Known(2))
        assertThat(cached).isEqualTo(ServerSchema.Known(2))
        assertThat(afterTtlFail).isEqualTo(ServerSchema.Known(2))
        assertThat(hits.get()).isEqualTo(2)
    }

    @Test
    fun healthUrlRewritesSyncVersionAndProxySuffixes() {
        assertThat(SyncConstants.healthUrl("https://h.example/api/comics8/sync"))
            .isEqualTo("https://h.example/api/comics8/health")
        assertThat(SyncConstants.healthUrl("https://h.example/api/comics8/version"))
            .isEqualTo("https://h.example/api/comics8/health")
        assertThat(SyncConstants.healthUrl("https://h.example/api/comics8/proxy"))
            .isEqualTo("https://h.example/api/comics8/health")
    }

    @Test
    fun blankSourceIdOnSettingsDefaultsToEleven() {
        val row = JSONObject().put("toonId", "11").put("viewMode", "SCROLL")
        val filtered = SyncWire.filterOutbound(
            JSONObject().put("readerSettings", JSONArray().put(row)),
            ServerSchema.Known(1),
        )
        val setting = filtered.json.getJSONArray("readerSettings").getJSONObject(0)
        assertThat(setting.getString("sourceId")).isEqualTo("eleven")
        assertThat(filtered.omittedForeignSource).isFalse()
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
                "readerSettings",
                JSONArray()
                    .put(JSONObject().put("toonId", "11").put("viewMode", "SCROLL"))
                    .put(JSONObject().put("toonId", "99").put("sourceId", "hitomi").put("viewMode", "DUAL")),
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
