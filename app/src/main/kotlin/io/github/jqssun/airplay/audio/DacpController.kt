package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val state = DacpSessionState()
    private val lock = Any()
    private val exec = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16), { r ->
        Thread(r, "Yunyu-DACP").apply { isDaemon = true }
    })
    private val deadlines = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "Yunyu-DACP-timeout").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val pending = ConcurrentHashMap.newKeySet<SettableFuture<Unit>>()
    private val connection = AtomicReference<HttpURLConnection?>()

    val dacpId: String get() = state.currentSession()?.dacpId.orEmpty()
    val activeRemote: String get() = state.currentSession()?.activeRemote.orEmpty()
    val ready: Boolean get() = state.endpoint() != null

    fun update(dacpId: String, activeRemote: String) = synchronized(lock) {
        val previous = state.currentSession()
        val current = state.update(dacpId, activeRemote)
        if (current != previous) cancelOutstanding()
        if (current != null && state.endpoint() == null) _resolve(current)
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    fun reset() = synchronized(lock) {
        state.reset()
        cancelOutstanding()
    }

    fun release() = synchronized(lock) {
        state.release()
        cancelOutstanding()
        exec.shutdownNow()
        // Finish queued socket closes without blocking the service/main looper.
        deadlines.shutdown()
    }

    private fun cancelOutstanding() {
        pending.forEach { it.cancel(true) }
        exec.queue.clear()
        connection.getAndSet(null)?.let(::disconnectAsync)
    }

    private fun disconnectAsync(request: HttpURLConnection) {
        try { deadlines.execute { request.disconnect() } }
        catch (_: RejectedExecutionException) { /* The request worker also closes in finally. */ }
    }

    private fun _resolve(owner: DacpSessionState.Session) {
        val serviceName = "iTunes_Ctrl_${owner.dacpId}"
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            this.serviceName = serviceName
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress ?: return
                    if (state.resolve(owner, host, si.port)) Log.i(TAG, "DACP resolved: $host:${si.port}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    private fun _send(path: String): ListenableFuture<Unit> = synchronized(lock) {
        val result = SettableFuture.create<Unit>()
        val endpoint = state.endpoint()
        if (endpoint == null) {
            result.setException(IOException("dacp endpoint not resolved"))
            return@synchronized result
        }
        val queuedAt = System.nanoTime()
        pending += result
        result.addListener({ pending.remove(result) }, MoreExecutors.directExecutor())
        try {
            exec.execute {
                var conn: HttpURLConnection? = null
                var deadline: java.util.concurrent.Future<*>? = null
                try {
                    if (result.isDone || !state.isCurrent(endpoint.session)) { result.cancel(false); return@execute }
                    val remainingMs = 4000 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt)
                    if (remainingMs <= 0) throw SocketTimeoutException("DACP command expired in queue")
                    val request = endpoint.uri(path).toURL().openConnection() as HttpURLConnection
                    conn = request
                    request.requestMethod = "GET"
                    request.instanceFollowRedirects = false
                    request.setRequestProperty("Active-Remote", endpoint.session.activeRemote)
                    request.connectTimeout = 2000
                    request.readTimeout = 2000
                    connection.set(request)
                    result.addListener({ if (result.isCancelled) disconnectAsync(request) }, MoreExecutors.directExecutor())
                    deadline = deadlines.schedule({
                        result.setException(SocketTimeoutException("DACP request timed out"))
                        request.disconnect()
                    }, remainingMs, TimeUnit.MILLISECONDS)
                    if (result.isDone || !state.isCurrent(endpoint.session)) { result.cancel(false); return@execute }
                    val code = request.responseCode
                    // Commands have no response payload to retain. A streaming body must not hold the queue.
                    synchronized(lock) {
                        if (!state.isCurrent(endpoint.session)) { result.cancel(false); return@execute }
                        if (code in 200..299) {
                            result.set(Unit)
                        } else {
                            Log.w(TAG, "DACP $path -> HTTP $code")
                            result.setException(IOException("HTTP $code"))
                        }
                    }
                } catch (e: Exception) {
                    if (!result.isDone) Log.w(TAG, "DACP send failed: $path", e)
                    result.setException(e)
                } finally {
                    deadline?.cancel(false)
                    conn?.let { connection.compareAndSet(it, null); it.disconnect() }
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        result
    }

    companion object {
        private const val TAG = "DacpController"
    }
}
