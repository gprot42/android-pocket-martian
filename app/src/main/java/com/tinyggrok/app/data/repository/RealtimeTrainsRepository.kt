package com.tinyggrok.app.data.repository

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tinyggrok.app.data.local.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a check of the user's Realtime Trains token found. */
sealed class RttCheck {
    data class Valid(val summary: String) : RttCheck()
    data class Invalid(val message: String) : RttCheck()
}

/**
 * Live UK train times from Realtime Trains (data.rtt.io), with the user's own token.
 *
 * Web search could not give accurate train times: live departure boards are pages built
 * by script, which a search result rarely contains, so answers fell back on timetables
 * and guesses. Realtime Trains publishes Network Rail's live running data through an
 * API, and with a token Grok can ask for exactly the board it needs (see the tools in
 * [ChatRepository]).
 *
 * The token belongs to the person using the app. They create it at api-portal.rtt.io and
 * enter it in Settings; it is stored on their phone only, sent only to data.rtt.io, and
 * is never part of the app, its source code or the published APK. Realtime Trains forbids
 * tokens inside distributed apps; this app distributes none.
 *
 * A token may be a long-life access token, used as it is, or a refresh token, exchanged
 * for a short-life access token at /api/get_access_token. Which one was entered is found
 * out by trying the exchange.
 */
@Singleton
class RealtimeTrainsRepository @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settings: SettingsRepository
) {
    private val http = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    /** Access token from a refresh token, and until when it is good. */
    @Volatile private var exchanged: Pair<String, Long>? = null
    @Volatile private var exchangedFrom: String? = null

    suspend fun hasToken(): Boolean = settings.rttToken.first() != null

    /** Ask /api/info whether the token works and what it is entitled to. */
    suspend fun check(token: String? = null): RttCheck = withContext(Dispatchers.IO) {
        val saved = token?.trim()?.takeIf { it.isNotEmpty() } ?: settings.rttToken.first()
            ?: return@withContext RttCheck.Invalid("No token entered.")
        val access = accessTokenFor(saved)
        val (code, body) = get(BASE.newBuilder().addPathSegments("api/info").build(), access)
            ?: return@withContext RttCheck.Invalid("Couldn't reach data.rtt.io.")
        when (code) {
            200 -> RttCheck.Valid(describeInfo(body))
            401, 403 -> RttCheck.Invalid("Realtime Trains rejected the token (HTTP $code).")
            else -> RttCheck.Invalid("Realtime Trains answered HTTP $code.")
        }
    }

    /**
     * Departures from [station] (a CRS code such as SAC), optionally only trains that
     * later call at [callingAt] or earlier called at [comingFrom], from [time] for
     * [windowMinutes]. Returns a compact JSON summary for the model.
     */
    suspend fun departures(
        station: String,
        callingAt: String? = null,
        comingFrom: String? = null,
        time: String? = null,
        windowMinutes: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val token = settings.rttToken.first() ?: return@withContext error("No Realtime Trains token is set.")
        val url = BASE.newBuilder().addPathSegments("gb-nr/location")
            .addQueryParameter("code", station.trim().uppercase())
            .apply {
                callingAt?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("filterTo", it.uppercase()) }
                comingFrom?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("filterFrom", it.uppercase()) }
                addQueryParameter("timeFrom", ukQueryTime(time, System.currentTimeMillis()))
                addQueryParameter("timeWindow", (windowMinutes ?: DEFAULT_WINDOW_MIN).coerceIn(15, MAX_WINDOW_MIN).toString())
            }
            .build()
        val (code, body) = get(url, accessTokenFor(token)) ?: return@withContext error("Couldn't reach Realtime Trains.")
        when (code) {
            200 -> summariseLineUp(body)
            204 -> """{"source":"$SOURCE","services":[],"note":"No trains in that window."}"""
            400 -> error("Realtime Trains did not recognise the request (check the station codes).")
            401, 403 -> error("The Realtime Trains token was rejected.")
            429 -> error("Realtime Trains rate limit reached; try again in a minute.")
            else -> error("Realtime Trains answered HTTP $code.")
        }
    }

    /** One train's calling points, by the `id` a departures result gave for it. */
    suspend fun service(uniqueIdentity: String): String = withContext(Dispatchers.IO) {
        val token = settings.rttToken.first() ?: return@withContext error("No Realtime Trains token is set.")
        val url = BASE.newBuilder().addPathSegments("gb-nr/service")
            .addQueryParameter("uniqueIdentity", uniqueIdentity.trim())
            .build()
        val (code, body) = get(url, accessTokenFor(token)) ?: return@withContext error("Couldn't reach Realtime Trains.")
        when (code) {
            200 -> summariseService(body)
            404 -> error("Realtime Trains has no service with that id.")
            401, 403 -> error("The Realtime Trains token was rejected.")
            else -> error("Realtime Trains answered HTTP $code.")
        }
    }

    /** The token to send: an exchanged access token if [saved] is a refresh token, else [saved]. */
    private fun accessTokenFor(saved: String): String {
        val now = System.currentTimeMillis()
        exchanged?.takeIf { exchangedFrom == saved && it.second - 60_000 > now }?.let { return it.first }
        val url = BASE.newBuilder().addPathSegments("api/get_access_token").build()
        val result = get(url, saved)
        if (result != null && result.first == 200) {
            val obj = runCatching { JsonParser.parseString(result.second).asJsonObject }.getOrNull()
            val token = obj?.str("token")
            if (token != null) {
                val until = obj.str("validUntil")?.let { parseIsoMillis(it) } ?: (now + 10 * 60_000)
                exchanged = token to until
                exchangedFrom = saved
                return token
            }
        }
        // Not a refresh token (or the exchange is unavailable): use it as an access token.
        return saved
    }

    private fun get(url: HttpUrl, token: String): Pair<Int, String>? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
        ).execute().use { it.code to (it.body?.string().orEmpty()) }
    } catch (e: Exception) {
        Log.w(TAG, "Realtime Trains request failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun error(message: String) = """{"source":"$SOURCE","error":${quote(message)}}"""

    private fun describeInfo(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return "Token accepted."
        val version = obj.str("api_version")
        val creds = obj.obj("credentials")
        val namespaces = creds?.arr("namespacesAvailable")?.mapNotNull { it.asStringOrNull() }?.joinToString(", ")
        return buildString {
            append("Token accepted")
            version?.let { append(" · API $it") }
            if (!namespaces.isNullOrBlank()) append(" · $namespaces")
        }
    }

    companion object {
        private const val TAG = "RealtimeTrains"
        private val BASE = "https://data.rtt.io".toHttpUrl()
        internal const val SOURCE = "Realtime Trains live data (realtimetrains.co.uk)"
        private const val DEFAULT_WINDOW_MIN = 120
        private const val MAX_WINDOW_MIN = 720
    }
}

// ---- Summaries for the model: small, flat, and in UK local time. ----

private const val MAX_SERVICES = 20
private val UK: TimeZone = TimeZone.getTimeZone("Europe/London")

/** A station board from /gb-nr/location, as compact JSON. */
internal fun summariseLineUp(json: String): String {
    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        ?: return """{"source":"${RealtimeTrainsRepository.SOURCE}","error":"Unreadable response."}"""
    val location = root.obj("query")?.obj("location")
    val out = JsonObject()
    out.addProperty("source", RealtimeTrainsRepository.SOURCE)
    location?.let { out.addProperty("station", placeName(it)) }
    val services = JsonArray()
    root.arr("services")?.take(MAX_SERVICES)?.forEach { el ->
        val s = el.asJsonObjectOrNull() ?: return@forEach
        val t = s.obj("temporalData")
        val dep = t?.obj("departure") ?: t?.obj("arrival")
        val item = JsonObject()
        dep?.str("scheduleAdvertised")?.let { ukTime(it) }?.let { item.addProperty("scheduled", it) }
        val cancelled = dep?.bool("isCancelled") == true || t?.str("displayAs") == "CANCELLED"
        item.addProperty("status", statusOf(dep, t, cancelled))
        expectedOf(dep)?.let { item.addProperty("expected", it) }
        s.obj("locationMetadata")?.obj("platform")?.let { p ->
            (p.str("actual") ?: p.str("planned"))?.let { item.addProperty("platform", it) }
        }
        s.arr("destination")?.mapNotNull { it.asJsonObjectOrNull()?.obj("location")?.str("description") }
            ?.takeIf { it.isNotEmpty() }?.let { item.addProperty("to", it.joinToString(" & ")) }
        s.arr("origin")?.mapNotNull { it.asJsonObjectOrNull()?.obj("location")?.str("description") }
            ?.takeIf { it.isNotEmpty() }?.let { item.addProperty("from", it.joinToString(" & ")) }
        s.obj("scheduleMetadata")?.let { m ->
            m.obj("operator")?.str("name")?.let { item.addProperty("operator", it) }
            m.str("uniqueIdentity")?.let { item.addProperty("id", it) }
            m.str("modeType")?.takeIf { it != "TRAIN" }?.let { item.addProperty("mode", it) }
        }
        reasonsOf(s.arr("reasons"))?.let { item.addProperty("reason", it) }
        services.add(item)
    }
    out.add("services", services)
    reasonsOf(root.arr("reasons"))?.let { out.addProperty("station_notice", it) }
    return out.toString()
}

/** One train's calling points from /gb-nr/service, as compact JSON. */
internal fun summariseService(json: String): String {
    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
        ?: return """{"source":"${RealtimeTrainsRepository.SOURCE}","error":"Unreadable response."}"""
    val service = root.obj("service") ?: return """{"source":"${RealtimeTrainsRepository.SOURCE}","error":"No service in response."}"""
    val out = JsonObject()
    out.addProperty("source", RealtimeTrainsRepository.SOURCE)
    service.obj("scheduleMetadata")?.obj("operator")?.str("name")?.let { out.addProperty("operator", it) }
    val calls = JsonArray()
    service.arr("locations")?.forEach { el ->
        val l = el.asJsonObjectOrNull() ?: return@forEach
        val t = l.obj("temporalData")
        val display = t?.str("displayAs")
        if (display == null || display == "PASS") return@forEach
        val item = JsonObject()
        l.obj("location")?.let { item.addProperty("station", placeName(it)) }
        t.obj("arrival")?.let { a ->
            a.str("scheduleAdvertised")?.let { ukTime(it) }?.let { item.addProperty("arr", it) }
            expectedOf(a)?.let { item.addProperty("arr_expected", it) }
        }
        t.obj("departure")?.let { d ->
            d.str("scheduleAdvertised")?.let { ukTime(it) }?.let { item.addProperty("dep", it) }
            expectedOf(d)?.let { item.addProperty("dep_expected", it) }
        }
        if (display == "CANCELLED") item.addProperty("status", "cancelled here")
        l.obj("locationMetadata")?.obj("platform")?.let { p ->
            (p.str("actual") ?: p.str("planned"))?.let { item.addProperty("platform", it) }
        }
        calls.add(item)
    }
    out.add("calls", calls)
    reasonsOf(service.arr("reasons"))?.let { out.addProperty("reason", it) }
    return out.toString()
}

private fun placeName(location: JsonObject): String {
    val name = location.str("description") ?: "?"
    val crs = location.arr("shortCodes")?.firstOrNull()?.asStringOrNull()
    return if (crs != null) "$name ($crs)" else name
}

/** "on time", "late 7 min", "early 1 min", "cancelled", "departed 17:35", or "no report". */
private fun statusOf(time: JsonObject?, temporal: JsonObject?, cancelled: Boolean): String {
    if (cancelled) return "cancelled"
    if (time == null) return "unknown"
    time.str("realtimeActual")?.let { ukTime(it) }?.let { return "departed $it" }
    val late = time.int("realtimeAdvertisedLateness")
    return when {
        late != null && late > 0 -> "late $late min"
        late != null && late < 0 -> "early ${-late} min"
        late == 0 -> "on time"
        time.bool("realtimeNoReport") == true -> "no report"
        time.str("realtimeForecast") != null -> "expected ${ukTime(time.str("realtimeForecast")!!)}"
        else -> temporal?.str("status")?.lowercase() ?: "scheduled"
    }
}

private fun expectedOf(time: JsonObject?): String? =
    (time?.str("realtimeActual") ?: time?.str("realtimeForecast") ?: time?.str("realtimeEstimate"))?.let { ukTime(it) }

private fun reasonsOf(reasons: JsonArray?): String? =
    reasons?.mapNotNull { r ->
        val o = r.asJsonObjectOrNull() ?: return@mapNotNull null
        (o.str("longText") ?: o.str("shortText"))?.let { text -> o.str("type")?.let { "${it.lowercase()}: $text" } ?: text }
    }?.distinct()?.takeIf { it.isNotEmpty() }?.joinToString("; ")

/** An ISO 8601 time from the API as HH:mm in UK local time. */
internal fun ukTime(iso: String): String? {
    val millis = parseIsoMillis(iso) ?: return null
    return SimpleDateFormat("HH:mm", Locale.UK).apply { timeZone = UK }.format(Date(millis))
}

/** Millis for an ISO 8601 date-time; one without a zone is UK local time. */
internal fun parseIsoMillis(iso: String): Long? {
    val text = iso.trim().replace(Regex("\\.\\d+"), "") // drop fractions of a second
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")
    for (pattern in patterns) {
        val format = SimpleDateFormat(pattern, Locale.UK).apply { timeZone = UK; isLenient = false }
        val position = ParsePosition(0)
        val date = format.parse(text, position)
        if (date != null && position.index == text.length) return date.time
    }
    return null
}

/**
 * The timeFrom to ask for: the model's "17:30" (today, UK time) or "2026-10-04T17:30",
 * or now. Sent without an offset, which the API reads as the station's local time.
 */
internal fun ukQueryTime(requested: String?, nowMs: Long): String {
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = UK }
    val full = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:00", Locale.UK).apply { timeZone = UK }
    val r = requested?.trim().orEmpty()
    Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(r)?.let { m ->
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h in 0..23 && min in 0..59) return "${dayFormat.format(Date(nowMs))}T%02d:%02d:00".format(h, min)
    }
    parseIsoMillis(r)?.let { return full.format(Date(it)) }
    return full.format(Date(nowMs))
}

private fun quote(s: String) = com.google.gson.JsonPrimitive(s).toString()
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
private fun JsonObject.str(name: String): String? = get(name)?.asStringOrNull()
private fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()
private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
private fun JsonElement.asStringOrNull(): String? = takeIf { it.isJsonPrimitive }?.asString
private fun JsonElement.asJsonObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
