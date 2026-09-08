package com.daylight.window

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Rings until it is dealt with.
 *
 * A notification's sound plays once and stops, which is easy to sleep through and easy
 * to miss. A real alarm keeps going until you act on it, so the sound and vibration
 * live here, in a foreground service that holds them until the alarm screen is
 * dismissed or the safety timeout expires.
 *
 * The service is what makes this an alarm rather than a reminder.
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRinging()
            return START_NOT_STICKY
        }

        val kindName = intent?.getStringExtra(EXTRA_KIND)
            ?: throw IllegalStateException("Alarm service started with no kind")
        val message = intent.getStringExtra(EXTRA_MESSAGE)
            ?: throw IllegalStateException("Alarm service started with no message")
        val kind = AlertKind.valueOf(kindName)

        startForeground(NOTIFICATION_ID, buildNotification(kind, message))
        startRinging()

        // A safety net: if nothing dismisses the alarm — the phone is in a bag, the
        // screen never came on — stop rather than ringing until the battery dies.
        android.os.Handler(mainLooper).postDelayed({ stopRinging() }, MAX_RING_MILLIS)

        return START_STICKY
    }

    /**
     * Plays the phone's alarm ringtone on a loop, on the alarm audio stream so it is
     * heard even when the phone is set to silent — which is the point of an alarm.
     */
    private fun startRinging() {
        if (player != null) return

        val tone: Uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        player = MediaPlayer().apply {
            setDataSource(this@AlarmService, tone)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            prepare()
            start()
        }

        vibrator = currentVibrator()
        val pattern = longArrayOf(0, 500, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }

        // Keep the processor awake so the sound does not stop when the screen sleeps.
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "daylight:alarm"
        ).apply { acquire(MAX_RING_MILLIS) }
    }

    private fun stopRinging() {
        player?.run {
            if (isPlaying) stop()
            release()
        }
        player = null

        vibrator?.cancel()
        vibrator = null

        wakeLock?.run { if (isHeld) release() }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun currentVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * The notification that carries the alarm. Its full-screen intent is what brings
     * the alarm screen up over the lock screen, the way a wake-up alarm does.
     */
    private fun buildNotification(kind: AlertKind, message: String): Notification {
        val goingOut = kind == AlertKind.GO_OUT

        val screen = Intent(this, AlarmScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_MESSAGE, message)
        }
        val screenIntent = PendingIntent.getActivity(
            this, 0, screen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                getString(
                    if (goingOut) R.string.alert_go_out_title else R.string.alert_come_in_title
                )
            )
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(screenIntent, true)
            .setContentIntent(screenIntent)
            .addAction(0, getString(R.string.alarm_dismiss), stop)
            .build()
    }

    companion object {
        const val CHANNEL_RINGING = "daylight_ringing"
        const val ACTION_STOP = "com.daylight.window.STOP_ALARM"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MESSAGE = "message"

        private const val NOTIFICATION_ID = 3001

        /**
         * Stops on its own after this long. A missed alarm is a nuisance; one that
         * rings all day in a bag until the battery is flat is worse.
         */
        private const val MAX_RING_MILLIS = 5 * 60 * 1000L

        /**
         * The channel an alarm rings on. Its importance and sound are fixed when it is
         * first created, so the sound is deliberately set to none here: the service
         * plays the ringtone itself, on a loop, which a channel sound cannot do.
         */
        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_RINGING,
                context.getString(R.string.channel_ringing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_ringing_description)
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context, kind: AlertKind, message: String) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_MESSAGE, message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AlarmService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
