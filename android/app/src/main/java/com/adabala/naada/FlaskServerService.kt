package com.adabala.naada

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Runs the bundled Python/Flask backend on localhost, in its own process
 * (:flask) so a Python crash cannot take down the UI or the media session.
 *
 * Two design points worth knowing:
 *
 * 1. ASSET EXTRACTION. Chaquopy packages everything under src/main/python
 *    into a ZIP, so a static folder placed there is not a real directory at
 *    runtime and Flask cannot serve from it. The frontend therefore ships as
 *    ordinary Android assets (stored verbatim) and is extracted to filesDir
 *    on first launch, then handed to Flask as an absolute path.
 *
 * 2. NO RECURSIVE RETRY. An earlier version called itself from inside its
 *    own catch block, growing the stack on every failure. It is a bounded
 *    loop now, and the last error is published so the UI can show something
 *    more useful than a blank page.
 *
 * Note on comment style in this file: Kotlin allows block comments to NEST,
 * which Java does not. A stray slash-star sequence inside a comment opens a
 * new level that must be closed again, and if it is not, the rest of the
 * file is silently swallowed as comment text. That produced a cascade of
 * "unresolved reference" errors here once. Paths in comments are written
 * without wildcard characters for that reason.
 */
class FlaskServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread({ runFlask() }, "naada-flask").apply { isDaemon = true }.start()
        return START_STICKY
    }

    private fun runFlask() {
        val staticDir = try {
            extractStaticAssets()
        } catch (e: Exception) {
            fail("Could not unpack the app's web files: ${e.message}", e)
            return
        }

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                if (!Python.isStarted()) {
                    Log.i(TAG, "Starting Python runtime (first launch unpacks the stdlib)")
                    Python.start(AndroidPlatform(this))
                }
                Log.i(TAG, "Starting Flask on 127.0.0.1:$PORT (attempt $attempt)")
                // Blocks until the server stops.
                Python.getInstance()
                    .getModule("server")
                    .callAttr("run_server", staticDir.absolutePath, PORT)
                Log.w(TAG, "Flask returned unexpectedly")
            } catch (e: PyException) {
                // A Python-level traceback: almost always the real story.
                fail("Python error: ${e.message?.take(400)}", e)
            } catch (e: Throwable) {
                fail("Server error: ${e.message}", e)
            }

            if (attempt < MAX_ATTEMPTS) {
                Log.i(TAG, "Retrying in $RETRY_MS ms")
                try {
                    Thread.sleep(RETRY_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        Log.e(TAG, "Flask failed after $MAX_ATTEMPTS attempts. Last error: $lastError")
    }

    /**
     * Copy the bundled web files from assets into filesDir, once per app
     * version. Re-extracts whenever versionCode changes, so an update never
     * serves a stale UI left over from the previous install.
     */
    private fun extractStaticAssets(): File {
        val outDir = File(filesDir, "static")
        val stamp = File(filesDir, "static.version")
        // PackageInfoCompat rather than longVersionCode: that property is
        // API 28+ and minSdk here is 26, which fails the NewApi lint check.
        val version = PackageInfoCompat.getLongVersionCode(
            packageManager.getPackageInfo(packageName, 0)
        ).toString()

        if (outDir.isDirectory && stamp.isFile && stamp.readText() == version) {
            Log.i(TAG, "Static assets already extracted for version $version")
            return outDir
        }

        Log.i(TAG, "Extracting static assets for version $version")
        outDir.deleteRecursively()
        copyAssetDir("static", outDir)
        stamp.writeText(version)
        Log.i(TAG, "Extracted ${outDir.walkTopDown().count { it.isFile }} files")
        return outDir
    }

    /**
     * Recursively copy an assets directory to a real directory on disk.
     * AssetManager.list returns an empty array for leaf files, which is how
     * we tell files and directories apart.
     */
    private fun copyAssetDir(assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(target, child))
        }
    }

    private fun fail(msg: String, e: Throwable) {
        lastError = msg
        Log.e(TAG, msg, e)
    }

    companion object {
        private const val TAG = "NaadaFlask"
        const val PORT = 5000
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_MS = 2500L

        /** Last startup error, surfaced by MainActivity if the server never comes up. */
        @Volatile
        var lastError: String? = null
            private set
    }
}
