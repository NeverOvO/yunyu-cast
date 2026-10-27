package io.github.jqssun.airplay.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.jqssun.airplay.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// transformer requires a looper thread
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDownloader(context: Context) {

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private class Job(val file: File) {
        val cancelled = AtomicBoolean()
        var transformer: Transformer? = null
    }
    private var job: Job? = null // Main looper only; stays busy until publication completes.
    private val released = AtomicBoolean()
    private val publisher = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1), { r ->
        Thread(r, "Yunyu-video-save").apply { isDaemon = true }
    })

    // null = idle, -1 = unknown, else 0..100
    private val _progress = MutableStateFlow<Int?>(null)
    val progress = _progress.asStateFlow()

    private val _progressTick = object : Runnable {
        override fun run() {
            val current = job ?: return
            val t = current.transformer ?: return
            try {
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) _progress.value = holder.progress
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            } catch (e: Exception) {
                fail(current, e)
            }
        }
    }

    fun start(location: String) = mainHandler.post {
        if (released.get() || job != null) return@post
        var current: Job? = null
        try {
            val export = Job(File(context.cacheDir, "video-download-${UUID.randomUUID()}.mp4"))
            current = export
            job = export
            _progress.value = -1
            val t = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (job !== export || export.cancelled.get() || released.get()) return
                        mainHandler.removeCallbacks(_progressTick)
                        export.transformer = null
                        _progress.value = 100
                        try { publisher.execute {
                            val path = try { _publish(export) } finally { runCatching { export.file.delete() } }
                            mainHandler.post {
                                if (job === export) {
                                    _reset()
                                    if (!released.get() && !export.cancelled.get()) _toast(
                                        if (path != null) context.getString(R.string.download_saved, path)
                                        else context.getString(R.string.download_failed)
                                    )
                                }
                            }
                        } } catch (e: Exception) {
                            fail(export, e)
                        }
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        fail(export, exception)
                    }
                })
                .build()
            export.transformer = t
            t.start(MediaItem.fromUri(location), export.file.absolutePath)
            mainHandler.postDelayed(_progressTick, PROGRESS_INTERVAL_MS)
        } catch (e: Exception) {
            current?.let { fail(it, e) } ?: run {
                Log.w(TAG, "cannot create export", e)
                _progress.value = null
                _toast(context.getString(R.string.download_failed))
            }
        }
    }

    fun cancel() = mainHandler.post { cancelCurrent() }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainHandler.post {
            cancelCurrent()
            publisher.shutdownNow()
        }
    }

    private fun cancelCurrent() {
        val current = job ?: return
        current.cancelled.set(true)
        _reset()
        try { current.transformer?.cancel() }
        catch (e: Exception) { Log.w(TAG, "cancel export failed", e) }
        finally { current.transformer = null; runCatching { current.file.delete() } }
    }

    private fun fail(current: Job, error: Exception) {
        if (job !== current) return
        Log.w(TAG, "export failed", error)
        cancelCurrent()
        if (!released.get()) _toast(context.getString(R.string.download_failed))
    }

    private fun _reset() {
        mainHandler.removeCallbacks(_progressTick)
        job = null
        _progress.value = null
    }

    private fun _publish(export: Job): String? {
        val file = export.file
        fun cancelled() = released.get() || export.cancelled.get() || Thread.currentThread().isInterrupted
        if (cancelled()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "AirPlay_${stamp}_${UUID.randomUUID().toString().take(8)}.mp4"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                var published = false
                try {
                    (resolver.openOutputStream(uri) ?: throw IOException("Cannot open download destination")).use { out ->
                        file.inputStream().use { copyDownload(it, out, ::cancelled) }
                    }
                    if (cancelled()) throw CancellationException("Download cancelled")
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    if (resolver.update(uri, values, null, null) <= 0) throw IOException("Cannot finish download")
                    published = true
                    val path = runCatching { resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } }.getOrNull()
                    return path ?: "${Environment.DIRECTORY_DOWNLOADS}/$name"
                } finally {
                    if (!published) runCatching { resolver.delete(uri, null, null) }
                }
            } else {
                // pre-q public storage needs a runtime permission
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
                val dest = File(dir, name)
                var published = false
                try {
                    dest.outputStream().use { out -> file.inputStream().use { copyDownload(it, out, ::cancelled) } }
                    if (cancelled()) throw CancellationException("Download cancelled")
                    published = true
                    runCatching { MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null) }
                    return dest.absolutePath
                } finally { if (!published) dest.delete() }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Log.w(TAG, "publish failed", e)
            return null
        }
    }

    private fun _toast(msg: String) = mainHandler.post {
        if (!released.get()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "VideoDownloader"
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
