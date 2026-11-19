package com.hearassist.data

/** 更新检查与安装包下载共用镜像顺序，官方直连仅作备用，不依赖电脑代理。 */
object UpdateChannels {
    private val mirrors = listOf("https://gh-proxy.com/", "https://ghproxy.net/")
    private const val repository = "https://raw.githubusercontent.com/nakamisama/HearingAssist/main"

    fun apkUrls(release: ReleaseInfo): List<String> = mirrorFirst(release.downloadUrl)

    fun manifestUrls(): List<String> =
        mirrorFirst("$repository/updates/latest.json") + mirrorFirst("$repository/version.json")

    private fun mirrorFirst(official: String): List<String> = mirrors.map { it + official } + official
}
