package com.hearassist.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hearassist.MainActivity
import com.hearassist.R
import com.hearassist.audio.AudioSessions
import com.hearassist.audio.EnvSoundProcessor
import com.hearassist.audio.MicrophoneCapture
import com.hearassist.audio.StreamingProcessor
import com.hearassist.audio.SttProcessor
import com.hearassist.audio.VadProcessor
import com.hearassist.audio.VoiceprintProcessor
import com.hearassist.data.AppSessionController
import com.hearassist.data.CaptionBus
import com.hearassist.data.CaptionId
import com.hearassist.data.CaptionSession
import com.hearassist.data.CellularConsent
import com.hearassist.data.EngineStatus
import com.hearassist.data.ModelManager
import com.hearassist.data.SessionPolicy
import com.hearassist.data.Settings
import com.hearassist.data.VoiceprintStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** 只在 App 可见且用户请求收听时工作，停止时等待在途推理结束后释放资源。 */
class CaptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = AtomicReference<MicrophoneCapture?>(null)
    private var engineJob: Job? = null
    private var engineTicket: SessionPolicy.CaptionTicket? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = AppSessionController.ticketFrom(intent)
        if (ticket == null) {
            if (engineTicket?.let(AppSessionController::allowsCaption) != true) {
                requestStop()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (ticket == engineTicket && engineJob?.isActive == true) return START_NOT_STICKY
        requestStop()
        engineTicket = ticket
        try {
            startInForeground()
        } catch (e: RuntimeException) {
            Log.e(TAG, "无法创建麦克风前台服务", e)
            AppSessionController.serviceEnded(ticket, "启动受系统限制，请回到应用后点击开始")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        engineJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.Default) {
                    AudioSessions.gate.run(resources = { EngineResources() }) { owned ->
                        runEngine(ticket, owned)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "收听失败", e)
                AppSessionController.publish(ticket) {
                    AppSessionController.stopCaption()
                    CaptionBus.setError(e.message ?: "无法启动收听，请重试")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    AppSessionController.serviceEnded(ticket)
                    if (!destroyed && engineTicket == ticket) {
                        // 正常停机或异常退出时不留下一条假“监听中”通知。
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        AppSessionController.attachEngine(ticket) { requestStop() }
        engineJob?.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        requestStop()
        engineTicket?.let { AppSessionController.serviceEnded(it) }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 模型由 EngineResources 的所有者释放，不在主线程与推理抢着 close。
        super.onDestroy()
    }

    private fun requestStop() {
        engineJob?.cancel()
        capture.get()?.requestStop()
    }

    private suspend fun checkSession(ticket: SessionPolicy.CaptionTicket) {
        coroutineContext.ensureActive()
        AppSessionController.ensureCaptionAllowed(ticket)
    }

    private suspend fun runEngine(ticket: SessionPolicy.CaptionTicket, owned: EngineResources) {
        checkSession(ticket)
        val mode = Settings.getAsrMode(this)
        val entries = VoiceprintStore.load(this)
        val useVoiceprint = Settings.isVoiceprintEnabled(this) && entries.isNotEmpty()
        val useEnvironment = CaptionBus.envEnabled.value
        val needed = mutableListOf("vad")
        if (mode == Settings.ASR_REALTIME) {
            needed.add("streaming")
            if (ModelManager.isReady(this, "sensevoice")) needed.add("sensevoice")
        } else {
            needed.add("sensevoice")
        }
        if (useEnvironment) needed.add("audio_tagging")
        if (useVoiceprint) needed.add("voiceprint")
        val ready = ModelManager.ensureModels(this, needed, { _, message ->
            AppSessionController.publish(ticket) { CaptionBus.setStartMsg(message) }
        }) {
            CellularConsent.request { AppSessionController.allowsCaption(ticket) }
        }
        checkSession(ticket)
        check(ready) { "模型未准备好，下载已取消或失败" }
        AppSessionController.publish(ticket) { CaptionBus.setStartMsg("正在加载模型，请稍后…") }
        owned.vad = VadProcessor(this)
        checkSession(ticket)
        if (mode == Settings.ASR_REALTIME) owned.streaming = StreamingProcessor(this)
        checkSession(ticket)
        if (mode == Settings.ASR_ACCURATE || ModelManager.isReady(this, "sensevoice")) {
            owned.stt = SttProcessor(this, Settings.getLangMode(this))
        }
        checkSession(ticket)
        if (useEnvironment) owned.environment = EnvSoundProcessor(this)
        checkSession(ticket)
        if (useVoiceprint) owned.voiceprint = VoiceprintProcessor(this, entries)
        checkSession(ticket)
        audioLoop(ticket, owned)
    }

    private data class Work(val id: CaptionId, val samples: FloatArray, val fallback: String?)

    private suspend fun audioLoop(ticket: SessionPolicy.CaptionTicket, owned: EngineResources) = coroutineScope {
        val microphone = MicrophoneCapture(hardwareAgc = true)
        owned.microphone = microphone
        capture.set(microphone)
        checkSession(ticket)
        check(ContextCompat.checkSelfPermission(this@CaptionService, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "麦克风权限已撤销" }
        microphone.start()
        checkSession(ticket)
        AppSessionController.publish(ticket) {
            CaptionBus.setStartMsg("")
            CaptionBus.setStatus(EngineStatus.LISTENING)
        }
        val vad = requireNotNull(owned.vad)
        val captionSession = CaptionSession(ticket.epoch, ticket.generation)
        var segmentNumber = 0L
        var currentId = CaptionId(captionSession, segmentNumber)
        val queue = Channel<Work>(3)
        val worker = launch(Dispatchers.Default) {
            for (work in queue) {
                checkSession(ticket)
                AppSessionController.publish(ticket) { CaptionBus.setStatus(EngineStatus.RECOGNIZING) }
                try {
                    val text = owned.stt?.recognize(work.samples)?.takeIf { it.isNotBlank() }
                        ?: work.fallback.orEmpty()
                    checkSession(ticket)
                    var name: String? = null
                    var color = 0
                    if (text.isNotBlank()) {
                        try {
                            val vp = owned.voiceprint
                            val embedding = vp?.extract(work.samples)
                            checkSession(ticket)
                            name = if (embedding != null) vp?.search(embedding) else null
                            color = if (name != null) vp?.colorOf(name) ?: 0 else 0
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "声纹匹配失败，保留字幕", e)
                        }
                    }
                    AppSessionController.publish(ticket) { CaptionBus.complete(work.id, text, name, color) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "语音段处理失败", e)
                    AppSessionController.publish(ticket) {
                        CaptionBus.complete(work.id, work.fallback.orEmpty())
                        CaptionBus.setError("这段语音识别失败，已保留可用预览")
                    }
                } finally {
                    AppSessionController.publish(ticket) { CaptionBus.setStatus(EngineStatus.LISTENING) }
                }
            }
        }
        var gain = 1f
        val environmentBuffer = SampleBuffer(32000)
        val speakerBuffer = SampleBuffer(8 * VadProcessor.SAMPLE_RATE)
        val alertAt = HashMap<String, Long>()
        var environmentFrames = 0

        fun enqueue(work: Work) {
            AppSessionController.publish(ticket) { CaptionBus.pending(work.id, work.fallback.orEmpty()) }
            if (queue.trySend(work).isSuccess) return
            // 录音不能等待慢推理；保留已经显示的预览，而不是因跳过积压清空一行。
            val skipped = queue.tryReceive().getOrNull()
            if (skipped != null) {
                Log.w(TAG, "识别队列积压，跳过最旧的待处理段")
                AppSessionController.publish(ticket) {
                    CaptionBus.complete(skipped.id, skipped.fallback.orEmpty())
                    CaptionBus.setError("识别处理较慢，已保留积压段的可用预览")
                }
            }
            if (!queue.trySend(work).isSuccess) {
                AppSessionController.publish(ticket) { CaptionBus.complete(work.id, work.fallback.orEmpty()) }
            }
        }

        try {
            while (isActive) {
                checkSession(ticket)
                val samples = microphone.read()
                if (samples.isEmpty()) continue
                checkSession(ticket)
                val level = rms(samples)
                val desired = if (level > 1e-4f) (0.06f / level).coerceIn(1f, 6f) else gain
                gain = gain * 0.85f + desired * 0.15f
                for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-0.98f, 0.98f)
                val stream = owned.streaming
                val finalText = stream?.accept(samples)
                checkSession(ticket)
                vad.accept(samples)
                if (owned.stt == null && owned.voiceprint != null && vad.isSpeechDetected()) {
                    speakerBuffer.append(samples)
                }
                while (vad.hasSegments()) {
                    checkSession(ticket)
                    val segment = vad.nextSegment()
                    // 小模型的端点比 VAD 慢，不能提前 reset，否则永远丢失正式字幕。
                    if (owned.stt != null) {
                        val fallback = finalText ?: stream?.partial()
                        val id = currentId
                        enqueue(Work(id, segment.samples, fallback))
                        stream?.reset()
                        currentId = CaptionId(captionSession, ++segmentNumber)
                    }
                }
                if (stream != null) {
                    if (owned.stt == null && stream.endedUtterance) {
                        val id = currentId
                        if (owned.voiceprint != null && !finalText.isNullOrBlank()) {
                            enqueue(Work(id, speakerBuffer.snapshot(), finalText))
                        } else {
                            AppSessionController.publish(ticket) { CaptionBus.complete(id, finalText.orEmpty()) }
                        }
                        currentId = CaptionId(captionSession, ++segmentNumber)
                        speakerBuffer.clear()
                    }
                    val partial = stream.partial()
                    val previewId = currentId
                    // 空的reset结果不清屏；上一段保持原位，直到它定稿或下一段有实际文字。
                    if (partial.isNotBlank()) {
                        AppSessionController.publish(ticket) { CaptionBus.preview(previewId, partial) }
                    }
                }
                if (owned.environment != null) {
                    if (vad.isSpeechDetected()) {
                        environmentBuffer.clear()
                        environmentFrames = 0
                    } else {
                        environmentBuffer.append(samples)
                        if (++environmentFrames >= 64) {
                            environmentFrames = 0
                            val snapshot = environmentBuffer.snapshot()
                            if (snapshot.isNotEmpty() && rms(snapshot) >= 0.005f) {
                                val event = owned.environment?.detect(snapshot)
                                checkSession(ticket)
                                if (event != null) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - (alertAt[event.name] ?: -60000L) >= 60000L) {
                                        alertAt[event.name] = now
                                        AppSessionController.publish(ticket) {
                                            CaptionBus.pushEnvEvent(EnvSoundProcessor.zh(event.name))
                                            vibrate()
                                        }
                                        environmentBuffer.clear()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            microphone.requestStop()
            queue.cancel()
            withContext(NonCancellable) { worker.cancelAndJoin() }
            capture.compareAndSet(microphone, null)
        }
    }

    private class EngineResources : AutoCloseable {
        var vad: VadProcessor? = null
        var stt: SttProcessor? = null
        var streaming: StreamingProcessor? = null
        var environment: EnvSoundProcessor? = null
        var voiceprint: VoiceprintProcessor? = null
        var microphone: MicrophoneCapture? = null

        override fun close() {
            listOf(microphone, voiceprint, environment, stt, streaming, vad).forEach {
                try { it?.close() } catch (e: Exception) { Log.w(TAG, "释放收听资源失败", e) }
            }
            microphone = null
            voiceprint = null
            environment = null
            stt = null
            streaming = null
            vad = null
        }
    }

    private class SampleBuffer(private val capacity: Int) {
        private val samples = FloatArray(capacity)
        private var cursor = 0
        private var count = 0
        fun append(chunk: FloatArray) {
            for (sample in chunk) {
                samples[cursor] = sample
                cursor = (cursor + 1) % capacity
                count = (count + 1).coerceAtMost(capacity)
            }
        }
        fun snapshot(): FloatArray = FloatArray(count) {
            samples[(cursor - count + capacity + it) % capacity]
        }
        fun clear() { cursor = 0; count = 0 }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return sqrt(sum / samples.size).toFloat()
    }

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
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pending)
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
    }
}
