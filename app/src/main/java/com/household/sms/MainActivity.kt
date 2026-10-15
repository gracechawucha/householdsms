package com.household.sms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** One plain screen: connect it once, then check the status whenever you like. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var url: EditText
    private lateinit var secret: EditText
    private lateinit var device: EditText
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#7fa396"))
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#4d6f60"))
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#06150f"))
            addView(col)
        }
        setContentView(scroll)

        col.addView(TextView(this).apply {
            text = "Household SMS"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Catches every M-Pesa message and sends it to your own Supabase. Nothing else leaves this phone."
            setTextColor(Color.parseColor("#9ab8ab"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#0e2a1f"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(status)

        col.addView(label("FUNCTION URL"))
        url = field("https://YOUR-REF.supabase.co/functions/v1/ingest", Cfg.url(this))
        col.addView(url)
        col.addView(label("SECRET (your INGEST_SECRET)"))
        secret = field("long random string", Cfg.secret(this), password = true)
        col.addView(secret)
        col.addView(label("NAME OF THIS PHONE"))
        device = field("e.g. Lloyd", Cfg.device(this))
        col.addView(device)

        col.addView(button("Save and test connection") { saveAndTest() })

        col.addView(label("STEP 1: ALLOW MESSAGES"))
        col.addView(button("Allow SMS access") {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 1)
        })
        col.addView(TextView(this).apply {
            text = "Android 13+ may refuse an app installed from a file. If the button does nothing: " +
                "Settings > Apps > Household SMS > ⋮ menu > Allow restricted settings, then try again."
            setTextColor(Color.parseColor("#7fa396"))
            textSize = 12f
        })

        col.addView(label("STEP 2: NEVER LET IT SLEEP"))
        col.addView(button("Open battery settings") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                openAppSettings()
            }
        })
        col.addView(TextView(this).apply {
            text = "Find Household SMS and choose Unrestricted (or Don't optimize). In app settings > Battery, pick Unrestricted."
            setTextColor(Color.parseColor("#7fa396"))
            textSize = 12f
        })
        col.addView(button("Open app settings") { openAppSettings() })

        col.addView(label("CATCH UP ON ANYTHING MISSED"))
        col.addView(button("Check inbox: last 3 days") { Scheduler.sweepNow(this, 3); toast("Checking…") })
        col.addView(button("Check inbox: last 30 days") { Scheduler.sweepNow(this, 30); toast("Checking…") })
        col.addView(button("Re-check everything: last 60 days") {
            Db.get(this).clearSent()
            Scheduler.sweepNow(this, 60)
            toast("Re-checking…")
        })
        col.addView(TextView(this).apply {
            text = "The app also re-reads the last 3 days of your inbox about every 15 minutes by itself, " +
                "and sends any M-Pesa message the server does not have."
            setTextColor(Color.parseColor("#7fa396"))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })

        Scheduler.schedulePeriodic(this)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun saveAndTest() {
        Cfg.save(this, url.text.toString(), secret.text.toString(), device.text.toString())
        if (!Cfg.configured(this)) {
            toast("Fill in the URL and the secret")
            return
        }
        toast("Testing…")
        Thread {
            val note = try {
                val r = Net.post(Cfg.url(this), Cfg.secret(this), JSONObject().put("codes", org.json.JSONArray()).toString())
                when {
                    r.ok -> "Connected"
                    r.status == 401 -> "Wrong secret"
                    else -> "Server said ${r.status}"
                }
            } catch (e: Exception) {
                "Could not reach it: ${e.message}"
            }
            runOnUiThread { toast(note); refresh() }
            if (note == "Connected") {
                Scheduler.sendSoon(this)
                Scheduler.sweepNow(this, 30)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun fmt(t: Long): String =
        if (t == 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

    private fun refresh() {
        val hasSms = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val queued = Db.get(this).pendingCount()
        val err = Cfg.lastError(this)
        status.text = buildString {
            append(if (hasSms) "✔ Messages: allowed\n" else "✖ Messages: not allowed yet (step 1)\n")
            append(if (Cfg.configured(this@MainActivity)) "✔ Connection: saved\n" else "✖ Connection: not set up\n")
            append("Waiting to send: $queued\n")
            append("Last sent: ${fmt(Cfg.lastOk(this@MainActivity))}\n")
            append("Last inbox check: ${fmt(Cfg.lastSweep(this@MainActivity))}\n")
            val note = Cfg.lastSweepNote(this@MainActivity)
            if (note.isNotBlank()) append("  $note\n")
            if (err.isNotBlank()) append("⚠ $err")
        }.trimEnd()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED && Cfg.configured(this)) {
            Scheduler.sweepNow(this, 30)
        }
    }
}
