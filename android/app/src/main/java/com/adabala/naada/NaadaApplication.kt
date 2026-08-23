package com.adabala.naada

import android.app.Application

/**
 * Application class that holds a weak reference to the active MainActivity
 * so that NaadaMediaService can post JavaScript callbacks into the WebView
 * when transport commands arrive from headphone buttons or the lock screen.
 *
 * This is the standard pattern for service-to-activity communication when
 * you need to cross from a service process to the UI thread.
 */
class NaadaApplication : Application() {
    // Nullable: null when the activity is not on screen
    var mainActivity: MainActivity? = null
}
