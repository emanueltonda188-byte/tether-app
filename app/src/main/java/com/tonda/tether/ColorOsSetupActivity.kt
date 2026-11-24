package com.tonda.tether

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First-launch checklist for ColorOS (Oppo) phones.
 *
 * ColorOS is one of the most aggressive app killers in Android. A tether
 * app that is not freed by the user WILL be killed mid-session — which
 * looks exactly like "the app turned my data off". These three switches,
 * in this order, are the fix. The user taps each checkbox when done;
 * "Done" is only enabled once all three are ticked, and it is saved so
 * this screen only appears on first launch (it can be reopened from
 * MainActivity's "Phone setup" button).
 *
 * The "Open settings" buttons deep-link where Android allows it; ColorOS
 * renames/moves some screens between versions, so each step also has
 * plain-language directions the user can follow by hand.
 */
class ColorOsSetupActivity : AppCompatActivity() {

    private lateinit var prefs: TetherPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coloros_setup)
        prefs = TetherPrefs(this)

        val step1 = findViewById<CheckBox>(R.id.step1_check)
        val step2 = findViewById<CheckBox>(R.id.step2_check)
        val step3 = findViewById<CheckBox>(R.id.step3_check)
        val doneButton = findViewById<Button>(R.id.done_button)
        val hint = findViewById<TextView>(R.id.hint_text)

        fun updateDone() {
            doneButton.isEnabled = step1.isChecked && step2.isChecked && step3.isChecked
            hint.text = if (doneButton.isEnabled) {
                "All three done — your phone will now leave Tether alone."
            } else {
                "Tick each box as you finish it. All three are needed."
            }
        }
        listOf(step1, step2, step3).forEach {
            it.setOnCheckedChangeListener { _, _ -> updateDone() }
        }

        findViewById<Button>(R.id.step1_open).setOnClickListener {
            // App info page: from there, Battery usage → Allow background
            // activity / Unrestricted. ColorOS path varies; the text on the
            // screen explains the taps.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            Toast.makeText(this, "Tap Battery usage, then choose Unrestricted", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.step2_open).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
            Toast.makeText(this, "Look for Auto-launch / Auto start and switch it ON", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.step3_open).setOnClickListener {
            Toast.makeText(
                this,
                "Open the Recents screen (square button), find Tether, pull its card down and tap the lock icon",
                Toast.LENGTH_LONG
            ).show()
        }

        doneButton.setOnClickListener {
            prefs.colorOsSetupDone = true
            Toast.makeText(this, "Setup saved — you can reopen it anytime from the main screen", Toast.LENGTH_SHORT).show()
            finish()
        }
        updateDone()
    }
}
