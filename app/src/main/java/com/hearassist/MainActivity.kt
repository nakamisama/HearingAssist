package com.hearassist

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.hearassist.data.CaptionBus
import com.hearassist.data.EngineStatus
import com.hearassist.data.Settings
import com.hearassist.data.VoiceprintStore
import com.hearassist.service.CaptionService
import com.hearassist.audio.SttProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSubtitles: TextView
    private lateinit var tvEnvAlert: TextView
    private lateinit var scrollSubtitles: ScrollView
    private lateinit var swEnvSound: Switch
    private lateinit var spnLang: Spinner
    private lateinit var swVoiceprint: Switch
    private lateinit var btnVoiceprintManage: Button
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
        swEnvSound = findViewById(R.id.swEnvSound)
        spnLang = findViewById(R.id.spnLang)
        swVoiceprint = findViewById(R.id.swVoiceprint)
        btnVoiceprintManage = findViewById(R.id.btnVoiceprintManage)
        btnMain = findViewById(R.id.btnMain)

        btnMain.setOnClickListener {
            if (CaptionBus.running.value) {
                stopService(Intent(this, CaptionService::class.java))
            } else {
                ensurePermissionThenStart()
            }
        }

        swEnvSound.setOnCheckedChangeListener { _, checked ->
            CaptionBus.setEnvEnabled(checked)
        }

        swVoiceprint.setOnCheckedChangeListener { _, checked ->
            Settings.setVoiceprintEnabled(this, checked)
            restartServiceIfRunning()
        }

        btnVoiceprintManage.setOnClickListener {
            startActivity(Intent(this, VoiceprintActivity::class.java))
        }

        // 识别语言选择：自动 / 普通话（切换后自动重启服务生效）
        val langLabels = resources.getStringArray(R.array.lang_options)
        val langModes = intArrayOf(
            SttProcessor.LANG_AUTO, SttProcessor.LANG_ZH
        )
        spnLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        spnLang.setSelection(Settings.getLangMode(this))
        spnLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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

        // 通知权限不自动请求：避免与麦克风权限回调混淆导致误导性提示。
        // 服务前台通知在用户授予通知权限后自动显示，不影响功能。

        observeState()
    }

    override fun onResume() {
        super.onResume()
        // 未录入声纹时禁用声纹开关
        swVoiceprint.isEnabled = VoiceprintStore.load(this).isNotEmpty()
    }

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

    /** 语言切换后重启服务使新语言生效（等旧服务释放模型再启动） */
    private fun restartServiceIfRunning() {
        if (!CaptionBus.running.value) return
        stopService(Intent(this, CaptionService::class.java))
        lifecycleScope.launch {
            delay(800)
            if (CaptionBus.running.value) return@launch
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, CaptionService::class.java))
        }
    }

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
                        if (!running) tvEnvAlert.visibility = android.view.View.GONE
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
                            tvEnvAlert.visibility = android.view.View.VISIBLE
                            envAlertJob = launch {
                                delay(4000)
                                tvEnvAlert.visibility = android.view.View.GONE
                                CaptionBus.clearEnvEvent()
                            }
                        }
                    }
                }
            }
        }
    }
}
