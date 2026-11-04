package com.hearassist

import android.Manifest
import android.app.AlertDialog
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.text.InputType
import android.view.View
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
import com.hearassist.data.VoiceprintStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声纹管理页：列表/删除 + 录入流程（指定句子朗读，每人约 1 分钟）。
 */
class VoiceprintActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    private var recording = false
    private var selectedChip = 0
    private val collectedEmbeddings = mutableListOf<FloatArray>()
    private var vp: VoiceprintProcessor? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
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
        scope.cancel()
        try { vp?.close() } catch (_: Exception) {}
        vp = null
        super.onDestroy()
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
            override fun getView(
                position: Int,
                convertView: View?,
                parent: android.view.ViewGroup?,
            ): View {
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
        enrollName = name
        enrollColor = presetColors[VoiceprintStore.load(this).size % presetColors.size]
        selectedChip = presetColors.indexOf(enrollColor).let { if (it < 0) 0 else it }
        sentenceIndex = 0
        collectedEmbeddings.clear()
        try {
            vp = VoiceprintProcessor(assets)
        } catch (e: Exception) {
            Toast.makeText(this, "声纹模型加载失败", Toast.LENGTH_SHORT).show()
            backToList()
            return
        }
        viewList.visibility = View.GONE
        viewEnroll.visibility = View.VISIBLE
        buildColorChips()
        showSentence()
        startRecordingSentence()
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
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        recording = true
        tvStatus.text = getString(R.string.vp_recording)
        btnRetry.isEnabled = false
        btnCancel.isEnabled = false
        scope.launch {
            val segments = recordSentence()
            recording = false
            btnRetry.isEnabled = true
            btnCancel.isEnabled = true
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
                showSentence()
                startRecordingSentence()
            }
        }
    }

    /** 录制一句指定的话：VAD 切段 → 提取声纹，最多收集 2 段 */
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
            val deadline = System.currentTimeMillis() + 25_000
            while (segments.size < 2 && System.currentTimeMillis() < deadline) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                vad.accept(floats)
                while (vad.hasSegments() && segments.size < 2) {
                    val seg = vad.nextSegment()
                    val emb = extractor.extract(seg.samples)
                    if (emb != null) segments.add(emb)
                }
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
        Toast.makeText(this, R.string.vp_enroll_success, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun backToList() {
        recording = false
        try { vp?.close() } catch (_: Exception) {}
        vp = null
        showList()
    }
}
