package com.daylight.window

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * A real ultraviolet measurement from a ground station, used to correct the forecast.
 *
 * The forecast is a model, and models drift — unpredicted cloud is the usual reason.
 * A measurement taken minutes ago at a station near you is the better number, and the
 * gap between the two tells you how far off today's model is running.
 *
 * Australia only. ARPANSA runs the stations and publishes the readings openly; there
 * is no equivalent free live feed with worldwide coverage, so everywhere else the app
 * uses the forecast alone.
 */
data class LiveReading(
    val stationName: String,
    val uv: Double,
    val distanceKm: Double,
    val observedUtc: String
)

object LiveUv {

    private const val FEED_URL = "https://uvdata.arpansa.gov.au/xml/uvvalues.xml"
    private const val TIMEOUT_MS = 10_000

    /**
     * A station further away than this is measuring different weather, so its reading
     * says nothing useful about your sky. Roughly the distance across a large city and
     * its surrounds.
     */
    const val USEFUL_RANGE_KM = 75.0

    /**
     * Below this the reading is too small for the ratio between forecast and
     * measurement to mean anything — dividing two near-zero numbers amplifies noise
     * rather than correcting it.
     */
    private const val MEANINGFUL_UV = 0.5

    /** ARPANSA's monitoring stations, with coordinates. */
    private val STATIONS = mapOf(
        "Adelaide" to (-34.92 to 138.60),
        "Alice Springs" to (-23.70 to 133.88),
        "Brisbane" to (-27.47 to 153.03),
        "Canberra" to (-35.28 to 149.13),
        "Casey" to (-66.28 to 110.53),
        "Darwin" to (-12.46 to 130.84),
        "Davis" to (-68.58 to 77.97),
        "Emerald" to (-23.53 to 148.16),
        "Gold Coast" to (-28.02 to 153.40),
        "Kingston" to (-42.98 to 147.31),
        "Macquarie Island" to (-54.50 to 158.94),
        "Mawson" to (-67.60 to 62.87),
        "Melbourne" to (-37.81 to 144.96),
        "Newcastle" to (-32.93 to 151.78),
        "Perth" to (-31.95 to 115.86),
        "Sydney" to (-33.87 to 151.21),
        "Townsville" to (-19.26 to 146.82)
    )

    /**
     * The nearest station's current reading, or null when there is no station close
     * enough, the feed is unreachable, or the station is reporting a fault.
     *
     * Never throws: a missing live reading is a lost improvement, not a failure. The
     * forecast alone is still a usable answer.
     */
    suspend fun nearestReading(latitude: Double, longitude: Double): LiveReading? =
        withContext(Dispatchers.IO) {
            val nearest = STATIONS
                .map { (name, coords) ->
                    name to distanceKm(latitude, longitude, coords.first, coords.second)
                }
                .minByOrNull { it.second }
                ?: return@withContext null

            if (nearest.second > USEFUL_RANGE_KM) return@withContext null

            val body = try {
                val connection = (URL(FEED_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    connection.inputStream.bufferedReader().readText()
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                return@withContext null
            }

            parseStation(body, nearest.first)?.let { (uv, observed) ->
                LiveReading(nearest.first, uv, nearest.second, observed)
            }
        }

    /**
     * Pulls one station's reading out of the feed. Returns null if the station is
     * missing, marked as anything other than working, or carries an unreadable value —
     * a broken sensor must never be treated as "the sun is off".
     */
    internal fun parseStation(xml: String, stationId: String): Pair<Double, String>? {
        val start = xml.indexOf("id=\"$stationId\"")
        if (start < 0) return null
        val end = xml.indexOf("</location>", start)
        if (end < 0) return null
        val block = xml.substring(start, end)

        val status = tag(block, "status") ?: return null
        if (!status.equals("ok", ignoreCase = true)) return null

        val uv = tag(block, "index")?.toDoubleOrNull() ?: return null
        if (uv < 0) return null

        val observed = tag(block, "utcdatetime") ?: return null
        return uv to observed
    }

    private fun tag(block: String, name: String): String? {
        val open = block.indexOf("<$name>")
        if (open < 0) return null
        val close = block.indexOf("</$name>", open)
        if (close < 0) return null
        return block.substring(open + name.length + 2, close).trim()
    }

    /**
     * How much to scale the forecast by, given what a nearby station is actually
     * measuring. Returns 1.0 (leave the forecast alone) whenever the comparison would
     * not be meaningful.
     *
     * The correction is capped: a live reading should nudge the day's shape, not
     * replace it. One station under a passing cloud should not rewrite an entire
     * afternoon.
     */
    fun correctionFactor(forecastUvNow: Double, measuredUvNow: Double): Double {
        if (forecastUvNow < MEANINGFUL_UV || measuredUvNow < MEANINGFUL_UV) return 1.0
        val raw = measuredUvNow / forecastUvNow
        return raw.coerceIn(MIN_CORRECTION, MAX_CORRECTION)
    }

    /**
     * Bounds on the correction. Cloud can genuinely halve ground-level ultraviolet, and
     * a forecast that underestimated clear sky can be low by a similar margin, so the
     * cap sits at half and one and a half. Beyond that the more likely explanation is a
     * faulty sensor or a station measuring different weather, and trusting it would be
     * worse than trusting the model.
     */
    private const val MIN_CORRECTION = 0.5
    private const val MAX_CORRECTION = 1.5

    /** Great-circle distance in kilometres. */
    internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
