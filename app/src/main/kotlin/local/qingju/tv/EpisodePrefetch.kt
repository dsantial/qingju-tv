// SPDX-License-Identifier: GPL-3.0-only
package local.qingju.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.RequestScope
import com.hgtv.star.core.VideoCacheStore
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One network worker fills a rolling three-episode window, yielding to the active player. */
class EpisodePrefetch(
    private val repository: ContentRepository,
    private val cache: VideoCacheStore?,
    private val mayDownload: () -> Boolean,
) : Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadScheduledExecutor { Thread(it,"qingju-next-episodes") }
    private val mediaHttp=repository.http.newBuilder().connectTimeout(5,TimeUnit.SECONDS)
        .readTimeout(10,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build()
    private val lock=Any()
    private var closed=false
    private var busy=false
    private var wake: ScheduledFuture<*>?=null
    private var current=-1
    private var series=""
    private var quality=0
    private var depth=3
    private val tasks=linkedMapOf<Int,Task>()
    private class Task(val series: String,val index: Int,val id: String,val quality: Int) {
        val scope=RequestScope()
        @Volatile var retired=false
        var video: RemoteVideo?=null
        var delivered=false
        var blocked=false
        var failures=0
        var retryAt=0L
        var blockedAtBytes=0L
    }
    private var deliver: (Int,Int,RemoteVideo)->Unit={_,_,_->}
    private var report: (Int,Throwable)->Unit={_,_->}

    fun maintain(
        seriesId: String, index: Int, ids: List<String>, selectedQuality: Int,
        queued: Map<Int,RemoteVideo>, ahead: Int,
        onPrepared: (Int,Int,RemoteVideo)->Unit, onError: (Int,Throwable)->Unit,
    ) {
        synchronized(lock) {
            if(closed) return
            val changed=series!=seriesId || quality!=selectedQuality || depth!=ahead
            val moved=current!=index
            current=index;series=seriesId;quality=selectedQuality;depth=ahead.coerceIn(0,3);deliver=onPrepared;report=onError
            val last=minOf(ids.lastIndex,index+maxOf(1,depth))
            val remove=tasks.values.filter { changed || it.index<index || it.index>last }
            remove.forEach { retire(it);tasks.remove(it.index) }
            if(moved) tasks.values.forEach { if(it.failures<2) it.blocked=false }
            tasks.values.forEach { if(it.blocked && it.failures<2 && (cache?.usedBytes ?: 0)<it.blockedAtBytes) it.blocked=false }
            for(next in index..last) {
                if(tasks.containsKey(next)) continue
                val task=Task(seriesId,next,ids[next],selectedQuality)
                queued[next]?.let { task.video=it;task.delivered=true }
                tasks[next]=task
            }
            kick(0)
        }
    }
    private fun retire(task: Task) {
        task.retired=true
        // A delivered stream belongs to ExoPlayer. Let its current block finish; cancelling a
        // shared in-flight block here could interrupt foreground reads during promotion.
        if(!task.delivered) { task.scope.cancel();task.video?.close();task.video=null }
    }
    private fun kick(delay: Long) {
        if(closed || busy || wake!=null || tasks.values.none { !it.retired && !it.blocked && it.video?.fullyCached!=true }) return
        wake=worker.schedule({ pump() },delay,TimeUnit.MILLISECONDS)
    }
    private fun pump() {
        val task=synchronized(lock) {
            wake=null
            if(closed || !mayDownload()) { kick(500);return }
            val ready=tasks.values.filter { !it.retired && !it.blocked && it.retryAt<=SystemClock.elapsedRealtime() && it.video?.fullyCached!=true }
                .minWithOrNull(compareBy<Task>(
                    { if(it.index==current+1 && !it.delivered) 0 else 1 },
                    { it.index }))
            if(ready==null) { kick(500);return }
            busy=true;ready
        }
        try {
            task.scope.run {
                var video=synchronized(lock) { task.video }
                if(video==null) {
                    val fresh=RemoteVideo(mediaHttp,repository.stream(task.id,task.quality),cache)
                    val accepted=synchronized(lock) {
                        if(task.retired || closed) false else { task.video=fresh;true }
                    }
                    if(!accepted) { fresh.close();return@run }
                    video=fresh
                }
                val media=video ?: return@run
                if(task.retired || !mayDownload()) return@run
                if(!task.delivered) {
                    if(!media.warmIfAllowed { task.retired || !mayDownload() }) return@run
                    main.post {
                        val callback=synchronized(lock) {
                            if(closed || task.retired || tasks[task.index]!==task || task.delivered) null
                            else { task.delivered=true;deliver }
                        }
                        if(callback!=null) {
                            try { callback(task.index,task.quality,media) }
                            catch(error: Exception) { media.close();Log.w("QingjuTV","prefetch delivery failed ${error.javaClass.simpleName}") }
                        }
                    }
                    // Publish the next episode before filling whole files. On the next pass
                    // the current episode regains priority; the next header is already warm.
                    return@run
                }
                if(synchronized(lock) { depth==0 }) { synchronized(lock) { task.blocked=true };return@run }
                val result=media.prefetchStatus({ task.retired || !mayDownload() })
                synchronized(lock) {
                    if(result==RemoteVideo.PrefetchStatus.LIMITED && !task.retired) {
                        task.blocked=true;task.blockedAtBytes=cache?.usedBytes ?: 0 // storage/episode limit
                    }
                }
                if(result==RemoteVideo.PrefetchStatus.COMPLETE) Log.i("QingjuTV","episode cached index=${task.index} bytes=${media.cachedBytes} complete=true")
            }
        } catch(error: Exception) {
            val notify=synchronized(lock) {
                if(task.retired || closed) false else {
                    task.failures++;task.retryAt=SystemClock.elapsedRealtime()+10000;task.blocked=task.failures>=2
                    if(!task.delivered) { task.video?.close();task.video=null }
                    true
                }
            }
            if(notify) main.post {
                val callback=synchronized(lock) { if(task.retired || closed) null else report }
                callback?.invoke(task.index,error)
            }
        } finally {
            synchronized(lock) { busy=false;kick(300) }
        }
    }
    fun completedAhead(): Int=synchronized(lock) { tasks.values.count { !it.retired && it.index>current && it.video?.fullyCached==true } }
    fun cachedBytes(): Long=synchronized(lock) { tasks.values.sumOf { it.video?.cachedBytes ?: 0L } }
    fun cancel() {
        synchronized(lock) {
            wake?.cancel(false);wake=null
            tasks.values.forEach(::retire);tasks.clear();current=-1
        }
    }
    override fun close() {
        synchronized(lock) {
            if(closed) return
            cancel();closed=true
        }
        // Do not interrupt a delivered stream's in-flight block while the player is closing.
        worker.shutdown()
    }
}
