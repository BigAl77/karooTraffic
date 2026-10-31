package com.mybiketraffic.karootraffic.radar

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.HighPrioritySearchTimeout
import com.dsi.ant.message.LibConfig
import com.dsi.ant.message.LowPrioritySearchTimeout
import com.dsi.ant.message.fromant.AcknowledgedDataMessage
import com.dsi.ant.message.fromant.BroadcastDataMessage
import com.dsi.ant.message.fromant.ChannelEventMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import timber.log.Timber

/**
 * Hardware ANT+ Bike Radar channel implementation using android_antlib_4-16-0.aar (com.dsi.ant).
 * Connects on PredefinedNetwork.ANT_PLUS (Device Type 40, RF 57, Period 4084) and delegates
 * packet decoding to AntRadarPacketParser.
 */
class RawAntRadarChannel(
    private val context: Context,
    private val parser: AntRadarPacketParser = AntRadarPacketParser()
) : RawAntRadarController {

    private var antService: AntService? = null
    private var antChannelProvider: AntChannelProvider? = null
    private var antChannel: AntChannel? = null
    private var serviceBound: Boolean = false
    private var intentionalClose: Boolean = false

    override var isOpen: Boolean = false
        private set

    override val isSearching: Boolean
        get() = parser.isSearching

    override val isTracking: Boolean
        get() = parser.isTracking()

    override val lastPage: Int?
        get() = parser.lastPage

    override val lastRfEvent: Int?
        get() = parser.lastRfEvent

    override val rawDeviceState: Int
        get() = parser.rawDeviceState

    override val sawErrorPage: Boolean
        get() = parser.sawErrorPage

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val svc = AntService(service)
                antService = svc
                antChannelProvider = svc.channelProvider
                if (isOpen && antChannel == null && !intentionalClose) {
                    acquireAndOpenChannel()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to initialize ANT channel provider")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            antChannelProvider = null
            antService = null
        }
    }

    override fun openChannel(): Boolean {
        if (isOpen) return true
        intentionalClose = false
        parser.onChannelOpened()

        try {
            if (!serviceBound) {
                serviceBound = AntService.bindService(context.applicationContext, serviceConnection)
            }
            if (antChannelProvider != null) {
                return acquireAndOpenChannel()
            }
            // Service binding is in flight; mark open while searching
            isOpen = serviceBound
            return isOpen
        } catch (e: Exception) {
            Timber.w(e, "Unable to bind/open ANT radar channel")
            isOpen = false
            return false
        }
    }

    private fun acquireAndOpenChannel(): Boolean {
        val provider = antChannelProvider ?: return false
        try {
            val channel = provider.acquireChannel(context.applicationContext, PredefinedNetwork.ANT_PLUS)
            channel.setChannelEventHandler(object : IAntChannelEventHandler {
                override fun onReceiveMessage(
                    messageType: MessageFromAntType?,
                    antParcel: AntMessageParcel?
                ) {
                    if (antParcel == null || messageType == null) return
                    handleAntMessage(messageType, antParcel)
                }

                override fun onChannelDeath() {
                    Timber.w("ANT radar channel died")
                    releaseChannelInternal()
                }
            })

            channel.assign(ChannelType.BIDIRECTIONAL_SLAVE)
            channel.setRfFrequency(AntRadarPacketParser.ANT_RADAR_RADIO_FREQUENCY)
            channel.setPeriod(AntRadarPacketParser.ANT_RADAR_MESSAGE_PERIOD)
            channel.setSearchTimeout(
                LowPrioritySearchTimeout.TWELVE_AND_A_HALF_SECONDS,
                HighPrioritySearchTimeout.DISABLED
            )
            channel.setAdapterWideLibConfig(LibConfig(true, true, false))
            channel.setChannelId(
                ChannelId(
                    0,
                    AntRadarPacketParser.ANT_RADAR_DEVICE_TYPE,
                    AntRadarPacketParser.ANT_RADAR_TRANSMISSION_TYPE
                )
            )
            channel.open()
            antChannel = channel
            isOpen = true
            return true
        } catch (e: Exception) {
            Timber.w(e, "Failed to acquire/open ANT radar channel")
            releaseChannelInternal()
            isOpen = false
            return false
        }
    }

    private fun handleAntMessage(messageType: MessageFromAntType, antParcel: AntMessageParcel) {
        when (messageType) {
            MessageFromAntType.BROADCAST_DATA -> {
                val msg = BroadcastDataMessage(antParcel)
                parser.onBroadcastPayload(msg.payload)
            }
            MessageFromAntType.ACKNOWLEDGED_DATA -> {
                val msg = AcknowledgedDataMessage(antParcel)
                parser.onBroadcastPayload(msg.payload)
            }
            MessageFromAntType.CHANNEL_EVENT -> {
                val evt = ChannelEventMessage(antParcel)
                val code = evt.eventCode.rawValue
                parser.onRfEvent(code)
                if (code == AntRadarPacketParser.RF_EVENT_CHANNEL_CLOSED ||
                    code == AntRadarPacketParser.RF_EVENT_RX_SEARCH_TIMEOUT
                ) {
                    if (!intentionalClose) {
                        restartSearch()
                    }
                }
            }
            else -> {}
        }
    }

    private fun restartSearch() {
        releaseChannelInternal()
        parser.onChannelOpened()
        acquireAndOpenChannel()
    }

    private fun releaseChannelInternal() {
        val ch = antChannel
        antChannel = null
        if (ch != null) {
            try {
                ch.close()
            } catch (_: Exception) {}
            try {
                ch.clearChannelEventHandler()
                ch.clearAdapterEventHandler()
            } catch (_: Exception) {}
            try {
                ch.release()
            } catch (_: Exception) {}
        }
    }

    override fun closeChannel() {
        if (!isOpen && !serviceBound) return
        intentionalClose = true
        releaseChannelInternal()
        if (serviceBound) {
            try {
                context.applicationContext.unbindService(serviceConnection)
            } catch (_: Exception) {}
            serviceBound = false
        }
        isOpen = false
        parser.onChannelClosed()
    }

    override fun getRadarInfo(): List<RadarTarget>? {
        return parser.getRadarInfo()
    }
}
