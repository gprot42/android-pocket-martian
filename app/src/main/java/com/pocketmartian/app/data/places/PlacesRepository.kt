package com.pocketmartian.app.data.places

import android.util.Log
import com.google.gson.JsonParser
import com.pocketmartian.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shops and amenities near the user, from OpenStreetMap, for "show me 3 local 7-11 stores".
 *
 * Asked from web search alone, Grok gives addresses it half remembers and distances it
 * cannot know. OpenStreetMap records most chains with their position, and its search,
 * Nominatim, finds them by name or kind inside an area, free and without a key, so the
 * places shown are real and the distances are measured, not guessed.
 *
 * Overpass was the first choice, being made for "the nearest of these to this point", but
 * its public servers were overloaded when tried: a one-line query took 24 seconds, and the
 * mirrors did not answer at all. Nominatim answered the same searches in one to three.
 *
 * Nominatim ranks by importance, not distance, and returns at most 40, so it is asked about
 * a small area first and a wider one only when too few were found; the nearest are then
 * picked here. Only the point searched from is sent: the phone's location, or a place the
 * user named. Its rules ask for an identifying User-Agent and at most one request a second.
 */
@Singleton
class PlacesRepository @Inject constructor(okHttpClient: OkHttpClient) {
    private val http = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val pacing = Mutex()
    @Volatile private var lastRequestAt = 0L

    /**
     * Up to [count] places matching [name] and/or [osmTag] nearest to [near] (a place name)
     * or else [userOrigin]. Returns the JSON for the model, and what was found for the map.
     */
    suspend fun find(
        name: String?,
        osmTag: String?,
        count: Int,
        near: String?,
        userOrigin: PlaceOrigin?
    ): Pair<String, PlacesResult?> = withContext(Dispatchers.IO) {
        val wanted = count.coerceIn(1, MAX_COUNT)
        val q = nominatimQuery(name, osmTag)
            ?: return@withContext placesError("Give a name (e.g. 7-Eleven) or an OpenStreetMap tag (e.g. amenity=pharmacy).") to null
        val origin = when {
            !near.isNullOrBlank() -> geocode(near)
                ?: return@withContext placesError("Couldn't find \"$near\" on the map. Ask the user for a nearby street or town.") to null
            userOrigin != null -> userOrigin
            else -> return@withContext placesError(
                "The user's location isn't available (location is off or not permitted). Ask " +
                    "which town or street to search near, and pass it as near."
            ) to null
        }
        var places = emptyList<Place>()
        for (radius in RADII_M) {
            val body = get(
                search().addQueryParameter("q", q)
                    .addQueryParameter("viewbox", viewbox(origin.lat, origin.lon, radius))
                    .addQueryParameter("bounded", "1")
                    .addQueryParameter("limit", "40")
                    .addQueryParameter("addressdetails", "1")
                    .addQueryParameter("extratags", "1")
                    .build()
            ) ?: return@withContext placesError("OpenStreetMap's search (Nominatim) didn't answer; try again shortly.") to null
            // A name and a kind together: search by name, keep only that kind.
            val named = name?.takeIf { it.isNotBlank() }
            places = parseNominatim(body, origin, wanted, named, osmTag.takeIf { named != null })
            if (places.size >= wanted) break
        }
        val query = listOfNotNull(name?.trim(), osmTag?.trim()).filter { it.isNotEmpty() }.joinToString(" / ")
        val result = PlacesResult(query = query, origin = origin, places = places)
        placesJsonForModel(result) to result.takeIf { places.isNotEmpty() }
    }

    /**
     * A walking route from [from] to the place, from OSRM's walking router run by FOSSGIS
     * (as openstreetmap.org's directions are), or null when it can't be had.
     */
    suspend fun walkingRoute(from: PlaceOrigin, toLat: Double, toLon: Double): WalkingRoute? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(osrmFootUrl(from, toLat, toLon)).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { resp ->
                resp.body?.string()?.takeIf { resp.isSuccessful }?.let { parseOsrmRoute(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Walking route failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** A named place's position. */
    private suspend fun geocode(place: String): PlaceOrigin? {
        val body = get(search().addQueryParameter("q", place.trim()).addQueryParameter("limit", "1").build()) ?: return null
        val first = runCatching { JsonParser.parseString(body).asJsonArray.firstOrNull()?.asJsonObject }.getOrNull()
        val lat = first?.get("lat")?.asString?.toDoubleOrNull()
        val lon = first?.get("lon")?.asString?.toDoubleOrNull()
        return if (lat != null && lon != null) PlaceOrigin(lat, lon, label = place.trim()) else null
    }

    /** Names in the phone's language where OpenStreetMap has them: 7-Eleven, not セブン-イレブン. */
    private fun search(): HttpUrl.Builder = NOMINATIM.toHttpUrl().newBuilder()
        .addQueryParameter("format", "jsonv2")
        .addQueryParameter("accept-language", Locale.getDefault().toLanguageTag())

    /** One request, at least a second after the last, as Nominatim's rules ask. */
    private suspend fun get(url: HttpUrl): String? = pacing.withLock {
        val wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null.also { Log.w(TAG, "Nominatim answered HTTP ${resp.code}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "Places"
        const val MAX_COUNT = 10
        /** Search close first; widen only when too few were found, as in the countryside. */
        private val RADII_M = listOf(600, 2_500, 10_000, 40_000)
        private const val MIN_GAP_MS = 1_000L
        private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
        /** OpenStreetMap's services refuse requests that don't say which app is asking. */
        val USER_AGENT = "PocketMartian/${BuildConfig.VERSION_NAME} (+https://github.com/gprot42/android-pocket-martian)"
    }
}
