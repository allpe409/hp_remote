package com.hpremote.clone.wifidirect

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager

data class WifiDirectPeer(val name: String, val address: String)

/**
 * Thin wrapper around Android's Wi-Fi Direct (WifiP2pManager) APIs. The
 * receiving phone always hosts a group (so it has a fixed, predictable IP);
 * the sending phone discovers peers and connects to the chosen one, which
 * hands back the receiver's IP automatically - no manual IP entry needed.
 */
class WifiDirectHelper(private val activity: Activity) {

    private val manager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(activity, activity.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    private var onConnectionChanged: (() -> Unit)? = null

    var onPeersChanged: ((List<WifiDirectPeer>) -> Unit)? = null

    fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersInternal()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged?.invoke()
                }
            }
        }
        receiver = r
        activity.registerReceiver(r, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        })
    }

    fun unregister() {
        receiver?.let { activity.unregisterReceiver(it) }
        receiver = null
    }

    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }

    private fun requestPeersInternal() {
        manager.requestPeers(channel) { peers ->
            onPeersChanged?.invoke(peers.deviceList.map { WifiDirectPeer(it.deviceName, it.deviceAddress) })
        }
    }

    /** Connects to the peer at [address]; [onConnected] receives the group owner's (receiver's) IP. */
    fun connectToPeer(address: String, onConnected: (String) -> Unit, onFailed: (String) -> Unit) {
        onConnectionChanged = {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                if (info.groupFormed && info.groupOwnerAddress != null) {
                    onConnectionChanged = null
                    onConnected(info.groupOwnerAddress.hostAddress ?: "")
                }
            }
        }
        val config = WifiP2pConfig().apply { deviceAddress = address }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                onConnectionChanged = null
                onFailed("연결 요청 실패 (코드 $reason)")
            }
        })
    }

    /** Hosts a P2P group so this device becomes the group owner at a fixed, known IP. */
    fun createGroup(onReady: () -> Unit, onFailed: (String) -> Unit) {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onReady()
            override fun onFailure(reason: Int) = onFailed("그룹 생성 실패 (코드 $reason)")
        })
    }

    fun removeGroup() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {}
        })
    }
}
