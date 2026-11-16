package com.hearassist.data

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.hearassist.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 由可见 Activity 托管流量确认；服务不能用自身 Context 创建普通弹窗。 */
object CellularConsent {
    class Request(val visible: () -> Boolean) {
        val answer = CompletableDeferred<Boolean>()
    }

    private val requests = Channel<Request>(Channel.RENDEZVOUS)
    val events = requests.receiveAsFlow()

    suspend fun request(visible: () -> Boolean): Boolean {
        if (!visible()) return false
        val request = Request(visible)
        try {
            requests.send(request)
            return request.answer.await() && visible()
        } finally {
            request.answer.cancel()
        }
    }

    suspend fun handle(activity: Activity, request: Request) {
        try {
            val allowed = confirm(activity, request.visible) { request.answer.isActive }
            request.answer.complete(allowed)
        } finally {
            request.answer.complete(false)
        }
    }

    suspend fun confirm(
        activity: Activity,
        visible: () -> Boolean,
        requestActive: () -> Boolean = { true },
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val alive = { visible() && requestActive() && !activity.isFinishing && !activity.isDestroyed }
        if (!alive()) return@withContext false
        // ModelManager 只在当前连接需要许可时调用；即使是计费 WiFi，也不可跳过双确认。
        val first = ask(activity, R.string.cellular_title, R.string.cellular_switch_wifi,
            R.string.cellular_continue, alive)
        if (!first || !alive()) return@withContext false
        ask(activity, R.string.cellular_cost_title, R.string.cellular_cost_message,
            R.string.cellular_confirm_download, alive) && alive()
    }

    private suspend fun ask(
        activity: Activity,
        title: Int,
        message: Int,
        positive: Int,
        alive: () -> Boolean,
    ): Boolean = kotlinx.coroutines.coroutineScope {
        if (!alive()) return@coroutineScope false
        val answer = CompletableDeferred<Boolean>()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> answer.complete(alive()) }
            .setNegativeButton(R.string.common_cancel) { _, _ -> answer.complete(false) }
            .setOnCancelListener { answer.complete(false) }
            .create()
        dialog.show()
        val watcher = launch {
            while (!answer.isCompleted) {
                if (!alive()) {
                    answer.complete(false)
                    break
                }
                kotlinx.coroutines.delay(100)
            }
        }
        try {
            answer.await()
        } finally {
            watcher.cancel()
            dialog.dismiss()
        }
    }
}
