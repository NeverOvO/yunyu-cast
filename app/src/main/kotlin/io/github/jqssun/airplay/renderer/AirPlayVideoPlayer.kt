package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private var playbackSequence = 0L
    private var playbackStartedAt = 0L
    private var diagnosticCount = 0

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null

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
            if (released.get() || player !== source) return
            diagnose("状态=${stateName(state)}，位置=${source.currentPosition}ms")
            if (state == Player.STATE_ENDED) finishPlayback()
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (released.get() || this@AirPlayVideoPlayer.player !== source) return
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY)) _reportPlaybackInfo()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (released.get() || player !== source) return
            val kind = when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                else -> reason.toString()
            }
            diagnose("进度变化 $kind：${oldPosition.positionMs} → ${newPosition.positionMs}ms")
        }
        override fun onRenderedFirstFrame() {
            if (!released.get() && player === source) diagnose("首帧已显示，位置=${source.currentPosition}ms")
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
        playbackSequence++
        playbackStartedAt = SystemClock.elapsedRealtime()
        diagnosticCount = 0
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers).build()
        player = p
        listener = newListener(p).also(p::addListener)
        pendingSurface?.takeIf { it.isValid }?.let(p::setVideoSurface)
        p.volume = clientGain
        val start = startPositionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        diagnose("创建播放器，起点=${(start * 1000).toLong()}ms")
        p.setMediaItem(MediaItem.fromUri(location), (start * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        _reportPlaybackInfo()
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float, origin: String = "AirPlay") = postCommand {
        val p = player ?: return@postCommand
        if (positionSeconds.isFinite()) {
            val target = (positionSeconds.coerceAtLeast(0f) * 1000).toLong()
            diagnose("请求跳转 来源=$origin，${p.currentPosition} → ${target}ms")
            p.seekTo(target)
        }
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
        diagnose("请求跳转 来源=本机快进快退，${p.currentPosition} → ${target}ms")
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = postCommand {
        if (!surface.isValid) return@postCommand
        if (pendingSurface === surface) return@postCommand
        pendingSurface = surface
        if (player != null) diagnose("挂接画面，位置=${player?.currentPosition}ms")
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = postCommand {
        if (pendingSurface !== surface) return@postCommand
        pendingSurface = null
        if (player != null) diagnose("移除画面，位置=${player?.currentPosition}ms")
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
            onDiagnostic = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else mainHandler.post(cleanup)
    }

    private fun postCommand(command: () -> Unit): Boolean {
        if (released.get()) return false
        val action = Runnable {
            if (released.get()) return@Runnable
            try {
                command()
            } catch (e: RuntimeException) {
                Log.w(TAG, "player command failed", e)
                finishPlayback("播放失败，请重新投屏")
            }
        }
        // DLNA already marshals commands to this looper and waits for completion.
        // A second post acknowledged Play/Seek before they had reached ExoPlayer,
        // allowing a subsequent status query to observe the previous command's state.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
            return true
        }
        return mainHandler.post(action)
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
        if (old != null) diagnose("释放播放器，位置=${old.currentPosition}ms")
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
        snapshot()?.let { onPlaybackInfo?.invoke(it) }
    }

    /** A control response must use the current position, not the 250 ms UI report cache. */
    fun snapshot(): PlaybackSnapshot? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (released.get()) return null
        val p = player ?: return null
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        return PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun diagnose(message: String) {
        if (playbackSequence == 0L) return
        // No URLs, headers or metadata; cap diagnostics even during repeated drag seeks.
        if (diagnosticCount < 120) {
            onDiagnostic?.invoke("播放器 #$playbackSequence +${SystemClock.elapsedRealtime() - playbackStartedAt}ms $message")
        } else if (diagnosticCount == 120) {
            onDiagnostic?.invoke("播放器 #$playbackSequence 详细记录已达上限，后续仅保留 DLNA 控制记录")
        }
        if (diagnosticCount <= 120) diagnosticCount++
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
