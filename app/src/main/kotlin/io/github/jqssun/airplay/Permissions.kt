package io.github.jqssun.airplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

fun Context.canAutoLaunchCast(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)

/** Some television builds expose only the app details page, or neither settings activity. */
fun Context.openAutoLaunchSettings(): Boolean {
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
        appSettingsIntent(),
    )
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the app details page next.
        } catch (_: SecurityException) {
            // Vendor firmware may restrict the special-permission page.
        }
    }
    return false
}
