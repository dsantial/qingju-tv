// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** One playback stream, optionally backed by bounded encrypted disk blocks. */
class RemoteVideo @JvmOverloads constructor(
    private val http: OkHttpClient,
    val info: StreamInfo,
    cacheStore: VideoCacheStore? = null,
) : Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    private val cache=cacheStore?.open()
    private val chunkLock=java.lang.Object()
    private class Loading {
        var done=false
        var result: Range?=null
        var error: Throwable?=null
    }
    private val loading=mutableMapOf<Long,Loading>()
    private class BackgroundYield: IOException()
    enum class PrefetchStatus { COMPLETE, YIELDED, LIMITED }
    private val networkBytes=AtomicLong()
    private val networkMillis=AtomicLong()
    private val cacheHitBytes=AtomicLong()
    private val requestCount=AtomicLong()
    private val lastHeaderMs=AtomicLong(-1)
    private val lastBodyMs=AtomicLong(-1)
    private val lastTrailerMs=AtomicLong(-1)
    @Volatile private var sourceHost=""
    data class Stats(
        val networkBytes: Long,val networkMillis: Long,val cacheHitBytes: Long,
        val cachedBytes: Long,val totalBytes: Long,val requests: Long,
        val sourceHost: String,val lastHeaderMs: Long,val lastBodyMs: Long,val lastTrailerMs: Long,
    )
    fun stats()=Stats(networkBytes.get(),networkMillis.get(),cacheHitBytes.get(),cachedBytes,total,requestCount.get(),
        sourceHost,lastHeaderMs.get(),lastBodyMs.get(),lastTrailerMs.get())
    val cachedBytes: Long get()=cache?.cachedBytes ?: 0L
    val fullyCached: Boolean get()=!closed && total>0 && cachedBytes==total
    data class Range(val bytes: ByteArray,val total: Long)
    @Volatile var total: Long=0; private set
    @Volatile private var prepared: MediaCrypto.Prepared?=null
    @Volatile private var prefix=byteArrayOf()
    @Volatile private var selectedUrl=info.url
    @Volatile private var sourceSelected=false
    private data class Fetched(val range: Range,val finalUrl: String,val elapsedNanos: Long,val headerMs: Long,val bodyMs: Long,val trailerMs: Long)
    private class ProbeBatch {
        private var cancelled=false
        private val calls=mutableSetOf<Call>()
        @Synchronized fun add(call: Call) {
            if(cancelled) { call.cancel();throw IOException("播放请求已取消") }
            calls+=call
        }
        @Synchronized fun remove(call: Call) { calls-=call }
        @Synchronized fun cancel() { cancelled=true;calls.forEach { it.cancel() };calls.clear() }
    }

    /** Tiny bounded race only once per rendition; never switch sources between cached blocks. */
    private fun selectSource(shouldYield: (() -> Boolean)?) {
        if(sourceSelected) return
        selectSourceInternal(shouldYield)
    }
    @Synchronized private fun selectSourceInternal(shouldYield: (() -> Boolean)?) {
        if(sourceSelected) return
        ensureOpen()
        if(shouldYield?.invoke()==true) throw BackgroundYield()
        val addresses=(listOf(info.url)+info.backupUrls).mapNotNull { raw ->
            raw.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
        }.distinct().take(3)
        if(addresses.size<=1) { sourceSelected=true;return }
        val batch=ProbeBatch()
        val workers=Executors.newFixedThreadPool(addresses.size) { Thread(it,"qingju-source-probe").apply { isDaemon=true } }
        val completed=ExecutorCompletionService<Pair<String,Fetched?>>(workers)
        val scope=RequestScope.current()
        val transport=http.newBuilder().connectTimeout(1500,TimeUnit.MILLISECONDS)
            .readTimeout(1500,TimeUnit.MILLISECONDS).callTimeout(2500,TimeUnit.MILLISECONDS).build()
        val pending=mutableListOf<Future<Pair<String,Fetched?>>>()
        val results=linkedMapOf<String,Fetched>()
        try {
            addresses.forEach { address ->
                pending+=completed.submit(java.util.concurrent.Callable {
                    val result=try {
                        RequestScope.withScope(scope) {
                            fetchRange(address,0,256*1024-1L,transport,false,batch).also {
                                // Match the same MP4 container rules that foreground prepare uses.
                                MediaCrypto.headerEnd(it.range.bytes,it.range.total)
                            }
                        }
                    } catch (_: IOException) { null }
                    address to result
                })
            }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            var remaining=pending.size
            while(remaining>0 && System.nanoTime()<deadline) {
                ensureOpen()
                if(shouldYield?.invoke()==true) throw BackgroundYield()
                val done=completed.poll(minOf(50L,TimeUnit.NANOSECONDS.toMillis((deadline-System.nanoTime()).coerceAtLeast(0))),TimeUnit.MILLISECONDS) ?: continue
                remaining--
                try { val (address,result)=done.get();if(result!=null) results[address]=result }
                catch (_: java.util.concurrent.ExecutionException) { }
            }
            ensureOpen()
            if(shouldYield?.invoke()==true) throw BackgroundYield()
            val main=results[addresses.first()]
            val valid=if(main!=null) results.values.filter {
                it.range.total==main.range.total && it.range.bytes.contentEquals(main.range.bytes)
            } else {
                val backups=results.values.toList()
                if(backups.size>1 && backups.any { it.range.total!=backups.first().range.total || !it.range.bytes.contentEquals(backups.first().range.bytes) }) emptyList()
                else backups
            }
            // If every small probe timed out, retain the original source's normal timeout and
            // validation instead of turning a slow but usable rendition into a hard failure.
            val chosen=valid.minByOrNull { it.elapsedNanos }
            if(chosen!=null) {
                selectedUrl=chosen.finalUrl
                networkBytes.addAndGet(chosen.range.bytes.size.toLong())
                networkMillis.addAndGet(chosen.elapsedNanos/1_000_000)
                requestCount.incrementAndGet()
                total=chosen.range.total;cache?.bindTotal(total)
                prefix=chosen.range.bytes
                sourceHost=selectedUrl.toHttpUrlOrNull()?.host.orEmpty()
                lastHeaderMs.set(chosen.headerMs);lastBodyMs.set(chosen.bodyMs);lastTrailerMs.set(chosen.trailerMs)
            }
            sourceSelected=true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt();throw IOException("播放请求已取消")
        } finally {
            batch.cancel();pending.forEach { if(!it.isDone) it.cancel(true) };workers.shutdownNow()
        }
    }

    private fun ensureOpen() { if(closed || Thread.currentThread().isInterrupted) throw IOException("播放请求已取消") }
    private fun networkRange(start: Long,end: Long): Range =
        fetchRange(selectedUrl,start,end,http,true,null).range

    private fun fetchRange(address: String,start: Long,end: Long,transport: OkHttpClient,commit: Boolean,batch: ProbeBatch?): Fetched {
        ensureOpen()
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(address).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=transport.newCall(request)
        synchronized(calls) { ensureOpen(); calls+=call }
        val began=System.nanoTime();if(commit) requestCount.incrementAndGet()
        if(commit) { sourceHost=request.url.host;lastHeaderMs.set(-1);lastBodyMs.set(-1);lastTrailerMs.set(-1) }
        var headerMs=-1L;var bodyMs=-1L;var trailerMs=-1L
        try {
            batch?.add(call)
            return RequestScope.execute(call) { response ->
                headerMs=(System.nanoTime()-began)/1_000_000
                if(commit) { lastHeaderMs.set(headerMs);sourceHost=response.request.url.host }
                if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLongOrNull() ?: throw IOException("视频分段大小异常")
                val last=minOf(end,full-1)
                if(begin.toLongOrNull()!=start || finish.toLongOrNull()!=last || full<=0 || last<start) throw IOException("视频分段范围不匹配")
                if(info.expectedBytes?.let { it>0 && it!=full }==true) throw IOException("视频资源大小与模型不匹配")
                if(commit) {
                    synchronized(chunkLock) {
                        if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                        total=full
                    }
                    cache?.bindTotal(full)
                }
                val n=(last-start+1).toInt();val body=response.body ?: throw IOException("视频分段为空")
                if(body.contentLength()>=0 && body.contentLength()!=n.toLong()) throw IOException("视频分段长度异常")
                val source=body.source();val bodyBegan=System.nanoTime()
                val bytes=try { source.readByteArray(n.toLong()) }
                    finally { bodyMs=(System.nanoTime()-bodyBegan)/1_000_000;if(commit) lastBodyMs.set(bodyMs) }
                val trailerBegan=System.nanoTime()
                try { if(!source.exhausted()) throw IOException("视频分段超出声明范围") }
                finally { trailerMs=(System.nanoTime()-trailerBegan)/1_000_000;if(commit) lastTrailerMs.set(trailerMs) }
                ensureOpen();if(commit) networkBytes.addAndGet(bytes.size.toLong())
                Fetched(Range(bytes,full),response.request.url.toString(),System.nanoTime()-began,headerMs,bodyMs,trailerMs)
            }
        } finally {
            if(commit && headerMs<0) lastHeaderMs.set((System.nanoTime()-began)/1_000_000)
            if(commit) networkMillis.addAndGet((System.nanoTime()-began)/1_000_000)
            batch?.remove(call)
            synchronized(calls) { calls-=call }
        }
    }

    private fun chunk(index: Long, shouldYield: (() -> Boolean)? = null): Range {
        ensureOpen()
        cache?.read(index)?.let { cacheHitBytes.addAndGet(it.size.toLong()); return Range(it,total) }
        val task: Loading
        var owner=false
        synchronized(chunkLock) {
            ensureOpen()
            task=loading[index] ?: run {
                // A yield happens before publishing an in-flight load, so a foreground waiter
                // can never inherit a background-only yield exception.
                if(shouldYield?.invoke()==true) throw BackgroundYield()
                Loading().also { loading[index]=it;owner=true }
            }
            if(!owner) {
                while(!task.done && !closed) {
                    try { chunkLock.wait() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("播放请求已取消") }
                }
                ensureOpen()
                task.error?.let { if(it is IOException) throw it else throw IOException("视频分段读取失败",it) }
                return task.result ?: throw IOException("视频分段读取失败")
            }
        }
        try {
            cache?.read(index)?.let {
                cacheHitBytes.addAndGet(it.size.toLong())
                val result=Range(it,total)
                synchronized(chunkLock) { task.result=result;task.done=true;loading.remove(index);chunkLock.notifyAll() }
                return result
            }
            val start=index*VideoCacheStore.BLOCK_BYTES
            val end=if(total>0) minOf(total-1,start+VideoCacheStore.BLOCK_BYTES-1) else start+VideoCacheStore.BLOCK_BYTES-1
            val knownPrefix=prefix
            val result=if(index==0L && knownPrefix.isNotEmpty()) {
                val count=minOf(knownPrefix.size.toLong(),end+1).toInt()
                if(count.toLong()==end+1) Range(knownPrefix.copyOf(count),total)
                else networkRange(count.toLong(),end).let { Range(knownPrefix.copyOf(count)+it.bytes,it.total) }
            } else networkRange(start,end)
            cache?.write(index,result.bytes)
            synchronized(chunkLock) { task.result=result;task.done=true;loading.remove(index);chunkLock.notifyAll() }
            return result
        } catch(error: Throwable) {
            synchronized(chunkLock) { task.error=error;task.done=true;loading.remove(index);chunkLock.notifyAll() }
            throw error
        }
    }
    private fun range(start: Long,end: Long, shouldYield: (() -> Boolean)? = null): Range {
        ensureOpen()
        selectSource(shouldYield)
        val out=ByteArrayOutputStream((end-start+1).toInt())
        var position=start
        if(cache==null) {
            val rawPrefix=prefix
            val available=(minOf(end+1,rawPrefix.size.toLong())-position).coerceAtLeast(0).toInt()
            if(available>0) { out.write(rawPrefix,position.toInt(),available);position+=available }
            while(position<=end && (total==0L || position<total)) {
                if(shouldYield?.invoke()==true) throw BackgroundYield()
                val stop=if(shouldYield==null) end else minOf(end,position+VideoCacheStore.BLOCK_BYTES-1)
                val part=networkRange(position,stop)
                out.write(part.bytes)
                // The no-disk fallback still keeps its original bounded one-MiB prefix.
                if(shouldYield!=null && position==prefix.size.toLong() && prefix.size<1024*1024) {
                    val count=minOf(part.bytes.size,1024*1024-prefix.size)
                    prefix=prefix+part.bytes.copyOf(count)
                }
                position+=part.bytes.size
            }
        } else while(position<=end && (total==0L || position<total)) {
            val index=position/VideoCacheStore.BLOCK_BYTES
            val part=chunk(index,shouldYield)
            val offset=(position%VideoCacheStore.BLOCK_BYTES).toInt()
            val count=minOf((end-position+1).toInt(),part.bytes.size-offset)
            if(count<=0) throw IOException("视频缓存分段范围异常")
            out.write(part.bytes,offset,count);position+=count
        }
        ensureOpen()
        return Range(out.toByteArray(),total)
    }
    fun prepare(): RemoteVideo = prepareInternal(null)

    @Synchronized private fun prepareInternal(shouldYield: (() -> Boolean)?): RemoteVideo {
        ensureOpen()
        if(prepared!=null) return this
        val first=range(0,256*1024-1,shouldYield)
        if(prefix.size<first.bytes.size) prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L,shouldYield).bytes else first.bytes.copyOf(end)
        val parsed=MediaCrypto.prepare(header,info.key)
        if(parsed.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        prepared=parsed
        return this
    }
    /** One MiB warm-up remains bounded; disk-backed streams retain only their header in RAM. */
    fun warm(): RemoteVideo = warmInternal(null)

    @Synchronized private fun warmInternal(shouldYield: (() -> Boolean)?): RemoteVideo {
        prepareInternal(shouldYield)
        val size=minOf(total,1024*1024L).toInt()
        if(cache!=null) range(0,size-1L,shouldYield)
        else if(prefix.size<size) {
            val old=prefix
            val extra=range(old.size.toLong(),size-1L,shouldYield).bytes
            if(prefix.size<size) prefix=old+extra
        }
        return this
    }
    /** Background-only gate; partial disk blocks survive and can be resumed next time. */
    fun warmIfAllowed(shouldYield: () -> Boolean): Boolean {
        ensureOpen()
        if(shouldYield()) return false
        return try { warmInternal(shouldYield);true } catch (_: BackgroundYield) { false }
    }
    /** Full prefetch has distinct yield/space outcomes so a controller never blocks a yielded job. */
    fun prefetchStatus(shouldYield: () -> Boolean = { false }, onProgress: (Long,Long) -> Unit = { _,_ -> }): PrefetchStatus {
        ensureOpen()
        val target=cache ?: return PrefetchStatus.LIMITED
        if(fullyCached) return PrefetchStatus.COMPLETE
        if(shouldYield()) return PrefetchStatus.YIELDED
        if(!target.storageAvailable) return PrefetchStatus.LIMITED
        return try {
            prepareInternal(shouldYield)
            if(!target.canWrite) return if(fullyCached) PrefetchStatus.COMPLETE else PrefetchStatus.LIMITED
            val chunks=(total+VideoCacheStore.BLOCK_BYTES-1)/VideoCacheStore.BLOCK_BYTES
            for(index in 0 until chunks) {
                ensureOpen()
                if(target.has(index)) continue
                if(shouldYield()) return PrefetchStatus.YIELDED
                if(!target.storageAvailable) return PrefetchStatus.LIMITED
                chunk(index,shouldYield)
                onProgress(cachedBytes,total)
                if(!target.has(index)) return PrefetchStatus.LIMITED
            }
            if(fullyCached) PrefetchStatus.COMPLETE else PrefetchStatus.LIMITED
        } catch (_: BackgroundYield) { PrefetchStatus.YIELDED }
    }
    fun prefetchFully(shouldYield: () -> Boolean = { false }, onProgress: (Long,Long) -> Unit = { _,_ -> }): Boolean =
        prefetchStatus(shouldYield,onProgress)==PrefetchStatus.COMPLETE
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        ensureOpen(); if(prepared==null) prepare()
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        ensureOpen()
        return MediaCrypto.decrypt(raw,start,info.key,prepared ?: throw IOException("视频尚未准备完成"))
    }
    override fun close() {
        synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() }
        synchronized(chunkLock) { chunkLock.notifyAll() }
        cache?.closeAsync()
        prefix=byteArrayOf()
    }
}
