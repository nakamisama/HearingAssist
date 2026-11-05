package com.hearassist.data

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 模型按需下载 + 本地缓存（A2），国内镜像优先（A1）。
 * 模型不打包进 APK：首次/升级时下载到 filesDir/models/<id>/，之后代码更新不重下模型。
 */
object ModelManager {

    data class ModelSpec(val id: String, val url: String, val archive: Boolean, val files: List<String>)

    // 模型清单：url 为 GitHub 原始地址，下载时自动套国内镜像
    val MODELS = listOf(
        ModelSpec("vad", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", false, listOf("silero_vad.onnx")),
        ModelSpec("sensevoice", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2", true, listOf("model.int8.onnx", "tokens.txt")),
        ModelSpec("streaming", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2", true, listOf("model.int8.onnx", "tokens.txt")),
        ModelSpec("voiceprint", "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", false, listOf("3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")),
        ModelSpec("audio_tagging", "https://github.com/k2-fsa/sherpa-onnx/releases/download/audio-tagging-models/sherpa-onnx-zipformer-small-audio-tagging-2024-04-15.tar.bz2", true, listOf("model.int8.onnx", "class_labels_indices.csv")),
    )

    private fun dir(ctx: Context, id: String) = File(ctx.filesDir, "models/$id")

    fun modelFile(ctx: Context, id: String, name: String): String = File(dir(ctx, id), name).absolutePath

    fun isReady(ctx: Context, id: String): Boolean {
        val spec = MODELS.first { it.id == id }
        return spec.files.all { File(dir(ctx, id), it).length() > 0 }
    }

    // 小模型随 APK 内置（assets/bundled），首次使用解压到本地，免下载开箱即用
    private val BUNDLED = setOf("vad", "streaming")

    /** 内置模型从 assets 解压到本地缓存；成功返回 true。 */
    private fun extractBundled(ctx: Context, id: String): Boolean {
        val spec = MODELS.first { it.id == id }
        return try {
            val target = dir(ctx, id)
            target.mkdirs()
            if (!spec.archive) {
                // 单文件模型（vad）直接复制
                ctx.assets.open("bundled/${spec.files[0]}").use { input ->
                    FileOutputStream(File(target, spec.files[0])).use { input.copyTo(it) }
                }
                return isReady(ctx, id)
            }
            ctx.assets.open("bundled/$id.tar.bz2").use { input ->
                TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entry.isDirectory) continue
                        val base = entry.name.substringAfterLast('/')
                        if (base !in spec.files) continue
                        FileOutputStream(File(target, base)).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            isReady(ctx, id)
        } catch (_: Exception) {
            false
        }
    }

    /** 确保所需模型已下载，返回是否全部就绪。onProgress(总体百分比, 状态文本)。
     *  confirmCellular：需要走网络下载且当前是流量时，先调用它征求用户同意（返回 false 则取消）。 */
    fun ensureModels(
        ctx: Context,
        ids: List<String>,
        onProgress: (Int, String) -> Unit,
        confirmCellular: () -> Boolean = { true },
    ): Boolean {
        val specs = MODELS.filter { it.id in ids }
        var done = 0
        var cellularChecked = false
        for (spec in specs) {
            if (!isReady(ctx, spec.id)) {
                // 内置小模型先从 APK 解压（免下载，不耗网络）
                if (spec.id in BUNDLED && extractBundled(ctx, spec.id)) {
                    done++; onProgress(done * 100 / specs.size, "模型 ${spec.id} 就绪"); continue
                }
                // 需要网络下载：流量环境下先征求同意（一次会话只问一次）
                if (!cellularChecked) {
                    cellularChecked = true
                    if (isCellular(ctx) && !confirmCellular()) return false
                }
                if (!downloadModel(ctx, spec) { p ->
                        onProgress((done * 100 + p) / specs.size, "下载模型 ${spec.id} $p%")
                    }) return false
            }
            done++
            onProgress(done * 100 / specs.size, "模型 ${spec.id} 就绪")
        }
        return true
    }

    /** 当前是否为移动数据（非WiFi、非以太网）。 */
    fun isCellular(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun mirrors(url: String) = listOf(
        "https://ghproxy.net/$url",
        "https://gh-proxy.com/$url",
        url,
    )

    private fun downloadModel(ctx: Context, spec: ModelSpec, onProgress: (Int) -> Unit): Boolean {
        val target = dir(ctx, spec.id)
        target.mkdirs()
        for (u in mirrors(spec.url)) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) continue
                val total = conn.contentLengthLong
                val tmp = File(target, "download.tmp")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
                if (spec.archive) extractTarBz2(tmp, target, spec.files) else tmp.copyTo(File(target, spec.files[0]), overwrite = true)
                tmp.delete()
                if (isReady(ctx, spec.id)) { onProgress(100); return true }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** 解压 tar.bz2，只提取需要的文件（按 basename 匹配）。 */
    private fun extractTarBz2(archive: File, target: File, wanted: List<String>) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                if (base !in wanted) continue
                FileOutputStream(File(target, base)).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = tar.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                    }
                }
            }
        }
    }
}
