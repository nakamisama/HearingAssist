package com.hearassist.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.hearassist.service.CaptionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 统一处理启动、停止及前台内恢复；Application 的后台事件撤销一切旧许可。 */
object AppSessionController {
    private val policy = SessionPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private val listeners = LinkedHashSet<() -> Unit>()
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground
    private var engineTicket: SessionPolicy.CaptionTicket? = null
    private var stopEngine: (() -> Unit)? = null

    fun initialize(context: Context) {
        app = context.applicationContext
    }

    fun enterForeground() {
        policy.enterForeground()
        _foreground.value = true
    }

    fun enterBackground() {
        if (!policy.isForeground()) return
        policy.enterBackground()
        _foreground.value = false
        stopCurrentEngine()
        CaptionBus.stopUi()
        app.stopService(Intent(app, CaptionService::class.java))
        listeners.toList().forEach { it() }
    }

    fun isForeground(): Boolean = policy.isForeground()
    fun visibilityTicket(): SessionPolicy.VisibilityTicket = policy.visibilityTicket()
    fun isVisible(ticket: SessionPolicy.VisibilityTicket): Boolean = policy.isVisible(ticket)
    fun wantsCaption(): Boolean = policy.wantsCaption()
    fun allowsCaption(ticket: SessionPolicy.CaptionTicket): Boolean = policy.allowsCaption(ticket)
    fun allowsEnrollment(ticket: SessionPolicy.EnrollmentTicket): Boolean = policy.allowsEnrollment(ticket)

    fun addBackgroundListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeBackgroundListener(listener: () -> Unit) { listeners.remove(listener) }

    fun startCaption() {
        val ticket = policy.startCaption() ?: return
        startWithTicket(ticket)
    }

    fun restartCaption() {
        val ticket = policy.restartCaption() ?: return
        stopCurrentEngine()
        startWithTicket(ticket)
    }

    fun stopCaption() {
        policy.stopCaption()
        stopCurrentEngine()
        CaptionBus.stopUi()
        app.stopService(Intent(app, CaptionService::class.java))
    }

    fun beginEnrollment(): SessionPolicy.EnrollmentTicket? {
        val ticket = policy.beginEnrollment() ?: return null
        stopCurrentEngine()
        CaptionBus.stopUi()
        app.stopService(Intent(app, CaptionService::class.java))
        return ticket
    }

    fun finishEnrollment(ticket: SessionPolicy.EnrollmentTicket, resumeCaption: Boolean) {
        val resume = policy.finishEnrollment(ticket, resumeCaption) ?: return
        startWithTicket(resume)
    }

    fun attachEngine(ticket: SessionPolicy.CaptionTicket, requestStop: () -> Unit) {
        engineTicket = ticket
        stopEngine = requestStop
    }

    fun detachEngine(ticket: SessionPolicy.CaptionTicket) {
        if (engineTicket == ticket) {
            engineTicket = null
            stopEngine = null
        }
    }

    fun serviceEnded(ticket: SessionPolicy.CaptionTicket, error: String? = null) {
        detachEngine(ticket)
        if (policy.endCaption(ticket)) {
            CaptionBus.stopUi()
            if (error != null) CaptionBus.setError(error)
        }
    }

    /** 所有 UI/字幕发布都在主线程重检许可，旧任务不能在停止后覆盖新状态。 */
    fun publish(ticket: SessionPolicy.CaptionTicket, update: () -> Unit) {
        scope.launch {
            if (policy.allowsCaption(ticket)) update()
        }
    }

    private fun stopCurrentEngine() {
        stopEngine?.invoke()
    }

    private fun startWithTicket(ticket: SessionPolicy.CaptionTicket) {
        if (!policy.allowsCaption(ticket)) return
        CaptionBus.setError(null)
        CaptionBus.setStartMsg("")
        CaptionBus.setPartial("")
        CaptionBus.setRunning(true)
        CaptionBus.setStatus(EngineStatus.STARTING)
        try {
            ContextCompat.startForegroundService(app, Intent(app, CaptionService::class.java).apply {
                putExtra(EXTRA_EPOCH, ticket.epoch)
                putExtra(EXTRA_GENERATION, ticket.generation)
            })
        } catch (e: RuntimeException) {
            Log.e("AppSession", "启动收听失败", e)
            policy.stopCaption()
            CaptionBus.stopUi()
            CaptionBus.setError("无法启动麦克风，请回到应用后重试")
        }
    }

    fun ticketFrom(intent: Intent?): SessionPolicy.CaptionTicket? {
        if (intent == null || !intent.hasExtra(EXTRA_EPOCH) || !intent.hasExtra(EXTRA_GENERATION)) return null
        return SessionPolicy.CaptionTicket(
            intent.getLongExtra(EXTRA_EPOCH, -1), intent.getLongExtra(EXTRA_GENERATION, -1),
        ).takeIf { policy.allowsCaption(it) }
    }

    fun ensureCaptionAllowed(ticket: SessionPolicy.CaptionTicket) {
        if (!policy.allowsCaption(ticket)) throw CancellationException("收听会话已停止")
    }

    private const val EXTRA_EPOCH = "caption_epoch"
    private const val EXTRA_GENERATION = "caption_generation"
}
