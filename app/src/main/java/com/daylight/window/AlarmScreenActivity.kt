package com.daylight.window

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen that appears when an alarm rings: over the lock screen, over whatever
 * else is open, with the screen turned on.
 *
 * The window flags follow Google's own Clock app (AlarmActivity in the open-source
 * DeskClock): show when locked, turn the screen on, keep it on, and dismiss the
 * keyguard. Volume buttons are routed to the alarm stream so pressing them adjusts
 * the alarm rather than the ringer.
 */
class AlarmScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverEverything()
        setContentView(R.layout.activity_alarm_screen)

        // Volume keys should change the alarm volume while it is ringing.
        volumeControlStream = AudioManager.STREAM_ALARM

        val kindName = intent.getStringExtra(AlarmService.EXTRA_KIND)
            ?: throw IllegalStateException("Alarm screen opened with no kind")
        val message = intent.getStringExtra(AlarmService.EXTRA_MESSAGE)
            ?: throw IllegalStateException("Alarm screen opened with no message")
        val kind = AlertKind.valueOf(kindName)

        findViewById<TextView>(R.id.alarm_title).setText(
            if (kind == AlertKind.GO_OUT) R.string.alert_go_out_title
            else R.string.alert_come_in_title
        )
        findViewById<TextView>(R.id.alarm_message).text = message

        findViewById<Button>(R.id.alarm_stop).setOnClickListener {
            AlarmService.stop(this)
            finish()
        }

        findViewById<Button>(R.id.alarm_open).setOnClickListener {
            AlarmService.stop(this)
            startActivity(
                android.content.Intent(this, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }

    /**
     * Brings the screen up over the lock screen and over any other app, and turns the
     * display on. The modern calls are used where available; the flags remain for
     * older versions, and the keyguard is dismissed separately because the flag alone
     * does not do it on newer Android.
     */
    private fun showOverEverything() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    /**
     * The back button must not silence the alarm — that is how a ringing alarm gets
     * dismissed by accident in a pocket. Only the buttons on the screen stop it.
     */
    @Deprecated("Kept deliberately: back must not dismiss a ringing alarm")
    override fun onBackPressed() {
        // Intentionally does nothing.
    }
}
