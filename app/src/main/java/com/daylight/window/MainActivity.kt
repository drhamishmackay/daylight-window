package com.daylight.window

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var placeText: TextView
    private lateinit var statusText: TextView
    private lateinit var headlineText: TextView
    private lateinit var detailText: TextView
    private lateinit var peakText: TextView
    private lateinit var curve: UvCurveView
    private lateinit var windowList: LinearLayout
    private lateinit var summaryValue: TextView
    private lateinit var summaryBar: ProgressBar
    private lateinit var summaryHint: TextView
    private lateinit var skinSpinner: Spinner
    private lateinit var limitSpinner: Spinner
    private lateinit var remindButton: Button

    private var forecast: DayForecast? = null

    /** The limits offered, in the order they appear in the picker. */
    private val limitChoices = listOf(1.0, 2.0, 3.0, 5.0, 99.0)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) findLocation() else useStoredOrExplain()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        settings.remindersOn = granted
        if (granted) {
            Reminders.scheduleDailyReplan(this)
            Reminders.scheduleNextWindow(this)
        }
        updateRemindButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        Reminders.createChannel(this)

        placeText = findViewById(R.id.place)
        statusText = findViewById(R.id.status)
        headlineText = findViewById(R.id.headline)
        detailText = findViewById(R.id.detail)
        peakText = findViewById(R.id.peak)
        curve = findViewById(R.id.curve)
        windowList = findViewById(R.id.windows)
        summaryValue = findViewById(R.id.summary_value)
        summaryBar = findViewById(R.id.summary_bar)
        summaryHint = findViewById(R.id.summary_hint)
        skinSpinner = findViewById(R.id.skin)
        limitSpinner = findViewById(R.id.limit)
        remindButton = findViewById(R.id.remind)

        paintCurve()
        setUpPickers()
        findViewById<Button>(R.id.relocate).setOnClickListener { requestLocation() }
        remindButton.setOnClickListener { toggleReminders() }
        updateRemindButton()

        requestLocation()
    }

    override fun onResume() {
        super.onResume()
        forecast?.let { show(it) }
    }

    private fun paintCurve() {
        curve.applyPalette(
            curve = color(R.color.accent),
            grid = color(R.color.rule),
            night = color(R.color.night),
            window = color(R.color.uv_none),
            limit = color(R.color.uv_real),
            now = color(R.color.ink),
            label = color(R.color.ink_soft)
        )
    }

    private fun setUpPickers() {
        skinSpinner.adapter = ArrayAdapter.createFromResource(
            this, R.array.skin_types, android.R.layout.simple_spinner_item
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        skinSpinner.setSelection(settings.skinType - 1)
        skinSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.skinType = pos + 1
                forecast?.let { show(it) }
                if (settings.remindersOn) Reminders.scheduleNextWindow(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        limitSpinner.adapter = ArrayAdapter.createFromResource(
            this, R.array.uv_limits, android.R.layout.simple_spinner_item
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        limitSpinner.setSelection(limitChoices.indexOf(settings.uvLimit).coerceAtLeast(0))
        limitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.uvLimit = limitChoices[pos]
                forecast?.let { show(it) }
                if (settings.remindersOn) Reminders.scheduleNextWindow(this@MainActivity)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            findLocation()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    private fun findLocation() {
        placeText.text = getString(R.string.locating)
        val manager = getSystemService(LocationManager::class.java)
        val fix: Location? = manager.getProviders(true)
            .mapNotNull {
                try { manager.getLastKnownLocation(it) } catch (e: SecurityException) { null }
            }
            .maxByOrNull { it.time }

        if (fix != null) {
            settings.lastLatitude = fix.latitude
            settings.lastLongitude = fix.longitude
            loadForecast(fix.latitude, fix.longitude)
        } else {
            useStoredOrExplain()
        }
    }

    /**
     * With no fresh fix, fall back to the last place we knew. If there has never been
     * one, say so plainly rather than guessing at a city the user is not in.
     */
    private fun useStoredOrExplain() {
        if (settings.hasStoredLocation()) {
            loadForecast(settings.lastLatitude, settings.lastLongitude)
        } else {
            statusText.text = getString(R.string.status_no_location)
            headlineText.text = getString(R.string.headline_no_location)
            detailText.text = getString(R.string.detail_no_location)
            placeText.text = getString(R.string.place_unknown)
        }
    }

    private fun loadForecast(latitude: Double, longitude: Double) {
        statusText.text = getString(R.string.status_loading)
        headlineText.text = getString(R.string.headline_loading)
        detailText.text = getString(R.string.detail_loading)

        lifecycleScope.launch {
            try {
                val day = Forecast.fetch(latitude, longitude)
                forecast = day
                show(day)
            } catch (e: ForecastUnavailable) {
                statusText.text = getString(R.string.status_offline)
                headlineText.text = getString(R.string.headline_offline)
                detailText.text = e.message ?: getString(R.string.detail_offline)
            }
        }
    }

    private fun show(day: DayForecast) {
        val calendar = Calendar.getInstance()
        val nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val result = Verdict.decide(day, settings.skinType, settings.uvLimit, nowMinute)

        placeText.text = day.placeName
        statusText.text = result.status
        headlineText.text = result.headline
        detailText.text = result.detail

        val accent = when (result.mood) {
            Verdict.Mood.GO -> color(R.color.uv_none)
            Verdict.Mood.WAIT -> color(R.color.uv_low)
            else -> color(R.color.uv_real)
        }
        statusText.setTextColor(accent)

        peakText.text = getString(
            R.string.peak_and_limit,
            String.format("%.1f", result.peakUv),
            if (settings.uvLimit >= 99) getString(R.string.limit_none)
            else settings.uvLimit.toInt().toString()
        )

        curve.setDay(
            SunModel.interpolate(day.hourlyUv),
            result.windows,
            day.sunriseMinute,
            day.sunsetMinute,
            settings.uvLimit
        )

        renderWindows(result, nowMinute)
        renderSummary(result)
    }

    private fun renderWindows(result: Verdict.Result, nowMinute: Int) {
        windowList.removeAllViews()
        if (result.windows.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.row_window, windowList, false)
            empty.findViewById<TextView>(R.id.row_time).visibility = View.GONE
            empty.findViewById<TextView>(R.id.row_cost).visibility = View.GONE
            empty.findViewById<TextView>(R.id.row_note).text =
                getString(R.string.no_windows_row, settings.uvLimit.toInt())
            windowList.addView(empty)
            return
        }

        for (window in result.windows) {
            val row = layoutInflater.inflate(R.layout.row_window, windowList, false)
            val live = nowMinute in window.startMinute..window.endMinute
            val past = nowMinute > window.endMinute

            row.findViewById<TextView>(R.id.row_time).text = getString(
                R.string.window_range,
                Format.clock(window.startMinute),
                Format.clock(window.endMinute)
            )

            val state = when {
                live -> getString(R.string.window_open_now)
                past -> getString(R.string.window_passed)
                else -> ""
            }
            row.findViewById<TextView>(R.id.row_note).text = getString(
                R.string.window_note,
                Format.duration(window.lengthMinutes),
                String.format("%.1f", window.peakUv),
                state
            )

            val pinkAt = SunModel.minutesUntilPinking(window.peakUv, settings.skinType)
            row.findViewById<TextView>(R.id.row_cost).text =
                if (pinkAt == null || window.dosePercent < 100) getString(R.string.window_safe)
                else getString(R.string.window_cap, Format.duration(pinkAt))

            row.alpha = if (past) 0.45f else 1f
            windowList.addView(row)
        }
    }

    private fun renderSummary(result: Verdict.Result) {
        summaryValue.text = if (result.totalMinutesOutside > 0)
            Format.duration(result.totalMinutesOutside) else getString(R.string.summary_none)
        summaryBar.progress = result.totalDosePercent.coerceIn(0.0, 100.0).toInt()
        summaryHint.text = when {
            result.totalMinutesOutside == 0 -> getString(R.string.summary_hint_none)
            result.totalDosePercent < 100 ->
                getString(R.string.summary_hint_ok, Format.percent(result.totalDosePercent))
            else -> getString(R.string.summary_hint_over)
        }
    }

    private fun toggleReminders() {
        if (settings.remindersOn) {
            settings.remindersOn = false
            Reminders.cancelAll(this)
            updateRemindButton()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            settings.remindersOn = true
            Reminders.scheduleDailyReplan(this)
            Reminders.scheduleNextWindow(this)
            updateRemindButton()
        }
    }

    private fun updateRemindButton() {
        remindButton.text = getString(
            if (settings.remindersOn) R.string.reminders_on else R.string.reminders_off
        )
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
