package io.github.jqssun.airplay.renderer

/** Maps sender timestamps to local display time without pinning frames after a clock jump. */
internal class FrameReleaseClock {
    private var anchorPtsUs: Long? = null
    private var anchorNs = 0L

    fun reset() {
        anchorPtsUs = null
    }

    fun releaseTimeNs(ptsUs: Long, nowNs: Long): Long {
        val base = anchorPtsUs
        if (base != null) {
            val target = runCatching {
                Math.addExact(anchorNs, Math.multiplyExact(Math.subtractExact(ptsUs, base), 1_000L))
            }.getOrNull()
            val offset = target?.let { runCatching { Math.subtractExact(it, nowNs) }.getOrNull() }
            if (target != null && offset != null && offset in -MAX_DRIFT_NS..MAX_DRIFT_NS) return target
        }
        anchorPtsUs = ptsUs
        anchorNs = nowNs
        return nowNs
    }

    companion object {
        private const val MAX_DRIFT_NS = 500_000_000L
    }
}
