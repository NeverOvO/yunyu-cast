package io.github.jqssun.airplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleasePolicyTest {
    private val release = UpdateRelease(
        tag = "v0.6.0-preview.1",
        versionName = "0.6.0-preview.1",
        name = "云屿投屏 0.6.0-preview.1",
        notes = "更新说明",
        assetId = 1234,
        assetName = "yunyu-cast-0.6.0-preview.1.apk",
        downloadUrl = "https://github.com/NeverOvO/yunyu-cast/releases/download/v0.6.0-preview.1/yunyu-cast-0.6.0-preview.1.apk",
        sizeBytes = 20_000_000,
        sha256 = "ab".repeat(32),
    )

    @Test fun validProjectAttachmentCanResumeWithOrWithoutPublishedDigest() {
        assertTrue(UpdateReleasePolicy.accepts(release))
        assertTrue(UpdateReleasePolicy.accepts(release.copy(sha256 = release.sha256!!.uppercase())))
        assertTrue(UpdateReleasePolicy.accepts(release.copy(sha256 = null)))
    }

    @Test fun restoredMalformedDigestIsRejectedInsteadOfDowngradedToNoDigest() {
        listOf("", "abc", "a".repeat(63), "a".repeat(65), "g".repeat(64), "sha256:${release.sha256}")
            .forEach { assertFalse(it, UpdateReleasePolicy.accepts(release.copy(sha256 = it))) }
    }

    @Test fun updateCannotChangeOriginRepositoryOrAddCredentials() {
        val url = release.downloadUrl
        listOf(
            url.replace("https:", "http:"),
            url.replace("github.com", "github.com.example.org"),
            url.replace("github.com", "user@github.com"),
            url.replace("github.com", "github.com:8443"),
            url.replace("NeverOvO/yunyu-cast", "someone/another-app"),
            "$url?download=another", "$url#fragment",
        ).forEach { assertFalse(it, UpdateReleasePolicy.accepts(release.copy(downloadUrl = it))) }
    }

    @Test fun attachmentIdentityMustAgreeWithTagAndVersion() {
        assertFalse(UpdateReleasePolicy.accepts(release.copy(tag = "v0.5.4-preview.1")))
        assertFalse(UpdateReleasePolicy.accepts(release.copy(versionName = "0.7.0")))
        assertFalse(UpdateReleasePolicy.accepts(release.copy(assetName = "unrelated.apk")))
        assertFalse(UpdateReleasePolicy.accepts(release.copy(downloadUrl = release.downloadUrl.replace("/download/", "/download/../"))))
    }

    @Test fun missingOrOversizedAttachmentMetadataCannotResume() {
        assertFalse(UpdateReleasePolicy.accepts(release.copy(assetId = 0)))
        assertFalse(UpdateReleasePolicy.accepts(release.copy(sizeBytes = 0)))
        assertFalse(UpdateReleasePolicy.accepts(release.copy(sizeBytes = UpdateReleasePolicy.MAX_APK_BYTES + 1)))
        assertTrue(UpdateReleasePolicy.accepts(release.copy(sizeBytes = UpdateReleasePolicy.MAX_APK_BYTES)))
    }

    @Test fun standardHttpsPortAndHostCaseAreAccepted() {
        assertTrue(UpdateReleasePolicy.accepts(release.copy(downloadUrl = release.downloadUrl.replace("github.com", "GITHUB.COM:443"))))
    }
}
