package com.hearassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hearassist.audio.AudioSessions
import com.hearassist.audio.MicrophoneCapture
import com.hearassist.audio.VadProcessor
import com.hearassist.audio.VoiceprintProcessor
import com.hearassist.data.AppSessionController
import com.hearassist.data.CellularConsent
import com.hearassist.data.ModelManager
import com.hearassist.data.SessionPolicy
import com.hearassist.data.VoiceprintStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** 三句朗读使用一个受控会话；取消或切后台后等录音/推理退出再释放模型。 */
class VoiceprintActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val capture = AtomicReference<MicrophoneCapture?>(null)
    private var enrollmentJob: Job? = null
    private var enrollmentTicket: SessionPolicy.EnrollmentTicket? = null
    private var retrySignal: CompletableDeferred<Unit>? = null
    private var restoreCaption = false
    private var pendingName: String? = null
    private var permissionTicket: SessionPolicy.VisibilityTicket? = null

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
    private var selectedChip = 0
    private var enrollColor = 0

    private val presetColors = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFFFFB300.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt(),
        0xFFBA68C8.toInt(), 0xFFFF8A65.toInt(), 0xFF4DD0E1.toInt(), 0xFFAED581.toInt(),
    )
    private val sentences = listOf(
        "今天天气很好，我们出去玩吧。",
        "这个计划我觉得还需要再考虑一下。",
        "希望大家都能按时参加明天的会议，如果有事情请提前告诉我。",
    )

    private val onBackground: () -> Unit = {
        pendingName = null
        permissionTicket = null
        restoreCaption = false
        cancelEnrollment()
    }
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val name = pendingName
        val visible = permissionTicket
        pendingName = null
        permissionTicket = null
        if (name != null && visible != null && AppSessionController.isVisible(visible)) {
            if (granted) startEnroll(name)
            else Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
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
        btnRetry.setOnClickListener { retrySignal?.complete(Unit) }
        btnCancel.setOnClickListener { cancelEnrollment() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewEnroll.visibility == View.VISIBLE) cancelEnrollment() else finish()
            }
        })
        AppSessionController.addBackgroundListener(onBackground)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                CellularConsent.events.collect { CellularConsent.handle(this@VoiceprintActivity, it) }
            }
        }
        showList()
    }

    override fun onDestroy() {
        AppSessionController.removeBackgroundListener(onBackground)
        // 内部返回不抹掉前台恢复意图；真正后台由 Application 撤销 epoch，旧票据仍不可复活。
        pendingName = null
        permissionTicket = null
        cancelEnrollment()
        scope.cancel()
        // 清理在 enrollmentJob 的 finally 中完成，不和 IO 推理抢着释放。
        super.onDestroy()
    }

    private fun showList() {
        viewList.visibility = View.VISIBLE
        viewEnroll.visibility = View.GONE
        refreshList()
    }

    private fun refreshList() {
        val entries = VoiceprintStore.load(this)
        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        lvSpeakers.adapter = object : ArrayAdapter<VoiceprintProcessor.SpeakerEntry>(
            this, R.layout.activity_voiceprint_item, entries,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.activity_voiceprint_item, parent, false)
                val entry = entries[position]
                view.findViewById<TextView>(R.id.speakerName).text = entry.name
                view.findViewById<TextView>(R.id.chipColor).backgroundTintList =
                    android.content.res.ColorStateList.valueOf(entry.color)
                view.findViewById<TextView>(R.id.btnDelete).setOnClickListener {
                    AlertDialog.Builder(this@VoiceprintActivity)
                        .setTitle(getString(R.string.vp_delete_confirm, entry.name))
                        .setPositiveButton(R.string.vp_delete) { _, _ ->
                            try {
                                VoiceprintStore.save(this@VoiceprintActivity,
                                    VoiceprintStore.load(this@VoiceprintActivity).filterNot { it.name == entry.name })
                                refreshList()
                                AppSessionController.restartCaption()
                            } catch (_: Exception) {
                                Toast.makeText(this@VoiceprintActivity, R.string.vp_save_fail, Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton(R.string.common_cancel, null)
                        .show()
                }
                return view
            }
        }
    }

    private fun showNameDialog() {
        if (enrollmentJob?.isCompleted == false) return
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.vp_name_hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vp_add)
            .setView(edit)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edit.text.toString().trim()
                if (name.isEmpty()) { edit.error = getString(R.string.vp_name_hint); return@setOnClickListener }
                dialog.dismiss()
                if (VoiceprintStore.load(this).any { it.name == name }) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.vp_replace_title)
                        .setMessage(getString(R.string.vp_replace_message, name))
                        .setPositiveButton(android.R.string.ok) { _, _ -> requestEnrollment(name) }
                        .setNegativeButton(R.string.common_cancel, null).show()
                } else requestEnrollment(name)
            }
        }
        dialog.show()
    }

    private fun requestEnrollment(name: String) {
        if (!AppSessionController.isForeground()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEnroll(name)
        } else {
            pendingName = name
            permissionTicket = AppSessionController.visibilityTicket()
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private suspend fun checkEnrollment(ticket: SessionPolicy.EnrollmentTicket) {
        coroutineContext.ensureActive()
        if (!AppSessionController.allowsEnrollment(ticket)) throw CancellationException("录入会话已取消")
    }

    private fun startEnroll(name: String) {
        if (enrollmentJob?.isCompleted == false) return
        val ticket = AppSessionController.beginEnrollment() ?: return
        enrollmentTicket = ticket
        restoreCaption = true
        val entries = VoiceprintStore.load(this)
        enrollColor = entries.find { it.name == name }?.color ?: presetColors[entries.size % presetColors.size]
        selectedChip = presetColors.indexOf(enrollColor).coerceAtLeast(0)
        btnAdd.isEnabled = false
        btnRetry.isEnabled = false
        viewList.visibility = View.GONE
        viewEnroll.visibility = View.VISIBLE
        tvSentence.text = ""
        tvStep.text = ""
        tvStatus.setText(R.string.vp_loading_model)
        buildColorChips()

        enrollmentJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val embeddings = withContext(Dispatchers.Default) {
                    AudioSessions.gate.run(resources = { EnrollmentResources() }) { owned ->
                        prepareEnrollment(ticket, owned)
                        val result = mutableListOf<FloatArray>()
                        for (index in sentences.indices) {
                            checkEnrollment(ticket)
                            var embedding: FloatArray? = null
                            while (embedding == null) {
                                withContext(Dispatchers.Main.immediate) {
                                    checkEnrollment(ticket)
                                    tvSentence.text = sentences[index]
                                    tvStep.text = getString(R.string.vp_step, index + 1, sentences.size)
                                    tvStatus.setText(R.string.vp_recording)
                                    btnRetry.isEnabled = false
                                }
                                val samples = recordSentence(ticket, owned)
                                checkEnrollment(ticket)
                                if (samples.isNotEmpty()) {
                                    withContext(Dispatchers.Main.immediate) { tvStatus.setText(R.string.vp_processing_sentence) }
                                    embedding = requireNotNull(owned.voiceprint).extract(samples)
                                    checkEnrollment(ticket)
                                }
                                if (embedding == null) awaitRetry(ticket)
                            }
                            result.add(requireNotNull(embedding))
                            if (index < sentences.lastIndex) delay(400)
                        }
                        result
                    }
                }
                checkEnrollment(ticket)
                val entry = VoiceprintProcessor.SpeakerEntry(name, enrollColor, embeddings)
                VoiceprintStore.save(this@VoiceprintActivity,
                    VoiceprintStore.load(this@VoiceprintActivity).filterNot { it.name == name } + entry)
                Toast.makeText(this@VoiceprintActivity, R.string.vp_enroll_success, Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AppSessionController.allowsEnrollment(ticket)) {
                    Toast.makeText(this@VoiceprintActivity, e.message ?: getString(R.string.vp_record_error), Toast.LENGTH_LONG).show()
                    if (!isDestroyed) showList()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    capture.getAndSet(null)?.requestStop()
                    retrySignal = null
                    if (enrollmentTicket == ticket) {
                        enrollmentTicket = null
                        if (!isDestroyed) btnAdd.isEnabled = true
                    }
                    AppSessionController.finishEnrollment(ticket, resumeCaption = restoreCaption)
                }
            }
        }
        enrollmentJob?.start()
    }

    private suspend fun prepareEnrollment(ticket: SessionPolicy.EnrollmentTicket, owned: EnrollmentResources) {
        checkEnrollment(ticket)
        val ready = ModelManager.ensureModels(this, listOf("voiceprint", "vad"), { _, message ->
            scope.launch {
                if (AppSessionController.allowsEnrollment(ticket)) tvStatus.text = message
            }
        }) { CellularConsent.request { AppSessionController.allowsEnrollment(ticket) } }
        checkEnrollment(ticket)
        check(ready) { "模型下载已取消或失败" }
        owned.vad = VadProcessor(this)
        checkEnrollment(ticket)
        owned.voiceprint = VoiceprintProcessor(this)
        checkEnrollment(ticket)
    }

    private suspend fun awaitRetry(ticket: SessionPolicy.EnrollmentTicket) {
        val signal = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            checkEnrollment(ticket)
            retrySignal = signal
            btnRetry.isEnabled = true
            tvStatus.setText(R.string.vp_no_speech)
        }
        try { signal.await() } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (retrySignal === signal) retrySignal = null
            }
        }
    }

    private suspend fun recordSentence(ticket: SessionPolicy.EnrollmentTicket, owned: EnrollmentResources): FloatArray =
        withContext(Dispatchers.IO) {
            val vad = requireNotNull(owned.vad)
            vad.reset()
            val microphone = MicrophoneCapture()
            owned.microphone = microphone
            capture.set(microphone)
            val segments = mutableListOf<FloatArray>()
            var sampleCount = 0
            try {
                checkEnrollment(ticket)
                check(ContextCompat.checkSelfPermission(this@VoiceprintActivity, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) { "麦克风权限已撤销" }
                microphone.start()
                val deadline = SystemClock.elapsedRealtime() + 20000
                var lastSpeech = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() < deadline) {
                    checkEnrollment(ticket)
                    val samples = microphone.read()
                    if (samples.isEmpty()) continue
                    checkEnrollment(ticket)
                    vad.accept(samples)
                    if (vad.isSpeechDetected()) lastSpeech = SystemClock.elapsedRealtime()
                    while (vad.hasSegments()) {
                        val segment = vad.nextSegment().samples
                        segments.add(segment)
                        sampleCount += segment.size
                        lastSpeech = SystemClock.elapsedRealtime()
                    }
                    if (sampleCount >= VoiceprintProcessor.MIN_SEGMENT_SAMPLES && !vad.isSpeechDetected() &&
                        SystemClock.elapsedRealtime() - lastSpeech >= 1200) break
                }
                checkEnrollment(ticket)
                FloatArray(sampleCount).also { output ->
                    var offset = 0
                    for (segment in segments) {
                        segment.copyInto(output, offset)
                        offset += segment.size
                    }
                }
            } finally {
                microphone.requestStop()
                microphone.close()
                owned.microphone = null
                capture.compareAndSet(microphone, null)
            }
        }

    private fun cancelEnrollment() {
        pendingName = null
        permissionTicket = null
        enrollmentJob?.cancel()
        retrySignal?.cancel()
        capture.get()?.requestStop()
        if (!isDestroyed && ::viewList.isInitialized) showList()
        // 后台事件已撤销 ticket；finally 再恢复时会被策略拒绝。
    }

    private class EnrollmentResources : AutoCloseable {
        var microphone: MicrophoneCapture? = null
        var vad: VadProcessor? = null
        var voiceprint: VoiceprintProcessor? = null
        override fun close() {
            listOf(microphone, voiceprint, vad).forEach {
                try { it?.close() } catch (_: Exception) {}
            }
            microphone = null
            voiceprint = null
            vad = null
        }
    }

    private fun buildColorChips() {
        colorChips.removeAllViews()
        presetColors.forEachIndexed { index, color ->
            val chip = TextView(this).apply {
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.chip_bg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(6) }
                setOnClickListener {
                    selectedChip = index
                    enrollColor = color
                    updateChipSelection()
                }
            }
            colorChips.addView(chip)
        }
        updateChipSelection()
    }

    private fun updateChipSelection() {
        for (index in 0 until colorChips.childCount) {
            val chip = colorChips.getChildAt(index) as TextView
            chip.text = if (index == selectedChip) "✓" else ""
            chip.setTextColor(0xFF062733.toInt())
            chip.contentDescription = getString(R.string.vp_color_option, index + 1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
