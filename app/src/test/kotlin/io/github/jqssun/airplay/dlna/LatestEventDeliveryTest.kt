package io.github.jqssun.airplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class LatestEventDeliveryTest {
    @Test fun disconnectedSubscriberCannotBlockAnotherAndIntermediateStatesAreCoalesced() {
        val executor = Executors.newFixedThreadPool(2)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latest = CountDownLatch(1)
        val other = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        try {
            val slow = LatestEventDelivery(executor) { body ->
                delivered += body
                if (body == "playing") {
                    blocked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } else latest.countDown()
            }
            slow.submit("playing")
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            repeat(1000) { slow.submit("seek-$it") }
            LatestEventDelivery(executor) { other.countDown() }.submit("paused")
            assertTrue("A slow phone must not block another phone's event", other.await(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(latest.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("playing", "seek-999"), delivered.toList())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun unsubscribedOrStoppedReceiverDoesNotSendQueuedNotifications() {
        val queued = mutableListOf<Runnable>()
        val delivered = mutableListOf<String>()
        val delivery = LatestEventDelivery(Executor { queued += it }) { delivered += it }
        delivery.submit("playing")
        delivery.close()
        delivery.submit("stopped")
        queued.forEach(Runnable::run)
        assertTrue(delivered.isEmpty())
    }

    @Test fun fullQueueDoesNotPermanentlyDisableLaterStatusUpdates() {
        var saturated = true
        val queued = mutableListOf<Runnable>()
        val delivered = mutableListOf<String>()
        val delivery = LatestEventDelivery(Executor {
            if (saturated) throw RejectedExecutionException()
            queued += it
        }) { delivered += it }
        delivery.submit("playing")
        saturated = false
        delivery.submit("paused")
        queued.forEach(Runnable::run)
        assertEquals(listOf("paused"), delivered)
    }

    @Test fun continuouslyChangingSubscriberYieldsToAlreadyQueuedPeers() {
        val queued = mutableListOf<Runnable>()
        val executor = Executor { queued += it }
        val delivered = mutableListOf<String>()
        lateinit var busy: LatestEventDelivery
        busy = LatestEventDelivery(executor) {
            delivered += it
            busy.submit("updated")
        }
        val other = LatestEventDelivery(executor) { delivered += it }
        busy.submit("first")
        other.submit("other-phone")
        queued.removeAt(0).run()
        queued.removeAt(0).run()
        busy.close()
        queued.forEach(Runnable::run)
        assertEquals(listOf("first", "other-phone"), delivered)
    }
}
