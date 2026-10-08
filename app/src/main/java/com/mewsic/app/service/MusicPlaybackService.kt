package com.mewsic.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mewsic.app.MainActivity
import com.mewsic.app.R
import com.mewsic.app.model.Song
import com.mewsic.app.scanner.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "mewsic_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.mewsic.app.action.PLAY"
        const val ACTION_PAUSE = "com.mewsic.app.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.mewsic.app.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.mewsic.app.action.NEXT"
        const val ACTION_PREV = "com.mewsic.app.action.PREV"
        const val ACTION_STOP = "com.mewsic.app.action.STOP"

        private var currentSong: Song? = null
        private var isCurrentlyPlaying: Boolean = false
        private var currentDuration: Long = 0L
        private var currentPosition: Long = 0L

        fun startOrUpdate(
            context: Context,
            song: Song,
            isPlaying: Boolean,
            durationMs: Long = song.durationMs,
            positionMs: Long = 0L
        ) {
            currentSong = song
            isCurrentlyPlaying = isPlaying
            currentDuration = durationMs
            currentPosition = positionMs

            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = if (isPlaying) ACTION_PLAY else ACTION_PAUSE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MewsicMediaSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    MusicPlaybackController.triggerPlayPause()
                }

                override fun onPause() {
                    MusicPlaybackController.triggerPlayPause()
                }

                override fun onSkipToNext() {
                    MusicPlaybackController.triggerNext()
                }

                override fun onSkipToPrevious() {
                    MusicPlaybackController.triggerPrev()
                }

                override fun onSeekTo(pos: Long) {
                    MusicPlaybackController.triggerSeekTo(pos)
                }

                override fun onStop() {
                    MusicPlaybackController.triggerStop()
                    stopService()
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                MusicPlaybackController.triggerPlayPause()
            }
            ACTION_PLAY -> {
                updatePlaybackStateAndNotification()
            }
            ACTION_PAUSE -> {
                updatePlaybackStateAndNotification()
            }
            ACTION_NEXT -> {
                MusicPlaybackController.triggerNext()
            }
            ACTION_PREV -> {
                MusicPlaybackController.triggerPrev()
            }
            ACTION_STOP -> {
                MusicPlaybackController.triggerStop()
                stopService()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun updatePlaybackStateAndNotification() {
        val song = currentSong ?: return
        val isPlaying = isCurrentlyPlaying
        val duration = currentDuration
        val position = currentPosition

        // 1. Update MediaSession PlaybackState
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        // 2. Synchronously obtain cached bitmap or generate fast fallback
        val cached = ThumbnailLoader.getCachedBitmap(song.id)
        val initialBitmap = cached ?: ThumbnailLoader.getFallbackBitmap(song.artist, song.album, 256)

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, initialBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, initialBitmap)
        mediaSession.setMetadata(metadataBuilder.build())

        // 3. Immediately satisfy Android startForegroundService contract
        val notification = buildNotification(song, isPlaying, initialBitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // If paused, detach from foreground requirement so notification can be dismissed if desired
        if (!isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        }

        // 4. If artwork was not cached yet, load it asynchronously and refresh notification & metadata
        if (cached == null) {
            serviceScope.launch {
                val fullBitmap = withContext(Dispatchers.IO) {
                    ThumbnailLoader.getOrLoadBitmap(applicationContext, song, 512)
                }

                val updatedMetadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, fullBitmap)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, fullBitmap)
                    .build()
                mediaSession.setMetadata(updatedMetadata)

                val updatedNotification = buildNotification(song, isCurrentlyPlaying, fullBitmap)
                notificationManager.notify(NOTIFICATION_ID, updatedNotification)
            }
        }
    }

    private fun buildNotification(song: Song, isPlaying: Boolean, albumArt: Bitmap): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY_PAUSE }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            4,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mewsic_logo)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(if (song.album.isNotBlank() && song.album != "Unknown Album") song.album else null)
            .setLargeIcon(albumArt)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setColor(ContextCompat.getColor(this, R.color.brand_emerald))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_player_prev, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_player_next, "Next", nextPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent media controls and playback information"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
