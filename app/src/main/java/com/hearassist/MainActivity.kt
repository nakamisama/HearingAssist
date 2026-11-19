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
import android.text.format.Formatter
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hearassist.data.AppSessionController
import com.hearassist.data.CaptionBus
import com.hearassist.data.CellularConsent
import com.hearassist.data.CancellableHttp
import com.hearassist.data.DownloadProgressMeter
import com.hearassist.data.EngineStatus
import com.hearassist.data.PendingApk
import com.hearassist.data.ReleaseInfo
import com.hearassist.data.SessionPolicy
import com.hearassist.data.Settings
import com.hearassist.data.UpdateChannels
import com.hearassist.data.VoiceprintStore
import com.hearassist.ui.CaptionScrollPolicy
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
    private lateinit var btnLatest: Button
    private val scrollPolicy = CaptionScrollPolicy()
    private var scrollPending = false
    private var scrollListener: OneShotPreDrawListener? = null
    private var userScrollActive = false
    private var touchingCaptions = false
    private var autoScrolling = false
    private var touchStartY = 0f
    private var renderAfterTouch = false
    private var renderedLines = emptyList<CaptionBus.SubtitleLine>()
    private var renderedStarts = IntArray(0)
    private var pendingAnchor: CaptionAnchor? = null
    private data class CaptionAnchor(val line: CaptionBus.SubtitleLine, val offset: Int, val pixels: Int)
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
        btnLatest = findViewById(R.id.btnLatest)
        configureCaptionScrolling()
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

    override fun onStop() {
        scrollListener?.removeListener()
        scrollListener = null
        scrollPending = false
        touchingCaptions = false
        userScrollActive = false
        super.onStop()
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
            } else if (latest.isNewerThan(BuildConfig.VERSION_CODE)) {
                updateDialog?.dismiss()
                updateDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(getString(R.string.update_message, latest.versionName))
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

    private fun confirmDownload(latest: ReleaseInfo) {
        if (wifiNetwork() == null) {
            Toast.makeText(this, R.string.update_wifi_only, Toast.LENGTH_LONG).show()
            return
        }
        val ticket = AppSessionController.visibilityTicket()
        updateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_confirm_title)
            .setMessage(getString(R.string.update_confirm_message, latest.versionName))
            .setPositiveButton(R.string.update_confirm_yes) { _, _ ->
                if (visible(ticket)) downloadAndInstall(latest)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstall(latest: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val network = wifiNetwork() ?: run {
            Toast.makeText(this, R.string.update_wifi_only, Toast.LENGTH_LONG).show()
            return
        }
        val ticket = AppSessionController.visibilityTicket()
        val progress = ProgressDialog(this).apply {
            setTitle(R.string.update_downloading)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMessage(getString(R.string.update_connecting_source, "gh-proxy.com", 1, 3))
            isIndeterminate = true
            max = 100
            setCancelable(true)
            setButton(ProgressDialog.BUTTON_NEGATIVE, getString(R.string.common_cancel)) { _, _ -> downloadJob?.cancel() }
            setOnCancelListener { downloadJob?.cancel() }
            show()
        }
        downloadProgress = progress
        downloadJob = lifecycleScope.launch {
            try {
                val file = downloadApk(latest, network, ticket) { message, percent ->
                    runOnUiThread {
                        if (visible(ticket) && downloadProgress === progress && downloadJob?.isActive == true) {
                            progress.isIndeterminate = percent == null
                            if (percent != null) progress.progress = percent
                            progress.setMessage(message)
                        }
                    }
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
        connectTimeout: Int = 10000,
        readTimeout: Int = 30000,
        block: suspend (HttpURLConnection) -> T,
    ): T {
        var url = original
        repeat(6) {
            try {
                return CancellableHttp.withConnection(url, network, connectTimeout, readTimeout, block)
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
        latest: ReleaseInfo,
        network: Network,
        ticket: SessionPolicy.VisibilityTicket,
        onState: (String, Int?) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext null
        val output = File(root, "update-${latest.versionCode}.apk")
        if (output.exists() && validUpdate(output, latest)) return@withContext output
        val urls = UpdateChannels.apkUrls(latest)
        for ((index, url) in urls.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!AppSessionController.isVisible(ticket) || wifiNetwork() != network) return@withContext null
            val source = URL(url).host
            onState(getString(R.string.update_connecting_source, source, index + 1, urls.size), null)
            var pending: PendingApk? = null
            try {
                val download = PendingApk(root, latest.versionCode).also { pending = it }
                val temporary = download.file
                // 源端无响应时尽快切备用；没有明确总大小也显示已收字节，而不是假0%。
                withRedirects(url, network, connectTimeout = 5000, readTimeout = 10000) { connection ->
                    val length = connection.contentLengthLong
                    if (length > root.usableSpace) throw IOException("存储空间不足")
                    if (connection.contentType?.startsWith("text/html", ignoreCase = true) == true) {
                        throw IOException("更新源返回网页而非安装包")
                    }
                    onState(getString(if (length > 0) R.string.update_receiving_source
                        else R.string.update_receiving_unknown_size, source), if (length > 0) 0 else null)
                    val meter = DownloadProgressMeter { SystemClock.elapsedRealtime() }
                    var received = 0L
                    fun showSample(sample: DownloadProgressMeter.Snapshot) {
                        val downloaded = Formatter.formatShortFileSize(this@MainActivity, sample.received)
                        val speed = Formatter.formatShortFileSize(this@MainActivity, sample.bytesPerSecond)
                        val message = if (sample.total > 0) {
                            getString(R.string.update_transfer_details, source, downloaded,
                                Formatter.formatShortFileSize(this@MainActivity, sample.total), speed)
                        } else getString(R.string.update_transfer_unknown_total, source, downloaded, speed)
                        onState(message, sample.percent)
                    }
                    FileOutputStream(temporary).use { stream ->
                        CancellableHttp.copy(connection.inputStream, stream, length,
                            checkTransfer = {
                                if (!AppSessionController.isVisible(ticket)) throw CancellationException("应用已进入后台")
                                if (wifiNetwork() != network) throw IOException("WiFi 已断开，停止下载")
                            }, onBytes = { bytes, total ->
                                received = bytes
                                meter.sample(bytes, total)?.let(::showSample)
                            })
                        stream.fd.sync()
                    }
                    meter.sample(received, length, finished = true)?.let(::showSample)
                }
                currentCoroutineContext().ensureActive()
                onState(getString(R.string.update_verifying_apk), null)
                if (!validUpdate(temporary, latest)) throw IOException("安装包校验失败")
                currentCoroutineContext().ensureActive()
                if (!AppSessionController.isVisible(ticket)) throw CancellationException("应用已进入后台")
                return@withContext download.publish(output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w("AppUpdate", "更新源未完成：$source", e)
                if (index < urls.lastIndex) onState(getString(R.string.update_switching_source), null)
            } finally {
                pending?.close()
            }
        }
        null
    }

    @Suppress("DEPRECATION")
    private fun validUpdate(file: File, release: ReleaseInfo): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val update = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (update.packageName != packageName) return false
        val version = if (Build.VERSION.SDK_INT >= 28) update.longVersionCode else update.versionCode.toLong()
        if (version != release.versionCode.toLong() || version <= BuildConfig.VERSION_CODE) return false
        if (!release.legacy && update.versionName != release.versionName) return false
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

    private suspend fun fetchLatestVersion(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (source in UpdateChannels.manifestUrls()) {
            try {
                return@withContext ReleaseInfo.parse(readReleaseText(source))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        // 旧 API 只对含 Android 整数编号的 tag 有依据，不能从 bate 名称猜整数版本。
        try {
            val text = CancellableHttp.readText("https://api.github.com/repos/nakamisama/HearingAssist/releases/latest")
            ReleaseInfo.fromLegacyTag(JSONObject(text).getString("tag_name"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { null }
    }

    private suspend fun readReleaseText(source: String): String = withRedirects(source) { connection ->
        val output = java.io.ByteArrayOutputStream()
        if (connection.contentLengthLong > 65536) throw IOException("版本清单过大")
        CancellableHttp.copy(connection.inputStream, output, connection.contentLengthLong, {
            if (output.size() > 65536) throw IOException("版本清单过大")
        })
        if (output.size() > 65536) throw IOException("版本清单过大")
        output.toString("UTF-8")
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
        view.findViewById<TextView>(R.id.tvVersion).text = getString(R.string.current_version, BuildConfig.VERSION_NAME)
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

    @Suppress("ClickableViewAccessibility")
    private fun configureCaptionScrolling() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        scrollSubtitles.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.y
                    touchingCaptions = true
                    userScrollActive = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.y - touchStartY) > slop) userScrollActive = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchingCaptions = false
                    if (renderAfterTouch) {
                        renderAfterTouch = false
                        renderSubtitles()
                    } else requestCaptionScroll()
                }
            }
            // ScrollView 本身处理点击/拖动；这里只记录用户回看，不吞掉事件。
            false
        }
        scrollSubtitles.setOnScrollChangeListener { _, _, y, _, _ ->
            if (userScrollActive && !autoScrolling && (touchingCaptions || !scrollPending)) {
                val child = scrollSubtitles.getChildAt(0)
                val maximum = (child.height - scrollSubtitles.height +
                    scrollSubtitles.paddingTop + scrollSubtitles.paddingBottom).coerceAtLeast(0)
                scrollPolicy.onUserScroll(y, maximum, (24 * resources.displayMetrics.density).toInt())
                if (scrollPolicy.followLatest && !touchingCaptions) userScrollActive = false
                btnLatest.visibility = if (scrollPolicy.followLatest) View.GONE else View.VISIBLE
            }
        }
        btnLatest.setOnClickListener {
            userScrollActive = false
            pendingAnchor = null
            scrollPolicy.resume()
            btnLatest.visibility = View.GONE
            requestCaptionScroll()
        }
        scrollSubtitles.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestCaptionScroll() }
    }

    /** 布局后跟随，或恢复回看锚点；不以旧 TextView 高度计算滚动距离。 */
    private fun requestCaptionScroll() {
        if (scrollPending || touchingCaptions || isDestroyed) return
        if (!scrollPolicy.followLatest && pendingAnchor == null) return
        scrollPending = true
        scrollListener = scrollSubtitles.doOnPreDraw {
            scrollPending = false
            scrollListener = null
            if (!touchingCaptions && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                val child = scrollSubtitles.getChildAt(0)
                val bottom = (child.height - scrollSubtitles.height +
                    scrollSubtitles.paddingTop + scrollSubtitles.paddingBottom).coerceAtLeast(0)
                val anchor = pendingAnchor
                pendingAnchor = null
                autoScrolling = true
                try {
                    if (scrollPolicy.followLatest && !touchingCaptions) {
                        scrollSubtitles.scrollTo(0, bottom)
                        // 在最终位置取消旧 fling，避免点击“回到最新”后又被惯性带走。
                        scrollSubtitles.fling(0)
                    } else if (!scrollPolicy.followLatest && anchor != null) {
                        val layout = tvSubtitles.layout
                        if (layout != null) {
                            val index = renderedLines.indexOfFirst { it === anchor.line }
                            val offset = if (index >= 0) {
                                (renderedStarts[index] + anchor.offset).coerceIn(0, tvSubtitles.length())
                            } else 0 // 回看的行已从200条历史中淘汰，停在最早仍保留的行。
                            val row = layout.getLineForOffset(offset)
                            val y = tvSubtitles.top + tvSubtitles.totalPaddingTop + layout.getLineTop(row) + anchor.pixels
                            scrollSubtitles.scrollTo(0, y.coerceIn(0, bottom))
                            scrollSubtitles.fling(0)
                        }
                    }
                } finally { autoScrolling = false }
            }
        }
        scrollSubtitles.invalidate()
    }

    private fun captionAnchor(): CaptionAnchor? {
        if (scrollPolicy.followLatest || renderedLines.isEmpty()) return null
        val layout = tvSubtitles.layout ?: return null
        val y = (scrollSubtitles.scrollY - tvSubtitles.top - tvSubtitles.totalPaddingTop).coerceAtLeast(0)
        val row = layout.getLineForVertical(y)
        val offset = layout.getLineStart(row)
        val index = renderedStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
        if (index !in renderedLines.indices) return null
        return CaptionAnchor(renderedLines[index], offset - renderedStarts[index], y - layout.getLineTop(row))
    }

    private fun renderSubtitles(scrollToBottom: Boolean = false) {
        if (scrollToBottom) previewRenderJob?.cancel()
        // 拖动回看时不重排文字；松手再渲染最新状态并恢复同一阅读锚点。
        if (touchingCaptions) {
            renderAfterTouch = true
            return
        }
        renderAfterTouch = false
        val anchor = pendingAnchor ?: captionAnchor()
        if (CaptionBus.status.value == EngineStatus.STARTING) {
            pendingAnchor = null
            renderedLines = emptyList()
            renderedStarts = IntArray(0)
            tvSubtitles.text = CaptionBus.startMsg.value.ifEmpty { getString(R.string.starting_hint) }
            requestCaptionScroll()
            return
        }
        val lines = CaptionBus.subtitles.value
        val partial = if (Settings.isPreviewEnabled(this) && Settings.getAsrMode(this) == Settings.ASR_REALTIME)
            CaptionBus.partial.value else ""
        val starts = IntArray(lines.size)
        if (lines.isEmpty() && partial.isEmpty()) tvSubtitles.setText(R.string.initial_hint)
        else {
            val text = SpannableStringBuilder()
            lines.forEachIndexed { index, line ->
                if (index > 0) text.append("\n")
                val start = text.length
                starts[index] = start
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
        renderedLines = lines
        renderedStarts = starts
        pendingAnchor = if (scrollPolicy.followLatest) null else anchor
        requestCaptionScroll()
    }
}
