package com.termux.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.termux.R

object AntigravityNotifier {
    const val PREFS_NAME = "antigravity_prefs"
    const val KEY_NOTIFY_DONE = "notify_agent_done"
    const val KEY_NOTIFY_FOREGROUND = "notify_when_foreground"
    private const val NOTIF_CHANNEL_ID = "antigravity_agent_done"
    private const val NOTIF_ID_AGENT_DONE = 4402

    @Volatile
    var isActivityForeground: Boolean = false

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Antigravity Agent Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when an Antigravity agent finishes a task"
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

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_antigravity_notif)
            .setColor(Color.parseColor("#3B82F6"))
            .setContentTitle(title.ifBlank { "Antigravity" })
            .setContentText(body.ifBlank { "Agent task finished" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Agent task finished" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID_AGENT_DONE, notif)
    }
}
