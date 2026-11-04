package com.hearassist.audio

import android.content.res.AssetManager
import android.graphics.Color
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager

/**
 * 声纹提取与比对封装。
 * 模型：3D-Speaker CAM++ 中文（28.3MB），sherpa-onnx 内置，单文件直接可用。
 *
 * 用法：
 *   - extract(samples): 从一段语音提取声纹特征（太短返回 null）
 *   - search(embedding): 与声纹库比对，返回说话人名；无匹配返回 null
 *   - 声纹库通过 SpeakerEmbeddingManager 管理（增/删/查），
 *     持久化由 VoiceprintStore 负责（本类只持有运行时内存副本）
 */
class VoiceprintProcessor(
    assets: AssetManager,
    preloaded: List<SpeakerEntry> = emptyList(),
) : AutoCloseable {

    data class SpeakerEntry(
        val name: String,
        val color: Int,
        val embeddings: List<FloatArray>,
    )

    private val extractor = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(
            model = "models/voiceprint/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private val manager = SpeakerEmbeddingManager(extractor.dim())
    private val nameColor = HashMap<String, Int>()

    init {
        preloaded.forEach { entry ->
            manager.add(entry.name, entry.embeddings.toTypedArray())
            nameColor[entry.name] = entry.color
        }
    }

    /** 从一段语音提取声纹特征；音频太短（<1.5s）返回 null */
    fun extract(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SEGMENT_SAMPLES) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, VadProcessor.SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    /** 与声纹库比对，返回说话人名；无匹配返回 null */
    fun search(embedding: FloatArray): String? {
        val name = manager.search(embedding, THRESHOLD)
        return if (name.isEmpty()) null else name
    }

    fun colorOf(name: String): Int = nameColor[name] ?: Color.GRAY

    fun numSpeakers(): Int = manager.numSpeakers()

    fun allSpeakerNames(): Array<String> = manager.allSpeakerNames()

    fun addSpeaker(entry: SpeakerEntry): Boolean {
        manager.add(entry.name, entry.embeddings.toTypedArray())
        nameColor[entry.name] = entry.color
        return true
    }

    fun removeSpeaker(name: String) {
        manager.remove(name)
        nameColor.remove(name)
    }

    override fun close() {
        manager.release()
        extractor.release()
    }

    companion object {
        // 余弦相似度阈值：CAM++ 同人样本相似度通常 >0.6，异人 <0.4
        // 0.5 为官方示例值，实测后可调
        const val THRESHOLD = 0.5f
        const val MIN_SEGMENT_SAMPLES = 24000  // 1.5s @16kHz，短于此声纹不可靠
    }
}
