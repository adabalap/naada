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
    private var recovering = false

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

        // 3. Wait for Flask to actually answer before loading the UI.
        //    A fixed delay was wrong: on first launch Chaquopy unpacks the
        //    whole Python stdlib and every pip package, which can take 15s+
        //    on a mid-range phone. Two seconds guaranteed ERR_CONNECTION_REFUSED.
        waitForServerThenLoad()
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
                recovering = false
                // Inject the glue code that connects JS MediaSession calls
                // to our native service
                injectBridgeScript()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?,
                                         error: WebResourceError?) {
                // Only react to the main document failing, and only once —
                // re-entering the wait loop here would stack pollers.
                if (request?.isForMainFrame == true && !recovering) {
                    recovering = true
                    Log.w(TAG, "Main frame failed to load; waiting for server again")
                    waitForServerThenLoad()
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
        webView.loadUrl("http://127.0.0.1:${FlaskServerService.PORT}/")
    }

    /**
     * Poll the local server until it answers, then load the UI.
     *
     * Runs off the main thread. Gives up after START_TIMEOUT_MS and shows a
     * real diagnostic page (including the Python traceback, if there was
     * one) rather than Chrome's generic "Web page not available", which
     * tells the user nothing actionable.
     */
    private fun waitForServerThenLoad() {
        // Uses an explicit Runnable rather than a lambda passed to the Thread
        // constructor: `return@Thread` inside a SAM-converted constructor
        // argument is not reliably valid Kotlin, and it is the kind of thing
        // that produces an opaque "Compilation error" with no useful message.
        val task = Runnable { pollForServer() }
        Thread(task, "naada-server-wait").apply { isDaemon = true }.start()
    }

    /** Blocks its thread until the server answers or the budget runs out. */
    private fun pollForServer() {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isServerUp()) {
                runOnUiThread { loadApp() }
                return
            }
            Log.d(TAG, "Waiting for local server… ${(deadline - System.currentTimeMillis()) / 1000}s left")
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        runOnUiThread { showStartupError() }
    }

    private fun isServerUp(): Boolean = try {
        (java.net.URL("http://127.0.0.1:${FlaskServerService.PORT}/api/health")
            .openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 800; readTimeout = 800; requestMethod = "GET"
                val ok = responseCode in 200..499   // anything answering means it's alive
                disconnect()
                ok
            }
    } catch (_: Exception) { false }

    private fun showStartupError() {
        progressBar.visibility = View.GONE
        val detail = FlaskServerService.lastError
            ?: "The server did not start within ${START_TIMEOUT_MS / 1000} seconds."
        val html = """
            <html><head><meta name="viewport"
              content="width=device-width,initial-scale=1"></head>
            <body style="font-family:system-ui;padding:28px;background:#120C1A;color:#eee">
              <h2 style="color:#c084fc;margin-bottom:4px">Naada couldn't start</h2>
              <p style="color:#aaa;line-height:1.5">The bundled Python server
                didn't come up. On the very first launch this can take a while
                as the Python runtime unpacks — try reopening the app once.</p>
              <pre style="background:#1e1630;padding:14px;border-radius:8px;
                          white-space:pre-wrap;word-break:break-word;
                          font-size:12px;color:#f9a8d4">$detail</pre>
              <button onclick="location.reload()"
                 style="background:#7c3aed;color:#fff;border:0;padding:12px 22px;
                        border-radius:8px;font-size:15px;margin-top:8px">
                Try again</button>
              <p style="color:#666;font-size:12px;margin-top:22px">
                If this keeps happening, capture the log with:<br>
                <code style="color:#888">adb logcat -s NaadaFlask:* NaadaMain:* python.stderr:*</code></p>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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
        // Generous: first launch unpacks the Python stdlib + pip packages.
        private const val START_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 500L
    }
}
