package com.tonda.tether

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The one screen that matters: Start/Stop, live status, and the proxy
 * address to type on the PC.
 *
 * Flow:
 *  - First launch → ColorOsSetupActivity (the three ColorOS switches).
 *  - Start → checks permissions, then starts TetherService.
 *  - Live status arrives as broadcasts from the service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: TetherPrefs
    private lateinit var statusText: TextView
    private lateinit var proxyAddressText: TextView
    private lateinit var clientsText: TextView
    private lateinit var dataText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleButton: Button
    private lateinit var portInput: EditText
    private lateinit var autoStartBox: CheckBox

    private var running = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TetherService.ACTION_STATUS) return
            running = intent.getBooleanExtra(TetherService.EXTRA_RUNNING, false)
            val ssid = intent.getStringExtra(TetherService.EXTRA_SSID).orEmpty()
            val pass = intent.getStringExtra(TetherService.EXTRA_PASSPHRASE).orEmpty()
            val address = intent.getStringExtra(TetherService.EXTRA_PROXY_ADDRESS).orEmpty()
            val clients = intent.getIntExtra(TetherService.EXTRA_CLIENTS, 0)
            val bytes = intent.getLongExtra(TetherService.EXTRA_BYTES, 0L)
            val log = intent.getStringExtra(TetherService.EXTRA_LOG).orEmpty()
            render(ssid, pass, address, clients, bytes, log)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = TetherPrefs(this)

        statusText = findViewById(R.id.status_text)
        proxyAddressText = findViewById(R.id.proxy_address_text)
        clientsText = findViewById(R.id.clients_text)
        dataText = findViewById(R.id.data_text)
        logText = findViewById(R.id.log_text)
        toggleButton = findViewById(R.id.toggle_button)
        portInput = findViewById(R.id.port_input)
        autoStartBox = findViewById(R.id.autostart_checkbox)

        portInput.setText(prefs.proxyPort.toString())
        autoStartBox.isChecked = prefs.autoStartOnBoot
        autoStartBox.setOnCheckedChangeListener { _, checked ->
            prefs.autoStartOnBoot = checked
        }

        toggleButton.setOnClickListener {
            if (running) stopTether() else startTether()
        }
        findViewById<Button>(R.id.setup_button).setOnClickListener {
            startActivity(Intent(this, ColorOsSetupActivity::class.java))
        }
        findViewById<Button>(R.id.save_port_button).setOnClickListener {
            val port = portInput.text.toString().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "Port must be 1024–65535", Toast.LENGTH_SHORT).show()
            } else {
                prefs.proxyPort = port
                Toast.makeText(this, "Port saved: $port (restart tether to apply)", Toast.LENGTH_SHORT).show()
            }
        }

        // First launch: ColorOS setup before anything else. ColorOS kills
        // background apps unless the user frees this one first — starting
        // tether before that is a waste of a session.
        if (!prefs.colorOsSetupDone) {
            startActivity(Intent(this, ColorOsSetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // The status broadcast is app-internal (sent with setPackage), so
        // register as NOT_EXPORTED on Android 13+; older versions use the
        // two-arg call.
        val filter = IntentFilter(TetherService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
    }

    private fun startTether() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        val intent = Intent(this, TetherService::class.java)
            .setAction(TetherService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        running = true
        renderToggle()
    }

    private fun stopTether() {
        val intent = Intent(this, TetherService::class.java)
            .setAction(TetherService.ACTION_STOP)
        startService(intent)
        running = false
        renderToggle()
    }

    private fun render(ssid: String, pass: String, address: String, clients: Int, bytes: Long, log: String) {
        statusText.text = if (running) {
            "SHARING — network: $ssid"
        } else {
            "Stopped"
        }
        proxyAddressText.text = if (running) {
            "On your PC, set the proxy to:\n$address\nWi-Fi password: $pass"
        } else {
            "Press Start, then the address to type on your PC appears here."
        }
        clientsText.text = "Devices connected: $clients"
        dataText.text = "Data shared this session: ${formatBytes(bytes)}"
        logText.text = log
        renderToggle()
    }

    private fun renderToggle() {
        toggleButton.text = if (running) "STOP SHARING" else "START SHARING"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_048_576 -> "${bytes / 1024} KB"
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    // ---- permissions -------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (hasPermissions()) {
                startTether()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Permissions needed")
                    .setMessage(
                        "Tether needs location + nearby-devices permission to create " +
                            "the Wi-Fi Direct network. Android requires it for Wi-Fi Direct — " +
                            "the app does not track your location."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 1001
    }
}
