package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

/** Exercise anonymous playback and catalogue identity independently, without a real service. */
class PlaybackDeviceProfileTest {
    private fun televisionProfile() = linkedMapOf(
        "device_type" to "Test Television",
        "device_brand" to "Test Brand",
        "os_version" to "5.1.1",
        "os_api" to "22",
        "cdid" to "test-local-cdid",
        "openudid" to "test-local-openudid",
        "rom_version" to "test-local-rom"
    )

    private class Fixture : Closeable {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
            val body = when (request.url.encodedPath) {
                "/service/2/device_register/" -> {
                    response.addHeader("Set-Cookie", "install_id=202; Path=/; HttpOnly")
                    response.addHeader("Set-Cookie", "ttreq=test-registration-cookie; Path=/")
                    """{"device_id":"101","install_id":"202"}"""
                }
                "/novel/player/video_model/v1/" ->
                    """{"code":0,"data":{"video_model":{"fallback_api":"https://example.com/model"}}}"""
                "/model" ->
                    """{"video_info":{"data":{"video_list":{"video_0":{"vheight":720,"codec_type":"h264","main_url":"https://example.com/video"}}}}}"""
                "/reading/bookapi/search/tab/v" ->
                    """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}"""
                else -> error("Unexpected network request: ${request.url.encodedPath}")
            }
            response.body(body.toResponseBody()).build()
        }.build()
        val repository = ContentRepository(client)
        fun playbackRequest() = requests.last { it.url.encodedPath == "/novel/player/video_model/v1/" }
        fun catalogueRequest() = requests.last { it.url.encodedPath == "/reading/bookapi/search/tab/v" }
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun playbackAutomaticallyRegistersOnceAndUsesTheIssuedIdentityWithoutCookies() = Fixture().use { fixture ->
        val repo = fixture.repository
        repo.registerProfile = televisionProfile().apply {
            // Stale profile values cannot override the registration result.
            put("device_id", "999")
            put("iid", "888")
        }
        assertEquals("https://example.com/video", repo.stream("123").url)
        repo.stream("456")
        assertEquals(1,fixture.requests.count { it.url.encodedPath == "/service/2/device_register/" })

        val request = fixture.playbackRequest()
        assertEquals("101",request.url.queryParameter("device_id"))
        assertEquals("202",request.url.queryParameter("iid"))
        // The single-video signature has its own protocol profile; TV details remain catalogue-only.
        assertEquals("25053RT47C",request.url.queryParameter("device_type"))
        assertEquals("16",request.url.queryParameter("os_version"))
        assertEquals("36",request.url.queryParameter("os_api"))
        assertEquals("73532",request.url.queryParameter("version_code"))
        assertNull(request.url.queryParameter("cdid"))
        assertNull(request.url.queryParameter("openudid"))
        assertNull(request.header("Cookie"))
    }

    @Test fun catalogueKeepsAccountOverridesWhilePlaybackRemainsAnonymous() = Fixture().use { fixture ->
        val repo = fixture.repository
        repo.registerProfile = televisionProfile()
        assertNotNull(repo.registerDevice())
        repo.credentials = AccountCredentials(
            cookie = "sessionid=test-account-cookie",
            device = mapOf("device_type" to "Account Device", "device_id" to "303", "iid" to "404", "device_brand" to " ")
        )
        repo.search("test",1,ContentType.COMIC)
        val catalogue = fixture.catalogueRequest()
        assertEquals("Account Device",catalogue.url.queryParameter("device_type"))
        assertEquals("303",catalogue.url.queryParameter("device_id"))
        assertEquals("404",catalogue.url.queryParameter("iid"))
        assertEquals("Test Brand",catalogue.url.queryParameter("device_brand"))
        assertEquals("22",catalogue.url.queryParameter("os_api"))
        assertEquals("test-local-cdid",catalogue.url.queryParameter("cdid"))
        assertEquals("sessionid=test-account-cookie",catalogue.header("Cookie"))

        repo.stream("123")
        val playback = fixture.playbackRequest()
        assertEquals("101",playback.url.queryParameter("device_id"))
        assertEquals("202",playback.url.queryParameter("iid"))
        assertEquals("25053RT47C",playback.url.queryParameter("device_type"))
        assertNull(playback.header("Cookie"))
        assertNull(playback.header("X-TT-Token"))
    }

    @Test fun inheritedRepositoryKeepsIdentityAndCatalogueProfileWithoutRegisteringAgain() = Fixture().use { source ->
        Fixture().use { target ->
            source.repository.registerProfile = televisionProfile()
            assertNotNull(source.repository.registerDevice())
            target.repository.inheritDevice(source.repository)
            source.repository.registerProfile = mapOf("device_type" to "Later Source Device")
            source.repository.deviceIdentity = mapOf("device_id" to "505", "iid" to "606")
            target.repository.stream("123")
            assertEquals("101",target.playbackRequest().url.queryParameter("device_id"))
            assertEquals("202",target.playbackRequest().url.queryParameter("iid"))
            assertFalse(target.requests.any { it.url.encodedPath == "/service/2/device_register/" })

            target.repository.search("test",1,ContentType.COMIC)
            televisionProfile().forEach { (key,value) ->
                assertEquals(key,value,target.catalogueRequest().url.queryParameter(key))
            }
        }
    }

    @Test fun catalogueProfileIsDefensivelyCopiedAndBlankValuesKeepDefaults() = Fixture().use { fixture ->
        val supplied = televisionProfile().apply { put("language", " ") }
        fixture.repository.registerProfile = supplied
        supplied["device_type"] = "Mutated Outside Repository"
        supplied["cdid"] = "mutated-outside"
        fixture.repository.search("test",1,ContentType.COMIC)
        val request = fixture.catalogueRequest()
        assertEquals("Test Television",request.url.queryParameter("device_type"))
        assertEquals("test-local-cdid",request.url.queryParameter("cdid"))
        assertEquals("zh",request.url.queryParameter("language"))
    }
}
