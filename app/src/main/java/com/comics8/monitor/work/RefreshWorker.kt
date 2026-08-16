package com.comics8.monitor.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.comics8.monitor.ComicsApp
import com.comics8.monitor.MainActivity
import com.comics8.monitor.R
import java.util.concurrent.TimeUnit

import com.comics8.monitor.data.ToonItem

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ComicsApp ?: return Result.success()
        return try {
            val fresh = app.repository.checkLatestUpdates()
            if (fresh.isNotEmpty()) {
                notifyUpdates(fresh)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyUpdates(items: List<ToonItem>) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)

        val launch = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val count = items.size
        val title = if (count == 1) {
            "즐겨찾기: ${items.first().title}"
        } else {
            "즐겨찾기 만화 ${count}개 갱신"
        }
        val text = if (count == 1) {
            "새로운 회차가 등록되었습니다."
        } else {
            items.take(4).joinToString(" · ") { it.title }
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n") { "• ${it.title}" }))
            .setContentIntent(launch)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFY_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS
        }
    }

    companion object {
        private const val UNIQUE_NAME = "latest-refresh"
        private const val CHANNEL_ID = "latest-updates"
        private const val NOTIFY_ID = 81

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
