package com.mybiketraffic.karootraffic.karoo

import com.mybiketraffic.karootraffic.BuildConfig
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.FitEffect
import timber.log.Timber

class KarooTrafficExtensionService : KarooExtension(
    KarooTrafficManager.EXTENSION_ID,
    BuildConfig.VERSION_NAME
) {
    private val manager by lazy {
        KarooTrafficManager.getInstance(this)
    }

    override val types by lazy {
        listOf(
            MyBikeTrafficDataType(manager)
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("KarooTrafficExtensionService created")
        // Ensure manager is initialized
        manager
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.i("KarooTraffic FIT recording started")
        manager.fitHandler.start(emitter)
    }
}
