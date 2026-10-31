package com.mybiketraffic.karootraffic.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives tap events from the Karoo RemoteViews data field to support double-tap radar reset.
 */
class ResetRadarTapReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DATAFIELD_TAP = "com.mybiketraffic.karootraffic.ACTION_DATAFIELD_TAP"

        fun getIntent(context: Context): Intent {
            return Intent(context, ResetRadarTapReceiver::class.java).apply {
                action = ACTION_DATAFIELD_TAP
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DATAFIELD_TAP) {
            KarooTrafficManager.getInstance(context).onDataFieldTapped()
        }
    }
}
