package com.example.kdpsales

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object Scheduler {
    const val INTERVAL_HOURS = 6L
    private const val NAME = "kdp_check"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SalesWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun checkNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<SalesWorker>().setConstraints(network).build()
        )
    }
}

class SalesWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun usd(x: Double) = String.format(Locale.US, "$%.2f", x)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val user = CredentialStore.user(ctx)
        val pass = CredentialStore.password(ctx)
        if (user.isNullOrBlank() || pass.isNullOrBlank()) return@withContext Result.success()

        // A verification code is already waiting for the user: don't log in again,
        // that would make Amazon send yet another code.
        if (CredentialStore.pendingOtp(ctx) != null) return@withContext Result.success()

        try {
            val rows = KdpClient(ctx).fetchMonthSales(user, pass)
            SeenStore.setAlertShown(ctx, false)

            val firstRun = !SeenStore.initialized(ctx)
            val seen = SeenStore.load(ctx)

            // Only the increase in units since the last check counts as "new".
            val newRows = rows.mapNotNull { r ->
                val old = seen[r.key]
                val du = r.units - (old?.first ?: 0)
                if (du > 0) r.copy(units = du, royalty = r.royalty - (old?.second ?: 0.0)) else null
            }

            SeenStore.save(ctx, rows)

            if (firstRun || newRows.isEmpty()) return@withContext Result.success()

            val fx = CurrencyConverter()
            var unconverted = false

            val lines = newRows.map { r ->
                val inUsd = fx.toUsd(r.royalty, r.currency)
                val money = if (inUsd != null) usd(inUsd) else {
                    unconverted = true
                    String.format(Locale.US, "%.2f %s", r.royalty, r.currency)
                }
                val qty = if (r.units > 1) " x${r.units}" else ""
                "• ${r.title}$qty | ${r.format} | ${r.marketplace} | $money"
            }

            var monthTotal = 0.0
            for (r in rows) {
                val v = fx.toUsd(r.royalty, r.currency)
                if (v != null) monthTotal += v else unconverted = true
            }
            val totalLine = "Total this month: ${usd(monthTotal)} USD" +
                if (unconverted) " (some currencies not converted)" else ""

            Notifier.notifySales(ctx, lines, totalLine, newRows.sumOf { it.units })
            Result.success()
        } catch (e: IOException) {
            Result.retry() // offline / timeout: try again with backoff
        } catch (e: OtpRequiredException) {
            Notifier.notifyOtpNeeded(ctx)
            Result.success()
        } catch (e: Exception) {
            // Login or report-format problem: tell the user once, not every 6 hours.
            if (!SeenStore.alertShown(ctx)) {
                Notifier.notifyProblem(ctx, e.message ?: "Unknown error")
                SeenStore.setAlertShown(ctx, true)
            }
            Result.success()
        }
    }
}
