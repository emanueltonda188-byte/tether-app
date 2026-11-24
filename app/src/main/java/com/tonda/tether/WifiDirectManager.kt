package com.tonda.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/**
 * Thin wrapper around Android's Wi-Fi Direct (WifiP2pManager).
 *
 * v1 flow:
 *  1. [createGroup] — the phone becomes the group owner and broadcasts a
 *     Wi-Fi network (SSID + passphrase) the PC joins like any hotspot.
 *  2. [requestGroupInfo] — reads the SSID, passphrase and the phone's
 *     group-owner IP, which is what the PC types as its proxy address.
 *  3. [removeGroup] — tears the group down on Stop.
 *
 * Group state is delivered through [listener]; [TetherService] uses it for
 * the watchdog (a group that disappears without us asking is the
 * "connection just died" bug NetShare has — here it triggers a rebuild).
 *
 * TODO (real phone, ColorOS): Wi-Fi Direct group behaviour is the least
 * predictable part of v1. Things to verify on the real Oppo:
 *  - Does createGroup succeed while mobile data is on, or does ColorOS
 *    drop the data connection when the group starts (the exact NetShare
 *    complaint — if it does, note the exact sequence)?
 *  - Is the group-owner IP reliably 192.168.49.1?
 *  - Does the group survive the screen turning off / the phone locking?
 *  - Some ColorOS builds need location ON for Wi-Fi Direct scans even
 *    with the permission granted — verify.
 */
class WifiDirectManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onGroupAvailable(group: WifiP2pGroup)
        fun onGroupLost()
        fun onGroupInfo(info: WifiP2pInfo)
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, Looper.getMainLooper(), null)

    private var receiver: BroadcastReceiver? = null
    private var lastGroup: WifiP2pGroup? = null

    val isGroupActive: Boolean
        get() = lastGroup != null

    fun startListening() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                        if (info != null) {
                            listener.onGroupInfo(info)
                            if (info.groupFormed) requestGroupInfo() else {
                                if (lastGroup != null) {
                                    lastGroup = null
                                    listener.onGroupLost()
                                }
                            }
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    fun stopListening() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    fun createGroup(onResult: (Boolean) -> Unit) {
        val m = manager ?: return onResult(false)
        val ch = channel ?: return onResult(false)
        m.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    fun removeGroup(onResult: (Boolean) -> Unit = {}) {
        val m = manager ?: return onResult(false)
        val ch = channel ?: return onResult(false)
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                lastGroup = null
                onResult(true)
            }
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    fun requestGroupInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestGroupInfo(ch) { group ->
            if (group != null) {
                lastGroup = group
                listener.onGroupAvailable(group)
            }
        }
    }
}
