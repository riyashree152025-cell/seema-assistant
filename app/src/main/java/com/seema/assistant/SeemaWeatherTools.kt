package com.seema.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Weather forecasts from Open-Meteo (free, no API key).
 * KSP generates `SeemaWeatherTools.generatedTools()`.
 */
class SeemaWeatherTools(context: Context) {

    private val appContext = context.applicationContext

    private data class Place(val name: String, val latitude: Double, val longitude: Double)

    @Tool(
        description = "Gets the weather: current conditions, high and low temperature, chance of rain " +
            "and when rain is likely. Use for 'will it rain today', 'how hot is it', 'weather tomorrow in Delhi'."
    )
    fun getWeather(
        @Param("City name. Omit to use the phone's current location.") city: String? = null,
        @Param("0 for today, 1 for tomorrow, 2 for the day after. Omit for today.") day: Int? = null
    ): Map<String, String> {
        val dayIndex = (day ?: 0).coerceIn(0, MAX_DAY_INDEX)
        return try {
            val requested = city?.trim()?.takeIf { it.isNotEmpty() }
            val place = if (requested != null) {
                geocode(requested) ?: return failure("Could not find a place called '$requested'.")
            } else {
                // From background work (daily briefing) location may be denied; fall back to the home city.
                runCatching { currentPlace() }.getOrNull()
                    ?: BuildConfig.SEEMA_HOME_CITY.takeIf { it.isNotBlank() }?.let { geocode(it) }
                    ?: return failure(
                        "Location unknown. Ask the user which city, or to allow Location for SEEMA."
                    )
            }
            forecast(place, dayIndex)
        } catch (e: IOException) {
            failure("The weather service is unreachable. Check the internet connection.")
        } catch (e: JSONException) {
            failure("The weather service returned unexpected data.")
        }
    }

    // -------------------------------------------------------------- forecast

    /**
     * For background checks (not a tool): a short spoken alert if it is raining now or rain is
     * likely within [withinHours], otherwise null.
     */
    fun rainAlert(withinHours: Int = 2): String? = try {
        // Background location may be denied; fall back to the home city.
        val place = runCatching { currentPlace() }.getOrNull()
            ?: BuildConfig.SEEMA_HOME_CITY.takeIf { it.isNotBlank() }?.let { geocode(it) }
        if (place == null) {
            null
        } else {
            val json = fetchForecast(place)
            val nowCode = json.getJSONObject("current").optInt("weather_code", -1)
            if (nowCode in RAIN_CODES) {
                "Heads up: ${describeWeatherCode(nowCode)} in ${place.name} right now. Take an umbrella if you're heading out."
            } else {
                upcomingRain(json, withinHours)?.let { (hour, chance) ->
                    "Heads up: rain is likely around ${hour.format(HOUR_FORMAT)} in ${place.name}, " +
                        "about $chance percent. Take an umbrella."
                }
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    /** First hour within [withinHours] whose rain chance reaches the alert threshold. */
    private fun upcomingRain(json: JSONObject, withinHours: Int): Pair<LocalDateTime, Int>? {
        val zone = runCatching { ZoneId.of(json.getString("timezone")) }.getOrDefault(ZoneId.systemDefault())
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val chances = hourly.getJSONArray("precipitation_probability")
        val now = LocalDateTime.now(zone)
        val until = now.plusHours(withinHours.toLong())
        return (0 until times.length()).firstNotNullOfOrNull { i ->
            val hour = LocalDateTime.parse(times.getString(i))
            val chance = chances.optInt(i, 0)
            if (hour.isAfter(now.truncatedTo(ChronoUnit.HOURS)) && !hour.isAfter(until) && chance >= ALERT_RAIN_PERCENT) {
                hour to chance
            } else {
                null
            }
        }
    }

    private fun fetchForecast(place: Place): JSONObject = getJson(
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,precipitation_sum,sunrise,sunset" +
            "&hourly=precipitation_probability" +
            "&timezone=auto&forecast_days=${MAX_DAY_INDEX + 1}"
    )

    private fun forecast(place: Place, dayIndex: Int): Map<String, String> {
        val json = fetchForecast(place)
        val daily = json.getJSONObject("daily")
        val date = LocalDate.parse(daily.getJSONArray("time").getString(dayIndex))
        val zone = runCatching { ZoneId.of(json.getString("timezone")) }.getOrDefault(ZoneId.systemDefault())

        return buildMap {
            put("status", "ok")
            put("place", place.name)
            put("day", if (dayIndex == 0) "today" else if (dayIndex == 1) "tomorrow" else date.format(DAY_FORMAT))
            put("condition", describeWeatherCode(daily.getJSONArray("weather_code").optInt(dayIndex, -1)))
            daily.getJSONArray("temperature_2m_max").celsius(dayIndex)?.let { put("high", it) }
            daily.getJSONArray("temperature_2m_min").celsius(dayIndex)?.let { put("low", it) }
            daily.getJSONArray("precipitation_probability_max").optIntOrNull(dayIndex)
                ?.let { put("rain_chance_percent", it.toString()) }
            daily.getJSONArray("precipitation_sum").optDoubleOrNull(dayIndex)
                ?.let { put("rain_mm", "%.1f".format(Locale.ROOT, it)) }
            put("rain_likely", rainWindow(json.getJSONObject("hourly"), date, zone, dayIndex == 0))
            daily.getJSONArray("sunrise").localTime(dayIndex)?.let { put("sunrise", it) }
            daily.getJSONArray("sunset").localTime(dayIndex)?.let { put("sunset", it) }

            if (dayIndex == 0) {
                val current = json.getJSONObject("current")
                put("now_condition", describeWeatherCode(current.optInt("weather_code", -1)))
                current.optDoubleOrNull("temperature_2m")?.let { put("now_temperature", "%.0f°C".format(Locale.ROOT, it)) }
                current.optDoubleOrNull("apparent_temperature")?.let { put("feels_like", "%.0f°C".format(Locale.ROOT, it)) }
                current.optDoubleOrNull("relative_humidity_2m")?.let { put("humidity_percent", "%.0f".format(Locale.ROOT, it)) }
                current.optDoubleOrNull("wind_speed_10m")?.let { put("wind_kmh", "%.0f".format(Locale.ROOT, it)) }
            }
        }
    }

    /** "2 PM to 6 PM" for hours with a high chance of rain, or "no rain expected". */
    private fun rainWindow(hourly: JSONObject, date: LocalDate, zone: ZoneId, onlyFuture: Boolean): String {
        val times = hourly.getJSONArray("time")
        val chances = hourly.getJSONArray("precipitation_probability")
        val currentHour = LocalDateTime.now(zone).truncatedTo(ChronoUnit.HOURS)

        val rainyHours = (0 until times.length()).mapNotNull { i ->
            val hour = LocalDateTime.parse(times.getString(i))
            val relevant = hour.toLocalDate() == date && (!onlyFuture || !hour.isBefore(currentHour))
            hour.takeIf { relevant && chances.optInt(i, 0) >= RAIN_LIKELY_PERCENT }
        }
        if (rainyHours.isEmpty()) return "no rain expected"
        return "${rainyHours.first().format(HOUR_FORMAT)} to ${rainyHours.last().plusHours(1).format(HOUR_FORMAT)}"
    }

    // -------------------------------------------------------------- location

    private fun geocode(name: String): Place? {
        val json = getJson(
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=${URLEncoder.encode(name, "UTF-8")}&count=1&language=en&format=json"
        )
        val result = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val label = listOf(result.optString("name"), result.optString("country"))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return Place(label.ifEmpty { name }, result.getDouble("latitude"), result.getDouble("longitude"))
    }

    @SuppressLint("MissingPermission")
    private fun currentPlace(): Place? {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) return null

        val locationManager = appContext.getSystemService(LocationManager::class.java)
        val location = locationManager.getProviders(true)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < MAX_LOCATION_AGE_MS }
            .maxByOrNull { it.time }
            ?: freshLocation(locationManager)
            ?: return null
        return Place(placeName(location) ?: "your location", location.latitude, location.longitude)
    }

    /** One-shot location fix (Android 11+), waiting at most a few seconds. */
    @SuppressLint("MissingPermission")
    private fun freshLocation(locationManager: LocationManager): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val providers = locationManager.getProviders(true)
        val provider = PREFERRED_PROVIDERS.firstOrNull { it in providers } ?: providers.firstOrNull() ?: return null

        var result: Location? = null
        val latch = CountDownLatch(1)
        locationManager.getCurrentLocation(provider, null, appContext.mainExecutor) { location ->
            result = location
            latch.countDown()
        }
        latch.await(LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result
    }

    @Suppress("DEPRECATION")
    private fun placeName(location: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(appContext, Locale.ENGLISH)
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()

    // --------------------------------------------------------------- helpers

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.optDoubleOrNull(index: Int): Double? =
        if (isNull(index)) null else optDouble(index).takeIf { !it.isNaN() }

    private fun JSONArray.optIntOrNull(index: Int): Int? = optDoubleOrNull(index)?.toInt()

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeIf { !it.isNaN() }

    private fun JSONArray.celsius(index: Int): String? =
        optDoubleOrNull(index)?.let { "%.0f°C".format(Locale.ROOT, it) }

    private fun JSONArray.localTime(index: Int): String? =
        optString(index).takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDateTime.parse(it).format(TIME_FORMAT) }.getOrNull() }

    /** WMO weather interpretation codes used by Open-Meteo. */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mainly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80 -> "light rain showers"
        81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unknown conditions"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun failure(message: String): Map<String, String> =
        mapOf("status" to "error", "error" to message)

    private companion object {
        const val MAX_DAY_INDEX = 2
        const val RAIN_LIKELY_PERCENT = 50
        const val ALERT_RAIN_PERCENT = 70

        /** WMO codes for drizzle, rain, showers and thunderstorms. */
        val RAIN_CODES = (51..67).toSet() + (80..82) + (95..99)
        const val HTTP_TIMEOUT_MS = 8_000
        const val LOCATION_TIMEOUT_SECONDS = 6L
        const val MAX_LOCATION_AGE_MS = 6 * 60 * 60 * 1000L

        val PREFERRED_PROVIDERS = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
    }
}
