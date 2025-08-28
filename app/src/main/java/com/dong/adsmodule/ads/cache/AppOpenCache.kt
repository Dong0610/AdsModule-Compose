package com.dong.adsmodule.ads.cache

import com.google.android.gms.ads.appopen.AppOpenAd

data class AdEntry(val ad: AppOpenAd, val id: String, val loadedAt: Long)

fun AppOpenAd?.isValid(loadedAt: Long, freshMs: Long): Boolean {
    if (this == null) return false
    val age = System.currentTimeMillis() - loadedAt
    return age in 0 until freshMs
}

val splashPool = ArrayDeque<AdEntry>()
val resumePool = ArrayDeque<AdEntry>()
private const val MAX_POOL_SIZE = 5

internal fun prunePool(pool: ArrayDeque<AdEntry>, freshnessMs: Long) {
    val it = pool.iterator()
    while (it.hasNext()) {
        val e = it.next()
        if (!e.ad.isValid(e.loadedAt, freshnessMs)) {
            // defensive: drop any callback that might capture Activity
            e.ad.fullScreenContentCallback = null
            it.remove()
        }
    }
    // Bound the pool size (FIFO)
    while (pool.size > MAX_POOL_SIZE) {
        val removed = pool.removeFirst()
        removed.ad.fullScreenContentCallback = null
    }
}

// Always pick pool on main thread
internal fun poolOf(isSplash: Boolean) = if (isSplash) splashPool else resumePool