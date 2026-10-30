package me.cazan.zen.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class WeatherNow(
    val place: WeatherPlace,
    val tempC: Double,
    val code: Int,
    val fetchedAt: Long,
    /** From Open-Meteo's is_day for the city's own sunrise/sunset. */
    val isDay: Boolean = true,
)

/**
 * Current conditions from Open-Meteo: free, keyless, no account. The only data sent is the
 * coordinates of the city the user typed in Settings.
 */
class WeatherRepository {
    private val state = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = state.asStateFlow()

    suspend fun refresh(place: WeatherPlace, maxAgeMs: Long = 30 * 60_000L) {
        val current = state.value
        if (current != null && current.place == place &&
            System.currentTimeMillis() - current.fetchedAt < maxAgeMs
        ) return

        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
                val cur = JSONObject(httpGetText(url)).getJSONObject("current")
                state.value = WeatherNow(
                    place = place,
                    tempC = cur.getDouble("temperature_2m"),
                    code = cur.getInt("weather_code"),
                    isDay = cur.optInt("is_day", 1) == 1,
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    suspend fun geocode(query: String): WeatherPlace? = withContext(Dispatchers.IO) {
        runCatching {
            val name = URLEncoder.encode(query.trim(), "UTF-8")
            val json = JSONObject(httpGetText("https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=$name"))
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) {
                null
            } else {
                val o = results.getJSONObject(0)
                WeatherPlace(
                    o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"),
                    o.optString("country_code").uppercase().ifEmpty { null },
                    o.optString("country").ifEmpty { null },
                )
            }
        }.getOrNull()
    }
}

/** WMO weather code → a single glyph that ships with Android's system fonts. */
fun weatherGlyph(code: Int): String = when (code) {
    0 -> "☀"
    1, 2 -> "⛅"
    3 -> "☁"
    45, 48 -> "≋"
    in 51..67, in 80..82 -> "☂"
    in 71..77, 85, 86 -> "❄"
    in 95..99 -> "⚡"
    else -> "☁"
}

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67, in 80..82 -> "Rain"
    in 71..77, 85, 86 -> "Snow"
    in 95..99 -> "Thunderstorm"
    else -> ""
}
