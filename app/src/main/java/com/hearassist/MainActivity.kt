package com.hearassist

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hearassist.data.AppSessionController
import com.hearassist.data.CaptionBus
import com.hearassist.data.CellularConsent
import com.hearassist.data.CancellableHttp
import com.hearassist.data.EngineStatus
import com.hearassist.data.PendingApk
import com.hearassist.data.SessionPolicy
import com.hearassist.data.Settings
import com.hearassist.data.VoiceprintStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvSubtitles: TextView
    private lateinit var tvEnvAlert: TextView
    private lateinit var scrollSubtitles: ScrollView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnMain: Button
    private var permissionTicket: SessionPolicy.VisibilityTicket? = null
    private var envAlertJob: Job? = null
    private var previewRenderJob: Job? = null
    private var lastPreviewRender = 0L
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadProgress: ProgressDialog? = null
    private var settingsDialog: BottomSheetDialog? = null
    private var updateDialog: AlertDialog? = null
    private var automaticCheckDone = false
    private var pendingInstall: File? = null

    private val onBackground: () -> Unit = {
        permissionTicket = null
        checkJob?.cancel()
        downloadJob?.cancel()
        previewRenderJob?.cancel()
        envAlertJob?.cancel()
        downloadProgress?.dismiss()
        settingsDialog?.dismiss()
        updateDialog?.dismiss()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ticket = permissionTicket
        permissionTicket = null
        if (ticket != null && AppSessionController.isVisible(ticket)) {
            if (granted) AppSessionController.startCaption()
            else Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
        }
    }
    private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 回到前台后只继续安装已下载的包，不恢复录音或下载任务。
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
        automaticCheckDone = savedInstanceState?.getBoolean("update_checked") ?: false
        pendingInstall = savedInstanceState?.getString("pending_install")?.let(::File)
        btnMain.setOnClickListener {
            if (AppSessionController.wantsCaption()) AppSessionController.stopCaption()
            else ensurePermissionThenStart()
        }
        btnSettings.setOnClickListener { showSettingsDialog() }
        AppSessionController.addBackgroundListener(onBackground)
        observeState()
    }

    override fun onPostResume() {
        super.onPostResume()
        val file = pendingInstall
        if (file != null) {
            pendingInstall = null
            if (packageManager.canRequestPackageInstalls()) installApk(file)
            else Toast.makeText(this, R.string.update_install_perm_message, Toast.LENGTH_LONG).show()
        }
        if (!automaticCheckDone) {
            automaticCheckDone = true
            checkForUpdate()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("update_checked", automaticCheckDone)
        outState.putString("pending_install", pendingInstall?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        AppSessionController.removeBackgroundListener(onBackground)
        onBackground()
        super.onDestroy()
    }

    private fun visible(ticket: SessionPolicy.VisibilityTicket): Boolean =
        AppSessionController.isVisible(ticket) && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun checkForUpdate(manual: Boolean = false) {
        if (!AppSessionController.isForeground() || checkJob?.isActive == true) return
        val ticket = AppSessionController.visibilityTicket()
        if (manual) Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        checkJob = lifecycleScope.launch {
            val latest = fetchLatestVersion()
            if (!visible(ticket)) return@launch
            if (latest == null) {
                if (manual) Toast.makeText(this@MainActivity, R.string.update_check_fail, Toast.LENGTH_LONG).show()
            } else if (latest > BuildConfig.VERSION_CODE) {
                updateDialog?.dismiss()
                updateDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(getString(R.string.update_message, latest))
                    .setPositiveButton(R.string.update_now) { _, _ ->
                        if (visible(ticket)) confirmDownload(latest)
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            } else if (manual) {
                Toast.makeText(this@MainActivity, R.string.update_latest, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun wifiNetwork(): Network? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun confirmDownload(latest: Int) {
        if (wifiNetwork() == null) {
            Toast.makeText(this, R.string.update_wifi_only, Toast.LENGTH_LONG).show()
            return
        }
        val ticket = AppSessionController.visibilityTicket()
        updateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_confirm_title)
            .setMessage(getString(R.string.update_confirm_message, latest))
            .setPositiveButton(R.string.update_confirm_yes) { _, _ ->
                if (visible(ticket)) downloadAndInstall(latest)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstall(latest: Int) {
        if (downloadJob?.isActive == true) return
        val network = wifiNetwork() ?: run {
            Toast.makeText(this, R.string.update_wifi_only, Toast.LENGTH_LONG).show()
            return
        }
        val ticket = AppSessionController.visibilityTicket()
        val progress = ProgressDialog(this).apply {
            setTitle(R.string.update_downloading)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
            setButton(ProgressDialog.BUTTON_NEGATIVE, getString(R.string.common_cancel)) { _, _ -> downloadJob?.cancel() }
            setOnCancelListener { downloadJob?.cancel() }
            show()
        }
        downloadProgress = progress
        downloadJob = lifecycleScope.launch {
            try {
                val file = downloadApk(latest, network, ticket) { percent ->
                    runOnUiThread { if (visible(ticket)) progress.progress = percent }
                }
                if (visible(ticket)) {
                    if (file != null) installApk(file)
                    else Toast.makeText(this@MainActivity, R.string.update_download_fail, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                progress.dismiss()
                if (downloadProgress === progress) downloadProgress = null
            }
        }
    }

    private suspend fun <T> withRedirects(
        original: String,
        network: Network? = null,
        block: suspend (HttpURLConnection) -> T,
    ): T {
        var url = original
        repeat(6) {
            try {
                return CancellableHttp.withConnection(url, network, block = block)
            } catch (e: CancellableHttp.HttpStatusException) {
                if (e.statusCode !in listOf(301, 302, 303, 307, 308) || e.location == null) throw e
                val next = URL(URL(url), e.location)
                if (next.protocol != "https") throw IOException("拒绝非 HTTPS 重定向")
                url = next.toString()
            }
        }
        throw IOException("下载地址重定向过多")
    }

    private suspend fun downloadApk(
        latest: Int,
        network: Network,
        ticket: SessionPolicy.VisibilityTicket,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext null
        val output = File(root, "update-$latest.apk")
        if (output.exists() && validUpdate(output, latest)) return@withContext output
        val original = "https://github.com/nakamisama/HearingAssist/releases/download/build-$latest/app-release.apk"
        for (url in listOf("https://ghproxy.net/$original", "https://gh-proxy.com/$original", original)) {
            currentCoroutineContext().ensureActive()
            if (!AppSessionController.isVisible(ticket) || wifiNetwork() != network) return@withContext null
            var pending: PendingApk? = null
            try {
                val download = PendingApk(root, latest).also { pending = it }
                val temporary = download.file
                withRedirects(url, network) { connection ->
                    val length = connection.contentLengthLong
                    if (length > root.usableSpace) throw IOException("存储空间不足")
                    FileOutputStream(temporary).use { stream ->
                        CancellableHttp.copy(connection.inputStream, stream, length, {
                            if (!AppSessionController.isVisible(ticket)) throw CancellationException("应用已进入后台")
                            if (wifiNetwork() != network) throw IOException("WiFi 已断开，停止下载")
                        }, onProgress)
                        stream.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!validUpdate(temporary, latest)) throw IOException("安装包校验失败")
                currentCoroutineContext().ensureActive()
                if (!AppSessionController.isVisible(ticket)) throw CancellationException("应用已进入后台")
                return@withContext download.publish(output)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            } finally {
                pending?.close()
            }
        }
        null
    }

    @Suppress("DEPRECATION")
    private fun validUpdate(file: File, expectedVersion: Int): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val update = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (update.packageName != packageName) return false
        val version = if (Build.VERSION.SDK_INT >= 28) update.longVersionCode else update.versionCode.toLong()
        if (version != expectedVersion.toLong() || version <= BuildConfig.VERSION_CODE) return false
        val current = packageManager.getPackageInfo(packageName, flags)
        return certificateBytes(update).toSet() == certificateBytes(current).toSet() && certificateBytes(update).isNotEmpty()
    }

    @Suppress("DEPRECATION")
    private fun certificateBytes(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { it.toCharsString() }.orEmpty()
    }

    private fun installApk(file: File) {
        if (!AppSessionController.isForeground() || !file.exists()) return
        if (!packageManager.canRequestPackageInstalls()) {
            updateDialog = AlertDialog.Builder(this)
                .setTitle(R.string.update_install_perm_title)
                .setMessage(R.string.update_install_perm_message)
                .setPositiveButton(R.string.update_install_perm_go) { _, _ ->
                    pendingInstall = file
                    try {
                        installPermissionLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        pendingInstall = null
                        Toast.makeText(this, R.string.update_fail, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.update_later, null).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_fail, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun fetchLatestVersion(): Int? = withContext(Dispatchers.IO) {
        val raw = "https://raw.githubusercontent.com/nakamisama/HearingAssist/main/version.json"
        val sources = listOf("https://ghproxy.net/$raw", "https://gh-proxy.com/$raw", raw)
        for (source in sources) {
            try {
                val version = withRedirects(source) { connection ->
                    val output = java.io.ByteArrayOutputStream()
                    if (connection.contentLengthLong > 65536) throw IOException("版本清单过大")
                    CancellableHttp.copy(connection.inputStream, output, connection.contentLengthLong, {
                        if (output.size() > 65536) throw IOException("版本清单过大")
                    })
                    JSONObject(output.toString("UTF-8")).optInt("version", 0)
                }
                if (version > 0) return@withContext version
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        try {
            val text = CancellableHttp.readText("https://api.github.com/repos/nakamisama/HearingAssist/releases/latest")
            JSONObject(text).optString("tag_name", "").removePrefix("build-").toIntOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        settingsDialog = dialog
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        dialog.setContentView(view)
        val swVoiceprint = view.findViewById<Switch>(R.id.swVoiceprint)
        val swEnvSound = view.findViewById<Switch>(R.id.swEnvSound)
        val btnManage = view.findViewById<Button>(R.id.btnVoiceprintManage)
        val spnLang = view.findViewById<Spinner>(R.id.spnLang)
        val spnEngine = view.findViewById<Spinner>(R.id.spnEngine)
        val swPreview = view.findViewById<Switch>(R.id.swPreview)
        val spnInterval = view.findViewById<Spinner>(R.id.spnInterval)
        val btnCheckUpdate = view.findViewById<Button>(R.id.btnCheckUpdate)
        swVoiceprint.isChecked = Settings.isVoiceprintEnabled(this)
        swVoiceprint.isEnabled = VoiceprintStore.load(this).isNotEmpty()
        swVoiceprint.setOnCheckedChangeListener { _, checked ->
            Settings.setVoiceprintEnabled(this, checked)
            AppSessionController.restartCaption()
        }
        btnManage.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, VoiceprintActivity::class.java))
        }
        swEnvSound.isChecked = CaptionBus.envEnabled.value
        swEnvSound.setOnCheckedChangeListener { _, checked ->
            CaptionBus.setEnvEnabled(checked)
            AppSessionController.restartCaption()
        }
        val langLabels = resources.getStringArray(R.array.lang_options)
        spnLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
        spnLang.setSelection(Settings.getLangMode(this).coerceIn(0, langLabels.lastIndex))
        spnLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position != Settings.getLangMode(this@MainActivity)) {
                    Settings.setLangMode(this@MainActivity, position)
                    CaptionBus.setLangMode(position)
                    AppSessionController.restartCaption()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        swPreview.isChecked = Settings.isPreviewEnabled(this)
        fun updatePreviewControls() {
            val realtime = Settings.getAsrMode(this) == Settings.ASR_REALTIME
            swPreview.isEnabled = realtime
            spnInterval.isEnabled = realtime && swPreview.isChecked
        }
        val engineLabels = resources.getStringArray(R.array.engine_options)
        spnEngine.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engineLabels)
        spnEngine.setSelection(Settings.getAsrMode(this).coerceIn(0, engineLabels.lastIndex))
        spnEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position != Settings.getAsrMode(this@MainActivity)) {
                    Settings.setAsrMode(this@MainActivity, position)
                    updatePreviewControls()
                    if (position != Settings.ASR_REALTIME) CaptionBus.setPartial("")
                    AppSessionController.restartCaption()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val intervals = Settings.PREVIEW_INTERVALS
        spnInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.interval_options))
        spnInterval.setSelection(intervals.indexOf(Settings.getPreviewInterval(this)).takeIf { it >= 0 } ?: 1)
        spnInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                Settings.setPreviewInterval(this@MainActivity, intervals[position])
                previewRenderJob?.cancel()
                renderSubtitles()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        swPreview.setOnCheckedChangeListener { _, checked ->
            Settings.setPreviewEnabled(this, checked)
            updatePreviewControls()
            previewRenderJob?.cancel()
            renderSubtitles()
        }
        updatePreviewControls()
        btnCheckUpdate.setOnClickListener {
            dialog.dismiss()
            checkForUpdate(manual = true)
        }
        dialog.show()
    }

    private fun ensurePermissionThenStart() {
        if (!AppSessionController.isForeground()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AppSessionController.startCaption()
        } else {
            permissionTicket = AppSessionController.visibilityTicket()
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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
                        tvStatus.setTextColor(getColor(if (status == EngineStatus.STARTING || status == EngineStatus.RECOGNIZING)
                            R.color.accent else R.color.text_secondary))
                        renderSubtitles()
                    }
                }
                launch {
                    CaptionBus.running.collect { running ->
                        btnMain.setText(if (running) R.string.btn_stop else R.string.btn_start)
                        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            tvEnvAlert.visibility = View.GONE
                        }
                    }
                }
                launch { CaptionBus.error.collect { message ->
                    if (message != null) tvStatus.text = message
                } }
                launch { CaptionBus.subtitles.collect { renderSubtitles(true) } }
                launch { CaptionBus.startMsg.collect { renderSubtitles() } }
                launch { CaptionBus.partial.collect { schedulePreviewRender() } }
                launch {
                    CaptionBus.envEvent.collect { event ->
                        envAlertJob?.cancel()
                        tvEnvAlert.visibility = if (event == null) View.GONE else View.VISIBLE
                        if (event != null) {
                            tvEnvAlert.text = event
                            envAlertJob = launch {
                                delay(4000)
                                CaptionBus.clearEnvEvent()
                            }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                CellularConsent.events.collect { CellularConsent.handle(this@MainActivity, it) }
            }
        }
    }

    private fun schedulePreviewRender() {
        if (!Settings.isPreviewEnabled(this)) return
        if (CaptionBus.partial.value.isEmpty()) {
            previewRenderJob?.cancel()
            renderSubtitles()
            return
        }
        if (previewRenderJob?.isActive == true) return
        previewRenderJob = lifecycleScope.launch {
            val remaining = Settings.getPreviewInterval(this@MainActivity).toLong() - (SystemClock.elapsedRealtime() - lastPreviewRender)
            if (remaining > 0) delay(remaining)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lastPreviewRender = SystemClock.elapsedRealtime()
                renderSubtitles()
            }
        }
    }

    private fun renderSubtitles(scrollToBottom: Boolean = false) {
        if (CaptionBus.status.value == EngineStatus.STARTING) {
            tvSubtitles.text = CaptionBus.startMsg.value.ifEmpty { getString(R.string.starting_hint) }
            return
        }
        val lines = CaptionBus.subtitles.value
        val partial = if (Settings.isPreviewEnabled(this) && Settings.getAsrMode(this) == Settings.ASR_REALTIME)
            CaptionBus.partial.value else ""
        if (lines.isEmpty() && partial.isEmpty()) tvSubtitles.setText(R.string.initial_hint)
        else {
            val text = SpannableStringBuilder()
            lines.forEachIndexed { index, line ->
                if (index > 0) text.append("\n")
                val start = text.length
                if (line.speaker != null) text.append("${line.speaker}：")
                text.append(line.text)
                text.setSpan(ForegroundColorSpan(if (line.color != 0) line.color else getColor(R.color.text_primary)),
                    start, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (partial.isNotEmpty()) {
                if (text.isNotEmpty()) text.append("\n")
                val start = text.length
                text.append(partial)
                text.setSpan(ForegroundColorSpan(getColor(R.color.accent)), start, text.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tvSubtitles.text = text
        }
        if (scrollToBottom) scrollSubtitles.post { scrollSubtitles.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
