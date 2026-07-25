package com.zerochat.network.lan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BroadcastReceiver for WiFi Direct system events.
 *
 * Registered in AndroidManifest.xml and also programmatically.
 * Filters: WIFI_P2P_STATE_CHANGED, WIFI_P2P_PEERS_CHANGED,
 *          WIFI_P2P_CONNECTION_CHANGE, WIFI_P2P_THIS_DEVICE_CHANGED
 */
@Singleton
@Suppress("unused")
class WifiDirectReceiver @Inject constructor(
    @field:ApplicationContext private val context: Context,
) : BroadcastReceiver() {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    // Callbacks
    var onPeersChanged: (() -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null

    fun initialize(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        this.manager = manager
        this.channel = channel
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED
                )
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Timber.d("Wi-Fi Direct state changed: enabled=$enabled")
                onStateChanged?.invoke(enabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Timber.d("Wi-Fi Direct peers changed")
                try {
                    manager?.requestPeers(channel) { peers ->
                        Timber.d("Peers list: ${peers?.deviceList?.size ?: 0} devices")
                        onPeersChanged?.invoke()
                    }
                } catch (_: SecurityException) {
                    Timber.w("Missing permission for Wi-Fi Direct peer request")
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Timber.d("Wi-Fi Direct connection changed")
                @Suppress("DEPRECATION")
                val networkInfo: android.net.NetworkInfo? = intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_NETWORK_INFO
                )
                val isConnected = networkInfo?.isConnected == true
                onConnectionChanged?.invoke(isConnected)
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Timber.d("Wi-Fi Direct this device changed")
            }
        }
    }

    companion object {
        @Suppress("unused")
        fun createIntentFilter(): IntentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }
}
