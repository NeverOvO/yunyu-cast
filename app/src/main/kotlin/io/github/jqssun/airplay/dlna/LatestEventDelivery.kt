package io.github.jqssun.airplay.dlna

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One delivery per subscriber at a time; a slow peer retains only the latest pending state. */
internal class LatestEventDelivery(
    private val executor: Executor,
    private val deliver: (String) -> Unit,
) {
    private val lock = Any()
    private var pending: String? = null
    private var scheduled = false
    private var closed = false

    fun submit(body: String) {
        synchronized(lock) {
            if (closed) return
            pending = body
            if (scheduled) return
            scheduleLocked()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        pending = null
    }

    private fun scheduleLocked() {
        scheduled = true
        try {
            executor.execute(::drain)
        } catch (_: RejectedExecutionException) {
            // A later state poll may retry after a full executor queue.
            scheduled = false
            pending = null
        }
    }

    private fun drain() {
        val body = synchronized(lock) {
            if (closed || pending == null) {
                scheduled = false
                return
            }
            pending!!.also { pending = null }
        }
        try {
            deliver(body)
        } finally {
            synchronized(lock) {
                scheduled = false
                // Yield to other subscribers before draining a continuously changing slow peer.
                if (!closed && pending != null) scheduleLocked()
            }
        }
    }
}
