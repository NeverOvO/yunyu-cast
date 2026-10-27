package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.HttpDataSource
import java.util.concurrent.atomic.AtomicBoolean

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null
    private var clientGain = 1f
    private val released = AtomicBoolean(false)
    private var listener: Player.Listener? = null
    private var endReported = false

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            if (released.get() || player == null) return
            _reportPlaybackInfo()
            if (!released.get() && player != null) mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private fun newListener(source: ExoPlayer) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (released.get() || player !== source) return
            val message = playbackFailure(error)
            Log.w(TAG, "playback error: $message")
            finishPlayback(message)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (!released.get() && player === source && state == Player.STATE_ENDED) finishPlayback()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // duration is usually established here (esp. hls): report so the held /play releases
            if (!released.get() && player === source) _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (!released.get() && player === source) onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (released.get() || player !== source || videoSize.height <= 0 || videoSize.width <= 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
        }
    }

    // Report useful causes without including signed media URLs, headers or response bodies.
    private fun playbackFailure(error: PlaybackException): String {
        val detail = generateSequence<Throwable>(error) { it.cause }.take(12).mapNotNull { cause ->
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> "视频服务器返回 HTTP ${cause.responseCode}"
                is HttpDataSource.InvalidContentTypeException -> "视频服务器返回了不支持的内容类型"
                is java.net.UnknownHostException -> "无法解析视频服务器地址"
                is java.net.SocketTimeoutException -> "连接视频服务器超时"
                is java.net.ConnectException -> "无法连接视频服务器"
                is javax.net.ssl.SSLException -> "视频服务器的 HTTPS 连接失败"
                else -> null
            }
        }.firstOrNull()
        return if (detail == null) error.errorCodeName else "${error.errorCodeName}（$detail）"
    }

    fun play(location: String, startPositionSeconds: Float) = postCommand {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        endReported = false
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers).build()
        player = p
        listener = newListener(p).also(p::addListener)
        pendingSurface?.takeIf { it.isValid }?.let(p::setVideoSurface)
        p.volume = clientGain
        val start = startPositionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        p.setMediaItem(MediaItem.fromUri(location), (start * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        onPlaybackInfo?.invoke(PlaybackSnapshot(start, 0f, 0f, false, true))
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = postCommand {
        if (positionSeconds.isFinite()) player?.seekTo((positionSeconds.coerceAtLeast(0f) * 1000).toLong())
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = postCommand {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = postCommand {
        val p = player ?: return@postCommand
        if (!rate.isFinite()) return@postCommand
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = postCommand {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float) = postCommand {
        if (speed.isFinite()) player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float) = postCommand {
        if (!gain.isFinite()) return@postCommand
        clientGain = gain.coerceIn(0f, 1f)
        player?.volume = clientGain
    }

    fun setSkipSilence(enabled: Boolean) = postCommand {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = postCommand {
        val p = player ?: return@postCommand
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = postCommand {
        if (!surface.isValid) return@postCommand
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = postCommand {
        if (pendingSurface !== surface) return@postCommand
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop() = postCommand { _stopInternal(reportStopped = true) }

    // Service destruction is final. Commands already queued by a native sender
    // must not recreate a player after its owning service has gone away.
    fun release() {
        if (!released.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            _stopInternal(reportStopped = false)
            pendingSurface = null
            onPlaybackInfo = null; onVideoSize = null; onTitle = null
            onEnded = null; onError = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else mainHandler.post(cleanup)
    }

    private fun postCommand(command: () -> Unit): Boolean {
        if (released.get()) return false
        return mainHandler.post {
            if (released.get()) return@post
            try {
                command()
            } catch (e: RuntimeException) {
                Log.w(TAG, "player command failed", e)
                finishPlayback("播放失败，请重新投屏")
            }
        }
    }

    private fun finishPlayback(error: String? = null) {
        if (endReported) return
        endReported = true
        _stopInternal(reportStopped = false)
        if (error != null) onError?.invoke(error)
        onEnded?.invoke()
    }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        val old = player
        player = null
        old?.let {
            runCatching { listener?.let(it::removeListener) }
            runCatching { it.release() }.onFailure { error -> Log.w(TAG, "player release failed", error) }
        }
        listener = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
