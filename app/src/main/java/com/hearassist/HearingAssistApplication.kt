package com.hearassist

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hearassist.data.AppSessionController

class HearingAssistApplication : Application(), DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private val started = HashSet<Activity>()
    private val resumed = HashSet<Activity>()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> AppSessionController.enterBackground()
                Intent.ACTION_USER_PRESENT -> if (resumed.isNotEmpty()) AppSessionController.enterForeground()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppSessionController.initialize(this)
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (started.isNotEmpty() && screenInteractive()) AppSessionController.enterForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        AppSessionController.enterBackground()
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        if (screenInteractive()) AppSessionController.enterForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed.add(activity)
        if (screenInteractive()) AppSessionController.enterForeground()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        // App 内跳转由另一个已 start 的页面接棒；不要等进程生命周期的防抖才停。
        if (started.isEmpty() && !activity.isChangingConfigurations) AppSessionController.enterBackground()
    }

    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
        resumed.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

    private fun screenInteractive(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
}
