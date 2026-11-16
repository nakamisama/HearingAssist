package com.hearassist.data

/** 前后台与用户操作的许可。旧启动、重启或录入结束不能复活已经停止的会话。 */
class SessionPolicy {

    data class VisibilityTicket(val epoch: Long)
    data class CaptionTicket(val epoch: Long, val generation: Long)
    data class EnrollmentTicket(val epoch: Long, val generation: Long)

    private var foreground = false
    private var epoch = 0L
    private var generation = 0L
    private var captionRequested = false
    private var enrollment: EnrollmentTicket? = null

    @Synchronized
    fun enterForeground() {
        foreground = true
    }

    @Synchronized
    fun enterBackground() {
        if (!foreground) return
        foreground = false
        epoch++
        generation++
        captionRequested = false
        enrollment = null
    }

    @Synchronized
    fun isForeground(): Boolean = foreground

    @Synchronized
    fun visibilityTicket(): VisibilityTicket = VisibilityTicket(epoch)

    @Synchronized
    fun isVisible(ticket: VisibilityTicket): Boolean = foreground && ticket.epoch == epoch

    @Synchronized
    fun wantsCaption(): Boolean = captionRequested

    @Synchronized
    fun startCaption(): CaptionTicket? {
        if (!foreground || enrollment != null) return null
        captionRequested = true
        return CaptionTicket(epoch, ++generation)
    }

    @Synchronized
    fun restartCaption(): CaptionTicket? {
        if (!foreground || !captionRequested || enrollment != null) return null
        return CaptionTicket(epoch, ++generation)
    }

    @Synchronized
    fun stopCaption() {
        captionRequested = false
        generation++
    }

    @Synchronized
    fun allowsCaption(ticket: CaptionTicket): Boolean =
        foreground && captionRequested && enrollment == null &&
            ticket.epoch == epoch && ticket.generation == generation

    @Synchronized
    fun endCaption(ticket: CaptionTicket): Boolean {
        if (ticket.epoch != epoch || ticket.generation != generation || enrollment != null) return false
        captionRequested = false
        generation++
        return true
    }

    @Synchronized
    fun beginEnrollment(): EnrollmentTicket? {
        if (!foreground || enrollment != null) return null
        return EnrollmentTicket(epoch, ++generation).also { enrollment = it }
    }

    @Synchronized
    fun allowsEnrollment(ticket: EnrollmentTicket): Boolean =
        foreground && enrollment == ticket && ticket.epoch == epoch

    @Synchronized
    fun finishEnrollment(ticket: EnrollmentTicket, resumeCaption: Boolean): CaptionTicket? {
        if (enrollment != ticket) return null
        enrollment = null
        if (!resumeCaption) captionRequested = false
        if (!foreground || ticket.epoch != epoch || !captionRequested) return null
        return CaptionTicket(epoch, ++generation)
    }
}
