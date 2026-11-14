package com.example.kdpsales

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AuthChallengeException(msg: String) : Exception(msg)
class LoginFailedException(msg: String) : Exception(msg)

/** Amazon asked for a one-time code. The form is saved; the user types the code in the app. */
class OtpRequiredException(msg: String) : Exception(msg)

/**
 * Signs in to KDP the way a browser does and reads the month-to-date sales report.
 *
 * Verification codes (OTP): a background job cannot type a code, so when Amazon asks for
 * one, login() saves the pending form and throws OtpRequiredException. The app notifies
 * the user, who enters the code in the app (submitOtp). The code is sent with "don't ask
 * again on this device" ticked and the session cookies are stored encrypted, so later
 * checks reuse the session and normally need no code.
 *
 * IMPORTANT: Amazon KDP has NO official API. Two parts here are unofficial and may need
 * adjusting for your account:
 *   1. drive() - scrapes Amazon's sign-in forms. A captcha cannot be solved in the
 *                background (AuthChallengeException).
 *   2. REPORT_URL + parse() - the endpoint/field names of the report data. Open
 *                kdpreports.amazon.com in desktop Chrome, DevTools > Network, and copy
 *                the request that returns the month-to-date sales rows into REPORT_URL.
 */
class KdpClient(private val ctx: Context) {

    companion object {
        const val START_URL = "https://kdpreports.amazon.com/"

        // TODO: replace with the real month-to-date report request (see class comment).
        const val REPORT_URL = "https://kdpreports.amazon.com/api/v2/reports/month-to-date"

        private val OTP_FIELDS = setOf("otpCode", "code", "cvf_input_code")

        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }

    /** Cookie jar kept in memory, loaded from / saved to encrypted storage. */
    private class PersistentCookieJar(private val ctx: Context) : CookieJar {
        private val store = CopyOnWriteArrayList<Cookie>()

        init {
            val raw = CredentialStore.loadCookies(ctx)
            if (raw != null) try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) store.add(fromJson(a.getJSONObject(i)))
            } catch (_: Exception) {
                store.clear()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                store.add(c)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }

        fun persist() {
            val a = JSONArray()
            store.filter { it.expiresAt > System.currentTimeMillis() }.forEach { a.put(toJson(it)) }
            CredentialStore.saveCookies(ctx, a.toString())
        }

        private fun toJson(c: Cookie) = JSONObject()
            .put("n", c.name).put("v", c.value).put("e", c.expiresAt).put("d", c.domain)
            .put("p", c.path).put("s", c.secure).put("h", c.httpOnly).put("ho", c.hostOnly)

        private fun fromJson(o: JSONObject): Cookie {
            val b = Cookie.Builder().name(o.getString("n")).value(o.getString("v"))
                .expiresAt(o.getLong("e")).path(o.getString("p"))
            if (o.getBoolean("ho")) b.hostOnlyDomain(o.getString("d")) else b.domain(o.getString("d"))
            if (o.getBoolean("s")) b.secure()
            if (o.getBoolean("h")) b.httpOnly()
            return b.build()
        }
    }

    private val jar = PersistentCookieJar(ctx)

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
        }
        .build()

    private data class Page(val url: HttpUrl, val body: String, val code: Int)

    private fun get(url: String): Page =
        http.newCall(Request.Builder().url(url).build()).execute().use {
            Page(it.request.url, it.body?.string().orEmpty(), it.code)
        }

    private fun post(url: String, form: FormBody): Page =
        http.newCall(Request.Builder().url(url).post(form).build()).execute().use {
            Page(it.request.url, it.body?.string().orEmpty(), it.code)
        }

    private fun isSignInPage(page: Page): Boolean {
        if (page.url.encodedPath.contains("/ap/")) return true
        val t = page.body.trimStart()
        if (t.startsWith("{") || t.startsWith("[")) return false
        val doc = Jsoup.parse(page.body)
        return doc.selectFirst("input[name=email], input[name=password]") != null
    }

    /** Walks Amazon's sign-in pages until the KDP site is reached. */
    private fun drive(start: Page, user: String, pass: String) {
        var page = start
        repeat(6) {
            val doc = Jsoup.parse(page.body, page.url.toString())
            val host = page.url.host
            val onKdp = host.endsWith("kdpreports.amazon.com") || host.endsWith("kdp.amazon.com")

            if (onKdp && doc.selectFirst("input[name=password]") == null) return // signed in

            doc.selectFirst("#auth-error-message-box")?.let {
                throw LoginFailedException("Amazon showed an error: ${it.text().take(200)}")
            }
            if (doc.selectFirst("input[name=cvf_captcha_input], #auth-captcha-guess") != null) {
                throw AuthChallengeException(
                    "Amazon showed a captcha, which cannot be solved in the background. " +
                        "Sign in once in a browser on this phone, then tap Check now."
                )
            }

            // One-time code page (2-step verification, or Amazon's email/SMS code check).
            val otpInput = doc.selectFirst(
                "#auth-mfa-otpcode, input[name=otpCode], input[name=cvf_input_code], input[name=code]"
            )
            if (otpInput != null) {
                val form = otpInput.parents().firstOrNull { it.tagName() == "form" }
                    ?: doc.selectFirst("form")
                    ?: throw LoginFailedException("Unexpected verification page from Amazon.")
                val fields = JSONObject()
                for (i in form.select("input[name]")) fields.put(i.attr("name"), i.attr("value"))
                CredentialStore.savePendingOtp(ctx, form.absUrl("action"), fields.toString())
                throw OtpRequiredException("Amazon sent a verification code.")
            }

            val form = doc.selectFirst(
                "form[name=signIn], form#ap_login_form, " +
                    "form:has(input[name=password]), form:has(input[name=email]), form"
            ) ?: throw LoginFailedException(
                "Sign-in stopped on an unexpected page. Amazon may be waiting for you to " +
                    "approve the sign-in on another device."
            )

            val body = FormBody.Builder()
            for (input in form.select("input[name]")) {
                val name = input.attr("name")
                val value = when (name) {
                    "email" -> user
                    "password" -> pass
                    else -> input.attr("value")
                }
                body.add(name, value)
            }
            page = post(form.absUrl("action"), body.build())
        }
        throw LoginFailedException("Sign-in did not complete.")
    }

    /**
     * Sends the code the user typed. Afterwards the session is stored, so the next check
     * runs without asking for a code again.
     */
    fun submitOtp(otp: String) {
        val user = CredentialStore.user(ctx).orEmpty()
        val pass = CredentialStore.password(ctx).orEmpty()
        val (url, json) = CredentialStore.pendingOtp(ctx)
            ?: throw LoginFailedException("No verification code is pending. Tap \"Check now\".")
        try {
            val saved = JSONObject(json)
            val body = FormBody.Builder()
            for (k in saved.keys()) {
                val v = when {
                    k in OTP_FIELDS -> otp.trim()
                    k == "rememberDevice" -> "true" // "don't require a code on this device"
                    else -> saved.getString(k)
                }
                body.add(k, v)
            }
            val page = post(url, body.build())
            CredentialStore.clearPendingOtp(ctx)
            drive(page, user, pass)
        } catch (e: OtpRequiredException) {
            throw LoginFailedException(
                "That code was not accepted (wrong or expired). Tap \"Check now\" to get a new code."
            )
        } finally {
            jar.persist()
        }
    }

    /** Returns this month's report rows. Throws OtpRequiredException if a code is needed. */
    fun fetchMonthSales(user: String, pass: String): List<SaleRow> {
        try {
            var page = get(REPORT_URL)
            if (isSignInPage(page)) {            // saved session expired or missing
                drive(get(START_URL), user, pass)
                page = get(REPORT_URL)
            }
            if (page.code !in 200..299) throw IOException("Report request failed: HTTP ${page.code}")
            return parse(page.body)
        } finally {
            jar.persist()
        }
    }

    private fun JSONObject.str(vararg keys: String): String {
        for (k in keys) {
            val v = optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun parse(body: String): List<SaleRow> {
        val text = body.trim()
        val arr: JSONArray = try {
            if (text.startsWith("[")) JSONArray(text) else {
                val o = JSONObject(text)
                listOf("rows", "data", "orders", "sales", "royalties")
                    .firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray()
            }
        } catch (e: JSONException) {
            throw IllegalStateException(
                "The report endpoint did not return JSON. Update KdpClient.REPORT_URL/parse()."
            )
        }

        val rows = ArrayList<SaleRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.str("title", "bookTitle", "Title")
            val format = o.str("format", "royaltyType", "binding", "bookFormat").ifBlank { "Unknown" }
            val market = o.str("marketplace", "store", "Marketplace").ifBlank { "Unknown" }
            val date = o.str("date", "royaltyDate", "orderDate")
            val units = if (o.has("netUnitsSold")) o.optInt("netUnitsSold")
            else if (o.has("units")) o.optInt("units")
            else if (o.has("unitsSold")) o.optInt("unitsSold") else 1
            val royalty = o.optDouble("royalty", o.optDouble("Royalty", 0.0))
            val currency = o.str("currency", "Currency").ifBlank { "USD" }
            if (title.isBlank()) continue
            rows.add(
                SaleRow("$date|$title|$market|$format", title, format, market, units, royalty, currency)
            )
        }
        return rows
    }
}
