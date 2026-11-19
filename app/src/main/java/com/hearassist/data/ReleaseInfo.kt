package com.hearassist.data

import org.json.JSONObject

/** 下载位置由受限的仓库 tag/文件名生成，版本比较始终使用 Android 整数版本号。 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val asset: String = APK_ASSET,
) {
    init {
        require(versionCode in 1..2_100_000_000) { "Invalid version code" }
        require(DISPLAY_NAME.matches(versionName)) { "Invalid version name" }
        require(asset == APK_ASSET) { "Unsupported APK asset" }
        when {
            RELEASE_TAG.matches(tag) -> require(tag == versionName) { "Version name and release tag differ" }
            LEGACY_TAG.matches(tag) -> require(tag.removePrefix("build-").toIntOrNull() == versionCode) {
                "Legacy tag and version code differ"
            }
            else -> throw IllegalArgumentException("Invalid release tag")
        }
    }

    val downloadUrl: String
        get() = "https://github.com/nakamisama/HearingAssist/releases/download/$tag/$asset"

    val legacy: Boolean get() = LEGACY_TAG.matches(tag)
    fun isNewerThan(installedCode: Int): Boolean = versionCode > installedCode

    companion object {
        const val APK_ASSET = "app-release.apk"
        private val RELEASE_TAG = Regex("bate[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
        private val LEGACY_TAG = Regex("build-[1-9][0-9]{0,9}")
        private val DISPLAY_NAME = Regex("(?:bate)?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}|[0-9]{1,10}")

        fun parse(text: String): ReleaseInfo {
            val json = JSONObject(text)
            if (json.has("versionCode")) {
                return ReleaseInfo(code(json, "versionCode"), json.getString("versionName"),
                    json.getString("tag"), json.getString("asset"))
            }
            val version = code(json, "version")
            // 旧清单没有显示版本名，只显示整数；兼容旧下载位置，但不伪造语义版本。
            return ReleaseInfo(version, version.toString(), "build-$version")
        }

        fun fromLegacyTag(tag: String): ReleaseInfo {
            require(LEGACY_TAG.matches(tag)) { "Tag does not contain an Android version code" }
            val version = requireNotNull(tag.removePrefix("build-").toIntOrNull())
            return ReleaseInfo(version, version.toString(), tag)
        }

        private fun code(json: JSONObject, key: String): Int {
            val raw = json.get(key)
            require(raw is Int || raw is Long) { "Version code must be an integer" }
            val value = (raw as Number).toLong()
            require(value in 1..2_100_000_000L) { "Version code is outside Android's range" }
            return value.toInt()
        }
    }
}
