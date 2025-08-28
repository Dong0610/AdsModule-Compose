package com.dong.adsmodule.utils

import androidx.lifecycle.MutableLiveData

object AppUtil {
    /** DEV flag – default true just like your static initializer */
    @JvmField
    var VARIANT_DEV: Boolean = true

    /** Tracks ad revenue total */
    @JvmField
    var currentTotalRevenue001Ad: Float = 0f

    /** Initialization messages */
    @JvmField
    val messageInit: MutableLiveData<String> = MutableLiveData()

    /** Counts occurrences of a character in a string */
    @JvmStatic
    fun countCharInStr(str: CharSequence, targetChar: Char): Int =
        str.count { it == targetChar }
}

