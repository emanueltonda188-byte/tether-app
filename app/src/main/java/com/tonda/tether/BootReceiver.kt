package com.tonda.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts tethering on boot, only when the user turned "auto-start" on.
 * (v1 default: off. Android 12+ also requires the user to have opened the
 * app at least once before BOOT_COMPLETED is delivered.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TetherPrefs(context).autoStartOnBoot) return
        val service = Intent(context, TetherService::class.java)
            .setAction(TetherService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
