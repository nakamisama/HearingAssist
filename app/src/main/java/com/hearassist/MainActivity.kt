package com.hearassist

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.appcompat.app.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        checkForUpdate()
    }

    /** 打开 App 时检测 GitHub 最新版本，若有新版弹窗提示更新（离线时静默跳过）。 */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val tag = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("https://api.github.com/repos/nakamisama/HearingAssist/releases/latest")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "HearingAssist")
                    if (conn.responseCode == 200) {
                        JSONObject(conn.inputStream.bufferedReader().readText()).optString("tag_name", "")
                    } else ""
                } catch (e: Exception) {
                    ""
                }
            }
            val latest = tag.removePrefix("build-").toIntOrNull() ?: return@launch
            if (latest > BuildConfig.VERSION_CODE && !isFinishing) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(getString(R.string.update_message, latest))
                    .setPositiveButton(R.string.update_now) { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/nakamisama/HearingAssist/releases/download/build-$latest/app-release.apk")))
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, R.string.update_fail, Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            }
        }
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
        val spnEngine = view.findViewById<Spinner>(R.id.spnEngine)
        val swPreview = view.findViewById<Switch>(R.id.swPreview)
        val spnInterval = view.findViewById<Spinner>(R.id.spnInterval)

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

        // 识别引擎：精准(SenseVoice整句) / 实时(流式逐字)
        val engineLabels = resources.getStringArray(R.array.engine_options)
        spnEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineLabels)
        spnEngine.setSelection(Settings.getAsrMode(this))
        spnEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position != Settings.getAsrMode(this@MainActivity)) {
                    Settings.setAsrMode(this@MainActivity, position)
                    // 引擎切换后同步预览控件可用状态（精准模式无预览）
                    val realtime = position == Settings.ASR_REALTIME
                    swPreview.isEnabled = realtime
                    spnInterval.isEnabled = realtime && swPreview.isChecked
                    if (!realtime) CaptionBus.setPartial("")
                    Toast.makeText(this@MainActivity, R.string.lang_restarted, Toast.LENGTH_SHORT).show()
                    restartServiceIfRunning()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 逐字预览开关（仅实时模式生效，无需重启服务）
        swPreview.isChecked = Settings.isPreviewEnabled(this)
        swPreview.isEnabled = Settings.getAsrMode(this) == Settings.ASR_REALTIME

        // 预览刷新间隔
        val intervalLabels = resources.getStringArray(R.array.interval_options)
        spnInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalLabels)
        val curInterval = Settings.getPreviewInterval(this)
        spnInterval.setSelection(Settings.PREVIEW_INTERVALS.indexOf(curInterval).coerceAtLeast(0))
        spnInterval.isEnabled = swPreview.isChecked
        spnInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                Settings.setPreviewInterval(this@MainActivity, Settings.PREVIEW_INTERVALS[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        swPreview.setOnCheckedChangeListener { _, checked ->
            Settings.setPreviewEnabled(this, checked)
            spnInterval.isEnabled = checked
            if (!checked) CaptionBus.setPartial("")
            renderSubtitles()
        }

        dialog.show()
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
                            EngineStatus.STARTING -> getString(R.string.status_starting)
                            EngineStatus.LISTENING -> getString(R.string.status_listening)
                            EngineStatus.RECOGNIZING -> getString(R.string.status_recognizing)
                        }
                        tvStatus.setTextColor(
                            if (status == EngineStatus.RECOGNIZING || status == EngineStatus.STARTING)
                                getColor(R.color.accent) else getColor(R.color.text_secondary)
                        )
                        btnMain.text = getString(
                            if (CaptionBus.running.value) R.string.btn_stop else R.string.btn_start
                        )
                        renderSubtitles()
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
                launch { CaptionBus.subtitles.collect { renderSubtitles(true) } }
                launch {
                    var lastPartialRender = 0L
                    CaptionBus.partial.collect { p ->
                        // 关闭逐字预览时不渲染半句
                        if (!Settings.isPreviewEnabled(this@MainActivity)) return@collect
                        val now = System.currentTimeMillis()
                        val interval = Settings.getPreviewInterval(this@MainActivity).toLong()
                        // 按设置间隔节流刷新、清空时立即刷新，避免整屏高频重绘导致闪烁
                        if (p.isEmpty() || now - lastPartialRender >= interval) {
                            lastPartialRender = now
                            renderSubtitles(false)
                        }
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

    /** 渲染已确认字幕（按说话人着色）+ 实时半句（高亮色）。scrollToBottom 仅在新增定稿行时为 true，预览刷新不滚动防闪。 */
    private fun renderSubtitles(scrollToBottom: Boolean = false) {
        // 启动中：显示加载提示，模型就绪后自动切换为正常字幕
        if (CaptionBus.status.value == EngineStatus.STARTING) {
            tvSubtitles.text = getString(R.string.starting_hint)
            return
        }
        val lines = CaptionBus.subtitles.value
        val partial = if (Settings.isPreviewEnabled(this)) CaptionBus.partial.value else ""
        if (lines.isEmpty() && partial.isEmpty()) {
            tvSubtitles.text = getString(R.string.initial_hint)
        } else {
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
            if (partial.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                val start = sb.length
                sb.append(partial)
                sb.setSpan(
                    ForegroundColorSpan(getColor(R.color.accent)),
                    start, sb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvSubtitles.text = sb
        }
        if (scrollToBottom) {
            scrollSubtitles.post { scrollSubtitles.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
