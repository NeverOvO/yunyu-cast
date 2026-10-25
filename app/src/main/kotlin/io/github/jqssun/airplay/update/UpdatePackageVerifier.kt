package io.github.jqssun.airplay.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class UpdatePackageException(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)

/**
 * Copies a completed download into private storage before handing it to the system installer.
 * The system installer still performs its own full APK verification and final confirmation.
 */
class UpdatePackageVerifier(context: Context) {
    private val context = context.applicationContext
    private val preparation = Mutex()

    suspend fun prepare(downloadedFile: File, release: UpdateRelease): File =
        withContext(Dispatchers.IO) {
            preparation.withLock {
                currentCoroutineContext().ensureActive()
                if (release.assetId <= 0 || release.sizeBytes !in 1..MAX_APK_BYTES) {
                    throw UpdatePackageException("更新文件信息有误，请重新检查更新。")
                }
                val expectedHash = release.sha256
                if (expectedHash != null && !SHA256_HEX.matches(expectedHash)) {
                    throw UpdatePackageException("更新文件信息有误，请重新检查更新。")
                }

                val directory = File(context.cacheDir, "updates")
                val partial = File(directory, "${release.assetId}.pending.apk")
                val prepared = File(directory, "${release.assetId}.apk")
                var promoted = false
                try {
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        throw IOException("Cannot create private update directory")
                    }
                    if (!downloadedFile.isFile || downloadedFile.length() != release.sizeBytes) {
                        throw UpdatePackageException("下载文件不完整，请重新下载。")
                    }
                    val freeBytes = directory.usableSpace
                    if (freeBytes > 0 && freeBytes < release.sizeBytes + STORAGE_MARGIN_BYTES) {
                        throw UpdatePackageException("电视存储空间不足，请清理空间后重试。")
                    }
                    // Only this attempt's temporary file is replaced. Existing prepared APKs
                    // stay available until the new candidate has passed every check below.
                    val hash = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    downloadedFile.inputStream().buffered().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied += count
                                if (copied > release.sizeBytes || copied > MAX_APK_BYTES) {
                                    throw UpdatePackageException("下载文件大小不符，请重新下载。")
                                }
                                output.write(buffer, 0, count)
                                hash.update(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    if (copied != release.sizeBytes) {
                        throw UpdatePackageException("下载文件不完整，请重新下载。")
                    }
                    val actualHash = hash.digest().hex()
                    if (expectedHash != null && !actualHash.equals(expectedHash, ignoreCase = true)) {
                        throw UpdatePackageException("下载文件校验失败，请重新下载。")
                    }
                    currentCoroutineContext().ensureActive()
                    checkPackage(partial, release)
                    currentCoroutineContext().ensureActive()
                    if (!partial.renameTo(prepared)) {
                        throw IOException("Cannot promote the prepared APK")
                    }
                    promoted = true
                    currentCoroutineContext().ensureActive()
                    removeExpiredFiles(directory, prepared)
                    prepared
                } catch (cancelled: CancellationException) {
                    if (promoted) prepared.delete()
                    throw cancelled
                } catch (error: UpdatePackageException) {
                    if (promoted) prepared.delete()
                    throw error
                } catch (error: IOException) {
                    if (promoted) prepared.delete()
                    throw UpdatePackageException("无法保存更新，请检查电视存储空间后重试。", error)
                } catch (error: Exception) {
                    if (promoted) prepared.delete()
                    throw UpdatePackageException("无法读取这份更新，请重新下载。", error)
                } finally {
                    partial.delete()
                }
            }
        }

    @Suppress("DEPRECATION")
    private fun checkPackage(file: File, release: UpdateRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdatePackageException("安装包无法识别，请重新下载。")
        val installed = manager.getPackageInfo(context.packageName, flags)

        if (candidate.packageName != context.packageName) {
            throw UpdatePackageException("这份更新不属于云屿投屏，已停止安装。")
        }
        if (versionCode(candidate) <= versionCode(installed)) {
            throw UpdatePackageException("这份安装包不能升级当前版本，请重新检查更新。")
        }
        val expectedVersion = UpdateVersion.parse(release.versionName)
        val candidateVersion = candidate.versionName?.let { UpdateVersion.parse(it) }
        if (expectedVersion == null || candidateVersion == null ||
            candidateVersion.compareTo(expectedVersion) != 0) {
            throw UpdatePackageException("安装包版本与更新信息不一致，请重新检查更新。")
        }
        val application = candidate.applicationInfo
            ?: throw UpdatePackageException("安装包信息不完整，请重新下载。")
        if (application.minSdkVersion > Build.VERSION.SDK_INT) {
            throw UpdatePackageException("这个版本需要更新的电视系统，暂时无法安装。")
        }
        val installedSigners = currentSigners(installed)
        val candidateSigners = currentSigners(candidate)
        if (installedSigners.isEmpty() || candidateSigners.isEmpty()) {
            throw UpdatePackageException("无法确认安装包来源，请重新下载。")
        }
        // Only the current signing set is accepted. Certificate rotation requires a
        // separate migration; a history-only match must not bypass this update policy.
        if (candidateSigners != installedSigners) {
            throw UpdatePackageException("这份更新与当前应用来源不一致，已停止安装。请联系开发者。")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
    }

    private fun removeExpiredFiles(directory: File, current: File) {
        val oldestKept = System.currentTimeMillis() - OLD_FILE_AGE_MS
        directory.listFiles()?.forEach { file ->
            // Keep recent older packages in case the system installer is still reading one.
            if (file != current && file.isFile && OWN_FILE_NAME.matches(file.name) &&
                file.lastModified() < oldestKept) {
                file.delete()
            }
        }
    }

    private fun ByteArray.hex(): String = buildString(size * 2) {
        for (byte in this@hex) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private companion object {
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        const val STORAGE_MARGIN_BYTES = 1024L * 1024
        const val OLD_FILE_AGE_MS = 24L * 60 * 60 * 1000
        const val HEX_DIGITS = "0123456789abcdef"
        val SHA256_HEX = Regex("[a-fA-F0-9]{64}")
        val OWN_FILE_NAME = Regex("[0-9]+(?:\\.pending\\.apk|\\.apk(?:\\.part)?)")
    }
}
