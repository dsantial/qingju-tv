// SPDX-License-Identifier: GPL-3.0-only
package local.qingju.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Small, page-owned poster loader. All public methods must run on the UI thread.
 * Sampling/cancellation approach informed by HGTV's GPL-3.0-only ArtworkLoader:
 * https://github.com/pan8664716/HGTV/blob/main/android/app/src/main/kotlin/com/hgtv/star/data/ArtworkLoader.kt
 * That implementation uses ordinary HTTP images and BitmapFactory, with no cover
 * decryption or HEIC transcoder. Unsupported formats on API 21 keep the placeholder.
 * This version intentionally uses a smaller cache and no disk/original-image cache.
 */
class PosterLoader(@Suppress("UNUSED_PARAMETER") context: Context) {
    private data class Key(val url: String, val width: Int, val height: Int)
    private class Binding(view: ImageView, val generation: Long, val key: Key) {
        val view = WeakReference(view)
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
        var future: Future<*>? = null
        fun cancel() { cancelled = true; call?.cancel(); future?.cancel(true) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Key, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
        // Never recycle here: an ImageView can still be displaying an evicted bitmap.
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64), { task ->
            Thread(task, "qingju-poster").apply { priority = Thread.NORM_PRIORITY - 1 }
        })
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var generation = 0L
    @Volatile private var closed = false

    fun load(url: String, view: ImageView, widthPx: Int, heightPx: Int) {
        uiThread()
        bindings.remove(view)?.cancel()
        view.setImageDrawable(ColorDrawable(Color.rgb(29, 39, 53)))
        if (closed || url.isBlank() || widthPx <= 0 || heightPx <= 0) return
        // Bound every thumbnail request even if a malformed layout supplies huge dimensions.
        val scale = min(1.0, 768.0 / max(widthPx, heightPx))
        val key = Key(url, max(1, (widthPx * scale).toInt()), max(1, (heightPx * scale).toInt()))
        cache.get(key)?.let { view.setImageBitmap(it); return }
        val binding = Binding(view, generation, key)
        bindings[view] = binding
        try {
            binding.future = workers.submit {
                val bitmap = try {
                    if (binding.cancelled || closed) null else cache.get(key) ?: fetch(binding)
                } catch (_: Exception) { null }
                  catch (_: OutOfMemoryError) { cache.evictAll(); null }
                if (binding.cancelled || closed) return@submit
                main.post {
                    val target = binding.view.get()
                    if (!closed && !binding.cancelled && generation == binding.generation &&
                        target != null && bindings[target] === binding) {
                        if (bitmap != null) { cache.put(key, bitmap); target.setImageBitmap(bitmap) }
                        bindings.remove(target)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            bindings.remove(view)
            binding.cancel()
        }
    }

    fun cancelPage() {
        uiThread()
        generation++
        bindings.values.toList().forEach { binding ->
            binding.cancel()
            binding.view.get()?.setImageDrawable(null)
        }
        bindings.clear()
        workers.purge()
        // This handler owns poster results only, never playback or catalog callbacks.
        main.removeCallbacksAndMessages(null)
    }

    fun close() {
        uiThread()
        if (closed) return
        closed = true
        cancelPage()
        cache.evictAll()
        workers.shutdownNow()
        // Socket disposal must not delay a TV's UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }, "qingju-poster-cleanup").start()
    }

    private fun fetch(binding: Binding): Bitmap? {
        if (binding.cancelled || closed) return null
        val request = Request.Builder().url(binding.key.url).build()
        val call = http.newCall(request)
        binding.call = call
        if (binding.cancelled || closed) { call.cancel(); return null }
        val bytes = call.execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
            val out = ByteArrayOutputStream(32 * 1024)
            val buffer = ByteArray(8192)
            body.byteStream().use { stream ->
                while (!binding.cancelled && !closed) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_BYTES) return null
                    out.write(buffer, 0, count)
                }
            }
            if (binding.cancelled || closed) return null
            out.toByteArray()
        }
        if (binding.cancelled || closed) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        val fit = min(1.0, min(binding.key.width.toDouble() / bounds.outWidth,
            binding.key.height.toDouble() / bounds.outHeight))
        val width = max(1, (bounds.outWidth * fit).toInt())
        val height = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 1024 * 1024) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = true
            inDensity = max(1, bounds.outWidth / sample)
            inTargetDensity = width
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (binding.cancelled || closed) { bitmap.recycle(); return null }
        return bitmap
    }

    private fun uiThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "PosterLoader requires the UI thread" } }
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
