package com.dong.adsmodule.ads.core

import android.util.Log

interface AdLogger {
    fun d(msg: String)
    fun e(msg: String, tr: Throwable? = null)
}

class Logger(
    private val tag: String ="AdsModules",
    private val enabled: Boolean = true
) : AdLogger {
    override fun d(msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    override fun e(msg: String, tr: Throwable?) {
        if (enabled) android.util.Log.e(tag, msg, tr)
    }
}
