package com.daylight.window

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var safetyText: TextView
    private lateinit var planList: LinearLayout
    private lateinit var totalText: TextView
    private lateinit var tripButton: Button
    private lateinit var tripStatus: TextView
    private lateinit var curve: UvCurveView
    private lateinit var peakText: TextView
    private lateinit var skinSpinner: Spinner
    private lateinit var profileSpinner: Spinner
    private lateinit var shapeSpinner: Spinner
    private lateinit var alertSpinner: Spinner
    private lateinit var profileNote: TextView

    private var forecast: DayForecast? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) findLocation() else useStoredOrExplain()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) settings.alertStyle = AlertStyle.IN_APP_ONLY
        syncAlerts()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        Reminders.createChannels(this)

        placeText = findViewById(R.id.place)
        statusText = findViewById(R.id.status)
        headlineText = findViewById(R.id.headline)
        detailText = findViewById(R.id.detail)
        safetyText = findViewById(R.id.safety)
        planList = findViewById(R.id.plan)
        totalText = findViewById(R.id.total)
        tripButton = findViewById(R.id.trip)
        tripStatus = findViewById(R.id.trip_status)
        curve = findViewById(R.id.curve)
        peakText = findViewById(R.id.peak)
        skinSpinner = findViewById(R.id.skin)
        profileSpinner = findViewById(R.id.profile)
        shapeSpinner = findViewById(R.id.shape)
        alertSpinner = findViewById(R.id.alerts)
        profileNote = findViewById(R.id.profile_note)

        paintCurve()
        setUpPickers()

        findViewById<Button>(R.id.relocate).setOnClickListener { requestLocation() }
        findViewById<Button>(R.id.sources).setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }
        tripButton.setOnClickListener { toggleTrip() }
        findViewById<Button>(R.id.test_alert).setOnClickListener { testAlert() }

        requestLocation()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        bind(skinSpinner, R.array.skin_types, settings.skinType - 1) { position ->
            settings.skinType = position + 1
        }

        val profiles = RiskProfile.entries
        bind(
            profileSpinner,
            profiles.map { it.shortLabel },
            profiles.indexOf(settings.riskProfile)
        ) { position ->
            settings.riskProfile = profiles[position]
        }

        val shapes = listOf(DayPlan.Shape.TWO_SESSIONS, DayPlan.Shape.ONE_SESSION)
        bind(
            shapeSpinner,
            listOf(getString(R.string.shape_two), getString(R.string.shape_one)),
            shapes.indexOf(settings.planShape)
        ) { position ->
            settings.planShape = shapes[position]
        }

        val styles = AlertStyle.entries
        bind(alertSpinner, styles.map { it.label }, styles.indexOf(settings.alertStyle)) { position ->
            val chosen = styles[position]
            settings.alertStyle = chosen
            if (chosen != AlertStyle.IN_APP_ONLY &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                syncAlerts()
            }
        }
    }

    private fun bind(
        spinner: Spinner,
        labels: List<String>,
        selected: Int,
        onChosen: (Int) -> Unit
    ) {
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(selected.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onChosen(pos)
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun bind(spinner: Spinner, arrayRes: Int, selected: Int, onChosen: (Int) -> Unit) =
        bind(spinner, resources.getStringArray(arrayRes).toList(), selected, onChosen)

    // ---- Location and forecast ---------------------------------------------------

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

    private fun useStoredOrExplain() {
        if (settings.hasStoredLocation()) {
            loadForecast(settings.lastLatitude, settings.lastLongitude)
        } else {
            statusText.text = getString(R.string.status_no_location)
            headlineText.text = getString(R.string.headline_no_location)
            detailText.text = getString(R.string.detail_no_location)
            safetyText.text = ""
            placeText.text = getString(R.string.place_unknown)
        }
    }

    private fun loadForecast(latitude: Double, longitude: Double) {
        statusText.text = getString(R.string.status_loading)
        headlineText.text = getString(R.string.headline_loading)
        detailText.text = getString(R.string.detail_loading)

        lifecycleScope.launch {
            try {
                val nowMinute = Calendar.getInstance().let {
                    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
                }
                val day = Forecast.fetchCorrected(latitude, longitude, nowMinute)
                forecast = day
                placeText.text = day.placeName
                syncAlerts()
                refresh()
            } catch (e: ForecastUnavailable) {
                statusText.text = getString(R.string.status_offline)
                headlineText.text = getString(R.string.headline_offline)
                detailText.text = e.message ?: getString(R.string.detail_offline)
                safetyText.text = ""
            }
        }
    }

    private fun syncAlerts() {
        if (settings.alertStyle == AlertStyle.IN_APP_ONLY) {
            Reminders.cancelAll(this)
        } else {
            Reminders.scheduleDailyReplan(this)
            Reminders.scheduleToday(this)
        }
    }

    // ---- The screen --------------------------------------------------------------

    private fun refresh() {
        val day = forecast ?: return
        val nowMinute = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

        val plan = DayPlan.build(
            forecast = day,
            skinType = settings.skinType,
            budget = settings.riskProfile.dailyDose,
            shape = settings.planShape,
            alreadySpent = settings.doseUsedToday
        )
        val advice = Advice.describe(
            plan = plan,
            profile = settings.riskProfile,
            nowMinute = nowMinute,
            minutesAlreadySpentOutside = 0,
            forecast = day
        )

        statusText.text = statusWord(advice.state)
        statusText.setTextColor(moodColour(advice.state))
        headlineText.text = advice.headline
        detailText.text = advice.detail
        safetyText.text = advice.safetyLine

        profileNote.text = settings.riskProfile.plainDescription

        renderPlan(plan, nowMinute)
        renderTotal(plan)
        renderTrip(day, plan, nowMinute)

        val samples = SunModel.interpolate(day.hourlyUv)
        peakText.text = getString(
            R.string.peak_today,
            String.format("%.1f", samples.maxOf { it.uv })
        )
        findViewById<TextView>(R.id.source_note).text = when {
            day.wasCorrected -> getString(
                R.string.source_corrected,
                day.liveReading!!.stationName,
                String.format("%.1f", day.liveReading.uv)
            )
            day.liveReading != null -> getString(
                R.string.source_confirmed, day.liveReading.stationName
            )
            else -> getString(R.string.source_forecast_only)
        }
        curve.setDay(
            samples,
            plan.sessions.map {
                SunModel.Window(it.startMinute, it.endMinute, it.peakUv, 0.0)
            },
            day.sunriseMinute,
            day.sunsetMinute,
            uvLimit = 0.0
        )
    }

    private fun statusWord(state: Advice.State) = getString(
        when (state) {
            Advice.State.OUT_NOW -> R.string.status_go
            Advice.State.LATER -> R.string.status_later
            Advice.State.DONE, Advice.State.SPENT -> R.string.status_done
            Advice.State.UNPLANNED -> R.string.status_unplanned
        }
    )

    private fun moodColour(state: Advice.State) = color(
        when (state) {
            Advice.State.OUT_NOW -> R.color.uv_none
            Advice.State.LATER, Advice.State.UNPLANNED -> R.color.uv_low
            else -> R.color.ink_soft
        }
    )

    private fun renderPlan(plan: DayPlan.Plan, nowMinute: Int) {
        planList.removeAllViews()

        if (plan.wholeDayIsSafe) {
            addPlanRow(
                getString(
                    R.string.plan_all_day,
                    Format.clock(plan.sunriseMinute),
                    Format.clock(plan.sunsetMinute)
                ),
                getString(R.string.plan_all_day_note),
                R.color.uv_none
            )
            return
        }

        if (plan.sessions.isEmpty()) {
            addPlanRow(
                getString(R.string.plan_none),
                getString(R.string.plan_none_note),
                R.color.uv_real
            )
            return
        }

        plan.sessions.forEachIndexed { index, session ->
            val past = nowMinute > session.endMinute
            val running = nowMinute in session.startMinute until session.endMinute
            val label = getString(
                if (index == 0) R.string.plan_first else R.string.plan_second
            )
            val note = when {
                running -> getString(R.string.plan_running, Format.duration(session.endMinute - nowMinute))
                past -> getString(R.string.plan_past)
                else -> getString(R.string.plan_length, Format.duration(session.lengthMinutes))
            }
            addPlanRow(
                "$label  ${Format.clock(session.startMinute)} – ${Format.clock(session.endMinute)}",
                note,
                if (running) R.color.uv_none else R.color.ink_soft,
                dimmed = past
            )
        }
    }

    private fun addPlanRow(title: String, note: String, colourRes: Int, dimmed: Boolean = false) {
        val row = layoutInflater.inflate(R.layout.row_plan, planList, false)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_note).apply {
            text = note
            setTextColor(color(colourRes))
        }
        if (dimmed) row.alpha = 0.45f
        planList.addView(row)
    }

    private fun renderTotal(plan: DayPlan.Plan) {
        totalText.text = if (plan.totalMinutes > 0) {
            getString(R.string.total_time, Format.duration(plan.totalMinutes))
        } else {
            getString(R.string.total_none)
        }
    }

    private fun renderTrip(day: DayForecast, plan: DayPlan.Plan, nowMinute: Int) {
        val started = settings.tripStartedAtMinute

        if (started == null) {
            tripButton.text = getString(R.string.trip_start)
            // The plan already has today's spending subtracted, so take what is left
            // of the plan rather than subtracting it a second time.
            val remaining = plan.budget - plan.totalDose
            val couldStay = DayPlan.minutesRemainingFrom(day, nowMinute, remaining)
            val uvNow = SunModel.uvAt(SunModel.interpolate(day.hourlyUv), nowMinute)
            tripStatus.text = Advice.unplannedTripAdvice(couldStay, uvNow)
        } else {
            val elapsed = nowMinute - started
            tripButton.text = getString(R.string.trip_stop)
            val remaining = plan.budget - plan.totalDose -
                DayPlan.doseBetween(SunModel.interpolate(day.hourlyUv), started, nowMinute)
            val left = DayPlan.minutesRemainingFrom(day, nowMinute, remaining)
            tripStatus.text = getString(
                R.string.trip_running,
                Format.duration(maxOf(0, elapsed)),
                Format.duration(left)
            )
        }
    }

    /**
     * Fires a sample alert immediately, so the alarm can be checked without waiting
     * for the moment it matters. Without this there is no way to find out the alarm
     * is silent until the day it needed to ring.
     */
    private fun testAlert() {
        if (settings.alertStyle == AlertStyle.IN_APP_ONLY) {
            tripStatus.text = getString(R.string.test_alert_off)
            return
        }
        Reminders.fire(this, AlertKind.GO_OUT, getString(R.string.test_alert_body))
    }

    private fun toggleTrip() {
        val day = forecast ?: return
        val nowMinute = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        val started = settings.tripStartedAtMinute

        if (started == null) {
            settings.tripStartedAtMinute = nowMinute
        } else {
            val samples = SunModel.interpolate(day.hourlyUv)
            settings.addDoseUsed(DayPlan.doseBetween(samples, started, nowMinute))
            settings.tripStartedAtMinute = null
        }
        refresh()
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
}
