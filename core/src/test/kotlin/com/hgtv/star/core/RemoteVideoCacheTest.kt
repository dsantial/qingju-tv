package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteVideoCacheTest {
    @get:Rule val temporary=TemporaryFolder()
    private val block=VideoCacheStore.BLOCK_BYTES
    private fun movie(size: Int): ByteArray = ByteArray(size) { (it*31).toByte() }.apply {
        (be32(24)+"ftyp".toByteArray()+"isom000000000000".toByteArray()+be32(8)+"moov".toByteArray()).copyInto(this)
    }
    private class Source(val bytes: ByteArray) {
        val calls=java.util.Collections.synchronizedList(mutableListOf<Long>())
        @Volatile var fail: Long?=null
        @Volatile var waitAt: Long?=null
        val entered=CountDownLatch(1)
        val released=CountDownLatch(1)
        @Volatile var badRange=false
        @Volatile var extraByte=false
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(chain.request().header("Range")!!)!!
            val start=range.groupValues[1].toLong();val requested=range.groupValues[2].toLong()
            calls+=start
            if(fail==start) throw IOException("synthetic network failure")
            if(waitAt==start) {
                entered.countDown()
                while(!released.await(10,TimeUnit.MILLISECONDS)) if(chain.call().isCanceled()) throw IOException("cancelled")
            }
            val end=minOf(requested,bytes.size-1L)
            val body=bytes.copyOfRange(start.toInt(),end.toInt()+1)+(if(extraByte) byteArrayOf(1) else byteArrayOf())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .header("Content-Range","bytes ${if(badRange) start+1 else start}-$end/${bytes.size}")
                .body(body.toResponseBody()).build()
        }.build()
        fun video(store: VideoCacheStore?)=RemoteVideo(http,StreamInfo("https://media.invalid/movie?token=private",null,"720P"),store)
    }
    private fun eventually(test: () -> Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!test() && System.nanoTime()<deadline) Thread.sleep(5)
        assertTrue(test())
    }

    @Test fun fullPrefetchCachesRawBlocksAndRandomReadsNoLongerUseTheNetwork() {
        val source=Source(movie(block*2+99));val dir=temporary.newFolder()
        VideoCacheStore(dir).use { store ->
            source.video(store).use { video ->
                assertTrue(video.prefetchFully())
                assertTrue(video.fullyCached)
                assertEquals(source.bytes.size.toLong(),video.cachedBytes)
                val before=source.calls.size
                for(start in listOf(0,256*1024-9,block-17,block+71,source.bytes.size-88)) {
                    val length=minOf(77,source.bytes.size-start)
                    assertArrayEquals(source.bytes.copyOfRange(start,start+length),video.read(start.toLong(),length))
                }
                assertEquals(before,source.calls.size)
                assertTrue(video.stats().cacheHitBytes>0)
                assertEquals(source.bytes.size.toLong(),video.stats().networkBytes)
                assertTrue(dir.walkTopDown().filter { it.isFile && it.name.endsWith(".block") }.all {
                    !it.readBytes().toString(Charsets.ISO_8859_1).contains("https://media.invalid")
                })
            }
            eventually { store.usedBytes==0L }
            assertFalse(dir.walkTopDown().any { it.name.endsWith(".block") })
        }
    }

    @Test fun yieldBeforePreparingDoesNoNetworkAndYieldedWorkContinuesFromCachedBlocks() {
        val source=Source(movie(block*3+17))
        VideoCacheStore(temporary.newFolder()).use { store ->
            source.video(store).use { video ->
                assertFalse(video.prefetchFully({ true }))
                assertEquals(0,source.calls.size)
                var yield=false
                assertFalse(video.prefetchFully({ yield }) { _,_ -> yield=true })
                val cached=video.cachedBytes
                assertTrue(cached>0 && cached<video.total)
                assertTrue(video.prefetchFully())
                assertTrue(video.fullyCached)
                assertEquals(1,source.calls.count { it==0L })
            }
        }
    }

    @Test fun backgroundWarmYieldsBetweenBlocksAndResumesWithoutFetchingTheFirstBlockAgain() {
        val source=Source(movie(block*3+17))
        VideoCacheStore(temporary.newFolder()).use { store ->
            source.video(store).use { video ->
                assertFalse(video.warmIfAllowed { source.calls.size>=1 })
                assertEquals(block.toLong(),video.cachedBytes)
                assertEquals(1,source.calls.size)
                assertTrue(video.warmIfAllowed { false })
                assertEquals(1,source.calls.count { it==0L })
                assertEquals(1,source.calls.count { it==block.toLong() })
                assertEquals(2L*block,video.cachedBytes)
                val before=source.calls.size
                assertArrayEquals(source.bytes.copyOfRange(block-7,block+17),video.read(block-7L,24))
                assertEquals(before,source.calls.size)
            }
        }
    }

    @Test fun backgroundPreparationAlsoYieldsInsideALargeHeaderAndForegroundReadsAreNotGated() {
        val bytes=movie(block*3+17)
        val headerEnd=block+80
        (be32(headerEnd-24)+"moov".toByteArray()).copyInto(bytes,24)
        val source=Source(bytes)
        VideoCacheStore(temporary.newFolder()).use { store ->
            source.video(store).use { video ->
                assertFalse(video.warmIfAllowed { source.calls.size>=1 })
                assertEquals(block.toLong(),video.cachedBytes)
                assertEquals(1,source.calls.size)
                // Normal foreground prepare/read does not inherit the background gate.
                video.prepare()
                assertArrayEquals(source.bytes.copyOfRange(block+91,block+119),video.read(block+91L,28))
                assertEquals(1,source.calls.count { it==0L })
                assertEquals(1,source.calls.count { it==block.toLong() })
                assertTrue(video.warmIfAllowed { false })
            }
        }
    }

    @Test fun aNoDiskBackgroundWarmStillYieldsAndForegroundReadsRemainUsable() {
        val source=Source(movie(block*3+17))
        source.video(null).use { video ->
            assertFalse(video.warmIfAllowed { source.calls.size>=1 })
            assertEquals(1,source.calls.size)
            assertArrayEquals(source.bytes.copyOfRange(block+1,block+24),video.read(block+1L,23))
            assertTrue(video.warmIfAllowed { false })
            assertEquals(1,source.calls.count { it==0L })
            val before=source.calls.size
            assertArrayEquals(source.bytes.copyOfRange(block-7,block+17),video.read(block-7L,24))
            assertEquals(before,source.calls.size)
        }
    }

    @Test fun prefetchStatusDistinguishesForegroundYieldFromDiskAndFileLimits() {
        val source=Source(movie(block+17))
        VideoCacheStore(temporary.newFolder()).use { store ->
            source.video(store).use { video ->
                assertEquals(RemoteVideo.PrefetchStatus.YIELDED,video.prefetchStatus({ true }))
                assertEquals(0,source.calls.size)
                assertEquals(RemoteVideo.PrefetchStatus.COMPLETE,video.prefetchStatus())
            }
        }
        VideoCacheStore(temporary.newFolder(),availableBytes={ 0L }).use { store ->
            source.video(store).use { video ->
                assertEquals(RemoteVideo.PrefetchStatus.LIMITED,video.prefetchStatus())
            }
        }
        source.video(null).use { video -> assertEquals(RemoteVideo.PrefetchStatus.LIMITED,video.prefetchStatus()) }
    }

    @Test fun networkFailureKeepsCompletedBlocksForTheNextAttempt() {
        val source=Source(movie(block*2+31));source.fail=block.toLong()
        VideoCacheStore(temporary.newFolder()).use { store ->
            source.video(store).use { video ->
                assertThrows(IOException::class.java) { video.prefetchFully() }
                assertEquals(block.toLong(),video.cachedBytes)
                source.fail=null
                assertTrue(video.prefetchFully())
                assertEquals(1,source.calls.count { it==0L })
                assertEquals(2,source.calls.count { it==block.toLong() })
            }
        }
    }

    @Test fun foregroundReadAndPrefetchShareAnInFlightBlock() {
        val source=Source(movie(block*2+17));source.waitAt=block.toLong()
        val workers=Executors.newFixedThreadPool(2)
        try {
            VideoCacheStore(temporary.newFolder()).use { store ->
                source.video(store).use { video ->
                    video.prepare()
                    val read=workers.submit<ByteArray> { video.read(block+11L,71) }
                    assertTrue(source.entered.await(2,TimeUnit.SECONDS))
                    val full=workers.submit<Boolean> { video.prefetchFully() }
                    source.released.countDown()
                    assertArrayEquals(source.bytes.copyOfRange(block+11,block+82),read.get(3,TimeUnit.SECONDS))
                    assertTrue(full.get(3,TimeUnit.SECONDS))
                    assertEquals(1,source.calls.count { it==block.toLong() })
                }
            }
        } finally { source.released.countDown();workers.shutdownNow() }
    }

    @Test fun closeCancelsANetworkReadWithoutWaitingAndDeletesOnlyThisVideo() {
        val source=Source(movie(block*2+17));source.waitAt=block.toLong()
        val worker=Executors.newSingleThreadExecutor()
        try {
            VideoCacheStore(temporary.newFolder()).use { store ->
                val video=source.video(store);video.prepare()
                val future=worker.submit<ByteArray> { video.read(block+1L,11) }
                assertTrue(source.entered.await(2,TimeUnit.SECONDS))
                val began=System.nanoTime();video.close()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<500)
                assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(2,TimeUnit.SECONDS) }
                eventually { store.usedBytes==0L }
            }
        } finally { source.released.countDown();worker.shutdownNow() }
    }

    @Test fun perVideoAndSharedStoreBudgetsDoNotGrowIntoRamOrDownloadTheWholeSeries() {
        val dir=temporary.newFolder();val source=Source(movie(block*2))
        VideoCacheStore(dir,block*3L,block*2L).use { store ->
            val first=source.video(store);val second=source.video(store)
            assertTrue(first.prefetchFully())
            assertFalse(second.prefetchFully())
            assertTrue(store.usedBytes<=block*3L)
            first.close();eventually { store.usedBytes==block.toLong() }
            assertTrue(second.prefetchFully())
            assertTrue(second.fullyCached)
            second.close();eventually { store.usedBytes==0L }
        }
        VideoCacheStore(temporary.newFolder(),block*2L,block.toLong()).use { store ->
            source.video(store).use { video ->
                assertFalse(video.prefetchFully())
                assertEquals(0L,store.usedBytes)
                assertArrayEquals(source.bytes.copyOfRange(17,31),video.read(17,14))
            }
        }
    }

    @Test fun lowDiskSpaceStopsPrefetchButPlaybackCanStillReadTheNetwork() {
        val source=Source(movie(block+17));var available=64L*1024*1024
        VideoCacheStore(temporary.newFolder(),availableBytes={ available }).use { store ->
            source.video(store).use { video ->
                assertFalse(video.prefetchFully())
                assertEquals(0,source.calls.size)
                video.prepare()
                assertEquals(0L,store.usedBytes)
                assertArrayEquals(source.bytes.copyOfRange(23,49),video.read(23,26))
                available=128L*1024*1024
                assertTrue(video.prefetchFully())
                assertEquals(source.bytes.size.toLong(),video.cachedBytes)
            }
        }
    }

    @Test fun anotherLiveStoreNeverDeletesThisStoresSessionAndUnrelatedFilesAreUntouched() {
        val dir=temporary.newFolder();val keep=java.io.File(dir,"other-files").apply { mkdir() }
        java.io.File(keep,"keep.txt").writeText("keep")
        val stale=java.io.File(dir,"qv-session-abandoned").apply { mkdir() }
        java.io.File(stale,"lease").writeBytes(byteArrayOf())
        java.io.File(stale,"old.block").writeText("old")
        val source=Source(movie(block+7))
        VideoCacheStore(dir).use { first ->
            source.video(first).use { video ->
                assertFalse(stale.exists());assertTrue(video.prefetchFully())
                val before=source.calls.size
                VideoCacheStore(dir).use { }
                assertArrayEquals(source.bytes.copyOfRange(block,block+7),video.read(block.toLong(),7))
                assertEquals(before,source.calls.size)
                assertTrue(java.io.File(keep,"keep.txt").exists())
            }
        }
    }

    @Test fun cachedEncryptedSamplesStillDecryptAtTheirAbsoluteOffsetAcrossBlocks() {
        val key=ByteArray(16) { it.toByte() };val iv=ByteArray(8) { (it+1).toByte() }+ByteArray(8)
        val plaintext=ByteArray(64) { (it*3+7).toByte() }
        val encrypted=javax.crypto.Cipher.getInstance("AES/CTR/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE,javax.crypto.spec.SecretKeySpec(key,"AES"),javax.crypto.spec.IvParameterSpec(iv));doFinal(plaintext)
        }
        fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
        val ftyp=box("ftyp","isom0000".toByteArray());val sampleStart=block-7
        fun moov(auxOffset: Int): ByteArray {
            val entry=box("encv",ByteArray(78)+box("sinf",box("frma","avc1".toByteArray())))
            val stsd=box("stsd",ByteArray(4)+be32(1)+entry)
            val stsz=box("stsz",ByteArray(4)+be32(64)+be32(1))
            val stsc=box("stsc",ByteArray(4)+be32(1)+be32(1)+be32(1)+be32(1))
            val stco=box("stco",ByteArray(4)+be32(1)+be32(sampleStart))
            val saiz=box("saiz",ByteArray(4)+byteArrayOf(8)+be32(1))
            val saio=box("saio",ByteArray(4)+be32(1)+be32(auxOffset))
            return box("moov",box("trak",box("mdia",box("minf",box("stbl",stsd+stsz+stsc+stco+saiz+saio))))+box("free",iv.copyOf(8)))
        }
        val header=ftyp+moov((ftyp+moov(0)).size-8)
        val raw=movie(block*2);header.copyInto(raw);encrypted.copyInto(raw,sampleStart)
        val expected=raw.copyOf().apply { plaintext.copyInto(this,sampleStart) }
        val source=Source(raw);val dir=temporary.newFolder()
        VideoCacheStore(dir).use { store ->
            RemoteVideo(source.http,StreamInfo("https://media.invalid/encrypted",key,"720P"),store).use { video ->
                assertTrue(video.prefetchFully())
                val calls=source.calls.size
                for(start in listOf(sampleStart-5,sampleStart,sampleStart+1,sampleStart+31,sampleStart+60)) {
                    assertArrayEquals(expected.copyOfRange(start,start+17),video.read(start.toLong(),17))
                }
                assertEquals(calls,source.calls.size)
                val disk=dir.walkTopDown().first { it.name=="0.block" }.readBytes()
                assertArrayEquals(encrypted.copyOfRange(0,7),disk.copyOfRange(sampleStart,block))
            }
        }
    }

    @Test fun malformedOrOverlongRangeResponsesNeverEnterTheDiskCache() {
        for(kind in 0..1) {
            val source=Source(movie(block+1));source.badRange=kind==0;source.extraByte=kind==1
            VideoCacheStore(temporary.newFolder()).use { store ->
                source.video(store).use { video ->
                    assertThrows(IOException::class.java) { video.prepare() }
                    assertEquals(0L,store.usedBytes)
                }
            }
        }
    }
}
