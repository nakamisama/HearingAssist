package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    @Test
    fun namedApkTriesBothMirrorsBeforeGithub() {
        val release = ReleaseInfo(43, "bate1.0.1", "bate1.0.1")
        assertEquals(listOf(
            "https://gh-proxy.com/${release.downloadUrl}",
            "https://ghproxy.net/${release.downloadUrl}",
            release.downloadUrl,
        ), UpdateChannels.apkUrls(release))
    }

    @Test
    fun currentManifestIsMirrorFirstAndLegacyIsOnlyFallback() {
        val urls = UpdateChannels.manifestUrls()
        assertEquals(6, urls.size)
        assertTrue(urls[0].startsWith("https://gh-proxy.com/"))
        assertTrue(urls[1].startsWith("https://ghproxy.net/"))
        assertTrue(urls[2].startsWith("https://raw.githubusercontent.com/"))
        assertTrue(urls.take(3).all { it.endsWith("/updates/latest.json") })
        assertTrue(urls.drop(3).all { it.endsWith("/version.json") })
    }

    @Test
    fun legacyApkUsesTheSameMirrorPriority() {
        val release = ReleaseInfo.fromLegacyTag("build-42")
        val urls = UpdateChannels.apkUrls(release)
        assertTrue(urls.first().startsWith("https://gh-proxy.com/"))
        assertTrue(urls.all { it.endsWith("/build-42/app-release.apk") })
        assertEquals(release.downloadUrl, urls.last())
    }

    @Test
    fun routesRemainHttpsAndNeverUseTheDevelopmentComputerProxy() {
        val urls = UpdateChannels.manifestUrls() +
            UpdateChannels.apkUrls(ReleaseInfo(43, "bate1.0.1", "bate1.0.1"))
        assertTrue(urls.all { it.startsWith("https://") })
        assertFalse(urls.any { "127.0.0.1" in it || "localhost" in it || ":7897" in it })
    }
}
