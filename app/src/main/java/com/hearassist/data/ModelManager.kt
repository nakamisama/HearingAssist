package com.hearassist.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** 模型按需准备，完整缓存不随 App 版本更新失效。 */
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

    private val BUNDLED = setOf("vad", "streaming")
    private val modelLocks = MODELS.associate { it.id to Mutex() }
    private const val READY_MARKER = ".ready.json"
    private const val COMPLETE_MARKER = ".complete.json"
    private const val MAX_MARKER_BYTES = 8192
    private const val MAX_FILE_BYTES = 1024L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16L * 1024 * 1024
    private const val MIN_MODEL_BYTES = 1024L * 1024
    private const val MAX_EXPANDED_BYTES = 2L * MAX_FILE_BYTES
    private const val MAX_ARCHIVE_ENTRIES = 4096
    private const val MAX_METADATA_BYTES = 64L * 1024
    private val generationName = Regex("cache-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private data class CacheMarker(
        val source: String,
        val origin: String,
        val directory: String,
        val sizes: Map<String, Long>,
    )

    private data class NetworkPermit(
        val network: Network,
        val wifi: Boolean,
        val ethernet: Boolean,
        val cellular: Boolean,
        val vpn: Boolean,
        val metered: Boolean,
    ) {
        val needsConfirmation: Boolean get() = cellular || metered
    }

    private class NetworkUnavailable : IOException("No validated, recognized network is available")

    private fun dir(ctx: Context, id: String) = File(ctx.filesDir, "models/$id")

    fun modelFile(ctx: Context, id: String, name: String): String {
        val spec = MODELS.first { it.id == id }
        require(name in spec.files) { "Unknown model file: $name" }
        val root = dir(ctx, id)
        val marker = readMarker(File(root, READY_MARKER))
        val target = if (marker != null && validCache(root, spec, marker)) {
            cacheDirectory(root, marker)!!
        } else root
        return File(target, name).absolutePath
    }

    // 只读取小标记和文件元数据；老缓存的标记迁移在 ensureModels 的 IO 上进行。
    fun isReady(ctx: Context, id: String): Boolean {
        val spec = MODELS.firstOrNull { it.id == id } ?: return false
        val root = dir(ctx, id)
        val markerFile = File(root, READY_MARKER)
        if (exists(markerFile)) {
            val marker = readMarker(markerFile) ?: return false
            return validCache(root, spec, marker)
        }
        return legacySizes(root, spec) != null
    }

    /** confirmCellular 须由可见 Activity 完成两次确认，默认拒绝；进度回调在 IO 上执行。 */
    suspend fun ensureModels(
        ctx: Context,
        ids: List<String>,
        onProgress: (Int, String) -> Unit,
        confirmCellular: suspend () -> Boolean = { false },
    ): Boolean = withContext(Dispatchers.IO) {
        if (ids.any { id -> MODELS.none { it.id == id } }) return@withContext false
        val specs = MODELS.filter { it.id in ids }
        var done = 0
        for (spec in specs) {
            currentCoroutineContext().ensureActive()
            val ready = modelLocks.getValue(spec.id).withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    prepareCached(ctx, spec) ||
                        (spec.id in BUNDLED && extractBundled(ctx, spec)) ||
                        downloadModel(ctx, spec, confirmCellular) { progress ->
                            onProgress((done * 100 + progress) / specs.size, "下载模型 ${spec.id} $progress%")
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    currentCoroutineContext().ensureActive()
                    false
                }
            }
            if (!ready) return@withContext false
            currentCoroutineContext().ensureActive()
            done++
            onProgress(done * 100 / specs.size, "模型 ${spec.id} 就绪")
        }
        true
    }

    fun isCellular(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    private suspend fun prepareCached(ctx: Context, spec: ModelSpec): Boolean {
        val root = dir(ctx, spec.id)
        if (!safeDirectory(root)) return false
        val readyFile = File(root, READY_MARKER)
        val ready = readMarker(readyFile)
        if (ready != null && validCache(root, spec, ready)) return true
        if (!exists(readyFile) && !exists(File(root, COMPLETE_MARKER))) {
            val sizes = legacySizes(root, spec)
            if (sizes != null) {
                val marker = CacheMarker(spec.url, "legacy", ".", sizes)
                writeMarker(File(root, COMPLETE_MARKER), marker)
                writeMarker(readyFile, marker)
                return true
            }
        }
        val legacy = readMarker(File(root, COMPLETE_MARKER))
        if (legacy != null && legacy.directory == "." && validCache(root, spec, legacy)) {
            writeMarker(readyFile, legacy)
            return true
        }
        // 发布中断留下的完整版本可重新挂接；从不删除已验证的旧版本。
        val generations = root.listFiles()?.filter { generationName.matches(it.name) && safeDirectory(it) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (generation in generations) {
            currentCoroutineContext().ensureActive()
            val marker = readMarker(File(generation, COMPLETE_MARKER)) ?: continue
            if (marker.directory == generation.name && validCache(root, spec, marker)) {
                writeMarker(readyFile, marker)
                return true
            }
        }
        return false
    }

    private suspend fun extractBundled(ctx: Context, spec: ModelSpec): Boolean {
        val root = ensureDirectory(ctx, spec.id)
        val staging = Files.createTempDirectory(root.toPath(), ".staging-").toFile()
        try {
            val asset = if (spec.archive) "bundled/${spec.id}.tar.bz2" else "bundled/${spec.files.single()}"
            val downloaded = File(staging, "download.tmp")
            val size = ctx.assets.open(asset).use { input ->
                FileOutputStream(downloaded).use { output -> CancellableHttp.copy(input, output) }
            }
            if (size <= 0 || downloaded.length() != size) throw IOException("Incomplete bundled model")
            val sizes = prepareFiles(downloaded, staging, spec, size)
            publish(root, staging, spec, asset, sizes)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            return false
        } finally {
            deleteStaging(staging)
        }
    }

    private fun mirrors(url: String) = listOf(
        "https://ghproxy.net/$url",
        "https://gh-proxy.com/$url",
        url,
    )

    private suspend fun downloadModel(
        ctx: Context,
        spec: ModelSpec,
        confirmCellular: suspend () -> Boolean,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val root = ensureDirectory(ctx, spec.id)
        for (source in mirrors(spec.url)) {
            currentCoroutineContext().ensureActive()
            val permit = try {
                chooseNetwork(ctx, confirmCellular) ?: return false
            } catch (_: NetworkUnavailable) {
                currentCoroutineContext().ensureActive()
                return false
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                continue
            }
            val staging = Files.createTempDirectory(root.toPath(), ".staging-").toFile()
            try {
                val downloaded = File(staging, "download.tmp")
                val (origin, size) = download(ctx, source, permit, downloaded, onProgress)
                currentCoroutineContext().ensureActive()
                val sizes = prepareFiles(downloaded, staging, spec, size)
                publish(root, staging, spec, origin, sizes)
                onProgress(100)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
            } finally {
                deleteStaging(staging)
            }
        }
        return false
    }

    private suspend fun download(
        ctx: Context,
        source: String,
        permit: NetworkPermit,
        target: File,
        onProgress: (Int) -> Unit,
    ): Pair<String, Long> {
        var endpoint = source
        val visited = HashSet<String>()
        for (redirects in 0..5) {
            currentCoroutineContext().ensureActive()
            checkNetwork(ctx, permit)
            if (!visited.add(endpoint)) throw IOException("HTTP redirect loop")
            try {
                val size = CancellableHttp.withConnection(endpoint, permit.network, readTimeout = 60000) { connection ->
                    checkNetwork(ctx, permit)
                    val expected = connection.contentLengthLong
                    if (expected <= 0 || expected > MAX_FILE_BYTES) throw IOException("Missing or excessive model length")
                    val encoding = connection.getHeaderField("Content-Encoding")
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                        throw IOException("Unexpected HTTP content encoding")
                    }
                    if (connection.contentType?.startsWith("text/html", ignoreCase = true) == true) {
                        throw IOException("Model endpoint returned HTML")
                    }
                    FileOutputStream(target).use { output ->
                        CancellableHttp.copy(connection.inputStream, output, expected,
                            checkTransfer = { checkNetwork(ctx, permit) },
                            onProgress = { onProgress(it.coerceAtMost(99)) })
                    }
                }
                if (target.length() != size) throw IOException("Incomplete model download")
                return endpoint to size
            } catch (e: CancellableHttp.HttpStatusException) {
                currentCoroutineContext().ensureActive()
                checkNetwork(ctx, permit)
                if (e.statusCode !in setOf(301, 302, 303, 307, 308) || redirects == 5 || e.location.isNullOrBlank()) throw e
                val next = URL(URL(endpoint), e.location)
                if (!next.protocol.equals("https", ignoreCase = true) || next.userInfo != null || next.host.isEmpty()) {
                    throw IOException("Unsafe HTTP redirect", e)
                }
                endpoint = next.toExternalForm()
            }
        }
        throw IOException("Too many HTTP redirects")
    }

    private suspend fun chooseNetwork(ctx: Context, confirmCellular: suspend () -> Boolean): NetworkPermit? {
        val permit = currentNetwork(ctx)
        if (permit.needsConfirmation && !withContext(Dispatchers.Main) { confirmCellular() }) return null
        currentCoroutineContext().ensureActive()
        checkNetwork(ctx, permit)
        return permit
    }

    private fun currentNetwork(ctx: Context): NetworkPermit {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: throw NetworkUnavailable()
        val network = cm.activeNetwork ?: throw NetworkUnavailable()
        val caps = cm.getNetworkCapabilities(network) ?: throw NetworkUnavailable()
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) throw NetworkUnavailable()
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (!wifi && !ethernet && !cellular) throw NetworkUnavailable()
        return NetworkPermit(network, wifi, ethernet, cellular,
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    private fun checkNetwork(ctx: Context, permit: NetworkPermit) {
        if (currentNetwork(ctx) != permit) throw IOException("Network or metering changed; fresh permission is required")
    }

    private suspend fun prepareFiles(downloaded: File, staging: File, spec: ModelSpec, downloadedSize: Long): Map<String, Long> {
        currentCoroutineContext().ensureActive()
        val sizes = if (spec.archive) {
            extractTarBz2(downloaded, staging, spec.files)
        } else {
            val name = spec.files.single()
            if (!validSize(name, downloadedSize)) throw IOException("Invalid model file size")
            Files.move(downloaded.toPath(), File(staging, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            mapOf(name to downloadedSize)
        }
        if (sizes.keys != spec.files.toSet() || sizes.any { (name, size) ->
                !validSize(name, size) || !regularFile(File(staging, name)) || File(staging, name).length() != size
            }) throw IOException("Model files are missing or incomplete")
        currentCoroutineContext().ensureActive()
        Files.deleteIfExists(downloaded.toPath())
        return sizes
    }

    private suspend fun extractTarBz2(archive: File, target: File, wanted: List<String>): Map<String, Long> {
        val context = currentCoroutineContext()
        val sizes = LinkedHashMap<String, Long>()
        val entries = HashSet<String>()
        var expanded = 0L
        val discard = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(buffer: ByteArray, offset: Int, length: Int) {}
        }
        FileInputStream(archive).use { file ->
            BufferedInputStream(file).use { buffered ->
                BZip2CompressorInputStream(buffered, true).use { decompressed ->
                    CheckedInputStream(decompressed, context).use { checked ->
                        val tarStream = object : TarArchiveInputStream(checked) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                context.ensureActive()
                                currentEntry?.let { entry ->
                                    if (entry.size < 0 || entry.size > MAX_FILE_BYTES ||
                                        ((entry.isPaxHeader || entry.isGlobalPaxHeader || entry.isGNULongNameEntry || entry.isGNULongLinkEntry) &&
                                            entry.size > MAX_METADATA_BYTES)) throw IOException("Excessive tar entry size")
                                }
                                val count = super.read(buffer, offset, length)
                                context.ensureActive()
                                return count
                            }
                        }
                        tarStream.use { tar ->
                            while (true) {
                                context.ensureActive()
                                val entry = tar.nextTarEntry ?: break
                                context.ensureActive()
                                val name = entry.name
                                if (entries.size >= MAX_ARCHIVE_ENTRIES || !entries.add(name)) throw IOException("Duplicate or excessive tar entries")
                                if (name.isEmpty() || name.length > 4096 || name.startsWith('/') || '\\' in name ||
                                    '\u0000' in name || ':' in name || name.split('/').any { it == ".." }) {
                                    throw IOException("Unsafe tar entry name")
                                }
                                if (entry.isSymbolicLink || entry.isLink || entry.isSparse ||
                                    (!entry.isDirectory && !entry.isFile) || !tar.canReadEntryData(entry)) {
                                    throw IOException("Unsupported tar entry")
                                }
                                if (entry.size < 0 || entry.size > MAX_FILE_BYTES || entry.size > MAX_EXPANDED_BYTES - expanded) {
                                    throw IOException("Excessive tar entry size")
                                }
                                expanded += entry.size
                                if (entry.isDirectory) {
                                    if (entry.size != 0L) throw IOException("Nonempty tar directory entry")
                                    continue
                                }
                                val base = name.substringAfterLast('/')
                                if (base in wanted) {
                                    if (base in sizes || !validSize(base, entry.size)) throw IOException("Duplicate or invalid model file")
                                    val outputFile = File(target, base)
                                    if (exists(outputFile)) throw IOException("Duplicate model destination")
                                    FileOutputStream(outputFile).use { output ->
                                        CancellableHttp.copy(tar, output, entry.size)
                                    }
                                    if (outputFile.length() != entry.size) throw IOException("Incomplete tar entry")
                                    sizes[base] = entry.size
                                } else {
                                    CancellableHttp.copy(tar, discard, entry.size)
                                }
                            }
                            // 验证压缩尾部；tar 结束后只允许零填充，拒绝额外拼接内容。
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = checked.read(buffer)
                                context.ensureActive()
                                if (count < 0) break
                                if ((0 until count).any { buffer[it] != 0.toByte() }) throw IOException("Unexpected data after tar archive")
                            }
                        }
                    }
                }
            }
        }
        context.ensureActive()
        if (sizes.keys != wanted.toSet()) throw IOException("Required model files are missing")
        return sizes
    }

    private class CheckedInputStream(input: InputStream, private val context: CoroutineContext) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            context.ensureActive()
            val value = `in`.read()
            context.ensureActive()
            if (value >= 0) record(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            val count = `in`.read(buffer, offset, length)
            context.ensureActive()
            if (count > 0) record(count)
            return count
        }

        override fun skip(count: Long): Long {
            val buffer = ByteArray(8192)
            var remaining = count
            while (remaining > 0) {
                val read = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read < 0) break
                remaining -= read
            }
            return count - remaining
        }

        private fun record(count: Int) {
            if (count.toLong() > MAX_EXPANDED_BYTES - total) throw IOException("Archive expansion limit exceeded")
            total += count
        }
    }

    private suspend fun publish(root: File, staging: File, spec: ModelSpec, origin: String, sizes: Map<String, Long>) {
        currentCoroutineContext().ensureActive()
        val generation = File(root, "cache-${UUID.randomUUID()}")
        val marker = CacheMarker(spec.url, origin, generation.name, sizes)
        writeMarker(File(staging, COMPLETE_MARKER), marker)
        currentCoroutineContext().ensureActive()
        Files.move(staging.toPath(), generation.toPath(), StandardCopyOption.ATOMIC_MOVE)
        writeMarker(File(root, READY_MARKER), marker)
    }

    private suspend fun writeMarker(file: File, marker: CacheMarker) {
        currentCoroutineContext().ensureActive()
        val sizes = JSONObject()
        marker.sizes.forEach { (name, size) -> sizes.put(name, size) }
        val bytes = JSONObject().put("format", 1).put("source", marker.source).put("origin", marker.origin)
            .put("directory", marker.directory).put("files", sizes).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MARKER_BYTES) throw IOException("Cache marker is too large")
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), ".marker-", ".tmp").toFile()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun readMarker(file: File): CacheMarker? {
        if (!regularFile(file) || file.length() !in 1..MAX_MARKER_BYTES.toLong()) return null
        return try {
            val bytes = ByteArray(MAX_MARKER_BYTES + 1)
            val count = FileInputStream(file).use { input ->
                var total = 0
                while (total < bytes.size) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read < 0) break
                    total += read
                }
                total
            }
            if (count > MAX_MARKER_BYTES) return null
            val json = JSONObject(String(bytes, 0, count, Charsets.UTF_8))
            if (json.getInt("format") != 1) return null
            val files = json.getJSONObject("files")
            val sizes = LinkedHashMap<String, Long>()
            val keys = files.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                sizes[name] = files.getLong(name)
            }
            CacheMarker(json.getString("source"), json.getString("origin"), json.getString("directory"), sizes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheDirectory(root: File, marker: CacheMarker): File? = when {
        marker.directory == "." -> root
        generationName.matches(marker.directory) -> File(root, marker.directory)
        else -> null
    }

    private fun validCache(root: File, spec: ModelSpec, marker: CacheMarker): Boolean {
        if (!safeDirectory(root) || marker.source != spec.url || marker.sizes.keys != spec.files.toSet()) return false
        val target = cacheDirectory(root, marker) ?: return false
        if (!safeDirectory(target) || exists(File(target, "download.tmp"))) return false
        return marker.sizes.all { (name, size) ->
            validSize(name, size) && regularFile(File(target, name)) && File(target, name).length() == size
        }
    }

    private fun legacySizes(root: File, spec: ModelSpec): Map<String, Long>? {
        if (!safeDirectory(root) || exists(File(root, "download.tmp"))) return null
        val sizes = LinkedHashMap<String, Long>()
        for (name in spec.files) {
            val file = File(root, name)
            val size = file.length()
            if (!regularFile(file) || !validSize(name, size)) return null
            sizes[name] = size
        }
        return sizes
    }

    private fun validSize(name: String, size: Long): Boolean = if (name.endsWith(".onnx")) {
        // Silero VAD 只有约 0.6 MB，不能套用其他模型的 1 MB 下限。
        val minimum = if (name == "silero_vad.onnx") 100_000L else MIN_MODEL_BYTES
        size in minimum..MAX_FILE_BYTES
    } else size in 1..MAX_TEXT_BYTES

    private fun ensureDirectory(ctx: Context, id: String): File {
        val models = File(ctx.filesDir, "models")
        val target = dir(ctx, id)
        for (directory in listOf(models, target)) {
            if (!exists(directory) && !directory.mkdir()) throw IOException("Cannot create model directory")
            if (!safeDirectory(directory)) throw IOException("Unsafe model directory")
        }
        return target
    }

    private fun regularFile(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun safeDirectory(file: File) = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun deleteStaging(staging: File) {
        try {
            if (safeDirectory(staging)) {
                staging.listFiles()?.forEach { file ->
                    if (safeDirectory(file)) deleteStaging(file) else Files.deleteIfExists(file.toPath())
                }
            }
            Files.deleteIfExists(staging.toPath())
        } catch (_: IOException) {
        }
    }
}
