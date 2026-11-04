package com.hearassist

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hearassist.audio.SttProcessor
import com.hearassist.data.CaptionBus
import com.hearassist.data.EngineStatus
import com.hearassist.data.Settings
import com.hearassist.data.VoiceprintStore
import com.hearassist.service.CaptionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSubtitles: TextView
    private lateinit var tvEnvAlert: TextView
    private lateinit var scrollSubtitles: ScrollView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnMain: Button

    private var pendingStart = false
    private var envAlertJob: Job? = null

    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingStart) startCaption()
            } else {
                pendingStart = false
                Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSubtitles = findViewById(R.id.tvSubtitles)
        tvEnvAlert = findViewById(R.id.tvEnvAlert)
        scrollSubtitles = findViewById(R.id.scrollSubtitles)
        btnSettings = findViewById(R.id.btnSettings)
        btnMain = findViewById(R.id.btnMain)

        btnMain.setOnClickListener {
            if (CaptionBus.running.value) {
                stopService(Intent(this, CaptionService::class.java))
            } else {
                ensurePermissionThenStart()
            }
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        observeState()
    }

    // ---------- 设置面板（底部弹出） ----------

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        dialog.setContentView(view)

        val swVoiceprint = view.findViewById<Switch>(R.id.swVoiceprint)
        val swEnvSound = view.findViewById<Switch>(R.id.swEnvSound)
        val btnManage = view.findViewById<Button>(R.id.btnVoiceprintManage)
        val spnLang = view.findViewById<Spinner>(R.id.spnLang)
        val btnCast = view.findViewById<Button>(R.id.btnCast)

        // 声纹：先设初始状态再挂监听，避免初始化触发回调
        swVoiceprint.isChecked = Settings.isVoiceprintEnabled(this)
        swVoiceprint.isEnabled = VoiceprintStore.load(this).isNotEmpty()
        swVoiceprint.setOnCheckedChangeListener { _, checked ->
            Settings.setVoiceprintEnabled(this, checked)
            restartServiceIfRunning()
        }

        btnManage.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, VoiceprintActivity::class.java))
        }

        // 环境音
        swEnvSound.isChecked = CaptionBus.envEnabled.value
        swEnvSound.setOnCheckedChangeListener { _, checked ->
            CaptionBus.setEnvEnabled(checked)
        }

        // 识别语言
        val langLabels = resources.getStringArray(R.array.lang_options)
        val langModes = intArrayOf(SttProcessor.LANG_AUTO, SttProcessor.LANG_ZH)
        spnLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        spnLang.setSelection(Settings.getLangMode(this))
        spnLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val mode = langModes[position]
                if (mode != Settings.getLangMode(this@MainActivity)) {
                    Settings.setLangMode(this@MainActivity, mode)
                    CaptionBus.setLangMode(mode)
                    Toast.makeText(this@MainActivity, R.string.lang_restarted, Toast.LENGTH_SHORT).show()
                    restartServiceIfRunning()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnCast.setOnClickListener { openCastSettings() }

        dialog.show()
    }

    /** 打开系统无线投屏设置。投屏本身由系统 Miracast 完成，把整个手机画面（含字幕）镜像到电视。 */
    private fun openCastSettings() {
        // 不同厂商入口 action 略有差异，逐个尝试
        val actions = listOf(
            "android.settings.WIFI_DISPLAY_SETTINGS",
            "android.settings.CAST_SETTINGS",
        )
        for (action in actions) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.cast_unavailable, Toast.LENGTH_LONG).show()
    }

    // ---------- 权限与服务 ----------

    private fun ensurePermissionThenStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> startCaption()
            else -> {
                pendingStart = true
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startCaption() {
        pendingStart = false
        ContextCompat.startForegroundService(this, Intent(this, CaptionService::class.java))
    }

    /** 设置变更后重启服务使其生效（等旧服务释放模型再启动） */
    private fun restartServiceIfRunning() {
        if (!CaptionBus.running.value) return
        stopService(Intent(this, CaptionService::class.java))
        lifecycleScope.launch {
            delay(800)
            if (CaptionBus.running.value) return@launch
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CaptionService::class.java))
        }
    }

    // ---------- 状态观察 ----------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CaptionBus.status.collect { status ->
                        tvStatus.text = when (status) {
                            EngineStatus.IDLE -> getString(R.string.status_idle)
                            EngineStatus.LISTENING -> getString(R.string.status_listening)
                            EngineStatus.RECOGNIZING -> getString(R.string.status_recognizing)
                        }
                        tvStatus.setTextColor(
                            if (status == EngineStatus.RECOGNIZING)
                                getColor(R.color.accent) else getColor(R.color.text_secondary)
                        )
                        btnMain.text = getString(
                            if (CaptionBus.running.value) R.string.btn_stop else R.string.btn_start
                        )
                    }
                }
                launch {
                    CaptionBus.running.collect { running ->
                        btnMain.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
                        // 收听时保持屏幕常亮，避免手机放一边当字幕屏时自动锁屏；停止后恢复正常休眠
                        if (running) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        if (!running) tvEnvAlert.visibility = View.GONE
                    }
                }
                launch {
                    CaptionBus.subtitles.collect { lines ->
                        if (lines.isEmpty()) {
                            tvSubtitles.text = getString(R.string.initial_hint)
                        } else {
                            // 每条字幕用说话人颜色渲染（名字+文字整行着色）
                            val sb = SpannableStringBuilder()
                            lines.forEachIndexed { i, line ->
                                if (i > 0) sb.append("\n")
                                val start = sb.length
                                if (line.speaker != null) sb.append("${line.speaker}：")
                                sb.append(line.text)
                                val color = if (line.color != 0) line.color else getColor(R.color.text_primary)
                                sb.setSpan(
                                    ForegroundColorSpan(color),
                                    start, sb.length,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                            tvSubtitles.text = sb
                        }
                        scrollSubtitles.post { scrollSubtitles.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
                launch {
                    CaptionBus.envEvent.collect { event ->
                        envAlertJob?.cancel()
                        if (event != null) {
                            tvEnvAlert.text = event
                            tvEnvAlert.visibility = View.VISIBLE
                            envAlertJob = launch {
                                delay(4000)
                                tvEnvAlert.visibility = View.GONE
                                CaptionBus.clearEnvEvent()
                            }
                        }
                    }
                }
            }
        }
    }
}
