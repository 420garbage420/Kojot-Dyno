package com.kojot.dyno

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

/**
 * Host activity: binds [DynoService], injects [NativeBridge] as `window.Dyno`, loads the SPA from assets.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var service: DynoService? = null
    private var bridge: NativeBridge? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as DynoService.LocalBinder).getService()
            service = svc
            val b = NativeBridge(
                engine = svc.engine,
                scope = lifecycleScope,
                webViewProvider = { if (::webView.isInitialized) webView else null },
                onNotificationText = { svc.updateNotification(it) },
            )
            b.bindEvents()
            bridge = b
            webView.addJavascriptInterface(b, "Dyno")
            webView.loadUrl("file:///android_asset/index.html")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bridge = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* SPA will surface scan failures if storage still denied */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).also { setContentView(it) }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        requestRuntimePermissions()
        DynoService.start(this)
        bindService(Intent(this, DynoService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // Android 13+: manage storage via SAF or all-files; for /Download/Local_Models many OEMs
        // still allow MediaStore / path access for the app's own scans when the dir is user-visible.
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Throwable) {}
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Dyno")
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
