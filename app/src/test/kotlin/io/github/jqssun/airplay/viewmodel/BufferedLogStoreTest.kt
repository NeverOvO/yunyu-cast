package io.github.jqssun.airplay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BufferedLogStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun appendingDoesNotWriteOnCallerAndCloseFlushesLatestBoundedSnapshot() {
        val file = temp.root.resolve("logs.txt")
        val store = BufferedLogStore(file, maxEntries = 3, flushDelayMs = 60_000) {}
        repeat(5) { store.append("line-$it") }
        assertFalse(file.exists())
        store.closeAsync().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("line-2", "line-3", "line-4"), file.readLines())
    }

    @Test fun newLogsSurviveConcurrentStartupRestore() {
        val file = temp.newFile("logs.txt").apply { writeText("old-1\nold-2") }
        val store = BufferedLogStore(file, maxEntries = 3, flushDelayMs = 60_000) {}
        store.append("new-1")
        store.append("new-2")
        store.closeAsync().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("old-2", "new-1", "new-2"), file.readLines())
    }

    @Test fun clearCannotBeUndoneByStartupRestoreOrPendingWrite() {
        val file = temp.newFile("logs.txt").apply { writeText("saved-old") }
        var published: List<String> = emptyList()
        val store = BufferedLogStore(file, flushDelayMs = 60_000) { published = it }
        store.append("unsaved-old")
        store.clear()
        store.append("after-clear")
        store.closeAsync().get(3, TimeUnit.SECONDS)
        assertEquals(listOf("after-clear"), published)
        assertEquals(listOf("after-clear"), file.readLines())
    }

    @Test fun concurrentMediaCallbacksDoNotLoseEntriesAndCloseRejectsLateCallbacks() {
        val file = temp.root.resolve("logs.txt")
        val store = BufferedLogStore(file, maxEntries = 2_000, flushDelayMs = 60_000) {}
        val producers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { producer ->
                producers.submit { repeat(250) { store.append("$producer:$it") } }
            }
            tasks.forEach { it.get(3, TimeUnit.SECONDS) }
            store.closeAsync().get(3, TimeUnit.SECONDS)
            store.append("late")
            store.clear()
            store.closeAsync().get(3, TimeUnit.SECONDS)
            assertEquals(1_000, file.readLines().toSet().size)
            assertEquals(1_000, store.snapshot().size)
        } finally {
            producers.shutdownNow()
            store.closeAsync().get(3, TimeUnit.SECONDS)
        }
    }
}
