package io.github.jqssun.airplay.dlna

/**
 * Some DLNA senders send their initial zero position after playback has started.
 * The captured Bilibili session sent it at +3055 ms, rewinding 1630 ms of media.
 * Treat only the first early zero seek as initial alignment. SOAP does not tell
 * us whether a seek is initialization or a user gesture, so the exception is
 * deliberately one-shot; a second seek, pause/resume or local control wins.
 * All calls are confined to the player looper. Times use elapsedRealtime.
 */
internal class DlnaStartupSeekGuard {
    private var freshMedia = false
    private var startedAtMs: Long? = null

    fun newMedia(hasMedia: Boolean) {
        freshMedia = hasMedia
        startedAtMs = null
    }

    fun playbackStarted(startPositionMs: Long, nowMs: Long) {
        startedAtMs = if (freshMedia && startPositionMs == 0L) nowMs else null
        freshMedia = false
    }

    fun cancel() {
        freshMedia = false
        startedAtMs = null
    }

    fun consumeInitialZero(
        targetMs: Long,
        positionMs: Long,
        playing: Boolean,
        buffering: Boolean,
        nowMs: Long,
    ): Boolean {
        val started = startedAtMs
        // Every seek consumes the opportunity, including a seek before Play.
        cancel()
        return started != null && targetMs == 0L && playing && !buffering &&
            nowMs - started in 0L..8_000L && positionMs in 1L..3_000L
    }
}
