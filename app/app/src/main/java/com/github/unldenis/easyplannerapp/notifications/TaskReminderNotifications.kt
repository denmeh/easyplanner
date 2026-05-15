package com.github.unldenis.easyplannerapp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.github.unldenis.easyplanner.PlannerSchedulerEvent
import com.github.unldenis.easyplannerapp.MainActivity
import com.github.unldenis.easyplannerapp.R

object TaskReminderNotifications {

    internal const val CHANNEL_ID = "task_reminders"

    private const val COMPLETED_KIND = "completed"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_tasks_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description =
                context.getString(R.string.notification_channel_tasks_description)
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Shows Android notifications when the Rust poller emits due/completed lifecycle events. */
    fun notifyForEvents(context: Context, events: List<PlannerSchedulerEvent>) {
        if (events.isEmpty()) return
        val appCtx = context.applicationContext
        if (!canPost(appCtx)) return
        ensureChannel(appCtx)
        val mgr = NotificationManagerCompat.from(appCtx)

        val openAppPending = PendingIntent.getActivity(
            appCtx,
            0,
            Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        for (e in events) {
            val notificationId = e.taskId.stableNotificationId()
            val isCompleted = e.eventKind.equals(COMPLETED_KIND, ignoreCase = true)

            val title =
                appCtx.getString(
                    if (isCompleted) {
                        R.string.notification_task_completed_title
                    } else {
                        R.string.notification_task_due_title
                    },
                    e.title.take(80),
                )
            val text =
                appCtx.getString(
                    if (isCompleted) {
                        R.string.notification_task_completed_body
                    } else {
                        R.string.notification_task_due_body
                    },
                )

            val notification =
                NotificationCompat.Builder(appCtx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_task)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openAppPending)
                    .setAutoCancel(true)
                    .build()

            mgr.notify(notificationId, notification)
        }
    }

    private fun Long.stableNotificationId(): Int =
        ((this xor (this ushr 32)) and 0x7fff_ffff).toInt()
}
