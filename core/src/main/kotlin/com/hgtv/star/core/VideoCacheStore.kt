// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Session-only encrypted media blocks. No URL, key, decrypted video, or persistent index is saved. */
class VideoCacheStore(
    private val directory: File,
    val maxBytes: Long = 192L * 1024 * 1024,
    val maxVideoBytes: Long = 64L * 1024 * 1024,
    val minimumFreeBytes: Long = 64L * 1024 * 1024,
    private val availableBytes: () -> Long = { directory.usableSpace },
) : Closeable {
    companion object {
        const val BLOCK_BYTES = 512 * 1024
        private val liveSessions=mutableSetOf<String>()
    }
    private val cleanup = Executors.newSingleThreadExecutor { Thread(it,"qingju-cache-cleanup").apply { isDaemon=true } }
    private val closed=AtomicBoolean(false)
    private val entries=mutableSetOf<Entry>()
    private val occupied=AtomicLong()
    private val session: File
    private val leaseFile: RandomAccessFile
    private val lease: FileLock
    init {
        require(maxBytes>0 && maxVideoBytes>0 && maxVideoBytes<=maxBytes && minimumFreeBytes>=0)
        if(!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建视频缓存目录")
        // A live store holds an OS lease, so another instance/process never deletes its data.
        directory.listFiles()?.filter { it.name.startsWith("qv-session-") && it.isDirectory && it.canonicalFile.parentFile==directory.canonicalFile && synchronized(liveSessions) { it.canonicalPath !in liveSessions } }?.forEach { old ->
            RandomAccessFile(File(old,"lease"),"rw").use { file ->
                val lock=try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if(lock!=null) try {
                    if(!old.deleteRecursively()) throw IOException("无法清理旧视频缓存")
                } finally { lock.release() }
            }
        }
        val id=UUID.randomUUID().toString()
        val pending=File(directory,"qv-pending-$id")
        if(!pending.mkdir()) throw IOException("无法创建视频缓存会话")
        val file=RandomAccessFile(File(pending,"lease"),"rw")
        val lock=try { file.channel.lock() } catch (error: Exception) { file.close();pending.deleteRecursively();throw error }
        val target=File(directory,"qv-session-$id")
        synchronized(liveSessions) { liveSessions+=target.canonicalPath }
        if(!pending.renameTo(target)) {
            synchronized(liveSessions) { liveSessions.remove(target.canonicalPath) }
            lock.release();file.close();pending.deleteRecursively();throw IOException("无法创建视频缓存会话")
        }
        leaseFile=file;lease=lock;session=target
    }
    val usedBytes: Long get()=occupied.get()
    private fun hasSpace(bytes: Long): Boolean = try {
        val available=availableBytes(); available>=minimumFreeBytes && bytes<=available-minimumFreeBytes
    } catch (_: Exception) { false }
    @Synchronized private fun reserve(bytes: Long): Boolean {
        if(closed.get() || bytes>maxBytes-occupied.get() || !hasSpace(bytes)) return false
        occupied.addAndGet(bytes); return true
    }
    @Synchronized private fun release(bytes: Long) { occupied.set((occupied.get()-bytes).coerceAtLeast(0)) }
    internal fun open(): Entry = synchronized(this) {
        if(closed.get()) throw IOException("视频缓存已关闭")
        Entry(File(session,UUID.randomUUID().toString())).also { entries+=it }
    }
    internal inner class Entry(private val folder: File) {
        private val disposed=AtomicBoolean(false)
        private val chunks=linkedMapOf<Long,Int>()
        private val retained=AtomicLong()
        private var size=0L
        private var writable=true
        val cachedBytes: Long get()=retained.get()
        val storageAvailable: Boolean get()=!disposed.get() && !closed.get() && usedBytes<maxBytes && hasSpace(1)
        val canWrite: Boolean get()=synchronized(this) { writable && !disposed.get() && size in 1..maxVideoBytes }
        @Synchronized fun bindTotal(total: Long) {
            if(size!=0L && size!=total) throw IOException("视频资源已变化，请重试")
            size=total
            if(total>maxVideoBytes) writable=false
        }
        @Synchronized fun has(index: Long)=!disposed.get() && chunks.containsKey(index)
        @Synchronized fun read(index: Long): ByteArray? {
            if(disposed.get()) return null
            val length=chunks[index] ?: return null
            return try {
                val file=File(folder,"$index.block")
                if(file.length()!=length.toLong()) throw IOException("缓存分段长度异常")
                file.readBytes().also { if(it.size!=length) throw IOException("缓存分段读取异常") }
            } catch (_: IOException) {
                File(folder,"$index.block").delete(); chunks.remove(index); retained.addAndGet(-length.toLong()); release(length.toLong()); null
            }
        }
        @Synchronized fun write(index: Long, bytes: ByteArray): Boolean {
            if(disposed.get() || !writable || size !in 1..maxVideoBytes) return false
            if(chunks.containsKey(index)) return true
            val expected=minOf(BLOCK_BYTES.toLong(),size-index*BLOCK_BYTES).toInt()
            if(expected<=0 || bytes.size!=expected) return false
            if(!reserve(bytes.size.toLong())) return false
            val file=File(folder,"$index.block")
            return try {
                if(disposed.get()) { release(bytes.size.toLong()); return false }
                if(!folder.isDirectory && !folder.mkdirs()) throw IOException("无法写入视频缓存")
                file.writeBytes(bytes)
                chunks[index]=bytes.size; retained.addAndGet(bytes.size.toLong())
                true
            } catch (_: IOException) {
                file.delete(); release(bytes.size.toLong()); writable=false; false
            }
        }
        fun closeAsync() = synchronized(this@VideoCacheStore) {
            if(!disposed.compareAndSet(false,true)) return@synchronized
            // This never waits for network or an in-progress disk operation on UI.
            cleanup.execute {
                synchronized(this) {
                    val bytes=retained.get()
                    if(folder.deleteRecursively()) { chunks.clear(); retained.set(0); release(bytes) }
                }
                synchronized(this@VideoCacheStore) { entries.remove(this) }
            }
        }
    }
    override fun close() = synchronized(this) {
        if(!closed.compareAndSet(false,true)) return@synchronized
        entries.toList().forEach { it.closeAsync() }
        cleanup.execute {
            try { lease.release();leaseFile.close();File(session,"lease").delete();session.delete() }
            finally { synchronized(liveSessions) { liveSessions.remove(session.canonicalPath) } }
        }
        cleanup.shutdown()
    }
}
