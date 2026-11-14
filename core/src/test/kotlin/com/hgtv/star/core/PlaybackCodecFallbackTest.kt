// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All requests are intercepted; these cases model codec negotiation, not real media or keys. */
class PlaybackCodecFallbackTest {
    private val fallbackUrl = "https://media.example.invalid/model?existing=preserved"

    private fun inlineRow(codec: String, quality: String = "720p", url: String = "https://video.example.invalid/inline") =
        JSONObject().put("main_url", url)
            .put("video_meta", JSONObject().put("codec_type", codec).put("definition", quality))
            .put("encrypt_info", JSONObject().put("encrypt", false))

    private fun model(vararg rows: JSONObject): String = JSONObject()
        .put("code", 0).put("data", JSONObject().put("video_model", JSONObject()
            .put("video_list", JSONArray(rows.toList())).put("fallback_api", fallbackUrl))).toString()

    private fun legacy(vararg rows: Triple<String, Int, String>): String {
        val list = JSONObject()
        rows.forEachIndexed { i, (codec, quality, url) ->
            list.put("video_$i", JSONObject().put("codec_type", codec)
                .put("vheight", quality).put("main_url", url))
        }
        return JSONObject().put("code", 0)
            .put("video_info", JSONObject().put("data", JSONObject().put("video_list", list))).toString()
    }

    private data class Reply(val body: String, val status: Int = 200)

    private class Fixture(respond: (Request) -> Reply) : Closeable {
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val reply = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(reply.status).message("synthetic response").body(reply.body.toResponseBody()).build()
        }.build()
        val repository = ContentRepository(client).apply {
            deviceIdentity = mapOf("device_id" to "101", "iid" to "202")
        }
        fun fallbackRequests() = requests.filter { it.url.host == "media.example.invalid" }
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun isModel(request: Request) = request.url.encodedPath == "/novel/player/video_model/v1/"

    @Test fun emptyInlineListFallsBackToTheLegacyModel() {
        Fixture { request -> when {
            isModel(request) -> Reply(model())
            request.url.host == "media.example.invalid" -> Reply(legacy(Triple("h264", 720, "https://video.example.invalid/legacy")))
            else -> error("Unexpected request")
        } }.use { fixture ->
            val info = fixture.repository.stream("123", 720)
            assertEquals("https://video.example.invalid/legacy", info.url)
            assertEquals("720P", info.quality)
            assertEquals(1, fixture.fallbackRequests().size)
            assertNull(fixture.fallbackRequests().single().url.queryParameter("force_codec"))
        }
    }

    @Test fun entirelyByteVc2InlineListFallsBackInsteadOfFailingImmediately() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2"), inlineRow("bvc2", "1080p")))
            request.url.host == "media.example.invalid" -> Reply(legacy(Triple("h265", 480, "https://video.example.invalid/compatible")))
            else -> error("Unexpected request")
        } }.use { fixture ->
            val info = fixture.repository.stream("123", 720)
            assertEquals("https://video.example.invalid/compatible", info.url)
            assertEquals("480P", info.quality)
            assertEquals(1, fixture.fallbackRequests().size)
        }
    }

    @Test fun byteVc2LegacyModelRequestsCompatibilityOnceAndChecksTheActualReturnedCodecs() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> {
                if (request.url.queryParameter("force_codec") == null)
                    Reply(legacy(Triple("bytevc2", 720, "https://video.example.invalid/unsupported")))
                else Reply(legacy(
                    Triple("bytevc2", 720, "https://video.example.invalid/still-unsupported"),
                    Triple("h265", 480, "https://video.example.invalid/hevc-low"),
                    Triple("h265", 1080, "https://video.example.invalid/hevc-high")))
            }
            else -> error("Unexpected request")
        } }.use { fixture ->
            val info = fixture.repository.stream("123", 720)
            assertEquals("https://video.example.invalid/hevc-low", info.url)
            assertEquals("480P", info.quality)
            val requests = fixture.fallbackRequests()
            assertEquals(2, requests.size)
            assertNull(requests[0].url.queryParameter("codec_type"))
            assertEquals("0", requests[1].url.queryParameter("codec_type"))
            assertEquals("h264", requests[1].url.queryParameter("force_codec"))
            requests.forEach { assertEquals("preserved", it.url.queryParameter("existing")) }
        }
    }

    @Test fun compatibilityResponseStillContainingOnlyByteVc2IsNotAllowedToStartPlayback() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> Reply(legacy(
                Triple("ByteVC2", 720, "https://video.example.invalid/unsupported"),
                Triple("bvc2", 1080, "https://video.example.invalid/unsupported-high")))
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(2, fixture.fallbackRequests().size)
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun usableInlineRenditionDoesNotReadTheFallbackEndpoint() {
        Fixture { request ->
            if (!isModel(request)) error("Usable inline model must not fetch fallback")
            Reply(model(inlineRow("h264")))
        }.use { fixture ->
            assertEquals("https://video.example.invalid/inline", fixture.repository.stream("123", 720).url)
            assertTrue(fixture.fallbackRequests().isEmpty())
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun invalidUrlOnAnOtherwiseCompatibleLegacyCodecDoesNotTriggerBlindCodecNegotiation() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> Reply(legacy(Triple("h264", 720, "not a media address")))
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.fallbackRequests().size)
            assertNull(fixture.fallbackRequests().single().url.queryParameter("force_codec"))
        }
    }

    @Test fun fallbackHttpRejectionIsNotRetriedWithCompatibilityParameters() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> Reply("synthetic rejection", 403)
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.fallbackRequests().size)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test fun upstreamModelBusinessRejectionDoesNotReadAnyFallback() {
        Fixture { request ->
            if (!isModel(request)) error("Business rejection must stop before fallback")
            Reply("""{"code":110001,"data":{}}""")
        }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun malformedFallbackIsNotRetriedAsACodecFailure() {
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> Reply("""{"code":0,"video_info":{"data":{}}}""")
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.fallbackRequests().size)
        }
    }

    @Test fun fallbackBusinessRejectionStopsEvenWhenItsBodyContainsAnOldVideoList() {
        val rejected = JSONObject(legacy(Triple("bytevc2", 720, "https://video.example.invalid/stale")))
            .put("code", 110001).toString()
        Fixture { request -> when {
            isModel(request) -> Reply(model(inlineRow("bytevc2")))
            request.url.host == "media.example.invalid" -> Reply(rejected)
            else -> error("Unexpected request")
        } }.use { fixture ->
            val error = assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertTrue(error.message.orEmpty().contains("110001"))
            assertEquals(1, fixture.fallbackRequests().size)
            assertNull(fixture.fallbackRequests().single().url.queryParameter("force_codec"))
        }
    }
}
