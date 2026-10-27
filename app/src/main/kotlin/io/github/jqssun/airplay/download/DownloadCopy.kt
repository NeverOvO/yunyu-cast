package io.github.jqssun.airplay.download

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

internal fun copyDownload(input: InputStream, output: OutputStream, cancelled: () -> Boolean) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (cancelled()) throw CancellationException("Download cancelled")
        val count = input.read(buffer)
        if (cancelled()) throw CancellationException("Download cancelled")
        if (count < 0) return
        if (count > 0) output.write(buffer, 0, count)
    }
}
