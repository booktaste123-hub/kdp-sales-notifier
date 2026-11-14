package com.example.kdpsales

import org.json.JSONObject
import java.net.URL

/** Converts royalties to USD using ECB reference rates from api.frankfurter.app (no key needed). */
class CurrencyConverter {
    private val cache = HashMap<String, Double?>()

    /** Returns null if the currency could not be converted. Call from a background thread. */
    fun toUsd(amount: Double, currency: String): Double? {
        val cur = currency.uppercase()
        if (cur == "USD") return amount
        val rate = if (cache.containsKey(cur)) cache[cur] else fetchRate(cur).also { cache[cur] = it }
        return rate?.let { amount * it }
    }

    private fun fetchRate(cur: String): Double? = try {
        val text = URL("https://api.frankfurter.app/latest?from=$cur&to=USD")
            .openStream().bufferedReader().use { it.readText() }
        JSONObject(text).getJSONObject("rates").getDouble("USD")
    } catch (e: Exception) {
        null
    }
}
