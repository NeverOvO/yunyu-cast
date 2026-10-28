package io.github.jqssun.airplay.viewmodel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One bounded writer for the process, including reception with no Activity. */
internal class AppLogStore private constructor(file: File) {
    private val entries = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = entries.asStateFlow()
    private val store = BufferedLogStore(file) { entries.value = it }
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun append(message: String) {
        store.append("${dateFormat.get()!!.format(Date())} $message")
    }

    fun clear() = store.clear()
    fun snapshot(): List<String> = store.snapshot()

    companion object {
        @Volatile private var instance: AppLogStore? = null

        fun get(context: Context): AppLogStore = instance ?: synchronized(this) {
            instance ?: AppLogStore(File(context.applicationContext.filesDir, "airplay_logs.txt"))
                .also { instance = it }
        }
    }
}
