package io.github.jqssun.airplay.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** DownloadManager owns the transfer, so leaving the screen does not lose a parent's download. */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("yunyu_updates", Context.MODE_PRIVATE)
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val client = GitHubReleaseClient()
    private val verifier = UpdatePackageVerifier(app)
    @Suppress("DEPRECATION")
    private val installedVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
    private val mutableState = MutableStateFlow(AppUpdateState(installedVersion))
    val state = mutableState.asStateFlow()
    private var transferJob: Job? = null
    private var checkJob: Job? = null
    private var preparedFile: File? = null
    private var autoInstall = false
    private val restoreJob = viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { readSavedRelease() }
        if (saved != null) {
            val current = UpdateVersion.parse(installedVersion)
            val available = UpdateVersion.parse(saved.second.versionName)
            if (current != null && available != null && available > current) {
                mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, release = saved.second,
                    totalBytes = saved.second.sizeBytes, message = "正在恢复更新进度") }
                monitor(saved.first, saved.second)
            } else {
                withContext(Dispatchers.IO) {
                    removeSavedDownload()
                    File(app.cacheDir, "updates/${saved.second.assetId}.apk").delete()
                }
            }
        } else if (prefs.contains(DOWNLOAD_ID)) {
            withContext(Dispatchers.IO) { removeSavedDownload() }
        }
    }

    fun open() {
        mutableState.update { it.copy(dialogVisible = true) }
        if (state.value.phase in setOf(UpdatePhase.IDLE, UpdatePhase.UP_TO_DATE) ||
            (state.value.phase == UpdatePhase.ERROR && state.value.release == null)) check()
    }

    fun dismiss() {
        autoInstall = false
        mutableState.update { it.copy(dialogVisible = false) }
    }

    fun check() = startCheck(manual = true)
    fun checkIfDue() = startCheck(manual = false)

    private fun startCheck(manual: Boolean) {
        if (manual) mutableState.update { it.copy(dialogVisible = true) }
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            restoreJob.join()
            if (transferJob?.isActive == true || state.value.phase in setOf(UpdatePhase.READY, UpdatePhase.INSTALL_PERMISSION)) return@launch
            val elapsed = System.currentTimeMillis() - prefs.getLong(LAST_CHECK, 0L)
            if (!manual && (state.value.phase == UpdatePhase.AVAILABLE || elapsed in 0 until CHECK_INTERVAL_MS)) return@launch
            mutableState.update { it.copy(phase = UpdatePhase.CHECKING, message = "", release = null) }
            try {
                withContext(Dispatchers.IO) { prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply() }
                val release = client.findUpdate(installedVersion)
                mutableState.update { it.copy(phase = if (release == null) UpdatePhase.UP_TO_DATE else UpdatePhase.AVAILABLE,
                    release = release, totalBytes = release?.sizeBytes ?: 0L, message = "") }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.ERROR, release = null,
                    message = (error as? UpdateCheckException)?.userMessage ?: "暂时连不上更新服务，请稍后重试。") }
            }
        }
    }

    fun download() {
        val release = state.value.release ?: return check()
        if (transferJob?.isActive == true || state.value.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        autoInstall = true
        preparedFile = null
        mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0,
            totalBytes = release.sizeBytes, message = "正在开始下载", dialogVisible = true) }
        transferJob = viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    removeSavedDownload()
                    val file = downloadFile(release)
                    file.parentFile?.mkdirs()
                    if (file.exists() && !file.delete()) throw UpdateCheckException("旧安装包无法清理，请重新打开应用后再试。")
                    if (file.parentFile!!.usableSpace < release.sizeBytes * 2 + 16L * 1024 * 1024) {
                        throw UpdateCheckException("电视存储空间不足，请清理一些空间后重试。")
                    }
                    val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
                        .setTitle("云屿投屏更新")
                        .setDescription("下载完成后回到云屿投屏安装")
                        .setMimeType(APK_MIME)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setAllowedOverMetered(true)
                        .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "updates/github-${release.assetId}.apk")
                        .addRequestHeader("User-Agent", "YunyuCast-AndroidTV/$installedVersion")
                    val requestId = downloads.enqueue(request)
                    if (!prefs.edit().putLong(DOWNLOAD_ID, requestId).putString(RELEASE, encode(release)).commit()) {
                        downloads.remove(requestId)
                        throw UpdateCheckException("无法保存下载进度，请检查电视存储空间。")
                    }
                    requestId
                }
                watch(id, release)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                transferFailed(error)
            }
        }
    }

    private fun monitor(id: Long, release: UpdateRelease) {
        transferJob = viewModelScope.launch {
            try { watch(id, release) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { transferFailed(error) }
        }
    }

    private suspend fun watch(id: Long, release: UpdateRelease) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val row = withContext(Dispatchers.IO) { downloadStatus(id, release) }
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    mutableState.update { it.copy(phase = UpdatePhase.VERIFYING, message = "正在准备安装，请稍等") }
                    preparedFile = verifier.prepare(downloadFile(release), release)
                    mutableState.update { it.copy(phase = UpdatePhase.READY, downloadedBytes = release.sizeBytes,
                        totalBytes = release.sizeBytes, message = "") }
                    return
                }
                DownloadManager.STATUS_FAILED -> throw UpdateCheckException(downloadError(row.reason))
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    if (row.bytes > release.sizeBytes || row.total > GitHubReleaseClient.MAX_APK_BYTES) {
                        throw UpdateCheckException("安装包大小不正确，请重新下载。")
                    }
                    mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING,
                        downloadedBytes = row.bytes.coerceAtLeast(0), totalBytes = release.sizeBytes,
                        message = when (row.status) {
                            DownloadManager.STATUS_PAUSED -> "下载暂时暂停，网络恢复后会继续。"
                            DownloadManager.STATUS_PENDING -> "正在等待系统开始下载"
                            else -> ""
                        }) }
                }
                else -> throw UpdateCheckException("下载记录已失效，请重新下载。")
            }
            delay(1_000)
        }
    }

    private suspend fun transferFailed(error: Exception) {
        autoInstall = false
        preparedFile = null
        withContext(Dispatchers.IO) { removeSavedDownload() }
        mutableState.update { it.copy(phase = UpdatePhase.ERROR, message = when (error) {
            is UpdateCheckException -> error.userMessage
            is UpdatePackageException -> error.userMessage
            else -> "更新下载未完成，请检查网络和电视存储后重试。"
        }) }
    }

    fun cancelDownload() {
        val release = state.value.release
        val previous = transferJob
        previous?.cancel()
        autoInstall = false
        preparedFile = null
        mutableState.update { it.copy(phase = UpdatePhase.VERIFYING, message = "正在取消下载") }
        transferJob = viewModelScope.launch {
            try { previous?.join() }
            finally { withContext(NonCancellable + Dispatchers.IO) { removeSavedDownload() } }
            mutableState.update { it.copy(phase = if (release == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                downloadedBytes = 0, message = "已取消，可随时重新下载。") }
        }
    }

    fun shouldAutoInstall(): Boolean = autoInstall && state.value.dialogVisible && state.value.phase == UpdatePhase.READY
    fun stopAutoInstall() { autoInstall = false }
    fun fileForInstall(): File? = preparedFile?.takeIf { it.isFile }

    fun requireInstallPermission(message: String = "首次更新需要允许安装。开启后返回这里即可继续。") {
        autoInstall = false
        mutableState.update { it.copy(phase = UpdatePhase.INSTALL_PERMISSION, message = message, dialogVisible = true) }
    }

    fun permissionReturned(granted: Boolean) {
        if (!granted) return requireInstallPermission("还没有允许安装更新。请开启权限后返回，投屏功能不受影响。")
        autoInstall = true
        mutableState.update { it.copy(phase = if (preparedFile != null) UpdatePhase.READY else it.phase,
            message = "正在继续安装", dialogVisible = true) }
    }

    fun installMessage(message: String) {
        autoInstall = false
        mutableState.update { it.copy(message = message, dialogVisible = true) }
    }

    fun installerOpened() {
        autoInstall = false
        mutableState.update { it.copy(phase = UpdatePhase.READY, message = "请在电视系统页面选择安装。安装后重新打开云屿投屏即可。") }
    }

    fun missingPreparedFile() {
        preparedFile = null
        autoInstall = false
        mutableState.update { it.copy(phase = UpdatePhase.ERROR, message = "安装包已被系统清理，请重新下载。") }
    }

    private fun downloadStatus(id: Long, release: UpdateRelease): DownloadRow {
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) throw UpdateCheckException("下载记录已失效，请重新下载。")
            if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) != release.downloadUrl) {
                throw UpdateCheckException("下载信息不匹配，请重新下载。")
            }
            return DownloadRow(
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private fun downloadFile(release: UpdateRelease): File {
        val parent = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw UpdateCheckException("电视存储暂不可用，请稍后重试。")
        return File(parent, "updates/github-${release.assetId}.apk")
    }

    private fun readSavedRelease(): Pair<Long, UpdateRelease>? = runCatching {
        val id = prefs.getLong(DOWNLOAD_ID, -1)
        if (id < 0) return@runCatching null
        val obj = JSONObject(prefs.getString(RELEASE, null) ?: return@runCatching null)
        val digest = when (val saved = obj.opt("sha256")) {
            null, JSONObject.NULL -> null
            is String -> saved.takeIf { it.isNotEmpty() }
            else -> return@runCatching null
        }
        val release = UpdateRelease(obj.getString("tag"), obj.getString("version"), obj.getString("name"),
            obj.getString("notes"), obj.getLong("assetId"), obj.getString("assetName"), obj.getString("url"),
            obj.getLong("size"), digest)
        if (UpdateReleasePolicy.accepts(release)) id to release else null
    }.getOrNull()

    private fun encode(release: UpdateRelease): String = JSONObject().apply {
        put("tag", release.tag); put("version", release.versionName); put("name", release.name)
        put("notes", release.notes); put("assetId", release.assetId); put("assetName", release.assetName)
        put("url", release.downloadUrl); put("size", release.sizeBytes); put("sha256", release.sha256 ?: "")
    }.toString()

    private fun removeSavedDownload() {
        val saved = readSavedRelease()
        val id = prefs.getLong(DOWNLOAD_ID, -1)
        if (id >= 0) runCatching { downloads.remove(id) }
        saved?.second?.let { runCatching { downloadFile(it).delete() } }
        prefs.edit().remove(DOWNLOAD_ID).remove(RELEASE).commit()
    }

    private fun downloadError(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "电视存储空间不足，请清理一些空间后重试。"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "电视存储暂不可用，请稍后重试。"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "旧安装包未清理完成，请重新下载。"
        else -> "下载没有完成，请检查网络后重试。当前版本仍可正常投屏。"
    }

    private data class DownloadRow(val status: Int, val reason: Int, val bytes: Long, val total: Long)
    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val DOWNLOAD_ID = "download_id"
        private const val RELEASE = "release"
        private const val LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1_000
    }
}
