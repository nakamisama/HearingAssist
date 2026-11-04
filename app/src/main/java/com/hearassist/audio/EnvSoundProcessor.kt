package com.hearassist.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerAudioTaggingModelConfig

/**
 * 环境音识别封装（Zipformer 音频打标模型，int8）。
 * 输入一小段非语音音频，输出 Top-K 声音类别及概率（AudioSet 527 类）。
 * 仅在用户开启“环境音识别”开关时加载。
 */
class EnvSoundProcessor(assets: AssetManager) : AutoCloseable {

    data class EnvEvent(val name: String, val prob: Float)

    private val tagger = AudioTagging(
        assets,
        AudioTaggingConfig(
            model = AudioTaggingModelConfig(
                zipformer = OfflineZipformerAudioTaggingModelConfig(
                    model = "models/audio_tagging/model.int8.onnx",
                ),
                numThreads = 1,
                provider = "cpu",
            ),
            labels = "models/audio_tagging/class_labels_indices.csv",
            topK = 3,
        ),
    )

    /** 对一段音频做分类，返回值得提醒的环境音事件（无则 null） */
    fun detect(samples: FloatArray): EnvEvent? {
        if (samples.size < MIN_SAMPLES) return null
        val stream = tagger.createStream()
        return try {
            stream.acceptWaveform(samples, VadProcessor.SAMPLE_RATE)
            val events = tagger.compute(stream)
            events.firstOrNull { it.prob >= THRESHOLD && isAlarmWorthy(it.name) }
                ?.let { EnvEvent(it.name, it.prob) }
        } finally {
            stream.release()
        }
    }

    override fun close() = tagger.release()

    companion object {
        const val THRESHOLD = 0.5f
        const val MIN_SAMPLES = 16000  // 至少 1 秒音频才做分类

        // 人声类不作为“环境音”提醒（说话交给字幕功能）
        private val SKIP_KEYWORDS = listOf(
            "speech", "conversation", "narration", "singing", "choir",
            "silence", "music", "radio", "television",
        )

        fun isAlarmWorthy(label: String): Boolean {
            val lower = label.lowercase()
            return SKIP_KEYWORDS.none { it in lower }
        }

        /** AudioSet 英文标签 → 中文提示；未收录的回退英文 */
        fun zh(label: String): String = LABEL_ZH[label] ?: label

        private val LABEL_ZH = mapOf(
            "Doorbell" to "🔔 门铃",
            "Doorbell bing-bong" to "🔔 门铃叮咚",
            "Bell" to "🔔 铃声",
            "Chime" to "🔔 钟琴声",
            "Ding" to "🔔 叮声",
            "Beep, bleep" to "🔊 蜂鸣声",
            "Buzzer" to "🔊 蜂鸣器",
            "Knock" to "🚪 敲击声",
            "Fire alarm" to "🚨 火灾警报",
            "Smoke detector, smoke alarm" to "🚨 烟雾报警",
            "Alarm" to "🚨 警报",
            "Alarm clock" to "⏰ 闹钟",
            "Siren" to "🚨 警笛",
            "Civil defense siren" to "🚨 防空警报",
            "Police siren" to "🚨 警车警笛",
            "Emergency vehicle sirens (ambulance, fire truck, police car)" to "🚨 救护车/消防警笛",
            "Car alarm" to "🚨 汽车警报",
            "Baby cry, infant cry" to "👶 婴儿哭声",
            "Crying, sobbing" to "😢 哭声",
            "Screaming" to "😱 尖叫声",
            "Shout" to "📢 呼喊",
            "Yell" to "📢 大喊",
            "Laughter" to "😄 笑声",
            "Dog bark" to "🐶 狗叫",
            "Dog" to "🐶 狗叫",
            "Bark" to "🐶 犬吠",
            "Howl" to "🐶 嚎叫",
            "Meow" to "🐱 猫叫",
            "Cat" to "🐱 猫叫",
            "Purr" to "🐱 猫呼噜",
            "Bird vocalization, bird call, bird song" to "🐦 鸟鸣",
            "Bird" to "🐦 鸟叫",
            "Rooster, cock" to "🐓 鸡叫",
            "Duck" to "🦆 鸭叫",
            "Telephone bell ringing" to "📞 电话铃响",
            "Ringtone" to "📱 手机铃声",
            "Vehicle horn, car horn, truck horn" to "📢 汽车喇叭",
            "Honking" to "📢 鸣笛",
            "Car" to "🚗 汽车驶过",
            "Truck" to "🚚 卡车",
            "Motorcycle" to "🏍️ 摩托车",
            "Glass shatter" to "💥 玻璃破碎",
            "Breaking" to "💥 破碎声",
            "Crack" to "💥 开裂声",
            "Explosion" to "💥 爆炸",
            "Gunshot, gunfire" to "💥 枪声",
            "Bang" to "💥 砰响",
            "Slam, bang" to "💥 摔门声",
            "Thump, thud" to "💥 重击声",
            "Footsteps" to "👣 脚步声",
            "Water" to "💧 水声",
            "Water tap, faucet" to "💧 水龙头",
            "Boiling" to "💧 烧水声",
            "Whistle, steaming kettle" to "🫖 水壶哨响",
            "Drip" to "💧 滴水",
            "Rain" to "🌧️ 下雨",
            "Thunder" to "⛈️ 雷",
            "Thunderstorm" to "⛈️ 雷雨",
            "Wind" to "🌬️ 风",
            "Applause" to "👏 掌声",
            "Clapping" to "👏 拍手声",
            "Microwave oven" to "📦 微波炉",
            "Blender" to "📦 搅拌机",
            "Vacuum cleaner" to "📦 吸尘器",
            "Washing machine" to "📦 洗衣机",
            "Mechanical fan" to "📦 电风扇",
        )
    }
}
