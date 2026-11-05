package com.hearassist.audio

import android.content.res.AssetManager
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

/**
 * 流式（实时）识别封装：streaming-zipformer-small-ctc-zh int8（约21MB）。
 * 边喂音频边解码，逐字出字；检测到端点（一句话结束）时返回整句 finalized 文本。
 * 与声纹无关——声纹由 VAD 段独立提取，两种引擎模式都可用。
 */
class StreamingProcessor(assets: AssetManager) : AutoCloseable {

    private val recognizer = OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                    model = "models/streaming/model.int8.onnx",
                ),
                tokens = "models/streaming/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    private val stream = recognizer.createStream()

    /** 喂一帧音频并解码。若到达端点（句末）返回该句简体文本，否则返回 null。 */
    fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, VadProcessor.SAMPLE_RATE)
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        if (recognizer.isEndpoint(stream)) {
            val raw = recognizer.getResult(stream).text.trim()
            recognizer.reset(stream)
            return if (raw.isEmpty()) null else ZhConverterUtil.toSimple(raw)
        }
        return null
    }

    /** 当前未说完的半句（用于实时逐字显示），简体。 */
    fun partial(): String {
        val raw = recognizer.getResult(stream).text.trim()
        return if (raw.isEmpty()) "" else ZhConverterUtil.toSimple(raw)
    }

    /** VAD 切段时重置流式状态，使逐字预览与 VAD 分段对齐，避免定稿后旧句预览重显。 */
    fun reset() {
        recognizer.reset(stream)
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }
}
