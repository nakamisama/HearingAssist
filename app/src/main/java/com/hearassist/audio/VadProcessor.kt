package com.hearassist.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD 封装：判断当前音频里是否有人说话，并把连续语音切分成段。
 * 模型仅 0.6MB，CPU 开销极小，是整个管线的“调度器”。
 */
class VadProcessor(assets: AssetManager) : AutoCloseable {

    private val vad: Vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/silero_vad.onnx",
                threshold = 0.5f,
                // 停顿 0.35 秒即认为一句话结束（比默认 0.25 略长，避免把停顿误切成两句）
                minSilenceDuration = 0.35f,
                minSpeechDuration = 0.25f,
                // 单段最长 8 秒，保护 Whisper 不吃超长输入导致卡顿
                maxSpeechDuration = 8.0f,
                windowSize = WINDOW_SIZE,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    val windowSize = WINDOW_SIZE

    fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
    fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
    fun hasSegments(): Boolean = !vad.empty()

    /** 取出队首的完整语音段（含起止样本），并弹出队列 */
    fun nextSegment(): SpeechSegment {
        val seg = vad.front()
        vad.pop()
        return seg
    }

    fun reset() = vad.reset()

    override fun close() = vad.release()

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 512  // 32ms @ 16kHz
    }
}
