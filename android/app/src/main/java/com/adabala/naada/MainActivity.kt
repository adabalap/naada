package com.adabala.naada

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
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
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private data class Metadata(val title: String, val artist: String,
                                val album: String, val artUrl: String)
    private var pendingMetadata: Metadata? = null
    private var pendingPlaying: Boolean? = null
    private var pendingQueue: Triple<String, Int, Long>? = null
    private lateinit var filePicker: ActivityResultLauncher<Array<String>>

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            mediaService = (binder as? NaadaMediaService.LocalBinder)?.service
            Log.d(TAG, "NaadaMediaService connected")
            // Replay anything that arrived while we were still binding.
            pendingMetadata?.let {
                Log.d(TAG, "Replaying held metadata: ${it.title}")
                mediaService?.updateMetadata(it.title, it.artist, it.album, it.artUrl)
                pendingMetadata = null
            }
            pendingQueue?.let { (json, idx, pos) ->
                Log.d(TAG, "Replaying held queue ($idx)")
                mediaService?.setQueue(json, idx, pos)
                pendingQueue = null
            }
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

        // Must be registered before the activity reaches RESUMED, hence
        // here rather than lazily at first use.
        filePicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            // Hand the result back to the page. Passing null is important on
            // cancel: the WebView keeps <input type="file"> disabled until it
            // receives *some* answer, so a dropped callback breaks the button
            // permanently until the app restarts.
            filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            filePathCallback = null
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

            /**
             * Keep the app on localhost. Without this, tapping the
             * "aistudio.google.com" link in Settings replaces the whole UI
             * with Google's site and the only way back is the system Back
             * button — the app appears to have been hijacked. External
             * http(s) links now open in the real browser instead.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: return false
                if (host == "localhost" || host == "127.0.0.1") return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: ActivityNotFoundException) {
                    toast("No app can open that link")
                    true
                }
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
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            /**
             * Without this override, <input type="file"> in a WebView does
             * absolutely nothing — no picker, no error, no console message.
             * That is why "Restore from file" appeared dead: the tap reached
             * the input, the input asked the WebView for a picker, and the
             * WebView silently declined.
             */
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                // Only one picker at a time; cancel any stale callback so the
                // page is never left waiting forever on a dropped request.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    val types = params?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?: arrayOf("*/*")
                    filePicker.launch(if (types.isEmpty()) arrayOf("*/*") else types)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    toast("No file manager app available")
                    false
                }
            }
        }

        /**
         * Downloads are the mirror-image problem. The backup button builds a
         * Blob, calls URL.createObjectURL() and clicks an <a download>. In a
         * browser that saves a file; in a WebView nothing happens unless the
         * app installs a DownloadListener. So backup was quietly broken too,
         * not just restore.
         *
         * blob: URLs cannot be fetched by DownloadManager, so those are read
         * back inside the page via FileReader and handed over as base64
         * through the JS bridge. data: URLs are decoded directly here.
         */
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("blob:") -> fetchBlobViaBridge(url, mimeType)
                url.startsWith("data:") -> saveDataUrl(url, guessName(contentDisposition, mimeType))
                else -> downloadViaManager(url, guessName(contentDisposition, mimeType))
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

    // ── Download support ──────────────────────────────────────────────

    private fun guessName(contentDisposition: String?, mime: String?): String {
        contentDisposition
            ?.let { Regex("filename\\*?=\"?([^\";]+)").find(it)?.groupValues?.getOrNull(1) }
            ?.let { return URLDecoder.decode(it.trim(), "UTF-8") }
        val ext = when {
            mime?.contains("json") == true -> "json"
            mime?.contains("text") == true -> "txt"
            else -> "bin"
        }
        return "naada-backup-${System.currentTimeMillis()}.$ext"
    }

    /**
     * A blob: URL only exists inside the page, so nothing on the Java side
     * can fetch it. Read it back in JavaScript via FileReader and return the
     * bytes as base64 through the bridge.
     */
    private fun fetchBlobViaBridge(blobUrl: String, mime: String?) {
        val js = """
            (function() {
              fetch('$blobUrl')
                .then(function(r) { return r.blob(); })
                .then(function(b) {
                  var fr = new FileReader();
                  fr.onloadend = function() {
                    window.NaadaBridge.onBlobDownload(fr.result, '${mime ?: ""}');
                  };
                  fr.readAsDataURL(b);
                })
                .catch(function(e) {
                  window.NaadaBridge.onBlobError(String(e));
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun saveDataUrl(dataUrl: String, filename: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) { toast("Could not read the file"); return }
            val meta = dataUrl.substring(0, comma)
            val payload = dataUrl.substring(comma + 1)
            val bytes = if (meta.contains(";base64"))
                Base64.decode(payload, Base64.DEFAULT)
            else
                URLDecoder.decode(payload, "UTF-8").toByteArray()

            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, filename)
            FileOutputStream(out).use { it.write(bytes) }

            // Make it visible in the Downloads app immediately.
            @Suppress("DEPRECATION")
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager)
                .addCompletedDownload(
                    out.name, "Naada backup", true,
                    "application/json", out.absolutePath, out.length(), true)

            toast("Saved to Downloads/${out.name}")
            Log.i(TAG, "Saved ${out.length()} bytes to ${out.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            toast("Couldn't save the file: ${e.message}")
        }
    }

    private fun downloadViaManager(url: String, filename: String) {
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $filename")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** JavaScript-accessible interface exposed as window.NaadaBridge */
    inner class NaadaBridge {
        @JavascriptInterface
        fun onTrackChange(title: String, artist: String, album: String, artUrl: String) {
            // Retained for compatibility, but no longer drives the
            // notification: media3 builds that from each MediaItem's
            // metadata, so title, artist and artwork all come from the
            // queue handed to setQueue(). Kept as a log hook.
            Log.d(TAG, "web reports track: $title / $artist")
        }

        @JavascriptInterface
        fun onPlaybackState(isPlaying: Boolean) {
            // The player is the source of truth for state now; this only
            // reflects what the web UI believes.
            Log.d(TAG, "web reports playing=$isPlaying")
        }

        // ── Native playback ──────────────────────────────────────────
        // The web layer resolves stream URLs and hands the whole queue over;
        // ExoPlayer in the foreground service does the playing. This is what
        // keeps music going with the screen off — the WebView can be frozen
        // and playback continues, because the WebView is no longer involved.

        @JavascriptInterface
        fun setQueue(tracksJson: String, startIndex: Int, positionMs: String) {
            val svc = mediaService
            if (svc == null) {
                Log.d(TAG, "service not bound; holding queue")
                pendingQueue = Triple(tracksJson, startIndex, positionMs.toLongOrNull() ?: 0L)
                return
            }
            svc.setQueue(tracksJson, startIndex, positionMs.toLongOrNull() ?: 0L)
        }

        @JavascriptInterface
        fun nativePlayPause(play: Boolean) { mediaService?.playPause(play) }

        @JavascriptInterface
        fun nativeNext() { mediaService?.next() }

        @JavascriptInterface
        fun nativePrev() { mediaService?.previous() }

        @JavascriptInterface
        fun nativeSeek(ms: String) { mediaService?.seekTo(ms.toLongOrNull() ?: 0L) }

        @JavascriptInterface
        fun nativeStop() { mediaService?.stopPlayback() }

        /** Polled by the UI to drive the progress bar while visible. */
        @JavascriptInterface
        fun nativePosition(): String = mediaService?.positionJson() ?: "{}"

        /** Receives blob download bytes as a data: URL from fetchBlobViaBridge. */
        @JavascriptInterface
        fun onBlobDownload(dataUrl: String, mime: String) {
            runOnUiThread { saveDataUrl(dataUrl, guessName(null, mime)) }
        }

        @JavascriptInterface
        fun onBlobError(err: String) {
            Log.e(TAG, "Blob download failed: $err")
            runOnUiThread { toast("Couldn't prepare the backup file") }
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
