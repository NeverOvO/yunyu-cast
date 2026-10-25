package io.github.jqssun.airplay.update

enum class UpdatePhase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALL_PERMISSION, ERROR }

data class AppUpdateState(
    val installedVersion: String,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "",
    val dialogVisible: Boolean = false,
) {
    val entryLabel: String get() = when (phase) {
        UpdatePhase.AVAILABLE -> "发现新版本"
        UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> "更新下载中"
        UpdatePhase.READY, UpdatePhase.INSTALL_PERMISSION -> "安装更新"
        else -> "检查更新"
    }
}
