package com.hearassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    @Test
    fun namedReleaseUsesTheDisplayNameAndNamedDownloadTag() {
        val release = ReleaseInfo.parse("""{"versionCode":43,"versionName":"bate1.0.1","tag":"bate1.0.1","asset":"app-release.apk"}""")
        assertEquals(43, release.versionCode)
        assertEquals("bate1.0.1", release.versionName)
        assertEquals("https://github.com/nakamisama/HearingAssist/releases/download/bate1.0.1/app-release.apk", release.downloadUrl)
        assertFalse(release.legacy)
    }

    @Test
    fun originalManifestRetainsTheLegacyDownloadAddressWithoutInventingAName() {
        val release = ReleaseInfo.parse("""{"version":42}""")
        assertEquals("42", release.versionName)
        assertTrue(release.legacy)
        assertTrue(release.downloadUrl.endsWith("/build-42/app-release.apk"))
    }

    @Test
    fun initialNewManifestCanDescribeTheActuallyPublishedLegacyApk() {
        val release = ReleaseInfo.parse("""{"versionCode":42,"versionName":"1.1.0","tag":"build-42","asset":"app-release.apk"}""")
        assertEquals("1.1.0", release.versionName)
        assertFalse(release.isNewerThan(42))
    }

    @Test
    fun updateComparisonUsesIntegerCodeNotLexicalVersionName() {
        val release = ReleaseInfo(43, "bate1.0.1", "bate1.0.1")
        assertTrue(release.isNewerThan(42))
        assertFalse(release.isNewerThan(43))
        assertFalse(release.isNewerThan(44))
    }

    @Test
    fun rejectsMismatchedAndUnsafeTagsAndAssets() {
        for (release in listOf(
            """{"versionCode":43,"versionName":"bate1.0.1","tag":"bate1.0.2","asset":"app-release.apk"}""",
            """{"versionCode":43,"versionName":"bate1.0.1","tag":"../other","asset":"app-release.apk"}""",
            """{"versionCode":43,"versionName":"bate1.0.1","tag":"https://example.com","asset":"app-release.apk"}""",
            """{"versionCode":43,"versionName":"bate1.0.1","tag":"bate1.0.1","asset":"../evil.apk"}""",
            """{"versionCode":43,"versionName":"bate1.0.1","tag":"build-42","asset":"app-release.apk"}""",
        )) assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(release) }
    }

    @Test
    fun rejectsNonIntegerAndInvalidVersionCodes() {
        for (value in listOf("0", "-1", "2100000001", "42.5", "\"43\"")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse("{\"version\":$value}") }
        }
    }

    @Test
    fun legacyVersionAtTheAndroidLimitStillParses() {
        val release = ReleaseInfo.parse("""{"version":2100000000}""")
        assertEquals(2_100_000_000, release.versionCode)
        assertEquals("2100000000", release.versionName)
    }

    @Test
    fun legacyApiFallbackDoesNotInterpretNamedTagsAsIntegerCodes() {
        assertEquals(42, ReleaseInfo.fromLegacyTag("build-42").versionCode)
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.fromLegacyTag("bate1.0.1") }
    }
}
