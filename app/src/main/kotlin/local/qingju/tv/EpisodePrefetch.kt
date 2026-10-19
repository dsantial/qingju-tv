// SPDX-License-Identifier: GPL-3.0-only
package local.qingju.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.RemoteVideo
import com.hgtv.star.core.RequestScope
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One speculative next episode; a successful callback transfers ownership to the player. */
class EpisodePrefetch(private val repository: ContentRepository) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work -> Thread(work, "qingju-next-episode") }
    private val lock = Any()
    private var closed = false
    private var active: Task? = null

    private data class Key(val seriesId: String, val index: Int, val episodeId: String, val quality: Int)
    private class Task(val key: Key) {
        val scope = RequestScope()
        var future: Future<*>? = null
        var video: RemoteVideo? = null
    }

    @Synchronized fun schedule(
        seriesId: String,
        index: Int,
        episodeId: String,
        quality: Int,
        callback: (Int, Int, RemoteVideo) -> Unit,
        onError: (Int, Throwable) -> Unit = { _, _ -> }
    ) {
        require(index >= 0)
        val key = Key(seriesId, index, episodeId, quality)
        synchronized(lock) { if (closed || active?.key == key) return }
        cancel()
        val task = Task(key)
        synchronized(lock) {
            if (closed) return
            active = task
            task.future = worker.submit {
                val began = SystemClock.elapsedRealtime()
                var result: RemoteVideo? = null
                try {
                    task.scope.run {
                        val video = RemoteVideo(repository.http, repository.stream(episodeId, quality))
                        result = video
                        val accepted = synchronized(lock) {
                            if (closed || active !== task) false else { task.video = video; true }
                        }
                        if (!accepted) { video.close(); return@run }
                        video.warm() // Retains at most 1 MiB of the next episode, without a second decoder.
                        Log.i("QingjuTV", "next episode prepared index=$index quality=${video.info.quality} elapsed=${SystemClock.elapsedRealtime() - began}ms")
                        val posted = main.post {
                            val deliver = synchronized(lock) {
                                if (closed || active !== task) false else {
                                    active = null
                                    task.video = null
                                    true
                                }
                            }
                            if (deliver) {
                                try { callback(index, quality, video) }
                                catch (error: Throwable) {
                                    video.close()
                                    Log.w("QingjuTV", "next episode delivery failed index=$index", error)
                                    onError(index, error)
                                }
                            } else video.close()
                        }
                        if (!posted) {
                            synchronized(lock) { if (active === task) active = null; task.video = null }
                            video.close()
                        }
                    }
                } catch (error: Throwable) {
                    result?.close()
                    main.post {
                        val report = synchronized(lock) {
                            if (closed || active !== task) false else {
                                active = null
                                task.video = null
                                true
                            }
                        }
                        if (report) {
                            Log.w("QingjuTV", "next episode unavailable index=$index type=${error.javaClass.simpleName}")
                            onError(index, error)
                        }
                    }
                }
            }
        }
    }

    /** Cancels pending work only; videos already delivered belong to the caller. */
    @Synchronized fun cancel() {
        val previous = synchronized(lock) {
            val task = active
            active = null
            task?.let {
                val video = it.video
                it.video = null
                it to video
            }
        } ?: return
        previous.first.scope.cancel()
        previous.first.future?.cancel(true)
        previous.second?.close()
    }

    @Synchronized override fun close() {
        synchronized(lock) { closed = true }
        cancel()
        worker.shutdownNow()
    }
}
