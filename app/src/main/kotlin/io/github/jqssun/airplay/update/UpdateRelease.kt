package io.github.jqssun.airplay.update

/** Public release metadata only. APK identity is checked again before installation. */
data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val name: String,
    val notes: String,
    val assetId: Long,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

class UpdateCheckException(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)
