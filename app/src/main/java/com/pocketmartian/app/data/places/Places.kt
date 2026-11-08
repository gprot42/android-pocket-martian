package com.pocketmartian.app.data.places

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a place search measured from: the phone, or a place the user named. */
data class PlaceOrigin(
    val lat: Double,
    val lon: Double,
    /** Null for the phone's own location; else the named place, e.g. "Leicester Square". */
    val label: String? = null
) {
    val isUser: Boolean get() = label == null
}

/** One shop or amenity from OpenStreetMap, numbered as on the map. */
data class Place(
    val number: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceM: Int,
    val address: String? = null,
    /** OpenStreetMap's opening_hours as recorded, e.g. "24/7" or "Mo-Sa 07:00-23:00". */
    val openingHours: String? = null
)

/** What a find_places call found; shown under the answer as a map with numbered pins. */
data class PlacesResult(
    val query: String,
    val origin: PlaceOrigin,
    val places: List<Place>
)

/**
 * "300 m" or "1.4 km". Straight-line distances: the card says so, rather than claiming a
 * route it never worked out.
 */
internal fun formatDistance(meters: Int): String = when {
    meters < 1000 -> "${(meters / 10.0).roundToInt() * 10} m"
    meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    else -> "${(meters / 1000.0).roundToInt()} km"
}

/** Minutes on foot at 80 m a minute (about 5 km/h), never less than one. */
internal fun walkMinutes(meters: Int): Int = ceil(meters / WALK_M_PER_MIN).toInt().coerceAtLeast(1)

/** Under an hour's walk it is worth saying; beyond that nobody is walking. */
internal fun walkLabel(meters: Int): String? =
    walkMinutes(meters).takeIf { it <= 60 }?.let { "$it min walk" }

/** Opening hours as a person would say them, when OpenStreetMap has them. */
internal fun hoursLabel(openingHours: String?): String? {
    val h = openingHours?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (h == "24/7" || h.equals("Mo-Su 00:00-24:00", ignoreCase = true)) return "Open 24 hours"
    return if (h.length <= MAX_HOURS_CHARS) h else h.take(MAX_HOURS_CHARS - 1) + "…"
}

/** Great-circle distance in metres. */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/**
 * What to ask Nominatim for: the name as given (it matches local and English names, so
 * "7-Eleven" finds セブン-イレブン), or a tag such as amenity=pharmacy in its bracket form.
 * Null when neither is usable.
 */
internal fun nominatimQuery(name: String?, osmTag: String?): String? {
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_NAME_CHARS) }
    return osmTag?.let { parseTag(it) }?.let { (key, value) -> "[$key=$value]" }
}

/** "amenity" to "pharmacy" from "amenity=pharmacy"; null for anything that is not a plain tag. */
internal fun parseTag(osmTag: String): Pair<String, String>? {
    val m = Regex("""^\s*([a-z_:]+)\s*=\s*([a-z0-9_;-]+)\s*$""").matchEntire(osmTag) ?: return null
    return m.groupValues[1] to m.groupValues[2]
}

/**
 * Nominatim's viewbox ("left,top,right,bottom") for a square [radiusM] from the point.
 * A degree of longitude shrinks towards the poles, so the box is widened to match.
 */
internal fun viewbox(lat: Double, lon: Double, radiusM: Int): String {
    val dLat = radiusM / M_PER_DEGREE
    val dLon = radiusM / (M_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    return String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", lon - dLon, lat + dLat, lon + dLon, lat - dLat)
}

/**
 * The nearest [count] places in a Nominatim answer, numbered from 1. Nominatim orders by
 * importance, not distance, so they are sorted here. With [name], only places whose name
 * contains it are kept, and with [osmTag], only places of that kind. A shop mapped both as a point and as its building comes
 * back twice; the second copy within [DUPLICATE_M] of one with the same name is dropped.
 */
internal fun parseNominatim(json: String, origin: PlaceOrigin, count: Int, name: String? = null, osmTag: String? = null): List<Place> {
    val results = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
    val kind = osmTag?.let { parseTag(it) }
    data class Found(val name: String, val lat: Double, val lon: Double, val distance: Double, val item: JsonObject)
    val found = results.mapNotNull { el ->
        val o = el.asObjectOrNull() ?: return@mapNotNull null
        if (kind != null && (o.str("category") != kind.first || o.str("type") != kind.second)) return@mapNotNull null
        val lat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
        val placeName = o.str("name") ?: return@mapNotNull null
        if (name != null && !nameMatches(name, placeName)) return@mapNotNull null
        Found(placeName, lat, lon, distanceMeters(origin.lat, origin.lon, lat, lon), o)
    }.sortedBy { it.distance }
    val kept = mutableListOf<Found>()
    for (f in found) {
        val duplicate = kept.any {
            it.name.equals(f.name, ignoreCase = true) && distanceMeters(it.lat, it.lon, f.lat, f.lon) < DUPLICATE_M
        }
        if (!duplicate) kept += f
        if (kept.size == count) break
    }
    return kept.mapIndexed { i, f ->
        Place(
            number = i + 1,
            name = f.name,
            lat = f.lat,
            lon = f.lon,
            distanceM = f.distance.roundToInt(),
            address = f.item.get("address").asObjectOrNull()?.let { addressOf(it) },
            openingHours = f.item.get("extratags").asObjectOrNull()?.str("opening_hours")
        )
    }
}

/**
 * Whether a place's name is the one asked for. Nominatim matches loosely: asked for
 * 7-Eleven in England, which has none, it offered "7 to Eleven Offy" 31 km away, and
 * asked for Starbucks it added "Coffee & Real eating co.". Letters and digits only, so
 * "McDonalds" is "McDonald's". A name in another script (セブン-イレブン) can't be
 * compared; Nominatim matched it on its English name, so it is trusted.
 */
internal fun nameMatches(asked: String, name: String): Boolean {
    val want = squash(asked)
    if (want.isEmpty()) return true
    if (name.none { it in 'a'..'z' || it in 'A'..'Z' }) return true
    return squash(name).contains(want)
}

private fun squash(text: String): String =
    text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** "12 High Street, St Albans", as much of it as OpenStreetMap records. */
private fun addressOf(address: JsonObject): String? {
    val road = address.str("road") ?: address.str("pedestrian")
    val number = address.str("house_number")
    val town = address.str("town") ?: address.str("city") ?: address.str("village") ?: address.str("suburb")
    val line = when {
        road != null && number != null -> "$number $road"
        road != null -> road
        else -> null
    }
    return listOfNotNull(line, town).joinToString(", ").ifBlank { null }
}

/** A walking route from OSRM: how far, how long, and the path to draw (lat to lon). */
data class WalkingRoute(
    val distanceM: Int,
    val durationS: Int,
    val points: List<Pair<Double, Double>>
)

/** "Walk 205 m · 3 min". */
internal fun routeLabel(route: WalkingRoute): String =
    "Walk ${formatDistance(route.distanceM)} \u00B7 ${((route.durationS + 59) / 60).coerceAtLeast(1)} min"

/**
 * The OSRM request for walking from [from] to [toLat],[toLon], on the FOSSGIS server that
 * openstreetmap.org's own directions use. OSRM takes longitude first.
 */
internal fun osrmFootUrl(from: PlaceOrigin, toLat: Double, toLon: Double): String =
    String.format(
        Locale.US,
        "https://routing.openstreetmap.de/routed-foot/route/v1/foot/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
        from.lon, from.lat, toLon, toLat
    )

/** The first route in an OSRM answer, or null when there is none. */
internal fun parseOsrmRoute(json: String): WalkingRoute? {
    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    if (root.str("code") != "Ok") return null
    val route = root.get("routes")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull().asObjectOrNull() ?: return null
    val coordinates = route.get("geometry").asObjectOrNull()?.get("coordinates")
        ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val points = coordinates.mapNotNull { c ->
        val pair = c.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() >= 2 } ?: return@mapNotNull null
        pair[1].asDouble to pair[0].asDouble
    }
    if (points.size < 2) return null
    val distance = route.get("distance")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
    val duration = route.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
    return WalkingRoute(distance.roundToInt(), duration.roundToInt(), points)
}

/** What Grok is told: the places, small and flat, and that the user sees them on a map. */
internal fun placesJsonForModel(result: PlacesResult): String {
    val out = JsonObject()
    out.addProperty("source", SOURCE)
    out.addProperty("measured_from", result.origin.label ?: "the user's current location")
    val list = JsonArray()
    result.places.forEach { p ->
        list.add(JsonObject().apply {
            addProperty("number", p.number)
            addProperty("name", p.name)
            p.address?.let { addProperty("address", it) }
            addProperty("distance", formatDistance(p.distanceM))
            walkLabel(p.distanceM)?.let { addProperty("walk", "about $it") }
            p.openingHours?.let { addProperty("opening_hours", it) }
        })
    }
    out.add("places", list)
    out.addProperty(
        "note",
        if (result.places.isEmpty()) {
            "Nothing matching was found nearby in OpenStreetMap. Say so; don't invent places."
        } else {
            "The user sees these on a map with numbered pins, each with a directions button, under " +
                "your reply. Keep the reply short, refer to places by number, and don't list " +
                "coordinates or repeat every detail. Distances are in a straight line."
        }
    )
    return out.toString()
}

internal fun placesError(message: String): String {
    val out = JsonObject()
    out.addProperty("source", SOURCE)
    out.addProperty("error", message)
    return out.toString()
}

internal const val SOURCE = "OpenStreetMap"
private const val EARTH_RADIUS_M = 6_371_000.0
private const val WALK_M_PER_MIN = 80.0
private const val MAX_HOURS_CHARS = 40
private const val DUPLICATE_M = 60.0
private const val MAX_NAME_CHARS = 100
private const val M_PER_DEGREE = 111_320.0

private fun JsonElement?.asObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
