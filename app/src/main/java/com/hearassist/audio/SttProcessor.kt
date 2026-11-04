package com.hearassist.audio

import android.content.res.AssetManager
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * SenseVoice-small（int8 量化）离线语音识别封装。
 * 专为中文优化，准确率远高于 Whisper tiny，自带标点与数字归一化（"两点三十" → "2:30"），
 * 输出再经 OpenCC 统一转为简体中文。完全在手机本地推理，不联网。
 */
class SttProcessor(assets: AssetManager) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "models/sensevoice/model.int8.onnx",
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = "models/sensevoice/tokens.txt",
                numThreads = 2,  // 荣耀 X50 八核，用 2 线程平衡速度与发热
                provider = "cpu",
            ),
        ),
    )

    /** 同步识别一段语音，返回简体中文文本。调用方需自行排队避免并发。 */
    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, VadProcessor.SAMPLE_RATE)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text.trim()
            if (raw.isEmpty()) "" else ZhConverterUtil.toSimple(raw)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}
