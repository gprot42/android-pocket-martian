package com.pocketmartian.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketmartian.app.MainActivity
import com.pocketmartian.app.R

/**
 * Keeps the app running while Grok's answer arrives, if the user switches away.
 *
 * Android freezes an app a few seconds after it leaves the screen (on the reporting Pixel:
 * "freezing com.pocketmartian.app" five seconds after it went to the background). A frozen
 * app can't read its connection or answer HTTP/2 pings, so an answer that takes a minute,
 * waited for in another app, came back as "Connection to api.x.ai dropped
 * (SocketException)" with the VPN working fine. A running foreground service is never
 * frozen. It shows a quiet notification while it runs and stops when the answer is in.
 *
 * Android 14 and later run it as a "short service": no special permission or Play Console
 * declaration, but at most about three minutes, after which it stops and the app may be
 * frozen again. Most answers arrive well within that.
 */
class AnswerKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (e: Exception) {
            // Not allowed to start now (the app had already left the screen): the answer
            // carries on without it, as before.
            Log.w(TAG, "Couldn't keep running in the background: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** A short service's time is up (Android 14+); it must stop now or the app is killed. */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Background time limit reached; the answer continues while the app is open")
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Answers in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Grok's answer arrives, so it isn't cut off when you switch apps"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_answering)
            .setContentTitle("Grok is answering…")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val TAG = "AnswerKeepAlive"
        private const val CHANNEL_ID = "answers_in_progress"
        private const val NOTIFICATION_ID = 41

        /** Call as a prompt is sent, while the app is on screen (Android allows it then). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AnswerKeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnswerKeepAliveService::class.java))
        }
    }
}
