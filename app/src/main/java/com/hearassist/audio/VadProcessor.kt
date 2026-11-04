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
                // 阈值 0.35（默认 0.5）：小声说话、离麦克风稍远也能被检测到
                threshold = 0.35f,
                // 停顿 0.4 秒认为一句话结束：容忍长句中的自然停顿，避免句子被切碎
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.2f,
                // 单段最长 8 秒：平衡识别完整度与字幕响应速度。
                // 连续说话无停顿时最多等 8 秒出字幕（15s 会导致字幕延迟过久）
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
