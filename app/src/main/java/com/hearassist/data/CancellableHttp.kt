package com.hearassist.data

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object CancellableHttp {

    private const val MAX_TRANSFER_BYTES = 1024L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 1024 * 1024

    class HttpStatusException(
        val statusCode: Int,
        val location: String?,
        val requestUrl: String,
    ) : IOException("HTTP $statusCode for $requestUrl")

    suspend fun <T> withConnection(
        url: String,
        network: Network? = null,
        connectTimeout: Int = 10000,
        readTimeout: Int = 30000,
        block: suspend (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        coroutineScope {
            currentCoroutineContext().ensureActive()
            val endpoint = URL(url)
            if (!endpoint.protocol.equals("https", ignoreCase = true) ||
                endpoint.host.isEmpty() || endpoint.userInfo != null) {
                throw IOException("Only HTTPS URLs without credentials are allowed")
            }
            if (connectTimeout <= 0 || readTimeout <= 0) {
                throw IOException("HTTP timeouts must be positive")
            }
            val connection = (network?.openConnection(endpoint) ?: endpoint.openConnection()) as HttpURLConnection
            var input: InputStream? = null
            var error: InputStream? = null
            val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "HearingAssist")
                currentCoroutineContext().ensureActive()
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                if (status != HttpURLConnection.HTTP_OK) {
                    error = connection.errorStream
                    throw HttpStatusException(status, connection.getHeaderField("Location"), url)
                }
                if (connection.contentLengthLong > MAX_TRANSFER_BYTES) {
                    throw IOException("HTTP response exceeds the transfer limit")
                }
                input = connection.inputStream
                currentCoroutineContext().ensureActive()
                val result = block(connection)
                currentCoroutineContext().ensureActive()
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                watcher.cancel()
                try {
                    connection.disconnect()
                } finally {
                    closeStream(input)
                    closeStream(error)
                }
            }
        }
    }

    // 流由调用方持有；每块传输都检查取消与调用方的网络许可。
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long = -1,
        checkTransfer: () -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        if (expectedBytes < -1 || expectedBytes > MAX_TRANSFER_BYTES) {
            throw IOException("Invalid or excessive transfer length")
        }
        val context = currentCoroutineContext()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var lastProgress = -1
        try {
            while (true) {
                context.ensureActive()
                checkTransfer()
                val count = input.read(buffer)
                context.ensureActive()
                checkTransfer()
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > MAX_TRANSFER_BYTES - total ||
                    (expectedBytes >= 0 && count.toLong() > expectedBytes - total)) {
                    throw IOException("Response is longer than the permitted length")
                }
                output.write(buffer, 0, count)
                total += count
                context.ensureActive()
                if (expectedBytes > 0) {
                    val progress = (total * 100 / expectedBytes).toInt().coerceIn(0, 99)
                    if (progress != lastProgress) {
                        onProgress(progress)
                        lastProgress = progress
                    }
                }
            }
            if (expectedBytes >= 0 && total != expectedBytes) {
                throw IOException("Incomplete response: expected $expectedBytes bytes, received $total")
            }
            context.ensureActive()
            checkTransfer()
            onProgress(100)
            total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.ensureActive()
            throw e
        }
    }

    suspend fun readText(
        url: String,
        network: Network? = null,
        maxBytes: Int = 131072,
    ): String = withContext(Dispatchers.IO) {
        if (maxBytes !in 1..MAX_TEXT_BYTES) throw IOException("Invalid text response limit")
        withConnection(url, network) { connection ->
            val expected = connection.contentLengthLong
            if (expected > maxBytes) throw IOException("Text response exceeds $maxBytes bytes")
            val bytes = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    if (bytes.size() >= maxBytes) throw IOException("Text response exceeds $maxBytes bytes")
                    bytes.write(value)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (length > maxBytes - bytes.size()) throw IOException("Text response exceeds $maxBytes bytes")
                    bytes.write(buffer, offset, length)
                }
            }
            copy(connection.inputStream, output, expected)
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun closeStream(stream: InputStream?) {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
    }
}
