package com.dong.adsmodule.ui

import com.dong.adsmodule.ads.app.AdsMultidexApplication
import com.dong.adsmodule.ads.ad.AppOpenManager
import com.dong.adsmodule.ui.screen.MainActivity

class AdModuleApplication : AdsMultidexApplication() {
    override fun onCreate() {
        super.onCreate()
        AppOpenManager.getInstance().disableResumeFor(MainActivity::class.java)
    }
}