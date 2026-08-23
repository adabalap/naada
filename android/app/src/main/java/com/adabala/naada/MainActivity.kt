package com.adabala.naada

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class MainActivity : AppCompatActivity() {

    // internal, not private: NaadaMediaService needs this to push JS
    // transport commands in from the lock screen / headphone buttons.
    internal lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var mediaService: NaadaMediaService? = null
    private var serverReady = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mediaService = (binder as? NaadaMediaService.LocalBinder)?.service
            Log.d(TAG, "NaadaMediaService connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mediaService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        // Register with the Application object. NaadaMediaService looks the
        // activity up here to run JavaScript when a transport control is
        // pressed. Without this the lock-screen buttons do nothing.
        (application as? NaadaApplication)?.mainActivity = this

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        progressBar = findViewById(R.id.progress_bar)

        setupWebView()

        // 1. Start the Flask server in its own process
        startService(Intent(this, FlaskServerService::class.java))

        // 2. Start (and bind to) the media session service so JS can push
        //    track metadata into it for the native media notification
        val mediaIntent = Intent(this, NaadaMediaService::class.java)
        startForegroundService(mediaIntent)
        bindService(mediaIntent, serviceConnection, BIND_AUTO_CREATE)

        // 3. Give Flask ~2 s to start, then load the UI
        webView.postDelayed({ loadApp() }, 2000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage for user data
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false  // autoplay must work
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }

        // JavaScript bridge: the web layer calls these to push track info
        // into the native MediaSession (the thing that controls the
        // notification badge, lock-screen controls, and Bluetooth metadata)
        webView.addJavascriptInterface(NaadaBridge(), "NaadaBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                // Inject the glue code that connects JS MediaSession calls
                // to our native service
                injectBridgeScript()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                                         error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    // Flask not ready yet — retry in 1 s
                    webView.postDelayed({ loadApp() }, 1000)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Allow microphone if Trivia AI needs it in future
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
    }

    private fun loadApp() {
        progressBar.visibility = View.VISIBLE
        webView.loadUrl("http://localhost:5000/static/index.html")
    }

    /**
     * Inject a small JS snippet that intercepts the existing MediaSession
     * calls in app.js and mirrors them to the native Android service.
     * This means we do NOT need to change app.js at all — the bridge sits
     * underneath and listens.
     */
    /**
     * Tell the web layer that a native bridge is available.
     *
     * An earlier version monkey-patched MediaSession.prototype from here to
     * intercept metadata writes. That was fragile — it depended on internal
     * property descriptors and broke silently if the browser changed them.
     * Instead app.js checks for window.NaadaBridge and calls it directly
     * from updateMediaSession(), which is explicit and cannot silently fail.
     */
    private fun injectBridgeScript() {
        webView.evaluateJavascript(
            "window.__naadaNative = true;" +
            "window.dispatchEvent(new Event('naada-native-ready'));", null)
    }

    /** JavaScript-accessible interface exposed as window.NaadaBridge */
    inner class NaadaBridge {
        @JavascriptInterface
        fun onTrackChange(title: String, artist: String, album: String, artUrl: String) {
            mediaService?.updateMetadata(title, artist, album, artUrl)
        }

        @JavascriptInterface
        fun onPlaybackState(isPlaying: Boolean) {
            mediaService?.updatePlaybackState(isPlaying)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if ((application as? NaadaApplication)?.mainActivity === this) {
            (application as? NaadaApplication)?.mainActivity = null
        }
        unbindService(serviceConnection)
        webView.destroy()
    }

    companion object {
        private const val TAG = "NaadaMain"
    }
}
