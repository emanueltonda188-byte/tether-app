package com.tonda.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service — the heart of v1.
 *
 * It owns three things:
 *  1. The [ProxyServer] (the PC's internet goes through this).
 *  2. The Wi-Fi Direct group (the network the PC joins).
 *  3. The watchdog — every [WATCHDOG_INTERVAL_MS] it checks that the
 *     group is still up and the proxy is still answering; if either died,
 *     it rebuilds it and writes the event to the log. This is the fix for
 *     the NetShare complaint: NetShare just dies when the connection
 *     drops; Tether notices and restarts.
 *
 * It runs as a sticky foreground service with a persistent notification,
 * which is the strongest "please don't kill me" signal Android offers
 * a normal app. It is still not a guarantee — ColorOS can kill anything —
 * which is why the first-launch ColorOS checklist (battery Unrestricted,
 * auto-launch, lock in Recents) matters as much as the code.
 *
 * State is broadcast with [ACTION_STATUS] intents so MainActivity can
 * show live status without binding to the service.
 */
class TetherService : Service(), WifiDirectManager.Listener {

    companion object {
        const val ACTION_START = "com.tonda.tether.START"
        const val ACTION_STOP = "com.tonda.tether.STOP"
        const val ACTION_STATUS = "com.tonda.tether.STATUS"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASSPHRASE = "passphrase"
        const val EXTRA_PROXY_ADDRESS = "proxy_address"
        const val EXTRA_CLIENTS = "clients"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_LOG = "log"

        const val CHANNEL_ID = "tether_running"
        const val NOTIFICATION_ID = 1

        /** Watchdog tick: 15s is frequent enough to catch a drop fast,
         *  rare enough not to chew battery. */
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        /** After this many failed rebuilds in a row, stop trying and
         *  surface the failure instead of looping forever. */
        private const val MAX_REBUILD_ATTEMPTS = 5
    }

    private lateinit var prefs: TetherPrefs
    private var wifiDirect: WifiDirectManager? = null
    private var proxy: ProxyServer? = null
    private val clients = AtomicInteger(0)
    private val handler = Handler(Looper.getMainLooper())
    private var rebuildAttempts = 0
    private var groupOwnerIp: String = "192.168.49.1" // Android's usual GO address
    private var ssid: String = ""
    private var passphrase: String = ""
    private val log = StringBuilder()

    private val watchdog = object : Runnable {
        override fun run() {
            checkHealth()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ---- service lifecycle -------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = TetherPrefs(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTethering()
            ACTION_STOP -> stopTethering()
        }
        // START_STICKY: if the system kills us, restart us — the watchdog
        // then rebuilds the group/proxy on the next tick.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        stopTetheringInternal()
        super.onDestroy()
    }

    // ---- start / stop ------------------------------------------------------

    private fun startTethering() {
        if (proxy != null) return // already running
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        logEvent("Starting tether")

        wifiDirect = WifiDirectManager(this, this).also { it.startListening() }
        wifiDirect?.createGroup { ok ->
            if (!ok) {
                logEvent("FAILED to create Wi-Fi Direct group")
                broadcastStatus()
            } else {
                logEvent("Wi-Fi Direct group requested")
            }
        }
        startProxy()
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        broadcastStatus()
    }

    private fun startProxy() {
        val port = prefs.proxyPort
        proxy = ProxyServer(
            port = port,
            onBytesTransferred = { broadcastStatus() },
            onClientConnected = { clients.incrementAndGet(); broadcastStatus() },
            onClientDisconnected = { clients.decrementAndGet(); broadcastStatus() },
        ).also { it.start() }
        logEvent("Proxy started on port $port")
    }

    private fun stopTethering() {
        stopTetheringInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTetheringInternal() {
        handler.removeCallbacks(watchdog)
        try { proxy?.stop() } catch (_: Exception) { }
        proxy = null
        wifiDirect?.removeGroup()
        wifiDirect?.stopListening()
        wifiDirect = null
        clients.set(0)
        logEvent("Tether stopped")
        broadcastStatus()
    }

    // ---- watchdog: the NetShare fix ----------------------------------------

    private fun checkHealth() {
        val proxyDead = proxy?.isAlive != true
        val groupDead = wifiDirect?.isGroupActive != true
        if (!proxyDead && !groupDead) {
            rebuildAttempts = 0
            return
        }
        if (rebuildAttempts >= MAX_REBUILD_ATTEMPTS) {
            logEvent("Watchdog gave up after $MAX_REBUILD_ATTEMPTS rebuilds — tap Stop, then Start")
            handler.removeCallbacks(watchdog)
            broadcastStatus()
            return
        }
        rebuildAttempts++
        if (proxyDead) {
            logEvent("Watchdog: proxy died — restarting (attempt $rebuildAttempts)")
            try { proxy?.stop() } catch (_: Exception) { }
            proxy = null
            startProxy()
        }
        if (groupDead) {
            logEvent("Watchdog: Wi-Fi Direct group dropped — recreating (attempt $rebuildAttempts)")
            wifiDirect?.createGroup { ok ->
                if (ok) logEvent("Watchdog: group recreated")
                else logEvent("Watchdog: group recreate failed")
                broadcastStatus()
            }
        }
        broadcastStatus()
    }

    // ---- WifiDirectManager.Listener ----------------------------------------

    override fun onGroupAvailable(group: WifiP2pGroup) {
        ssid = group.networkName ?: ""
        passphrase = group.passphrase ?: ""
        rebuildAttempts = 0
        logEvent("Group up: $ssid")
        updateNotification("Sharing — $ssid")
        broadcastStatus()
    }

    override fun onGroupLost() {
        logEvent("Group lost (phone or PC dropped it)")
        updateNotification("Group lost — watchdog will rebuild")
        broadcastStatus()
        // The watchdog's next tick rebuilds it; no need to act here.
    }

    override fun onGroupInfo(info: WifiP2pInfo) {
        info.groupOwnerAddress?.hostAddress?.let { groupOwnerIp = it }
        broadcastStatus()
    }

    // ---- status broadcast + notification -----------------------------------

    private fun proxyAddress(): String = "$groupOwnerIp:${prefs.proxyPort}"

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_RUNNING, proxy != null)
            putExtra(EXTRA_SSID, ssid)
            putExtra(EXTRA_PASSPHRASE, passphrase)
            putExtra(EXTRA_PROXY_ADDRESS, proxyAddress())
            putExtra(EXTRA_CLIENTS, clients.get().coerceAtLeast(0))
            putExtra(EXTRA_BYTES, proxy?.bytesTransferred?.get() ?: 0L)
            putExtra(EXTRA_LOG, log.toString())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun logEvent(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.append("[$time] $message\n")
        // Keep the log bounded.
        if (log.length > 8000) log.delete(0, log.length - 8000)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Tether running",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while Tether is sharing your internet" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tether is sharing your internet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
