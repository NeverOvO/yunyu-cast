package io.github.jqssun.airplay.update

import java.net.URI

/** Apply the same attachment identity rules to fresh and restored update metadata. */
internal object UpdateReleasePolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val sha256 = Regex("[0-9a-fA-F]{64}")

    fun accepts(release: UpdateRelease): Boolean {
        val version = UpdateVersion.parse(release.versionName) ?: return false
        val tagVersion = UpdateVersion.parse(release.tag) ?: return false
        return release.versionName == version.value && tagVersion.value == version.value &&
            release.assetId > 0 && release.sizeBytes in 1..MAX_APK_BYTES &&
            release.assetName == "yunyu-cast-${version.value}.apk" &&
            (release.sha256 == null || sha256.matches(release.sha256)) &&
            validAssetUrl(release.downloadUrl, release.tag, release.assetName)
    }

    fun validAssetUrl(value: String, tag: String, name: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.path == "/NeverOvO/yunyu-cast/releases/download/$tag/$name"
    } catch (_: Exception) {
        false
    }
}
