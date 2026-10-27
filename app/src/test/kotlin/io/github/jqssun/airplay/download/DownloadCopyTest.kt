package io.github.jqssun.airplay.download

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class DownloadCopyTest {
    @Test fun cancelledDownloadDoesNotStartReadingOrWriting() {
        val output = ByteArrayOutputStream()
        val source = object : InputStream() {
            override fun read(): Int = throw AssertionError("Cancelled job must not read")
        }
        assertThrows(CancellationException::class.java) { copyDownload(source, output) { true } }
        assertEquals(0, output.size())
    }

    @Test fun cancellationWhileReadingPreventsPublishingThatBuffer() {
        var cancelled = false
        val source = object : ByteArrayInputStream(ByteArray(100) { 7 }) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                return super.read(bytes, offset, length).also { cancelled = true }
            }
        }
        val output = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) { copyDownload(source, output) { cancelled } }
        assertEquals(0, output.size())
    }

    @Test fun normalCopyPreservesAllBytesAcrossBuffers() {
        val expected = ByteArray(200_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        copyDownload(ByteArrayInputStream(expected), output) { false }
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test fun storageFailureIsReturnedToPublicationRollback() {
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) = throw IOException("disk full")
        }
        assertThrows(IOException::class.java) { copyDownload(ByteArrayInputStream(byteArrayOf(1)), output) { false } }
    }
}
