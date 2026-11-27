package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.R
import java.util.concurrent.atomic.AtomicInteger

object AntigravityNotifier {
    const val PREFS_NAME = "antigravity_prefs"
    const val KEY_NOTIFY_DONE = "notify_agent_done"
    const val KEY_NOTIFY_FOREGROUND = "notify_when_foreground"
    private const val NOTIF_CHANNEL_ID = "antigravity_agent_done"
    private const val NOTIF_GROUP_KEY = "antigravity_agent_done_group"
    private const val NOTIF_ID_BASE = 4402
    private const val NOTIF_SLOT_COUNT = 16
    private val notifCounter = AtomicInteger(0)

    @JvmField
    @Volatile
    var isActivityForeground: Boolean = false

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                context.getString(R.string.agy_notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.agy_notif_channel_desc)
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun isNotifyDoneEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_DONE, true)

    fun setNotifyDoneEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFY_DONE, enabled).apply()
    }

    fun isNotifyForegroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_FOREGROUND, false)

    fun setNotifyForegroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFY_FOREGROUND, enabled).apply()
    }

    fun showAgentDoneNotification(
        context: Context,
        title: String,
        body: String,
        force: Boolean = false
    ) {
        if (!force) {
            if (!isNotifyDoneEnabled(context)) return
            if (isActivityForeground && !isNotifyForegroundEnabled(context)) return
        }
        createChannel(context)

        val intent = Intent(context, AntigravityMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, piFlags)

        val resolvedTitle = title.ifBlank { context.getString(R.string.antigravity_app_name) }
        val resolvedBody = body.ifBlank { context.getString(R.string.agy_notif_default_body) }
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antigravity_notif)
            .setColor(Color.parseColor("#3B82F6"))
            .setContentTitle(resolvedTitle)
            .setContentText(resolvedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(resolvedBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(NOTIF_GROUP_KEY)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notifId = NOTIF_ID_BASE + (notifCounter.getAndIncrement() and 0x7FFFFFFF) % NOTIF_SLOT_COUNT
        runCatching { nm?.notify(notifId, notif) }
    }

    fun showOpenUrlNotification(context: Context, url: String) {
        createChannel(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 4403, intent, piFlags)
        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antigravity_notif)
            .setColor(Color.parseColor("#3B82F6"))
            .setContentTitle(context.getString(R.string.agy_notif_signin_title))
            .setContentText(context.getString(R.string.agy_notif_signin_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        runCatching { nm?.notify(NOTIF_ID_BASE + NOTIF_SLOT_COUNT, notif) }
    }
}