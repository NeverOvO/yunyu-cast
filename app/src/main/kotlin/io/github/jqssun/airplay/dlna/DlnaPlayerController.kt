package io.github.jqssun.airplay.dlna

/** Called from the renderer's network workers. Implementations must marshal player work to its looper. */
interface DlnaPlayerController {
    fun setUri(uri: String, metadata: String)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun setVolume(percent: Int)
    fun setMute(muted: Boolean)
    fun snapshot(): DlnaPlaybackSnapshot
}

data class DlnaPlaybackSnapshot(
    val uri: String = "",
    val metadata: String = "",
    val state: String = "NO_MEDIA_PRESENT",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val transportStatus: String = "OK",
)

/** Can be thrown by the player bridge for a UPnP error, instead of returning a false success. */
class DlnaActionException(val code: Int, override val message: String) : Exception(message)

internal fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal fun dlnaTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
