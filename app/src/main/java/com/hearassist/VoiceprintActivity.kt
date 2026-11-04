package com.hearassist

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hearassist.audio.VadProcessor
import com.hearassist.audio.VoiceprintProcessor
import com.hearassist.data.CaptionBus
import com.hearassist.data.VoiceprintStore
import com.hearassist.service.CaptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声纹管理页：列表/删除 + 录入流程（指定句子朗读，每人约 1 分钟）。
 */
class VoiceprintActivity : AppCompatActivity() {

    // Main dispatcher：协程里直接操作 UI（按钮状态、文本），避免跨线程崩溃
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var viewList: View
    private lateinit var viewEnroll: View
    private lateinit var lvSpeakers: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAdd: Button
    private lateinit var colorChips: LinearLayout
    private lateinit var tvSentence: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStep: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnCancel: Button

    /** 预设色板：8 种高对比色，字幕整行着色 */
    private val presetColors = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFFFFB300.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt(),
        0xFFBA68C8.toInt(), 0xFFFF8A65.toInt(), 0xFF4DD0E1.toInt(), 0xFFAED581.toInt(),
    )

    /** 指定朗读句子（短/中/长，覆盖不同语速，每句读一遍） */
    private val sentences = listOf(
        "今天天气很好，我们出去玩吧。",
        "这个计划我觉得还需要再考虑一下。",
        "希望大家都能按时参加明天的会议，如果有事情请提前告诉我。",
    )

    private var enrollName = ""
    private var enrollColor = 0
    private var sentenceIndex = 0
    @Volatile private var recording = false
    private var selectedChip = 0
    private val collectedEmbeddings = mutableListOf<FloatArray>()
    private var vp: VoiceprintProcessor? = null
    // 退出录入后延迟关闭的旧模型（等 IO 录音循环先退出，避免原生竞态）
    private var pendingClose: VoiceprintProcessor? = null
    // 录入期间暂停字幕服务以独占麦克风；记录原状态以便结束后恢复
    private var serviceWasRunning = false
    // 录入代数令牌：取消/重新录入时递增，使旧的模型加载协程失效，避免竞态
    private var enrollGeneration = 0

    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecordingSentence()
            } else {
                Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
                backToList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voiceprint)

        viewList = findViewById(R.id.viewList)
        viewEnroll = findViewById(R.id.viewEnroll)
        lvSpeakers = findViewById(R.id.lvSpeakers)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnAdd = findViewById(R.id.btnAdd)
        colorChips = findViewById(R.id.colorChips)
        tvSentence = findViewById(R.id.tvSentence)
        tvStatus = findViewById(R.id.tvStatus)
        tvStep = findViewById(R.id.tvStep)
        btnRetry = findViewById(R.id.btnRetry)
        btnCancel = findViewById(R.id.btnCancel)

        btnAdd.setOnClickListener { showNameDialog() }
        btnRetry.setOnClickListener { startRecordingSentence() }
        btnCancel.setOnClickListener { backToList() }

        showList()
    }

    override fun onDestroy() {
        // 先通知 IO 录音循环停止，再取消协程/释放模型，避免循环继续调用已释放的模型
        recording = false
        scope.cancel()
        try { vp?.close() } catch (_: Exception) {}
        try { pendingClose?.close() } catch (_: Exception) {}
        vp = null
        pendingClose = null
        // 兜底：若录入中直接退出，恢复字幕服务
        if (serviceWasRunning) {
            serviceWasRunning = false
            try {
                ContextCompat.startForegroundService(this, Intent(this, CaptionService::class.java))
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 录入中按返回键 → 回到列表（并恢复服务），而不是直接退出
        if (viewEnroll.visibility == View.VISIBLE) {
            backToList()
        } else {
            super.onBackPressed()
        }
    }

    // ---------- 列表模式 ----------

    private fun showList() {
        viewList.visibility = View.VISIBLE
        viewEnroll.visibility = View.GONE
        refreshList()
    }

    private fun refreshList() {
        val entries = VoiceprintStore.load(this)
        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        lvSpeakers.adapter = object : ArrayAdapter<VoiceprintProcessor.SpeakerEntry>(
            this, R.layout.activity_voiceprint_item, entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view =
                    convertView ?: layoutInflater.inflate(R.layout.activity_voiceprint_item, parent, false)
                val entry = entries[position]
                view.findViewById<TextView>(R.id.speakerName).text = entry.name
                view.findViewById<TextView>(R.id.chipColor).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(entry.color)
                view.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
                    AlertDialog.Builder(this@VoiceprintActivity)
                        .setTitle(getString(R.string.vp_delete_confirm, entry.name))
                        .setPositiveButton("删除") { _, _ ->
                            VoiceprintStore.save(
                                this@VoiceprintActivity,
                                VoiceprintStore.load(this@VoiceprintActivity)
                                    .filterNot { it.name == entry.name }
                            )
                            refreshList()
                            restartCaptionServiceIfRunning()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                return view
            }
        }
    }

    private fun showNameDialog() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.vp_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vp_add)
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) startEnroll(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 录入流程（指定句子朗读） ----------

    private fun startEnroll(name: String) {
        // 先确认模型文件存在，避免原生层加载缺失模型时崩溃
        val modelPresent = try {
            assets.open(VoiceprintProcessor.MODEL_PATH).close()
            true
        } catch (e: Exception) {
            false
        }
        if (!modelPresent) {
            Toast.makeText(this, "声纹模型缺失，请更新到最新版 App", Toast.LENGTH_LONG).show()
            return
        }

        enrollName = name
        enrollColor = presetColors[VoiceprintStore.load(this).size % presetColors.size]
        selectedChip = presetColors.indexOf(enrollColor).let { if (it < 0) 0 else it }
        sentenceIndex = 0
        collectedEmbeddings.clear()
        val gen = ++enrollGeneration

        // 切到录入视图，先显示"加载模型"，避免主线程加载 28MB 模型卡死 UI
        viewList.visibility = View.GONE
        viewEnroll.visibility = View.VISIBLE
        buildColorChips()
        tvSentence.text = ""
        tvStep.text = ""
        tvStatus.text = getString(R.string.vp_loading_model)
        btnRetry.isEnabled = false

        // 暂停字幕服务，把麦克风让给录入
        pauseServiceForEnrollment()

        // 后台线程加载声纹模型，加载完成再进入朗读
        scope.launch {
            val processor = withContext(Dispatchers.IO) {
                try { VoiceprintProcessor(assets) } catch (e: Exception) { null }
            }
            // 加载期间若用户已退出/重新发起录入（代数变化或不在录入界面），关闭模型并放弃
            if (gen != enrollGeneration || viewEnroll.visibility != View.VISIBLE) {
                try { processor?.close() } catch (_: Exception) {}
                return@launch
            }
            if (processor == null) {
                Toast.makeText(this@VoiceprintActivity, "声纹模型加载失败", Toast.LENGTH_SHORT).show()
                backToList()
                return@launch
            }
            vp = processor
            showSentence()
            startRecordingSentence()
        }
    }

    /** 录入前暂停字幕服务，独占麦克风，避免录不到声音 */
    private fun pauseServiceForEnrollment() {
        if (CaptionBus.running.value) {
            serviceWasRunning = true
            stopService(Intent(this, CaptionService::class.java))
        }
    }

    /** 录入结束/取消后恢复字幕服务（若之前在运行） */
    private fun restoreServiceIfNeeded() {
        if (serviceWasRunning) {
            serviceWasRunning = false
            try {
                ContextCompat.startForegroundService(this, Intent(this, CaptionService::class.java))
            } catch (_: Exception) {}
        }
    }

    /** 删除声纹后重启运行中的字幕服务，使其重新加载声纹库（否则已删除的人仍会被识别）。
     *  用主线程 Handler 而非 activity 协程，避免删除后立即退出页面导致重启被取消、服务卡在关闭态。 */
    private fun restartCaptionServiceIfRunning() {
        if (!CaptionBus.running.value) return
        stopService(Intent(this, CaptionService::class.java))
        val appCtx = applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!CaptionBus.running.value) {
                try {
                    ContextCompat.startForegroundService(appCtx, Intent(appCtx, CaptionService::class.java))
                } catch (_: Exception) {}
            }
        }, 800)
    }

    private fun buildColorChips() {
        colorChips.removeAllViews()
        presetColors.forEachIndexed { i, color ->
            val chip = TextView(this).apply {
                text = " "
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setBackgroundResource(R.drawable.chip_bg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                setOnClickListener {
                    selectedChip = i
                    enrollColor = color
                    updateChipSelection()
                }
            }
            colorChips.addView(chip)
        }
        updateChipSelection()
    }

    private fun updateChipSelection() {
        for (i in 0 until colorChips.childCount) {
            val chip = colorChips.getChildAt(i)
            val sel = i == selectedChip
            chip.scaleX = if (sel) 1.15f else 1.0f
            chip.scaleY = if (sel) 1.15f else 1.0f
            chip.alpha = if (sel) 1.0f else 0.45f
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showSentence() {
        tvSentence.text = sentences[sentenceIndex]
        tvStep.text = getString(R.string.vp_step, sentenceIndex + 1, sentences.size)
        tvStatus.text = getString(R.string.vp_recording_hint)
    }

    private fun startRecordingSentence() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        recording = true
        tvStatus.text = getString(R.string.vp_recording)
        btnRetry.isEnabled = false
        // 取消按钮全程可用，用户随时可退出录入
        scope.launch {
            var proceedToNext = false
            try {
                val segments = recordSentence()
                // 录音期间用户已取消（返回列表）→ 不再继续录入
                if (viewEnroll.visibility != View.VISIBLE) return@launch
                if (segments.isEmpty()) {
                    tvStatus.text = getString(R.string.vp_no_speech)
                    return@launch
                }
                collectedEmbeddings.addAll(segments)
                sentenceIndex++
                if (sentenceIndex >= sentences.size) {
                    finishEnroll()
                } else {
                    delay(400)
                    // 句间间隙用户也可能取消 → 再次确认仍在录入界面
                    if (viewEnroll.visibility != View.VISIBLE) return@launch
                    showSentence()
                    proceedToNext = true
                }
            } catch (e: Exception) {
                // 录制异常：更新状态提示重录，避免一直停留在"正在聆听…"误导用户
                tvStatus.text = getString(R.string.vp_no_speech)
            } finally {
                // 无论是否进入下一句都必须复位 recording，
                // 否则下一句 startRecordingSentence 会被开头的 if(recording) return 挡住导致卡住
                recording = false
                if (!proceedToNext) {
                    btnRetry.isEnabled = true
                }
            }
            if (proceedToNext) startRecordingSentence()
        }
    }

    /** 录制一句指定的话：VAD 切段 → 提取声纹；读完（1.2s 无新语音）即进入下一句 */
    private suspend fun recordSentence(): List<FloatArray> = withContext(Dispatchers.IO) {
        val extractor = vp ?: return@withContext emptyList()
        val vad = VadProcessor(assets)
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
            vad.close()
            return@withContext emptyList()
        }
        rec.startRecording()
        val segments = mutableListOf<FloatArray>()
        val shorts = ShortArray(VadProcessor.WINDOW_SIZE)
        val floats = FloatArray(VadProcessor.WINDOW_SIZE)
        try {
            val deadline = System.currentTimeMillis() + 20_000
            var lastSegmentTime = 0L
            while (System.currentTimeMillis() < deadline) {
                // 用户取消/退出 或 协程已取消（Activity 销毁）→ 立即停止录音
                if (!recording || !isActive) break
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                vad.accept(floats)
                while (vad.hasSegments()) {
                    val seg = vad.nextSegment()
                    val emb = extractor.extract(seg.samples)
                    if (emb != null) {
                        segments.add(emb)
                        lastSegmentTime = System.currentTimeMillis()
                    }
                }
                // 已采到声纹且之后 1.2 秒无新语音 → 本句读完，立即进入下一句（不再傻等超时）
                if (segments.isNotEmpty() && lastSegmentTime > 0 &&
                    System.currentTimeMillis() - lastSegmentTime > 1200) break
                if (segments.size >= 3) break
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            vad.close()
        }
        segments
    }

    private fun finishEnroll() {
        val entry = VoiceprintProcessor.SpeakerEntry(
            enrollName, enrollColor, collectedEmbeddings.toList()
        )
        val all = VoiceprintStore.load(this).toMutableList()
        all.removeAll { it.name == enrollName }
        all.add(entry)
        VoiceprintStore.save(this, all)
        try { vp?.close() } catch (_: Exception) {}
        vp = null
        restoreServiceIfNeeded()
        Toast.makeText(this, R.string.vp_enroll_success, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun backToList() {
        recording = false
        enrollGeneration++  // 使仍在加载模型的旧协程失效
        restoreServiceIfNeeded()
        showList()
        // 延迟关闭模型：等 IO 录音循环退出（每 32ms 检查 recording 标志），避免原生竞态崩溃
        pendingClose = vp
        vp = null
        scope.launch {
            delay(500)
            val p = pendingClose
            pendingClose = null
            try { p?.close() } catch (_: Exception) {}
        }
    }
}
