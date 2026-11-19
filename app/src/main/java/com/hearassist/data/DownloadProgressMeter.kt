package com.hearassist.data

/** 以单调时间采样实际接收字节，未知总大小时仍能显示下载量和速率。 */
class DownloadProgressMeter(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Snapshot(val received: Long, val total: Long, val bytesPerSecond: Long) {
        val percent: Int? get() = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else null
    }

    private var lastTime = clock()
    private var lastBytes = 0L
    private var emitted = false

    fun sample(received: Long, total: Long, finished: Boolean = false): Snapshot? {
        require(received >= lastBytes) { "Received byte count must not decrease" }
        val now = clock()
        val elapsed = (now - lastTime).coerceAtLeast(0)
        if (emitted && elapsed < 250 && !finished) return null
        val rate = if (elapsed > 0) (received - lastBytes) * 1000 / elapsed else 0
        lastTime = now
        lastBytes = received
        emitted = true
        return Snapshot(received, total, rate)
    }
}
