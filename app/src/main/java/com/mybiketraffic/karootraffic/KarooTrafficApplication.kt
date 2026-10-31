package com.mybiketraffic.karootraffic

import android.app.Application
import com.mybiketraffic.karootraffic.karoo.KarooTrafficManager
import timber.log.Timber

class KarooTrafficApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        KarooTrafficManager.getInstance(this)
    }
}
