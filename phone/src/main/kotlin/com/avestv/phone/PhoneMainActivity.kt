package com.avestv.phone

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.avestv.protocol.DebugLogStore
import com.avestv.protocol.DiscoveryProtocol
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class PhoneMainActivity : AppCompatActivity() {

    private lateinit var logStore: DebugLogStore
    private lateinit var apiClient: TvApiClient
    private lateinit var scanner: TvLanScanner
    private lateinit var transcoder: ImageTranscoder
    private lateinit var usbAccessoryManager: UsbAoaAccessoryManager
    private lateinit var webView: WebView

    private val discoveredTvs = LinkedHashMap<String, DiscoveredTv>()
    private val tvsLock = Any()
    private val shareLock = Any()
    private var selectedTv: DiscoveredTv? = null
    private var pendingSharedUris: List<Uri> = emptyList()
    private var scanJob: Job? = null
    private var uploading = false
    private var pendingAlbum = "家庭相册"
    private var pendingReplace = false
    private var pendingOptimize = true
    private var pendingFromShare = false
    private var lastUsbConnected: Boolean? = null
    private var usbReconnectJob: Job? = null

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_STATE) return
            val connected = intent.getBooleanExtra("connected", false)
            val configured = intent.getBooleanExtra("configured", false)
            val prev = lastUsbConnected
            lastUsbConnected = connected
            val action = UsbReconnectPolicy.actionForUsbState(
                previousConnected = prev,
                connected = connected,
                configured = configured,
                isRetrying = usbReconnectJob?.isActive == true,
            )
            when (action) {
                UsbReconnectPolicy.UsbStateAction.HANDLE_DISCONNECT -> {
                    cancelUsbReconnect()
                    if (!uploading) {
                        handleUsbDisconnected()
                    }
                }
                UsbReconnectPolicy.UsbStateAction.START_RECONNECT -> {
                    if (!uploading) {
                        startUsbReconnect()
                    }
                }
                UsbReconnectPolicy.UsbStateAction.CHECK_ACCESSORIES_ONLY -> {
                    if (!uploading) {
                        usbAccessoryManager.checkConnectedAccessories()
                    }
                }
                UsbReconnectPolicy.UsbStateAction.NONE -> Unit
            }
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uploadUris(uris, pendingAlbum, pendingReplace, pendingOptimize, pendingFromShare)
        }
    }

    private val fallbackPickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = buildList {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { add(it) }
                }
            } else {
                data.data?.let { add(it) }
            }
        }
        if (uris.isNotEmpty()) {
            uploadUris(uris, pendingAlbum, pendingReplace, pendingOptimize, pendingFromShare)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logStore = PhoneLogHolder.get(this)
        apiClient = TvApiClient(logStore)
        scanner = TvLanScanner(this, apiClient, logStore)
        transcoder = ImageTranscoder(this)
        usbAccessoryManager = UsbAoaAccessoryManager(this, logStore) { localPort ->
            runOnUiThread {
                if (localPort != null) {
                    if (!uploading) {
                        startUsbReconnect()
                    }
                } else {
                    cancelUsbReconnect()
                    if (!uploading) {
                        handleUsbDisconnected()
                    }
                }
            }
        }
        webView = WebView(this)
        setContentView(webView)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                interceptAsset(Uri.parse(url))

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptAsset(request.url)
        }
        webView.webChromeClient = dialogChromeClient()
        webView.addJavascriptInterface(PhoneBridge(), "AvesTvNative")
        handleIncomingShareIntent(intent)
        usbAccessoryManager.start()
        usbAccessoryManager.handleIntent(intent)
        ContextCompat.registerReceiver(
            this,
            usbStateReceiver,
            IntentFilter(ACTION_USB_STATE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        webView.loadUrl(ASSET_PAGE)
    }

    override fun onResume() {
        super.onResume()
        usbAccessoryManager.checkConnectedAccessories()
        if (!uploading) {
            val shouldReconnectUsb = UsbReconnectPolicy.isLoopbackTv(selectedTv) ||
                (selectedTv == null && (lastUsbConnected == true || usbAccessoryManager.activeBridgePort != null))
            if (shouldReconnectUsb) {
                startUsbReconnect()
            } else if (selectedTv == null) {
                startAutoDiscovery(forceFullSubnetScan = false)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
        usbAccessoryManager.handleIntent(intent)
        emitShared()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        cancelUsbReconnect()
        runCatching { unregisterReceiver(usbStateReceiver) }
        usbAccessoryManager.stop()
        webView.removeJavascriptInterface("AvesTvNative")
        webView.destroy()
        super.onDestroy()
    }

    private fun interceptAsset(uri: Uri): WebResourceResponse? {
        if (!uri.host.equals("appassets.androidplatform.net", ignoreCase = true)) return null
        val relative = uri.path.orEmpty().removePrefix("/").removePrefix("assets/").trimStart('/')
        if (relative.isEmpty() || relative.contains("..") || relative.contains('\\')) return null
        val mime = when (relative.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
        val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("xml")) "utf-8" else null
        return try {
            WebResourceResponse(mime, encoding, assets.open(relative))
        } catch (_: IOException) {
            null
        }
    }

    private fun dialogChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            showJsResult(message, result)
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
            showJsResult(message, result)
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val lvl = consoleMessage.messageLevel()
            if (lvl == ConsoleMessage.MessageLevel.ERROR || lvl == ConsoleMessage.MessageLevel.WARNING) {
                val levelStr = if (lvl == ConsoleMessage.MessageLevel.ERROR) "ERROR" else "WARN"
                logStore.log(levelStr, "WEB", "${consoleMessage.message()} (L${consoleMessage.lineNumber()})")
            }
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private fun showJsResult(message: String?, result: JsResult) {
        var settled = false
        fun finish(ok: Boolean) {
            if (settled) return
            settled = true
            if (ok) result.confirm() else result.cancel()
        }
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> finish(true) }
            .setNegativeButton("取消") { _, _ -> finish(false) }
            .setOnCancelListener { finish(false) }
            .show()
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val incoming = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) incoming.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) incoming.addAll(uris)
            }
        }
        if (incoming.isNotEmpty()) {
            synchronized(shareLock) { pendingSharedUris = incoming }
        }
    }

    private fun takeShared(): List<Uri> = synchronized(shareLock) {
        val batch = pendingSharedUris
        pendingSharedUris = emptyList()
        batch
    }

    private fun sharedSize(): Int = synchronized(shareLock) { pendingSharedUris.size }

    private fun cancelUsbReconnect() {
        usbReconnectJob?.cancel()
        usbReconnectJob = null
    }

    private fun handleUsbDisconnected() {
        val result = synchronized(tvsLock) {
            val cleared = UsbReconnectPolicy.clearLoopbackOnDisconnect(
                discovered = discoveredTvs.values.toList(),
                selected = selectedTv,
            )
            if (cleared.clearedLoopback) {
                discoveredTvs.clear()
                for (tv in cleared.remainingTvs) {
                    discoveredTvs[tv.info.deviceId] = tv
                }
            }
            cleared
        }
        selectedTv = result.selectedTv
        if (result.clearedLoopback) {
            val preferredId = result.selectedTv?.info?.deviceId ?: scanner.lastConnectedDeviceId().orEmpty()
            publishScan(done = true, preferredId = preferredId)
            startAutoDiscovery(forceFullSubnetScan = false)
        }
    }

    private fun startUsbReconnect() {
        cancelUsbReconnect()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                for (attempt in 0 until UsbReconnectPolicy.MAX_RETRY_ATTEMPTS) {
                    if (uploading || !isActive) break
                    usbAccessoryManager.checkConnectedAccessories()
                    val usbTvs = scanner.probeUsb(usbAccessoryManager.activeBridgePort)
                    if (usbTvs.isNotEmpty()) {
                        scanJob?.cancel()
                        synchronized(tvsLock) {
                            val merged = UsbReconnectPolicy.mergeDiscovered(discoveredTvs, usbTvs)
                            discoveredTvs.clear()
                            discoveredTvs.putAll(merged)
                        }
                        publishScan(done = true, preferredId = usbTvs.first().info.deviceId)
                        selectedTv?.let { scanner.rememberTv(it) }
                        break
                    }

                    if (UsbReconnectPolicy.isStaleLoopbackSelection(selectedTv, usbTvs)) {
                        val cleared = synchronized(tvsLock) {
                            val res = UsbReconnectPolicy.clearLoopbackOnDisconnect(
                                discovered = discoveredTvs.values.toList(),
                                selected = selectedTv,
                            )
                            discoveredTvs.clear()
                            for (tv in res.remainingTvs) {
                                discoveredTvs[tv.info.deviceId] = tv
                            }
                            res
                        }
                        selectedTv = cleared.selectedTv
                        publishScan(
                            done = true,
                            preferredId = cleared.selectedTv?.info?.deviceId ?: scanner.lastConnectedDeviceId().orEmpty(),
                        )
                    }

                    if (attempt == 0 && selectedTv == null && scanJob?.isActive != true) {
                        startAutoDiscovery(forceFullSubnetScan = false)
                    }

                    val hasNext = UsbReconnectPolicy.shouldScheduleNextRetry(
                        nextAttempt = attempt + 1,
                        foundUsbTvs = usbTvs,
                        usbPhysicallyConnected = lastUsbConnected == true,
                        hasAoaBridge = usbAccessoryManager.activeBridgePort != null,
                        selectedTv = selectedTv,
                    )
                    if (!hasNext) break
                    delay(UsbReconnectPolicy.RETRY_INTERVAL_MS)
                }
            } finally {
                if (usbReconnectJob == currentJob) {
                    usbReconnectJob = null
                }
            }
        }
        usbReconnectJob = job
        job.start()
    }

    private fun startAutoDiscovery(forceFullSubnetScan: Boolean) {
        scanJob?.cancel()
        synchronized(tvsLock) { discoveredTvs.clear() }
        val preferredId = selectedTv?.info?.deviceId ?: scanner.lastConnectedDeviceId().orEmpty()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            val all = scanner.scan(
                forceFullSubnetScan = forceFullSubnetScan,
                usbBridgePort = usbAccessoryManager.activeBridgePort,
            ) { tv ->
                runOnUiThread {
                    if (scanJob != currentJob) return@runOnUiThread
                    synchronized(tvsLock) {
                        val merged = UsbReconnectPolicy.mergeDiscovered(discoveredTvs, listOf(tv))
                        discoveredTvs.clear()
                        discoveredTvs.putAll(merged)
                    }
                    if (UsbReconnectPolicy.isLoopbackTv(tv)) {
                        cancelUsbReconnect()
                    }
                    publishScan(done = false, preferredId = preferredId)
                }
            }
            if (scanJob != currentJob) return@launch
            synchronized(tvsLock) {
                val merged = UsbReconnectPolicy.mergeDiscovered(discoveredTvs, all)
                discoveredTvs.clear()
                discoveredTvs.putAll(merged)
            }
            if (all.any { UsbReconnectPolicy.isLoopbackTv(it) }) {
                cancelUsbReconnect()
            }
            if (all.isEmpty()) selectedTv = null
            publishScan(done = true, preferredId = preferredId)
        }
        scanJob = job
        job.start()
    }

    private fun publishScan(done: Boolean, preferredId: String) {
        val arr = JSONArray()
        val snapshot = synchronized(tvsLock) { discoveredTvs.values.toList() }
        val resolved = UsbReconnectPolicy.resolveSelection(snapshot, selectedTv, preferredId)
        selectedTv = resolved
        val effectivePreferredId = resolved?.info?.deviceId ?: preferredId
        snapshot.forEach { tv ->
            arr.put(JSONObject().apply {
                put("deviceId", tv.info.deviceId)
                put("deviceName", tv.info.deviceName)
                put("ip", tv.ip)
                put("httpPort", tv.port)
                put("discoverySource", tv.discoverySource)
                put("freeBytes", tv.info.freeBytes)
                put("albumCount", tv.info.albumCount)
                put("totalPhotos", tv.info.totalPhotos)
            })
        }
        val payload = JSONObject().apply {
            put("tvs", arr)
            put("done", done)
            put("preferredId", effectivePreferredId)
            put("phoneIps", JSONArray(scanner.localLanIps()))
        }
        emit("avesOnScan", payload.toString())
    }

    private fun launchPhotoPicker() {
        val launchedPicker = runCatching {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            true
        }.getOrDefault(false)
        if (launchedPicker) return

        val launchedDocument = runCatching {
            val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            fallbackPickLauncher.launch(openDoc)
            true
        }.getOrDefault(false)
        if (launchedDocument) return

        runCatching {
            val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            fallbackPickLauncher.launch(Intent.createChooser(getContent, "选择照片"))
        }.onFailure {
            Toast.makeText(this, "无法打开系统相册选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadUris(
        uris: List<Uri>,
        albumRaw: String,
        replaceWholeAlbum: Boolean,
        optimize4k: Boolean,
        fromShare: Boolean,
    ) {
        val tv = selectedTv
        if (tv == null) {
            emitUpload("done", 0, uris.size, "尚未连接到电视，请先确认电视与手机在同一局域网", fromShare)
            return
        }
        val albumName = DiscoveryProtocol.sanitizeAlbumName(albumRaw)
        uploading = true
        emitUpload("start", 0, uris.size, "准备推送 ${uris.size} 张照片到「$albumName」...", false)

        lifecycleScope.launch {
            var succeeded = 0
            var failed = 0
            var lastError: String? = null
            for ((idx, uri) in uris.withIndex()) {
                emitUpload("progress", idx + 1, uris.size, "正在推送 ${idx + 1} / ${uris.size}...", false)
                val mode = UploadPlanner.modeFor(replaceWholeAlbum, idx)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        transcoder.prepare(uri, optimizeFor4k = optimize4k).use { prepared ->
                            apiClient.uploadPhoto(tv, albumName, prepared, mode)
                        }
                    }
                }
                if (result.isSuccess) {
                    succeeded++
                } else {
                    failed++
                    lastError = result.exceptionOrNull()?.message
                }
            }
            uploading = false
            val message = if (failed == 0) {
                "推送完成：已写入 $succeeded 张到电视「$albumName」，原版 Aves 已自动更新"
            } else {
                "推送完成：成功 $succeeded 张，失败 $failed 张（${lastError ?: "网络中断"}）"
            }
            emitUpload("done", uris.size, uris.size, message, fromShare)
        }
    }

    private fun emitUpload(phase: String, index: Int, total: Int, message: String, sharedCleared: Boolean) {
        val payload = JSONObject().apply {
            put("phase", phase)
            put("index", index)
            put("total", total)
            put("message", message)
            put("sharedCleared", sharedCleared)
        }
        emit("avesOnUpload", payload.toString())
    }

    private fun emitShared() {
        val count = sharedSize()
        webView.post {
            webView.evaluateJavascript("window.avesOnShared && window.avesOnShared($count)", null)
        }
    }

    private fun emit(function: String, json: String) {
        val arg = JSONObject.quote(json)
        webView.post {
            webView.evaluateJavascript("window.$function && window.$function($arg)", null)
        }
    }

    private inner class PhoneBridge {
        @android.webkit.JavascriptInterface
        fun requestScan(force: Boolean) {
            runOnUiThread {
                if (uploading) return@runOnUiThread
                startAutoDiscovery(force)
            }
        }

        @android.webkit.JavascriptInterface
        fun remember(deviceId: String) {
            val tv = synchronized(tvsLock) { discoveredTvs[deviceId] } ?: return
            runOnUiThread {
                selectedTv = tv
                scanner.rememberTv(tv)
            }
        }

        @android.webkit.JavascriptInterface
        fun sharedCount(): Int = sharedSize()

        @android.webkit.JavascriptInterface
        fun clearShared() {
            takeShared()
            emitShared()
        }

        @android.webkit.JavascriptInterface
        fun connectManual(rawAddress: String) {
            runOnUiThread {
                if (uploading) return@runOnUiThread
                scanJob?.cancel()
                cancelUsbReconnect()
                lifecycleScope.launch {
                    val tv = scanner.connectByIp(rawAddress)
                    if (tv != null) {
                        synchronized(tvsLock) { discoveredTvs[tv.info.deviceId] = tv }
                        selectedTv = tv
                        publishScan(done = true, preferredId = tv.info.deviceId)
                    } else {
                        val payload = JSONObject().apply {
                            put("message", "无法连接到 ${rawAddress.trim()}，请确认电视端已运行且与手机在同一局域网")
                        }
                        emit("avesOnConnectError", payload.toString())
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun pickAndUpload(album: String, replace: Boolean, optimize: Boolean) {
            runOnUiThread {
                if (uploading) return@runOnUiThread
                if (selectedTv == null) {
                    selectedTv = synchronized(tvsLock) { discoveredTvs.values.firstOrNull() }
                }
                if (selectedTv == null) {
                    val msg = "尚未连接到电视，请先确认电视与手机在同一局域网"
                    Toast.makeText(this@PhoneMainActivity, msg, Toast.LENGTH_SHORT).show()
                    emitUpload("done", 0, 0, msg, false)
                    return@runOnUiThread
                }
                pendingAlbum = album
                pendingReplace = replace
                pendingOptimize = optimize
                pendingFromShare = false
                launchPhotoPicker()
            }
        }

        @android.webkit.JavascriptInterface
        fun uploadShared(album: String, replace: Boolean, optimize: Boolean) {
            runOnUiThread {
                if (uploading) return@runOnUiThread
                val batch = takeShared()
                emitShared()
                if (batch.isEmpty()) return@runOnUiThread
                uploadUris(batch, album, replace, optimize, fromShare = true)
            }
        }

        @android.webkit.JavascriptInterface
        fun phoneLogsJson(limit: Int): String =
            DiscoveryProtocol.json.encodeToString(logStore.entries(limit))

        @android.webkit.JavascriptInterface
        fun clearPhoneLogs(): Int = logStore.clear()

        @android.webkit.JavascriptInterface
        fun logClient(level: String, tag: String, message: String) {
            logStore.log(level, tag, message)
        }

        @android.webkit.JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("AvesTV 日志", text))
                Toast.makeText(this@PhoneMainActivity, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ASSET_PAGE = "http://appassets.androidplatform.net/assets/phone.html"
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}
