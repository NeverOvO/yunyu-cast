package io.github.jqssun.airplay.service

import org.junit.Assert.*
import org.junit.Test

class NativeConnectionTrackerTest {
    @Test fun queuedFinalDisconnectCannotStopAReconnectedPhone() {
        val tracker = NativeConnectionTracker()
        tracker.connected()
        val oldDisconnect = tracker.disconnected()
        tracker.connected()
        assertFalse(tracker.ownsFinalDisconnect(oldDisconnect))
        assertEquals(1, tracker.current().count)
    }

    @Test fun onlyNewestTerminalEventOwnsCleanupEvenWhenCountReturnsToZero() {
        val tracker = NativeConnectionTracker()
        tracker.connected()
        val oldDisconnect = tracker.disconnected()
        tracker.connected()
        val latestDisconnect = tracker.disconnected()
        assertFalse(tracker.ownsFinalDisconnect(oldDisconnect))
        assertTrue(tracker.ownsFinalDisconnect(latestDisconnect))
    }

    @Test fun oneControlSocketClosingDoesNotEndRemainingMediaConnection() {
        val tracker = NativeConnectionTracker()
        tracker.connected()
        tracker.connected()
        assertFalse(tracker.ownsFinalDisconnect(tracker.disconnected()))
        assertTrue(tracker.ownsFinalDisconnect(tracker.disconnected()))
    }

    @Test fun serverResetRetiresAlreadyQueuedDisconnects() {
        val tracker = NativeConnectionTracker()
        tracker.connected()
        val oldDisconnect = tracker.disconnected()
        tracker.reset()
        assertFalse(tracker.ownsFinalDisconnect(oldDisconnect))
        assertEquals(0, tracker.current().count)
    }
}
