package com.hearassist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hearassist.MainActivity
import com.hearassist.R
import com.hearassist.audio.EnvSoundProcessor
import com.hearassist.audio.StreamingProcessor
import com.hearassist.audio.SttProcessor
import com.hearassist.audio.VadProcessor
import com.hearassist.audio.VoiceprintProcessor
import com.hearassist.data.CaptionBus
import com.hearassist.data.EngineStatus
import com.hearassist.data.Settings
import com.hearassist.data.VoiceprintStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 前台服务：录音 → VAD → (语音) SenseVoice 字幕 / (环境音开关开启时) 声音分类。
 * 使用 microphone 类型前台服务，保证后台持续收音。
 */
class CaptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioRecord: AudioRecord? = null
    private var vad: VadProcessor? = null
    private var stt: SttProcessor? = null
    private var streaming: StreamingProcessor? = null
    private var env: EnvSoundProcessor? = null
    private var voiceprint: VoiceprintProcessor? = null
    private var asrMode = Settings.ASR_ACCURATE
    private val sttMutex = Mutex()
    private val voiceprintMutex = Mutex()
    private var agcEffect: AutomaticGainControl? = null
    // 防止服务未销毁时重复收到启动命令导致启动多套录音引擎
    private var engineStarted = false

    // 环境音环形缓冲（最近 2 秒音频）
    private val envBuffer = FloatArray(ENV_BUFFER_SIZE)
    private var envWritePos = 0
    private var envFilled = 0
    private var envWindowCount = 0
    private val lastAlertAt = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CaptionBus.setRunning(true)
        // 仅首次启动引擎，避免重复 start 命令导致多套录音循环/模型
        if (!engineStarted) {
            engineStarted = true
            CaptionBus.setStatus(EngineStatus.STARTING)
            scope.launch { runEngine() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        CaptionBus.setRunning(false)
        CaptionBus.setStatus(EngineStatus.IDLE)
        engineStarted = false
        scope.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { vad?.close() } catch (_: Exception) {}
        try { stt?.close() } catch (_: Exception) {}
        try { streaming?.close() } catch (_: Exception) {}
        try { env?.close() } catch (_: Exception) {}
        try { voiceprint?.close() } catch (_: Exception) {}
        try { agcEffect?.release() } catch (_: Exception) {}
        agcEffect = null
        vad = null; stt = null; streaming = null; env = null; voiceprint = null
        CaptionBus.setPartial("")
        super.onDestroy()
    }

    // ---------- 引擎 ----------

    private suspend fun runEngine() = withContext(Dispatchers.Default) {
        try {
            asrMode = Settings.getAsrMode(this@CaptionService)
            vad = VadProcessor(assets)
            // 实时模式额外加载流式引擎做逐字预览；最终定稿两种模式都用 SenseVoice（准确+标点）
            if (asrMode == Settings.ASR_REALTIME) {
                streaming = StreamingProcessor(assets)
            }
            stt = SttProcessor(assets, Settings.getLangMode(this@CaptionService))
            // 环境音模型仅在开关开启时加载，节省 ~30MB 内存与启动时间
            if (CaptionBus.envEnabled.value) env = EnvSoundProcessor(assets)
            // 声纹模型仅在开关开启且已录入声纹时加载
            val vpEntries = VoiceprintStore.load(this@CaptionService)
            if (Settings.isVoiceprintEnabled(this@CaptionService) && vpEntries.isNotEmpty()) {
                voiceprint = VoiceprintProcessor(assets, vpEntries)
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型初始化失败", e)
            stopSelf()
            return@withContext
        }
        // 模型加载完成，自动开始收听
        CaptionBus.setStatus(EngineStatus.LISTENING)
        audioLoop()
    }

    private suspend fun audioLoop() = withContext(Dispatchers.IO) {
        val windowSize = vad?.windowSize ?: return@withContext
        val minBuf = AudioRecord.getMinBufferSize(
            VadProcessor.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VadProcessor.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 16384),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            rec.release()
            stopSelf()
            return@withContext
        }
        audioRecord = rec
        rec.startRecording()
        enableHardwareAgc(rec)

        val shorts = ShortArray(windowSize)
        val floats = FloatArray(windowSize)

        try {
            while (isActive && CaptionBus.running.value) {
                val n = rec.read(shorts, 0, shorts.size)  // 阻塞式读取，32ms 一帧
                if (n <= 0) continue
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                applyAgc(floats)  // 远场/小声说话自动放大信号
                val chunk = if (n == floats.size) floats else floats.copyOf(n)

                val v = vad ?: break

                // 1) VAD 调度：有人说话 → 切段；否则 → 环境音缓冲
                v.accept(chunk)

                // 2) VAD 切出的完整语音段 → SenseVoice 定稿（准确+标点+声纹），两种模式相同
                while (v.hasSegments()) {
                    val seg = v.nextSegment()
                    if (asrMode == Settings.ASR_REALTIME) CaptionBus.setPartial("")
                    launchStt(seg.samples, seg.samples.size)
                }

                // 2.5) 实时模式：流式逐字预览（边说边蹦字），定稿后由 SenseVoice 替换为准确文本
                if (asrMode == Settings.ASR_REALTIME) {
                    val s = streaming
                    if (s != null) {
                        s.accept(chunk)
                        CaptionBus.setPartial(s.partial())
                    }
                }

                // 3) 环境音识别（开关开启时）
                if (CaptionBus.envEnabled.value && env != null) {
                    appendEnv(chunk)
                    envWindowCount++
                    if (envWindowCount >= ENV_EVERY_WINDOWS) {
                        envWindowCount = 0
                        classifyEnvironment()
                    }
                } else {
                    envFilled = 0; envWritePos = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频循环异常", e)
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun launchStt(samples: FloatArray, durationSamples: Int) {
        scope.launch {
            CaptionBus.setStatus(EngineStatus.RECOGNIZING)
            try {
                val text = sttMutex.withLock { stt?.recognize(samples) ?: "" }
                if (text.isNotBlank()) {
                    var speaker: String? = null
                    var color = 0
                    val vp = voiceprint
                    // 声纹比对：仅对足够长的语音段做（短句声纹不可靠）
                    if (vp != null && durationSamples >= VoiceprintProcessor.MIN_SEGMENT_SAMPLES) {
                        val emb = voiceprintMutex.withLock { vp.extract(samples) }
                        if (emb != null) {
                            speaker = vp.search(emb)
                            if (speaker != null) color = vp.colorOf(speaker)
                        }
                    }
                    CaptionBus.addSubtitle(text, speaker, color)
                }
            } catch (e: Exception) {
                Log.e(TAG, "识别失败", e)
            } finally {
                CaptionBus.setStatus(EngineStatus.LISTENING)
            }
        }
    }

    // ---------- 增益控制 ----------

    /** 尝试开启系统级硬件 AGC（设备不支持则静默忽略） */
    private fun enableHardwareAgc(rec: AudioRecord) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                agcEffect = AutomaticGainControl.create(rec.audioSessionId).apply { setEnabled(true) }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 软件自动增益：目标把语音 RMS 归一到 0.06，最大放大 6 倍。
     * 增益带平滑（时间常数约 0.2s），防止忽大忽小；硬限幅防削波。
     */
    private var agcGain = 1.0f

    private fun applyAgc(samples: FloatArray) {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size).toFloat()
        val desired = if (rms > 1e-4f) {
            (AGC_TARGET_RMS / rms).coerceIn(1.0f, AGC_MAX_GAIN)
        } else {
            agcGain
        }
        agcGain = agcGain * 0.85f + desired * 0.15f
        for (i in samples.indices) {
            samples[i] = (samples[i] * agcGain).coerceIn(-0.98f, 0.98f)
        }
    }

    // ---------- 环境音 ----------

    private fun appendEnv(chunk: FloatArray) {
        for (s in chunk) {
            envBuffer[envWritePos] = s
            envWritePos = (envWritePos + 1) % ENV_BUFFER_SIZE
            if (envFilled < ENV_BUFFER_SIZE) envFilled++
        }
    }

    private fun snapshotEnv(): FloatArray? {
        if (envFilled < EnvSoundProcessor.MIN_SAMPLES) return null
        return if (envFilled < ENV_BUFFER_SIZE) {
            envBuffer.copyOf(envFilled)
        } else {
            FloatArray(ENV_BUFFER_SIZE).also { out ->
                // 环形缓冲按时间顺序展开
                val head = envWritePos
                envBuffer.copyInto(out, 0, head, ENV_BUFFER_SIZE)
                envBuffer.copyInto(out, ENV_BUFFER_SIZE - head, 0, head)
            }
        }
    }

    private fun classifyEnvironment() {
        val v = vad ?: return
        // 正在说话时不做环境音判断（那段音频属于字幕管线）
        if (v.isSpeechDetected()) return
        val snapshot = snapshotEnv() ?: return
        if (rms(snapshot) < NOISE_FLOOR) return  // 安静环境，跳过

        val event = env?.detect(snapshot) ?: return
        val now = System.currentTimeMillis()
        val last = lastAlertAt[event.name] ?: 0L
        if (now - last < ALERT_COOLDOWN_MS) return  // 同类声音 1 分钟内不重复提醒

        lastAlertAt[event.name] = now
        CaptionBus.pushEnvEvent(EnvSoundProcessor.zh(event.name))
        vibrate()
        clearEnvBuffer()
    }

    private fun clearEnvBuffer() { envFilled = 0; envWritePos = 0 }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    // ---------- 系统交互 ----------

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
    }

    private fun startInForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "CaptionService"
        private const val CHANNEL_ID = "caption_service"
        private const val NOTIF_ID = 1

        private const val ENV_BUFFER_SIZE = 32000        // 2 秒 @16kHz
        private const val ENV_EVERY_WINDOWS = 64         // 每 ~2 秒分类一次
        private const val NOISE_FLOOR = 0.005f
        private const val ALERT_COOLDOWN_MS = 60_000L

        private const val AGC_TARGET_RMS = 0.06f
        private const val AGC_MAX_GAIN = 6.0f
    }
}
