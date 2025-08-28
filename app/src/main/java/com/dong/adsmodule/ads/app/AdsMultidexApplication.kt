package com.dong.adsmodule.ads.app

import androidx.multidex.MultiDexApplication
import com.dong.adsmodule.ads.ad.AppOpenManager
import com.dong.adsmodule.ads.core.Logger

// import các package đúng theo dự án của bạn
open class AdsMultidexApplication : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        AppOpenManager.init(this, Logger("AppOpenAds", enabled = true))
    }

    override fun onTerminate() {
        super.onTerminate()
    }
}