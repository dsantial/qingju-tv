// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic suggestion responses only: no accounts, catalogue traffic or real network. */
class SeasonDiscoveryTest {
    private fun row(id: String, title: String, name: String = title) = JSONObject()
        .put("name", name).put("word_type", "short_play_name")
        .put("video_data", JSONObject().put("series_id", id).put("series_title", title)
            .put("episode_cnt", 12).put("series_cover", "https://images.example.invalid/poster"))

    private fun suggestions(vararg rows: JSONObject) = JSONObject()
        .put("suggest_list", JSONArray(rows.toList())).toString()

    private class Fixture(
        clock: () -> Long = System::currentTimeMillis,
        respond: (Request) -> String,
    ) : Closeable {
        val requests = mutableListOf<Request>()
        val callBudgetsMs = mutableListOf<Long>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "hongguoduanju.com" && request.url.encodedPath == "/incent_resource/suggestion") {
                "Unexpected request outside season suggestions"
            }
            requests += request
            callBudgetsMs += TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("synthetic response").body(respond(request).toResponseBody()).build()
        }.build()
        val repository = ContentRepository(client, clock)
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun queryUsesTheSeriesBaseAndDoesNotCarryAccountHeaders() {
        Fixture { suggestions(row("101", "测试系列第一季"), row("102", "测试系列第二季")) }.use { fixture ->
            fixture.repository.credentials = AccountCredentials(cookie = "sessionid=synthetic-cookie")
            fixture.repository.sessionToken = "synthetic-token"
            assertEquals(listOf("101"), fixture.repository.seasons("102", "测试系列第二季").map { it.id })
            val request = fixture.requests.single()
            assertEquals("GET", request.method)
            assertEquals("8662", request.url.queryParameter("app_id"))
            assertEquals("测试系列", request.url.queryParameter("query"))
            assertEquals("20", request.url.queryParameter("count"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("X-TT-Token"))
            assertNull(request.header("X-Medusa"))
            assertEquals(listOf(8000L), fixture.callBudgetsMs)
        }
    }

    @Test fun onlyPlayableSameFamilyIdsAreDeduplicatedAndSortedBySeason() {
        val unrelatedQuery = JSONObject().put("name", "测试系列第三季").put("word_type", "common_query")
            .put("video_data", JSONObject())
        Fixture { suggestions(
            row("103", "测试系列第三季"),
            row("102", "测试系列第二季"),
            row("201", "测试系列外传第一季"),
            unrelatedQuery,
            row("not-an-id", "测试系列第四季"),
            row("9".repeat(31), "测试系列第五季"),
            row("101", "测试系列第一季"),
            row("103", "测试系列第三季", "同一条目重复建议"),
            JSONObject().put("name", "测试系列第六季").put("word_type", "title")
        ) }.use { fixture ->
            val seasons = fixture.repository.seasons("102", "测试系列第二季")
            assertEquals(listOf("101", "103"), seasons.map { it.id })
            assertEquals(listOf(1, 3), seasons.map { SeriesSeasons.season(it.title) })
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun sameFamilyCacheCanBeReusedFromAnotherCurrentSeason() {
        Fixture { suggestions(row("101", "测试系列第一季"), row("102", "测试系列第二季"), row("103", "测试系列第三季")) }.use { fixture ->
            assertEquals(listOf("102", "103"), fixture.repository.seasons("101", "测试系列第一季").map { it.id })
            // The cache must retain the first current season too, not only its filtered siblings.
            assertEquals(listOf("101", "103"), fixture.repository.seasons("102", "测试系列第二季").map { it.id })
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun normalizedPunctuationAndWidthShareTheSameFamilyCache() {
        Fixture { suggestions(row("101", "测试，Ａ系列第一季"), row("102", "测试，Ａ系列第二季")) }.use { fixture ->
            assertEquals(listOf("102"), fixture.repository.seasons("101", "测试，Ａ系列第一季").map { it.id })
            assertEquals(listOf("101"), fixture.repository.seasons("102", "测试,A系列 S2").map { it.id })
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun positiveCacheExpiresAfterTenMinutes() {
        var now = 1000L
        Fixture(clock = { now }) { suggestions(row("101", "测试系列第一季"), row("102", "测试系列第二季")) }.use { fixture ->
            fixture.repository.seasons("101", "测试系列第一季")
            now += TimeUnit.MINUTES.toMillis(10) - 1
            fixture.repository.seasons("101", "测试系列第一季")
            assertEquals(1, fixture.requests.size)
            now += 2
            fixture.repository.seasons("101", "测试系列第一季")
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun emptyResultIsNotCached() {
        var calls = 0
        Fixture {
            calls++
            if (calls == 1) suggestions() else suggestions(row("102", "测试系列第二季"))
        }.use { fixture ->
            assertTrue(fixture.repository.seasons("101", "测试系列第一季").isEmpty())
            assertEquals(listOf("102"), fixture.repository.seasons("101", "测试系列第一季").map { it.id })
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun failedReadIsNotCachedAndTheNextCallCanRecover() {
        var calls = 0
        Fixture {
            calls++
            if (calls == 1) throw IOException("synthetic transport failure")
            suggestions(row("102", "测试系列第二季"))
        }.use { fixture ->
            val failed = runCatching { fixture.repository.seasons("101", "测试系列第一季") }
            assertTrue(failed.isFailure || failed.getOrThrow().isEmpty())
            assertEquals(listOf("102"), fixture.repository.seasons("101", "测试系列第一季").map { it.id })
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun differentFamiliesNeverReuseEachOthersCandidates() {
        Fixture { request ->
            when (request.url.queryParameter("query")) {
                "系列甲" -> suggestions(row("101", "系列甲第一季"), row("102", "系列甲第二季"))
                "系列乙" -> suggestions(row("201", "系列乙第一季"), row("202", "系列乙第二季"))
                else -> error("Unexpected family")
            }
        }.use { fixture ->
            assertEquals(listOf("102"), fixture.repository.seasons("101", "系列甲第一季").map { it.id })
            assertEquals(listOf("202"), fixture.repository.seasons("201", "系列乙第一季").map { it.id })
            assertEquals(listOf("101"), fixture.repository.seasons("102", "系列甲第二季").map { it.id })
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun blankFamilyDoesNotIssueARequest() {
        Fixture { error("Blank family must not contact suggestions") }.use { fixture ->
            assertTrue(fixture.repository.seasons("101", "  ").isEmpty())
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun positiveCacheDoesNotRetainMoreThanSixteenFamilies() {
        Fixture { request ->
            val family = request.url.queryParameter("query")!!
            val number = family.removePrefix("系列").toInt()
            suggestions(row((7000 + number).toString(), family + "第二季"))
        }.use { fixture ->
            for (i in 0..16) {
                assertEquals(listOf((7000 + i).toString()), fixture.repository.seasons("9999", "系列${i}第一季").map { it.id })
            }
            assertEquals(17, fixture.requests.size)
            // A cache capped at 16 cannot satisfy all 17 families without at least one new read.
            for (i in 0..16) fixture.repository.seasons("9999", "系列${i}第一季")
            assertTrue(fixture.requests.size > 17)
        }
    }
}
