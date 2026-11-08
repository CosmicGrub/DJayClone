package com.oblivion.djayclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/** How the service reaches the decks (which live in Activity-scoped ViewModels). */
object PlaybackBridge {
    @Volatile var pauseAll: (() -> Unit)? = null
}

/**
 * Keeps the process at foreground priority and shows a media notification while a deck plays.
 *
 * The decks are owned by the Activity's ViewModels, so this service does not own audio: it is
 * what stops Android from reclaiming the app when it is in the background or the screen is off
 * mid-set, and what gives the DJ a Pause button in the shade and headset/lock-screen buttons.
 * MainActivity starts it when a deck starts playing and stops it when both are paused or the
 * Activity is destroyed.
 */
class PlaybackService : Service() {

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val s = MediaSession(this, "DJayClone")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPause() { PlaybackBridge.pauseAll?.invoke() }
            override fun onStop() { PlaybackBridge.pauseAll?.invoke() }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        s.isActive = true
        session = s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) {
            PlaybackBridge.pauseAll?.invoke()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Playing"
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_play)
            .setContentTitle("DJayClone")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pause all", pause).build())
        session?.let { builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0)) }
        return builder.build()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.oblivion.djayclone.action.PAUSE_ALL"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1002
    }
}
