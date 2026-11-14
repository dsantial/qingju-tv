// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** All traffic is synthetic; recovery must be bounded and keep account state independent. */
class PlaybackSessionRecoveryTest {
    private fun profile() = linkedMapOf(
        "cdid" to "test-old-installation-cdid", "openudid" to "test-old-openudid",
        "device_type" to "Test TV", "device_brand" to "Test Brand", "os_api" to "22", "os_version" to "5.1.1")
    private val oldIdentity = mapOf("device_id" to "101", "iid" to "202")
    private val newIdentity = mapOf("device_id" to "303", "iid" to "404")
    private val modelPath = "/novel/player/video_model/v1/"
    private val registrationPath = "/service/2/device_register/"
    private fun playable() = """{"code":0,"data":{"video_model":{"video_list":[{"main_url":"https://video.example.invalid/recovered","video_meta":{"definition":"720p","codec_type":"h264"},"encrypt_info":{"encrypt":false}}]}}}"""
    private fun registered(id: String = "303", iid: String = "404") = """{"device_id":"$id","install_id":"$iid"}"""

    private data class Reply(val body: String = "", val status: Int = 200, val cookies: List<String> = emptyList())
    private class Fixture(
        clock: () -> Long = System::currentTimeMillis,
        respond: (Request) -> Reply,
    ) : Closeable {
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val reply = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(reply.status).message("synthetic response")
                .apply { reply.cookies.forEach { addHeader("Set-Cookie", it) } }
                .body(reply.body.toResponseBody()).build()
        }.build()
        val repository = ContentRepository(client, clock).apply {
            registerProfile = linkedMapOf("cdid" to "test-old-installation-cdid", "openudid" to "test-old-openudid",
                "device_type" to "Test TV", "device_brand" to "Test Brand", "os_api" to "22", "os_version" to "5.1.1")
            deviceIdentity = mapOf("device_id" to "101", "iid" to "202")
        }
        fun models() = requests.filter { it.url.encodedPath == "/novel/player/video_model/v1/" }
        fun registrations() = requests.filter { it.url.encodedPath == "/service/2/device_register/" }
        override fun close() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun threeEmptyHostsCanRegisterOnceAndRecoverWithTheNewIdentity() {
        Fixture { request -> when (request.url.encodedPath) {
            registrationPath -> Reply(registered(), cookies = listOf("ttreq=new-cookie; Path=/", "install_id=404; Path=/"))
            modelPath -> Reply(if (request.url.queryParameter("device_id") == "101") "" else playable())
            else -> error("Unexpected request")
        } }.use { fixture ->
            val credentials = AccountCredentials(cookie = "sessionid=synthetic-account", device = mapOf("device_id" to "909", "iid" to "808"))
            fixture.repository.credentials = credentials
            fixture.repository.sessionToken = "synthetic-session-token"
            val info = fixture.repository.stream("123", 720)
            assertEquals("https://video.example.invalid/recovered", info.url)
            assertEquals("720P", info.quality)
            assertEquals(1, fixture.registrations().size)
            assertEquals(listOf("101", "101", "101", "303"), fixture.models().map { it.url.queryParameter("device_id") })
            assertEquals(newIdentity, fixture.repository.deviceIdentity)
            assertNotEquals(profile().getValue("cdid"), fixture.repository.registerProfile["cdid"])
            assertNotEquals(profile().getValue("openudid"), fixture.repository.registerProfile["openudid"])
            assertEquals("Test TV", fixture.repository.registerProfile["device_type"])
            assertSame(credentials, fixture.repository.credentials)
            assertEquals("synthetic-session-token", fixture.repository.sessionToken)
            fixture.models().forEach { assertNull(it.header("Cookie")) }
        }
    }

    @Test fun explicitHttpRejectionsNeverTriggerIdentityRecovery() {
        for (status in listOf(403, 429)) {
            Fixture { request ->
                if (request.url.encodedPath != modelPath) error("Rejection must not register")
                Reply("synthetic rejection", status)
            }.use { fixture ->
                assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
                assertEquals("HTTP $status", 1, fixture.models().size)
                assertTrue(fixture.registrations().isEmpty())
                assertEquals(oldIdentity, fixture.repository.deviceIdentity)
            }
        }
    }

    @Test fun nonzeroBusinessCodeNeverTriggersIdentityRecovery() {
        Fixture { request ->
            if (request.url.encodedPath != modelPath) error("Business rejection must not register")
            Reply("""{"code":110001,"data":{}}""")
        }.use { fixture ->
            val error = assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertTrue(error.message.orEmpty().contains("110001"))
            assertEquals(1, fixture.models().size)
            assertTrue(fixture.registrations().isEmpty())
        }
    }

    @Test fun transportFailuresDoNotCountAsThreeEmptySuccessfulResponses() {
        Fixture { request ->
            if (request.url.encodedPath != modelPath) error("Transport failure must not register")
            throw SocketTimeoutException("synthetic transport timeout")
        }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(3, fixture.models().size)
            assertTrue(fixture.registrations().isEmpty())
            assertEquals(profile(), fixture.repository.registerProfile)
        }
    }

    @Test fun twoEmptyResponsesAndOneTransportFailureDoNotTriggerRecovery() {
        var models = 0
        Fixture { request ->
            if (request.url.encodedPath != modelPath) error("Mixed failure must not register")
            models++
            if (models == 3) throw SocketTimeoutException("synthetic final timeout")
            Reply()
        }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(3, fixture.models().size)
            assertTrue(fixture.registrations().isEmpty())
        }
    }

    @Test fun aRecoveredSessionThatIsStillEmptyCannotRegisterAgainInTheSameCall() {
        Fixture { request -> when (request.url.encodedPath) {
            registrationPath -> Reply(registered())
            modelPath -> Reply()
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.registrations().size)
            assertEquals(listOf("101", "101", "101", "303", "303", "303"), fixture.models().map { it.url.queryParameter("device_id") })
        }
    }

    @Test fun manualRetriesWithinFiveMinutesDoNotRepeatRegistration() {
        var now = 1000L
        Fixture(clock = { now }) { request -> when (request.url.encodedPath) {
            registrationPath -> Reply(registered())
            modelPath -> Reply()
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.registrations().size)
            now += TimeUnit.MINUTES.toMillis(5) - 1
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(1, fixture.registrations().size)
            assertEquals(9, fixture.models().size)
            now += 2
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(2, fixture.registrations().size)
            assertEquals(15, fixture.models().size)
        }
    }

    @Test fun failedRegistrationPreservesTheOldProfileIdentityAndRegistrationCookies() {
        var registrations = 0
        Fixture { request -> when (request.url.encodedPath) {
            registrationPath -> {
                registrations++
                if (registrations == 1) Reply(registered("101", "202"), cookies = listOf("ttreq=old-cookie; Path=/", "install_id=202; Path=/"))
                else Reply(registered("0", "0"), cookies = listOf("ttreq=failed-cookie; Path=/"))
            }
            modelPath -> Reply()
            "/reading/bookapi/search/tab/v" -> Reply("""{"code":0,"search_tabs":[{"tab_type":19,"data":[{"video_data":[{"series_id":"919","title":"Synthetic search result","episode_cnt":1}]}],"has_more":false}]}""")
            else -> error("Unexpected request")
        } }.use { fixture ->
            assertNotNull(fixture.repository.registerDevice())
            val before = fixture.repository.registerProfile.toMap()
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(2, fixture.registrations().size)
            assertEquals(before, fixture.repository.registerProfile)
            assertEquals(oldIdentity, fixture.repository.deviceIdentity)
            fixture.repository.search("synthetic", 1, ContentType.COMIC)
            val search = fixture.requests.last { it.url.encodedPath == "/reading/bookapi/search/tab/v" }
            assertTrue(search.header("Cookie").orEmpty().contains("ttreq=old-cookie"))
            assertTrue(search.header("Cookie").orEmpty().contains("install_id=202"))
            assertFalse(search.header("Cookie").orEmpty().contains("failed-cookie"))
        }
    }

    @Test fun anUnprofiledGuestDoesNotRotateItsInstallationIdentity() {
        Fixture { request ->
            if (request.url.encodedPath != modelPath) error("Guest must not register a recovery")
            Reply()
        }.use { fixture ->
            fixture.repository.registerProfile = emptyMap()
            assertThrows(IOException::class.java) { fixture.repository.stream("123", 720) }
            assertEquals(3, fixture.models().size)
            assertTrue(fixture.registrations().isEmpty())
        }
    }
}
