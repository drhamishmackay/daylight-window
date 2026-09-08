package com.daylight.window

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Today's forecast for one place. */
data class DayForecast(
    val placeName: String,
    val hourlyUv: List<Double>,
    val sunriseMinute: Int,
    val sunsetMinute: Int,
    val fetchedAtMillis: Long,
    /**
     * A ground-station measurement used to correct the forecast, when one was
     * available near enough to be relevant. Null everywhere without a nearby station.
     */
    val liveReading: LiveReading? = null,
    /** What the forecast was scaled by; 1.0 means it was used unchanged. */
    val correction: Double = 1.0
) {
    /** True when a real measurement moved the numbers. */
    val wasCorrected: Boolean get() = liveReading != null && correction != 1.0
}

/**
 * Fetches the hourly UV forecast from Open-Meteo, which is free, needs no key, and
 * covers the whole world. Cloud cover is already reflected in the UV numbers.
 */
object Forecast {

    private const val TIMEOUT_MS = 15_000

    /**
     * Today's forecast, corrected against a nearby ground station where one exists.
     *
     * The correction is applied to the whole curve. A model running 20 per cent high
     * at noon is generally running high all day — the usual cause is cloud the model
     * did not predict, which persists for hours rather than minutes.
     */
    suspend fun fetchCorrected(
        latitude: Double,
        longitude: Double,
        nowMinuteOfDay: Int
    ): DayForecast {
        val raw = fetch(latitude, longitude)
        val live = LiveUv.nearestReading(latitude, longitude) ?: return raw

        val samples = SunModel.interpolate(raw.hourlyUv)
        val forecastNow = SunModel.uvAt(samples, nowMinuteOfDay)
        val factor = LiveUv.correctionFactor(forecastNow, live.uv)
        if (factor == 1.0) return raw.copy(liveReading = live)

        return raw.copy(
            hourlyUv = raw.hourlyUv.map { it * factor },
            liveReading = live,
            correction = factor
        )
    }

    suspend fun fetch(latitude: Double, longitude: Double): DayForecast =
        withContext(Dispatchers.IO) {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&hourly=uv_index&daily=sunrise,sunset" +
                    "&timezone=auto&forecast_days=1"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw ForecastUnavailable(
                        "The weather service answered with code ${connection.responseCode}."
                    )
                }
                parse(connection.inputStream.bufferedReader().readText())
            } catch (e: ForecastUnavailable) {
                throw e
            } catch (e: Exception) {
                throw ForecastUnavailable("Could not reach the weather service.", e)
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(body: String): DayForecast {
        val root = JSONObject(body)

        val hourly = root.getJSONObject("hourly").getJSONArray("uv_index")
        val uv = ArrayList<Double>(hourly.length())
        for (i in 0 until hourly.length()) {
            if (hourly.isNull(i)) throw ForecastUnavailable("The forecast had a gap in it.")
            uv.add(hourly.getDouble(i))
        }
        if (uv.isEmpty()) throw ForecastUnavailable("The forecast came back empty.")

        val daily = root.getJSONObject("daily")
        val sunrise = minuteOfDay(daily.getJSONArray("sunrise").getString(0))
        val sunset = minuteOfDay(daily.getJSONArray("sunset").getString(0))
        if (sunset <= sunrise) {
            throw ForecastUnavailable("The forecast reported sunset before sunrise.")
        }

        val zone = root.getString("timezone")
        return DayForecast(
            placeName = zone.substringAfterLast('/').replace('_', ' '),
            hourlyUv = uv,
            sunriseMinute = sunrise,
            sunsetMinute = sunset,
            fetchedAtMillis = System.currentTimeMillis()
        )
    }

    /** Reads "2026-09-08T06:31" into minutes past midnight. */
    private fun minuteOfDay(isoTime: String): Int {
        val time = isoTime.substringAfter('T')
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(3, 5).toInt()
        return hour * 60 + minute
    }
}

class ForecastUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
