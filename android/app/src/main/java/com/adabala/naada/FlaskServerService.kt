package com.adabala.naada

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*

/**
 * FlaskServerService — runs in a separate process (:flask) and starts the
 * bundled Python/Flask backend on localhost:5000.
 *
 * Running in a separate process means:
 *  - A Python crash cannot take down the WebView or the media service.
 *  - The Python GIL doesn't block the Kotlin main thread.
 *  - Memory is isolated — large Python objects don't fragment the main heap.
 *
 * The service is intentionally NOT a foreground service: on Android 8+,
 * a service in its own process is allowed some background CPU time, and
 * since Flask is only ever doing short HTTP API calls (not continuous work),
 * it doesn't need foreground status. The NaadaMediaService holds the
 * foreground slot for the whole app.
 */
class FlaskServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { startFlask() }
        return START_STICKY
    }

    private fun startFlask() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val py = Python.getInstance()
            Log.i(TAG, "Starting Flask on localhost:5000")
            // server.py lives in src/main/python/ and is bundled by Chaquopy
            py.getModule("server").callAttr("run_server")
            Log.i(TAG, "Flask exited normally")
        } catch (e: Exception) {
            Log.e(TAG, "Flask error: ${e.message}", e)
            // Retry after 3 s if it crashed
            Thread.sleep(3000)
            startFlask()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlaskService"
    }
}
