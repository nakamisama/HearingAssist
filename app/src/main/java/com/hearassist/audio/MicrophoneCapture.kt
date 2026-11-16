package com.hearassist.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.util.concurrent.CancellationException

/** 取消只 stop 以唤醒阻塞 read；录音任务退出后才由所有者 close/release。 */
class MicrophoneCapture(private val hardwareAgc: Boolean = false) : AutoCloseable {
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var stopped = false
    private val buffer = ShortArray(VadProcessor.WINDOW_SIZE)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = synchronized(lock) {
        if (stopped) throw CancellationException("录音已取消")
        val minimum = AudioRecord.getMinBufferSize(
            VadProcessor.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "设备不支持当前录音格式" }
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VadProcessor.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, 16384),
        )
        recorder = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        if (hardwareAgc && AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(rec.audioSessionId)?.also { it.setEnabled(true) }
            } catch (_: RuntimeException) {
                // 部分设备声明支持 AGC，但不允许创建；软件增益仍可工作。
            }
        }
        rec.startRecording()
        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音" }
    }

    fun read(): FloatArray {
        val rec = synchronized(lock) {
            if (stopped) throw CancellationException("录音已取消")
            recorder ?: throw IllegalStateException("麦克风未就绪")
        }
        val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        synchronized(lock) {
            if (stopped) throw CancellationException("录音已取消")
        }
        if (n < 0) throw IOException("麦克风读取失败：$n")
        return FloatArray(n) { buffer[it] / 32768f }
    }

    fun requestStop() = synchronized(lock) {
        stopped = true
        try { recorder?.stop() } catch (_: IllegalStateException) {}
    }

    override fun close() {
        synchronized(lock) {
            requestStop()
            try { agc?.release() } finally {
                agc = null
                recorder?.release()
                recorder = null
            }
        }
    }
}
