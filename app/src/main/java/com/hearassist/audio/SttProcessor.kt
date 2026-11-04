package com.hearassist.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Whisper tiny（多语言版，int8 量化）离线语音识别封装。
 * 完全在手机本地推理，不联网。VAD 切出的每一段语音在这里转成文字。
 */
class SttProcessor(assets: AssetManager) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "models/whisper/tiny-encoder.int8.onnx",
                    decoder = "models/whisper/tiny-decoder.int8.onnx",
                    language = "zh",
                    task = "transcribe",
                ),
                tokens = "models/whisper/tiny-tokens.txt",
                numThreads = 2,  // 荣耀 X50 八核，用 2 线程平衡速度与发热
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /** 同步识别一段语音，返回文本。调用方需自行排队避免并发。 */
    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, VadProcessor.SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
