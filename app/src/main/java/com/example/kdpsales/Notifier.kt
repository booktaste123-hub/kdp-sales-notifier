package com.example.kdpsales

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

object Notifier {
    private const val SALES_CH = "kdp_sales_v1"
    private const val ALERT_CH = "kdp_alerts_v1"
    private const val OTP_CH = "kdp_otp_v1"

    /**
     * Volume grows with the number of new sales: 1 sale = 15%, 2 = 30%, 3 = 45% ... 7+ = 100%.
     * (Final loudness is this fraction of the phone's notification volume.)
     */
    fun volumeFor(sales: Int): Float = (0.15f * sales.coerceAtLeast(1)).coerceAtMost(1f)

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Sound and vibration are played by us (see below) so their strength can scale with
        // the sale count - a notification channel can only play at one fixed volume.
        val sales = NotificationChannel(SALES_CH, "New KDP sales", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "Shown when new book sales appear in your KDP reports"
                setSound(null, null)
                enableVibration(false)
            }
        val alerts = NotificationChannel(ALERT_CH, "KDP check problems", NotificationManager.IMPORTANCE_LOW)
        val otp = NotificationChannel(OTP_CH, "KDP verification code needed", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(sales)
        nm.createNotificationChannel(alerts)
        nm.createNotificationChannel(otp)
    }

    /** Amazon sent a verification code; the user must type it into the app. */
    fun notifyOtpNeeded(ctx: Context) {
        ensureChannels(ctx)
        if (!canPost(ctx)) return
        val msg = "Amazon sent you a verification code (OTP). Tap here and enter it so " +
            "your KDP sales checks can run."
        val n = NotificationCompat.Builder(ctx, OTP_CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("KDP needs your verification code")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2002, n)
    }

    private fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun notifySales(ctx: Context, lines: List<String>, totalLine: String, saleCount: Int) {
        ensureChannels(ctx)
        if (!canPost(ctx)) return
        val body = lines.joinToString("\n") + "\n\n" + totalLine
        val title = if (saleCount == 1) "1 new KDP sale" else "$saleCount new KDP sales"
        val n = NotificationCompat.Builder(ctx, SALES_CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(totalLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), n)

        val v = volumeFor(saleCount)
        playSound(ctx, v)
        vibrate(ctx, v)
    }

    fun notifyProblem(ctx: Context, message: String) {
        ensureChannels(ctx)
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, ALERT_CH)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("KDP check failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2001, n)
    }

    private fun playSound(ctx: Context, volume: Float) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                setVolume(volume, volume)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context, strength: Float) {
        val vib = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!vib.hasVibrator()) return
        val timings = longArrayOf(0, 300, 150, 300)
        val effect = if (vib.hasAmplitudeControl()) {
            val amp = (255 * strength).toInt().coerceIn(1, 255)
            VibrationEffect.createWaveform(timings, intArrayOf(0, amp, 0, amp), -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        vib.vibrate(effect)
    }
}
