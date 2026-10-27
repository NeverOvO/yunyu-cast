package io.github.jqssun.airplay.audio

import org.junit.Assert.*
import org.junit.Test

class DacpSessionStateTest {
    @Test fun switchingPhonesInvalidatesOldResolutionAndQueuedCommands() {
        val state = DacpSessionState()
        val first = state.update("phone-a", "111")!!
        assertTrue(state.resolve(first, "192.168.1.2", 1024))
        val queued = state.endpoint()!!
        val second = state.update("phone-b", "222")!!
        assertNull(state.endpoint())
        assertFalse(state.isCurrent(queued.session))
        assertFalse(state.resolve(first, "192.168.1.2", 1024))
        assertTrue(state.resolve(second, "192.168.1.3", 1025))
        assertEquals("222", state.endpoint()!!.session.activeRemote)
        assertEquals("111", queued.session.activeRemote)
    }

    @Test fun duplicateMetadataKeepsResolvedSessionButResetRejectsLateCallbacks() {
        val state = DacpSessionState()
        val owner = state.update("phone", "123")!!
        assertTrue(state.resolve(owner, "192.168.1.2", 1024))
        assertEquals(owner, state.update("phone", "123"))
        assertNotNull(state.endpoint())
        state.reset()
        assertNull(state.endpoint())
        assertFalse(state.resolve(owner, "192.168.1.2", 1024))
    }

    @Test fun releaseCannotBeUndoneByQueuedUpdateOrResolution() {
        val state = DacpSessionState()
        val owner = state.update("phone", "123")!!
        state.release()
        assertNull(state.update("other", "456"))
        assertFalse(state.resolve(owner, "192.168.1.2", 1024))
        assertNull(state.endpoint())
    }

    @Test fun ipv6ControlEndpointIsBracketedAndInvalidPortsAreRejected() {
        val state = DacpSessionState()
        val owner = state.update("phone", "123")!!
        assertFalse(state.resolve(owner, "fe80::1", 0))
        assertFalse(state.resolve(owner, "fe80::1", 65536))
        assertTrue(state.resolve(owner, "fe80::1%wlan0", 1024))
        assertEquals("http://[fe80::1%wlan0]:1024/ctrl-int/1/play", state.endpoint()!!.uri("/ctrl-int/1/play").toString())
    }
}
