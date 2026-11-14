package com.example.kdpsales

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var otpBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val hasPass = !CredentialStore.password(this).isNullOrBlank()

        val userField = EditText(this).apply {
            hint = "KDP email / username"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(CredentialStore.user(this@MainActivity) ?: "")
        }
        val passField = EditText(this).apply {
            hint = if (hasPass) "Password saved - type to change" else "KDP password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        status = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            text = if (CredentialStore.hasCredentials(this@MainActivity))
                "Checking KDP every ${Scheduler.INTERVAL_HOURS} hours in the background."
            else "Enter your credentials to start."
        }

        val save = Button(this).apply { text = "Save & start checking" }
        val checkNow = Button(this).apply { text = "Check now" }

        // --- Verification code (OTP) section: only visible while Amazon is waiting for a code ---
        otpBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
            visibility = View.GONE
        }
        val otpInfo = TextView(this).apply {
            text = "Amazon sent a verification code (OTP) by SMS, email or your authenticator " +
                "app. Enter it here:"
        }
        val otpField = EditText(this).apply {
            hint = "Verification code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val otpSubmit = Button(this).apply { text = "Submit code" }
        otpBox.addView(otpInfo)
        otpBox.addView(otpField)
        otpBox.addView(otpSubmit)

        save.setOnClickListener {
            val u = userField.text.toString().trim()
            val p = passField.text.toString()
            if (u.isEmpty() || (p.isEmpty() && !hasPass)) {
                Toast.makeText(this, "Enter username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (p.isEmpty()) CredentialStore.saveUserOnly(this, u) else CredentialStore.save(this, u, p)
            SeenStore.clear(this)            // new account/password -> fresh baseline
            CredentialStore.clearSession(this) // and a fresh login session
            Scheduler.schedule(this)
            Scheduler.checkNow(this)         // first check now; Amazon may ask for a code
            status.text = "Saved. Checking now. If Amazon sends a verification code you will " +
                "get a notification - enter the code here. The first check sets a baseline " +
                "(no sale notification); after that you are notified of new sales every " +
                "${Scheduler.INTERVAL_HOURS} hours."
            passField.setText("")
        }

        checkNow.setOnClickListener {
            if (!CredentialStore.hasCredentials(this)) {
                Toast.makeText(this, "Save credentials first", Toast.LENGTH_SHORT).show()
            } else {
                CredentialStore.clearPendingOtp(this) // an expired code is replaced by a new login
                refreshOtp()
                Scheduler.checkNow(this)
                Toast.makeText(this, "Check started", Toast.LENGTH_SHORT).show()
            }
        }

        otpSubmit.setOnClickListener {
            val code = otpField.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "Enter the code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            otpSubmit.isEnabled = false
            status.text = "Verifying code..."
            val app = applicationContext
            Thread {
                val msg = try {
                    KdpClient(app).submitOtp(code)
                    Scheduler.checkNow(app)
                    "Verified. This phone is now remembered, so a code should not be needed " +
                        "again for a while. Checking sales now."
                } catch (e: Exception) {
                    e.message ?: "Verification failed."
                }
                runOnUiThread {
                    status.text = msg
                    otpSubmit.isEnabled = true
                    otpField.setText("")
                    refreshOtp()
                }
            }.start()
        }

        root.addView(userField)
        root.addView(passField)
        root.addView(save)
        root.addView(checkNow)
        root.addView(otpBox)
        root.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshOtp()
    }

    private fun refreshOtp() {
        val pending = CredentialStore.pendingOtp(this) != null
        otpBox.visibility = if (pending) View.VISIBLE else View.GONE
    }
}
