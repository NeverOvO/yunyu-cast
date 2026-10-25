package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameReleaseClockTest {
    @Test fun preservesSixtyFpsCadence() {
        val clock = FrameReleaseClock()
        assertEquals(1_000_000_000L, clock.releaseTimeNs(0, 1_000_000_000L))
        assertEquals(1_016_667_000L, clock.releaseTimeNs(16_667, 1_010_000_000L))
        assertEquals(1_033_333_000L, clock.releaseTimeNs(33_333, 1_030_000_000L))
    }

    @Test fun rebasesAfterSenderClockJumpsForward() {
        val clock = FrameReleaseClock()
        clock.releaseTimeNs(0, 1_000_000_000L)
        assertEquals(1_020_000_000L, clock.releaseTimeNs(5_000_000L, 1_020_000_000L))
        assertEquals(1_036_667_000L, clock.releaseTimeNs(5_016_667L, 1_030_000_000L))
    }

    @Test fun rebasesAfterSenderClockJumpsBackwards() {
        val clock = FrameReleaseClock()
        clock.releaseTimeNs(5_000_000L, 1_000_000_000L)
        assertEquals(1_020_000_000L, clock.releaseTimeNs(0, 1_020_000_000L))
    }

    @Test fun rebasesAfterLongDecoderStall() {
        val clock = FrameReleaseClock()
        clock.releaseTimeNs(0, 1_000_000_000L)
        assertEquals(3_000_000_000L, clock.releaseTimeNs(16_667, 3_000_000_000L))
    }

    @Test fun clearsAnchorBetweenSessionsAndHandlesOverflow() {
        val clock = FrameReleaseClock()
        clock.releaseTimeNs(Long.MIN_VALUE, 1_000_000_000L)
        assertEquals(1_020_000_000L, clock.releaseTimeNs(Long.MAX_VALUE, 1_020_000_000L))
        clock.reset()
        assertEquals(9_000_000_000L, clock.releaseTimeNs(0, 9_000_000_000L))
    }
}
