package io.github.jqssun.airplay.viewmodel

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded memory buffer; disk writes and UI snapshots are coalesced off media/UI threads. */
internal class BufferedLogStore(
    private val file: File,
    private val maxEntries: Int = 2_000,
    private val flushDelayMs: Long = 500,
    private val onSnapshot: (List<String>) -> Unit,
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val writer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "yunyu-log-writer").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private var pendingFlush: ScheduledFuture<*>? = null
    private var dirty = false
    private var clearedBeforeLoad = false
    private var closed = false
    private var closeResult: Future<*>? = null

    init {
        require(maxEntries > 0 && flushDelayMs >= 0)
        writer.execute {
            // Only this thread accesses the persistent file. Early logs are merged after restoration.
            val restored = ArrayDeque<String>()
            runCatching {
                if (file.exists()) file.useLines { saved ->
                    saved.forEach { line ->
                        if (restored.size == maxEntries) restored.removeFirst()
                        restored.addLast(line.take(MAX_LINE_LENGTH))
                    }
                }
            }
            synchronized(lock) {
                if (!clearedBeforeLoad) {
                    lines.forEach { line ->
                        if (restored.size == maxEntries) restored.removeFirst()
                        restored.addLast(line)
                    }
                    lines.clear()
                    lines.addAll(restored)
                }
                onSnapshot(lines.toList())
            }
        }
    }

    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        if (lines.size == maxEntries) lines.removeFirst()
        lines.addLast(line.take(MAX_LINE_LENGTH))
        dirty = true
        scheduleFlushLocked()
    }

    fun clear() = synchronized(lock) {
        if (closed) return@synchronized
        clearedBeforeLoad = true
        lines.clear()
        dirty = true
        // Serialize publication with the worker so an old snapshot cannot resurrect cleared logs.
        onSnapshot(emptyList())
        scheduleFlushLocked()
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    private fun scheduleFlushLocked() {
        if (pendingFlush == null) {
            pendingFlush = writer.schedule(::flushOnWorker, flushDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushOnWorker() {
        val snapshot = synchronized(lock) {
            pendingFlush = null
            if (!dirty) return
            dirty = false
            lines.toList().also(onSnapshot)
        }
        // Appends never wait for this write. Clear and final flush use the same serial worker.
        runCatching { file.writeText(snapshot.joinToString("\n")) }
    }

    /** Queue the last write without blocking Activity/ViewModel destruction. */
    fun closeAsync(): Future<*> = synchronized(lock) {
        closeResult?.let { return@synchronized it }
        closed = true
        pendingFlush?.cancel(false)
        pendingFlush = null
        writer.submit(::flushOnWorker).also {
            closeResult = it
            writer.shutdown()
        }
    }

    private companion object {
        const val MAX_LINE_LENGTH = 4_096
    }
}
