package io.github.jqssun.airplay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Upgrade old installations that had reception or background operation disabled.
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        listOf(Prefs.AUTO_START, Prefs.BOOT_AUTO_START, Prefs.RUN_IN_BACKGROUND, Prefs.LAUNCH_ON_CONNECT)
            .forEach { it.write(prefs, true) }
    }
}
