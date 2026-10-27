package io.github.jqssun.airplay.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads this project's public releases, without credentials or third-party download mirrors. */
class GitHubReleaseClient {
    suspend fun findUpdate(installedVersion: String): UpdateRelease? {
        val current = UpdateVersion.parse(installedVersion)
            ?: throw UpdateCheckException("无法读取当前版本，请重新打开应用后再试。")
        try {
            // A total timeout also handles a server that keeps sending tiny chunks before readTimeout.
            val payload = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { readReleases(current.value) }
            } ?: throw UpdateCheckException("检查更新超时，请稍后再试。当前版本仍可正常投屏。")
            return withContext(Dispatchers.IO) { selectRelease(payload, current) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: UpdateCheckException) {
            throw error
        } catch (error: SSLException) {
            throw UpdateCheckException("无法安全连接更新服务，请检查电视日期、时间和网络。", error)
        } catch (error: SocketTimeoutException) {
            throw UpdateCheckException("检查更新超时，请稍后再试。当前版本仍可正常投屏。", error)
        } catch (error: UnknownHostException) {
            throw UpdateCheckException("暂时连不上更新服务，请检查电视网络后重试。", error)
        } catch (error: JSONException) {
            throw UpdateCheckException("更新信息暂时无法读取，请稍后重试。", error)
        } catch (error: IOException) {
            throw UpdateCheckException("暂时无法获取更新，请检查网络后重试。当前版本仍可正常投屏。", error)
        } catch (error: Exception) {
            throw UpdateCheckException("暂时无法检查更新，请稍后重试。", error)
        }
    }

    private suspend fun readReleases(version: String): String = suspendCancellableCoroutine { continuation ->
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
        try {
            if (!continuation.isActive) throw CancellationException("Update check cancelled")
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("User-Agent", "YunyuCast-AndroidTV/$version")
            if (!continuation.isActive) throw CancellationException("Update check cancelled")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                429 -> throw UpdateCheckException("检查更新太频繁，请稍后再试。当前版本仍可正常投屏。")
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val limited = connection.getHeaderField("X-RateLimit-Remaining") == "0"
                    throw UpdateCheckException(if (limited) "检查更新太频繁，请稍后再试。当前版本仍可正常投屏。"
                        else "更新服务暂时拒绝连接，请稍后重试。")
                }
                HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateCheckException("暂时没有可用的更新信息，请稍后重试。")
                in 500..599 -> throw UpdateCheckException("更新服务暂时不可用，请稍后再试。")
                else -> throw UpdateCheckException("暂时无法获取更新信息，请稍后重试。（$status）")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw UpdateCheckException("更新信息过大，暂时无法读取，请稍后重试。")
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    if (!continuation.isActive) throw CancellationException("Update check cancelled")
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (bytes.size().toLong() + length > MAX_RESPONSE_BYTES) {
                        throw UpdateCheckException("更新信息过大，暂时无法读取，请稍后重试。")
                    }
                    bytes.write(buffer, 0, length)
                }
            }
            if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8.name()))
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun selectRelease(payload: String, current: UpdateVersion): UpdateRelease? {
        val releases = JSONArray(payload)
        var best: UpdateRelease? = null
        var bestVersion: UpdateVersion? = null
        var newerReleaseWithoutPackage = false
        for (index in 0 until releases.length()) {
            coroutineContext.ensureActive()
            val release = releases.optJSONObject(index) ?: continue
            if (release.opt("draft") != false) continue
            val tag = release.text("tag_name")
            val version = UpdateVersion.parse(tag) ?: continue
            if (version <= current) continue
            val assets = release.optJSONArray("assets")
            var candidate: UpdateRelease? = null
            if (assets != null) {
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    val name = asset.text("name")
                    if (name != "yunyu-cast-${version.value}.apk" || asset.text("state") != "uploaded") continue
                    val size = asset.opt("size")?.toString()?.toLongOrNull() ?: continue
                    val id = asset.opt("id")?.toString()?.toLongOrNull() ?: continue
                    if (id <= 0 || size !in 1..MAX_APK_BYTES) continue
                    val downloadUrl = asset.text("browser_download_url")
                    if (!UpdateReleasePolicy.validAssetUrl(downloadUrl, tag, name)) continue
                    val digestValue = asset.opt("digest")
                    val digest = if (digestValue == null || digestValue == JSONObject.NULL) null else {
                        val text = digestValue as? String ?: continue
                        if (!SHA256_DIGEST.matches(text)) continue
                        text.substringAfter(':').lowercase()
                    }
                    candidate = UpdateRelease(
                        tag = tag,
                        versionName = version.value,
                        name = release.text("name").take(120).ifBlank { "云屿投屏 ${version.value}" },
                        notes = release.text("body").take(2_000),
                        assetId = id,
                        assetName = name,
                        downloadUrl = downloadUrl,
                        sizeBytes = size,
                        sha256 = digest,
                    )
                    break
                }
            }
            if (candidate == null) {
                newerReleaseWithoutPackage = true
            } else if (bestVersion == null || version > bestVersion) {
                bestVersion = version
                best = candidate
            }
        }
        if (best == null && newerReleaseWithoutPackage) {
            throw UpdateCheckException("新版安装包暂未准备好，请稍后重试。当前版本仍可正常投屏。")
        }
        return best
    }

    private fun JSONObject.text(key: String): String = opt(key) as? String ?: ""

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/NeverOvO/yunyu-cast/releases?per_page=100"
        const val MAX_APK_BYTES = UpdateReleasePolicy.MAX_APK_BYTES
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private val SHA256_DIGEST = Regex("^sha256:[0-9a-fA-F]{64}$")
    }
}
