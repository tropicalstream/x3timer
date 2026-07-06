package com.tropicalstream.x3timer

import android.app.Application

/**
 * Application entry point. X3 Timer renders via its own dual-draw BinocularSbsLayout,
 * so the Mercury AAR is OPTIONAL — init reflectively if present, never crash.
 */
class X3TimerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val cls = Class.forName("com.ffalcon.mercury.android.sdk.MercurySDK")
            cls.getMethod("init", Application::class.java).invoke(null, this)
        }
    }
}
