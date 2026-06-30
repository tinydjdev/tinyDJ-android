package com.tinydj.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.tinydj.MainActivity
import com.tinydj.TinyDjApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_PLAY) {
                val container = (application as TinyDjApp).container
                container.audioEngine.togglePlay()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_PLAY = "com.tinydj.ACTION_TOGGLE_PLAY"
        const val ACTION_STOP_SERVICE = "com.tinydj.ACTION_STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        
        val filter = IntentFilter(ACTION_TOGGLE_PLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }

        // Call startForeground immediately to satisfy Android's foreground requirements
        val container = (application as TinyDjApp).container
        val engine = container.audioEngine
        val library = container.library
        val state = engine.state.value
        val track = library.tracks.value.find { it.id == state.trackId }
        val notification = buildNotification(state, track)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        observePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        unregisterReceiver(toggleReceiver)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        
        // Ensure audio stops if the service is explicitly stopped or swiped away
        val container = (application as TinyDjApp).container
        container.audioEngine.pause()
        
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows playback status and controls for TinyDJ"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        val container = (application as TinyDjApp).container
        val engine = container.audioEngine

        mediaSession = MediaSession(this, "TinyDJ").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    engine.play()
                }

                override fun onPause() {
                    engine.pause()
                }
            })
            isActive = true
        }
    }

    private fun observePlaybackState() {
        val container = (application as TinyDjApp).container
        val engine = container.audioEngine
        val library = container.library

        serviceScope.launch {
            engine.state.collectLatest { state ->
                val track = library.tracks.value.find { it.id == state.trackId }
                updateMediaSessionState(state)
                val notification = buildNotification(state, track)
                
                if (state.isPlaying) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID, 
                            notification, 
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun updateMediaSessionState(state: EngineState) {
        val playbackStateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE
            )
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs,
                state.speed
            )
        mediaSession?.setPlaybackState(playbackStateBuilder.build())
    }

    private fun buildNotification(state: EngineState, track: AudioTrack?): Notification {
        val title = track?.title ?: "No track loaded"
        val artist = track?.artist ?: "Unknown artist"

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseBroadcastIntent = Intent(ACTION_TOGGLE_PLAY)
        val playPausePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            playPauseBroadcastIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val deleteIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val deletePendingIntent = PendingIntent.getService(
            this,
            2,
            deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = if (state.isPlaying) "Pause" else "Play"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val icon = android.graphics.drawable.Icon.createWithResource(this, playPauseIcon)

        builder.setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(state.isPlaying)
            .addAction(
                Notification.Action.Builder(
                    icon,
                    playPauseLabel,
                    playPausePendingIntent
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )

        return builder.build()
    }
}
