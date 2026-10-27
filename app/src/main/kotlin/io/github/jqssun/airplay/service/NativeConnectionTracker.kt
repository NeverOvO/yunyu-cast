package io.github.jqssun.airplay.service

/** Native TCP events can overtake the main thread. A terminal event only owns
 * cleanup while its revision is current; later connections retire that event. */
internal class NativeConnectionTracker {
    data class Snapshot(val count: Int, val revision: Long)
    private var state = Snapshot(0, 0)

    @Synchronized fun connected(): Snapshot = update(state.count + 1)
    @Synchronized fun disconnected(): Snapshot = update((state.count - 1).coerceAtLeast(0))
    @Synchronized fun current(): Snapshot = state
    @Synchronized fun reset() { update(0) }
    @Synchronized fun ownsFinalDisconnect(snapshot: Snapshot): Boolean =
        snapshot.count == 0 && snapshot == state

    private fun update(count: Int): Snapshot = Snapshot(count, state.revision + 1).also { state = it }
}
