package io.github.jqssun.airplay.audio

import java.net.URI

/** Session identity is captured with each command, never assembled from independently changing fields. */
internal class DacpSessionState {
    data class Session(val generation: Long, val dacpId: String, val activeRemote: String)
    data class Endpoint(val session: Session, val host: String, val port: Int) {
        fun uri(path: String): URI = URI("http", null, host, port, path, null, null)
    }

    private var generation = 0L
    private var closed = false
    private var session: Session? = null
    private var endpoint: Endpoint? = null

    @Synchronized fun update(id: String, remote: String): Session? {
        if (closed) return null
        session?.takeIf { it.dacpId == id && it.activeRemote == remote }?.let { return it }
        reset()
        if (id.isEmpty() || remote.isEmpty()) return null
        return Session(generation, id, remote).also { session = it }
    }

    @Synchronized fun resolve(owner: Session, host: String, port: Int): Boolean {
        if (!isCurrent(owner) || host.isBlank() || port !in 1..65535) return false
        endpoint = Endpoint(owner, host, port)
        return true
    }

    @Synchronized fun currentSession(): Session? = session
    @Synchronized fun endpoint(): Endpoint? = endpoint
    @Synchronized fun isCurrent(owner: Session): Boolean = !closed && session == owner
    @Synchronized fun reset() { generation++; session = null; endpoint = null }
    @Synchronized fun release() { closed = true; reset() }
}
