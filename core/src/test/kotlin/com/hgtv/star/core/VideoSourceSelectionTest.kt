package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.TimeUnit

class VideoSourceSelectionTest {
    private fun movie(size: Int=1024*1024+31)=ByteArray(size) { (it*7).toByte() }.apply {
        (be32(24)+"ftyp".toByteArray()+"isom000000000000".toByteArray()+be32(8)+"moov".toByteArray()).copyInto(this)
    }
    private class Origin(val bytes: ByteArray,delayMs: Long=0,private val status: Int=206,private val throttled: Boolean=false): Closeable {
        val server=MockWebServer()
        val ranges=java.util.Collections.synchronizedList(mutableListOf<String>())
        init {
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range=request.getHeader("Range").orEmpty();ranges+=range
                    if(status!=206) return MockResponse().setResponseCode(status)
                    val match=Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!
                    val start=match.groupValues[1].toInt();val end=minOf(match.groupValues[2].toInt(),bytes.lastIndex)
                    return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${bytes.size}")
                        .setBody(Buffer().write(bytes,start,end-start+1)).setBodyDelay(delayMs,TimeUnit.MILLISECONDS)
                        .apply { if(throttled) throttleBody(64*1024,250,TimeUnit.MILLISECONDS) }
                }
            }
            server.start()
        }
        val url: String get()=server.url("/same-rendition?token=synthetic").toString()
        override fun close() { server.shutdown() }
    }
    @Test fun fasterMatchingBackupIsChosenOnceAndAllLaterChunksStayOnThatSource() {
        val bytes=movie()
        Origin(bytes,250).use { main -> Origin(bytes,0).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"720P",listOf(backup.url),bytes.size.toLong())).use { video ->
                video.prepare()
                assertArrayEquals(bytes.copyOfRange(600000,600099),video.read(600000,99))
                assertArrayEquals(bytes.copyOfRange(800000,800071),video.read(800000,71))
                assertEquals(listOf("bytes=0-262143"),main.ranges.toList())
                assertEquals(1,backup.ranges.count { it=="bytes=0-262143" })
                assertFalse(backup.ranges.any { it.startsWith("bytes=65536-") })
                assertTrue(backup.ranges.contains("bytes=600000-600098"))
                assertEquals(262144L+99+71,video.stats().networkBytes)
                assertEquals(3L,video.stats().requests)
            }
        } }
    }
    @Test fun initialFastBurstDoesNotHideASlowSustainedMainSource() {
        val bytes=movie()
        Origin(bytes,throttled=true).use { main -> Origin(bytes).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"720P",listOf(backup.url),bytes.size.toLong())).use { video ->
                video.prepare()
                // The chosen 256KiB prefix itself completes prepare, without fetching it again.
                assertEquals(listOf("bytes=0-262143"),main.ranges.toList())
                assertEquals(listOf("bytes=0-262143"),backup.ranges.toList())
                assertEquals(262144L,video.stats().networkBytes)
                assertEquals(1L,video.stats().requests)
                assertArrayEquals(bytes.copyOfRange(600000,600099),video.read(600000,99))
                assertEquals(1,main.ranges.size)
                assertTrue(backup.ranges.contains("bytes=600000-600098"))
            }
        } }
    }

    @Test fun fasterBackupWithDifferentBytesIsRejectedWhileMainRemainsPlayable() {
        val original=movie();val different=original.copyOf().apply { this[400]=99 }
        Origin(original,100).use { main -> Origin(different,0).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"720P",listOf(backup.url))).use { video ->
                video.prepare()
                assertArrayEquals(original.copyOfRange(600000,600017),video.read(600000,17))
                assertTrue(main.ranges.size>1)
                assertEquals(listOf("bytes=0-262143"),backup.ranges.toList())
            }
        } }
    }
    @Test fun failedMainCanUseAMatchingMetadataBackupWithoutRefreshingAnAccountOrSignature() {
        val bytes=movie()
        Origin(bytes,status=503).use { main -> Origin(bytes).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"480P",listOf(backup.url),bytes.size.toLong())).use { video ->
                video.prepare()
                assertArrayEquals(bytes.copyOfRange(900000,900037),video.read(900000,37))
                assertEquals(1,main.ranges.size)
                assertTrue(backup.ranges.size>1)
            }
        } }
    }
    @Test fun metadataLengthMismatchCannotReplaceTheAvailableMainSource() {
        val original=movie();val wrongLength=movie(original.size+101)
        Origin(original,100).use { main -> Origin(wrongLength).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"720P",listOf(backup.url),original.size.toLong())).use { video ->
                video.prepare();video.read(700000,19)
                assertTrue(main.ranges.size>1)
                assertEquals(1,backup.ranges.size)
            }
        } }
    }
    @Test fun noBackupMeansNoProbeAndBackgroundYieldStartsNoSourceRequests() {
        val bytes=movie()
        Origin(bytes).use { origin ->
            RemoteVideo(OkHttpClient(),StreamInfo(origin.url,null,"720P")).use { video -> video.prepare();assertEquals(listOf("bytes=0-262143"),origin.ranges.toList()) }
        }
        Origin(bytes).use { main -> Origin(bytes).use { backup ->
            RemoteVideo(OkHttpClient(),StreamInfo(main.url,null,"720P",listOf(backup.url))).use { video ->
                assertFalse(video.warmIfAllowed { true })
                assertEquals(0,main.ranges.size);assertEquals(0,backup.ranges.size)
            }
        } }
    }
    @Test fun parserRetainsAndDecodesSameRenditionBackupsWithoutDuplicates() {
        val main="https://main.invalid/video";val backup="https://backup.invalid/video"
        val row=JSONObject().put("main_url",main.toByteArray().base64())
            .put("backup_url",JSONArray().put(backup.toByteArray().base64()).put(main))
            .put("backup_url_1",backup).put("video_meta",JSONObject().put("definition","720p").put("codec_type","h265").put("size",12345))
            .put("encrypt_info",JSONObject().put("encrypt",false))
        val selected=PlaybackModels.candidates(JSONObject().put("video_list",JSONArray().put(row))).single()
        assertEquals(main,selected.url);assertEquals(listOf(backup),selected.backupUrls);assertEquals(12345L,selected.expectedBytes)
    }
}
