package com.hearassist.audio

import android.content.res.AssetManager
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * SenseVoice-small（int8 量化）离线语音识别封装。
 * 支持普通话模式与自动识别模式；中文模型训练数据含方言混合，
 * 对西南官话（川渝话）等接近普通话的方言有一定识别能力。
 * 输出再经 OpenCC 统一为简体中文。完全在手机本地推理，不联网。
 */
class SttProcessor(assets: AssetManager, langMode: Int = LANG_AUTO) : AutoCloseable {

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "models/sensevoice/model.int8.onnx",
                    language = languageCode(langMode),
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

    companion object {
        const val LANG_AUTO = 0
        const val LANG_ZH = 1

        fun languageCode(mode: Int): String = when (mode) {
            LANG_ZH -> "zh"
            else -> ""  // 空 = 模型自动识别语种
        }
    }
}
