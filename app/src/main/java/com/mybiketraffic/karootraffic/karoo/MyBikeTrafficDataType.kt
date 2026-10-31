package com.mybiketraffic.karootraffic.karoo

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.DeadObjectException
import android.widget.RemoteViews
import com.mybiketraffic.karootraffic.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Karoo DataType implementation that renders the Garmin-parity MyBikeTraffic multi-field canvas view.
 */
class MyBikeTrafficDataType(
    private val manager: KarooTrafficManager
) : DataTypeImpl(KarooTrafficManager.EXTENSION_ID, TYPE_ID) {

    companion object {
        const val TYPE_ID = "DATATYPE_MYBIKETRAFFIC"
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val renderer = MyBikeTrafficCanvasRenderer(manager.preferences.getSettings())

        val width = config.viewSize.first.coerceAtLeast(100)
        val height = config.viewSize.second.coerceAtLeast(60)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        emitter.onNext(ShowCustomStreamState(message = "", color = null))

        val tapPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            ResetRadarTapReceiver.getIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var lastHandledTapTime = manager.tapEventFlow.value

        scope.launch {
            combine(
                manager.snapshotFlow,
                manager.settingsFlow,
                manager.tapEventFlow
            ) { snapshot, settings, tapTime ->
                Triple(snapshot, settings, tapTime)
            }.collect { (snapshot, settings, tapTime) ->
                renderer.applySettings(settings)
                if (tapTime > lastHandledTapTime) {
                    lastHandledTapTime = tapTime
                    renderer.handleResetTap {
                        manager.radarManager.requestRawWaitReset()
                    }
                }

                val nightModeFlags =
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val darkTheme = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

                renderer.renderToBitmap(bitmap, snapshot, darkTheme)

                val remoteViews = RemoteViews(context.packageName, R.layout.remote_view_mybiketraffic)
                remoteViews.setImageViewBitmap(R.id.imageView_remote, bitmap)
                remoteViews.setOnClickPendingIntent(R.id.imageView_remote, tapPendingIntent)

                try {
                    emitter.updateView(remoteViews)
                } catch (e: DeadObjectException) {
                    Timber.w(e, "Karoo System view binder died, stopping view updates")
                    emitter.cancel()
                    scope.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to update Karoo RemoteViews")
                }
            }
        }

        emitter.setCancellable {
            scope.cancel()
        }
    }
}
