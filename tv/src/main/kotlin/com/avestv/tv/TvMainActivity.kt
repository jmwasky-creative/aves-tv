package com.avestv.tv

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException

class TvMainActivity : AppCompatActivity() {

    private lateinit var prefs: TvPreferences
    private lateinit var webView: WebView

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ReceiverForegroundService.start(this)
        } else {
            Toast.makeText(this, "Android 8/9 电视需要存储权限才能写入相册", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TvPreferences(this)
        webView = WebView(this)
        setContentView(webView)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.defaultFocusHighlightEnabled = false
        }
        webView.setBackgroundColor(0xFF0B1220.toInt())
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                interceptAsset(Uri.parse(url))

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                interceptAsset(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.requestFocus(View.FOCUS_DOWN)
                view?.evaluateJavascript("window.avesFocus && window.avesFocus()", null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                showMessage(message, result)
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                showMessage(message, result)
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                showPrompt(message, defaultValue, result)
                return true
            }
        }
        webView.addJavascriptInterface(TvBridge(), "AvesTvNative")
        ensurePermissionsAndStartService()
        webView.loadUrl(ASSET_PAGE)
        webView.requestFocus()
    }

    override fun onDestroy() {
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

    private fun showMessage(message: String?, result: JsResult) {
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

    private fun showPrompt(message: String?, defaultValue: String?, result: JsPromptResult) {
        val input = EditText(this).apply {
            setText(defaultValue.orEmpty())
            setSelection(text.length)
            hint = "例如：客厅电视"
        }
        var settled = false
        fun finish(value: String?) {
            if (settled) return
            settled = true
            if (value == null) result.cancel() else result.confirm(value)
        }
        AlertDialog.Builder(this)
            .setTitle(message ?: "修改电视名称")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> finish(input.text.toString()) }
            .setNegativeButton("取消") { _, _ -> finish(null) }
            .setOnCancelListener { finish(null) }
            .show()
        input.requestFocus()
    }

    private fun ensurePermissionsAndStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        ReceiverForegroundService.start(this)
    }

    private fun openAves(): String {
        val candidates = listOf("deckers.thibault.aves", "deckers.thibault.aves.libre")
        for (pkg in candidates) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                return ""
            }
        }
        val message = "电视上尚未安装原版 Aves 相册，请从 F-Droid 或 GitHub 安装 Aves 后再打开"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return message
    }

    private inner class TvBridge {
        @android.webkit.JavascriptInterface
        fun runtimeJson(): String {
            val state = ReceiverForegroundService.state.value
            val freeLabel = if (state.freeBytes > 0) Formatter.formatFileSize(this@TvMainActivity, state.freeBytes) else "--"
            val ipText = if (state.localIps.isNotEmpty()) {
                state.localIps.joinToString(" / ") { "$it:${state.httpPort}" }
            } else {
                "未检测到局域网连接（请确认电视已连 Wi-Fi 或网线）"
            }
            return JSONObject().apply {
                put("running", state.running)
                put("deviceName", state.deviceName)
                put("statusText", if (state.running) "运行中 · 自动可发现" else "未运行")
                put("albumCount", state.albumCount)
                put("totalPhotos", state.totalPhotos)
                put("freeBytes", state.freeBytes)
                put("freeLabel", freeLabel)
                put("ipText", ipText)
                put("lastEvent", state.lastEvent)
                put("autoStart", prefs.autoStartEnabled)
                put("usbConnected", state.usbConnected)
                put("usbStatusText", state.usbStatusText)
            }.toString()
        }

        @android.webkit.JavascriptInterface
        fun rename(name: String) {
            prefs.deviceName = name
            ReceiverForegroundService.refresh(this@TvMainActivity)
        }

        @android.webkit.JavascriptInterface
        fun autoStartEnabled(): Boolean = prefs.autoStartEnabled

        @android.webkit.JavascriptInterface
        fun setAutoStart(enabled: Boolean) {
            prefs.autoStartEnabled = enabled
        }

        @android.webkit.JavascriptInterface
        fun openAves(): String {
            var message = ""
            val done = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                message = runCatching { this@TvMainActivity.openAves() }.getOrDefault("")
                done.countDown()
            }
            done.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return message
        }
    }

    companion object {
        private const val ASSET_PAGE = "http://appassets.androidplatform.net/assets/tv.html"
    }
}
