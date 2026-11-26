package com.avestv.phone

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.avestv.protocol.DiscoveryProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class PhoneMainActivity : AppCompatActivity() {

    private val apiClient = TvApiClient()
    private lateinit var scanner: TvLanScanner
    private lateinit var transcoder: ImageTranscoder
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

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uploadUris(uris, pendingAlbum, pendingReplace, pendingOptimize, pendingFromShare)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = TvLanScanner(this, apiClient)
        transcoder = ImageTranscoder(this)
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
        webView.loadUrl(ASSET_PAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
        emitShared()
    }

    override fun onDestroy() {
        scanJob?.cancel()
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

    private fun startAutoDiscovery(forceFullSubnetScan: Boolean) {
        scanJob?.cancel()
        synchronized(tvsLock) { discoveredTvs.clear() }
        val preferredId = selectedTv?.info?.deviceId ?: scanner.lastConnectedDeviceId().orEmpty()
        scanJob = lifecycleScope.launch {
            val all = scanner.scan(forceFullSubnetScan = forceFullSubnetScan) { tv ->
                runOnUiThread {
                    synchronized(tvsLock) { discoveredTvs[tv.info.deviceId] = tv }
                    publishScan(done = false, preferredId = preferredId)
                }
            }
            all.forEach { tv -> synchronized(tvsLock) { discoveredTvs[tv.info.deviceId] = tv } }
            if (all.isEmpty()) selectedTv = null
            publishScan(done = true, preferredId = preferredId)
        }
    }

    private fun publishScan(done: Boolean, preferredId: String) {
        val arr = JSONArray()
        val snapshot = synchronized(tvsLock) { discoveredTvs.values.toList() }
        snapshot.forEach { tv ->
            arr.put(JSONObject().apply {
                put("deviceId", tv.info.deviceId)
                put("deviceName", tv.info.deviceName)
                put("ip", tv.ip)
                put("httpPort", tv.info.httpPort)
                put("discoverySource", tv.discoverySource)
                put("freeBytes", tv.info.freeBytes)
                put("albumCount", tv.info.albumCount)
                put("totalPhotos", tv.info.totalPhotos)
            })
        }
        val payload = JSONObject().apply {
            put("tvs", arr)
            put("done", done)
            put("preferredId", preferredId)
        }
        emit("avesOnScan", payload.toString())
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
        fun pickAndUpload(album: String, replace: Boolean, optimize: Boolean) {
            runOnUiThread {
                if (uploading) return@runOnUiThread
                if (selectedTv == null) {
                    Toast.makeText(this@PhoneMainActivity, "尚未连接到电视，请先确认电视与手机在同一局域网", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pendingAlbum = album
                pendingReplace = replace
                pendingOptimize = optimize
                pendingFromShare = false
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
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
    }

    companion object {
        private const val ASSET_PAGE = "http://appassets.androidplatform.net/assets/phone.html"
    }
}
