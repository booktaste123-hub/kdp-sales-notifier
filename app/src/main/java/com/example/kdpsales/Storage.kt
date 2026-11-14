package com.example.kdpsales

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the KDP report: a title sold in a marketplace/format (possibly per day). */
data class SaleRow(
    val key: String,
    val title: String,
    val format: String,
    val marketplace: String,
    val units: Int,
    val royalty: Double,
    val currency: String
)

/** KDP credentials, encrypted with a key held in the Android Keystore. */
object CredentialStore {
    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "kdp_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(ctx: Context, user: String, password: String) {
        prefs(ctx).edit().putString("user", user).putString("pass", password).apply()
    }

    fun saveUserOnly(ctx: Context, user: String) {
        prefs(ctx).edit().putString("user", user).apply()
    }

    fun user(ctx: Context): String? = prefs(ctx).getString("user", null)
    fun password(ctx: Context): String? = prefs(ctx).getString("pass", null)
    fun hasCredentials(ctx: Context) = !user(ctx).isNullOrBlank() && !password(ctx).isNullOrBlank()

    // --- Login session (so the "trusted device" cookie survives and OTP is rarely needed) ---
    fun saveCookies(ctx: Context, json: String) = prefs(ctx).edit().putString("cookies", json).apply()
    fun loadCookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)

    // --- Verification code (OTP) waiting for the user to type it in the app ---
    fun savePendingOtp(ctx: Context, url: String, fieldsJson: String) =
        prefs(ctx).edit().putString("otp_url", url).putString("otp_fields", fieldsJson).apply()

    fun pendingOtp(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx)
        val u = p.getString("otp_url", null) ?: return null
        val f = p.getString("otp_fields", null) ?: return null
        return u to f
    }

    fun clearPendingOtp(ctx: Context) =
        prefs(ctx).edit().remove("otp_url").remove("otp_fields").apply()

    fun clearSession(ctx: Context) =
        prefs(ctx).edit().remove("cookies").remove("otp_url").remove("otp_fields").apply()
}

/**
 * Remembers how many units of each report line were already notified, so only the
 * increase is reported. The first successful check just sets the baseline (no notification),
 * otherwise every sale already in this month's report would be announced.
 */
object SeenStore {
    private fun p(ctx: Context) = ctx.getSharedPreferences("kdp_seen", Context.MODE_PRIVATE)
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun initialized(ctx: Context) = p(ctx).getBoolean("init", false)

    fun load(ctx: Context): Map<String, Pair<Int, Double>> {
        if (p(ctx).getString("month", null) != month()) return emptyMap()
        val raw = p(ctx).getString("rows", null) ?: return emptyMap()
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith {
            val r = o.getJSONObject(it)
            r.getInt("u") to r.getDouble("r")
        }
    }

    fun save(ctx: Context, rows: List<SaleRow>) {
        val o = JSONObject()
        rows.forEach { o.put(it.key, JSONObject().put("u", it.units).put("r", it.royalty)) }
        p(ctx).edit().putString("rows", o.toString()).putString("month", month())
            .putBoolean("init", true).apply()
    }

    fun clear(ctx: Context) = p(ctx).edit().clear().apply()

    fun alertShown(ctx: Context) = p(ctx).getBoolean("alert", false)
    fun setAlertShown(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("alert", v).apply()
}
