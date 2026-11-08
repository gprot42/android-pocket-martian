package com.pocketmartian.app.data.places

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {

    private val stAlbans = PlaceOrigin(51.7504, -0.3364)

    @Test
    fun `distances read as a person would say them`() {
        assertEquals("300 m", formatDistance(304))
        assertEquals("990 m", formatDistance(994))
        assertEquals("1.4 km", formatDistance(1_420))
        assertEquals("23 km", formatDistance(22_600))
    }

    @Test
    fun `walking time rounds up and stops being offered past an hour`() {
        assertEquals(4, walkMinutes(300))
        assertEquals(1, walkMinutes(10))
        assertEquals("4 min walk", walkLabel(300))
        assertNull(walkLabel(6_000))
    }

    @Test
    fun `round the clock hours are said plainly and long ones are cut`() {
        assertEquals("Open 24 hours", hoursLabel("24/7"))
        assertEquals("Open 24 hours", hoursLabel("Mo-Su 00:00-24:00"))
        assertEquals("Mo-Sa 07:00-23:00", hoursLabel("Mo-Sa 07:00-23:00"))
        assertNull(hoursLabel(" "))
        assertTrue(hoursLabel("Mo-Fr 06:00-23:00; Sa 07:00-23:00; Su 08:00-22:00; PH off")!!.endsWith("…"))
    }

    @Test
    fun `distance between two known points`() {
        // St Albans City station to St Albans Abbey station: about 1.5 km apart.
        val d = distanceMeters(51.7505, -0.3274, 51.7448, -0.3423)
        assertTrue("was $d", d in 1_100.0..1_300.0)
    }

    @Test
    fun `a name is searched as given and a tag in its bracket form`() {
        assertEquals("7-Eleven", nominatimQuery(" 7-Eleven ", null))
        assertEquals("7-Eleven", nominatimQuery("7-Eleven", "shop=convenience"))
        assertEquals("[amenity=pharmacy]", nominatimQuery(null, "amenity=pharmacy"))
        assertEquals("[shop=convenience]", nominatimQuery(" ", " shop = convenience "))
        assertNull(nominatimQuery(null, "amenity=\"x\"];out;"))
        assertNull(nominatimQuery(null, "pharmacy"))
        assertNull(parseTag("Amenity=Pharmacy"))
    }

    @Test
    fun `the search box is wider in longitude away from the equator`() {
        val (left, top, right, bottom) = viewbox(51.75, -0.33, 1_000).split(",").map { it.toDouble() }
        assertEquals(51.75 + 1_000 / 111_320.0, top, 1e-6)
        assertEquals(51.75 - 1_000 / 111_320.0, bottom, 1e-6)
        // At 51.75° a degree of longitude is about 62% of one at the equator.
        assertEquals((top - bottom) / 0.619, right - left, 0.001)
        assertTrue(left < -0.33 && right > -0.33)
    }

    @Test
    fun `nearest places come first, only the kind asked for, and doubles are dropped`() {
        val json = """[
            {"lat":"51.7600","lon":"-0.3364","category":"shop","type":"convenience","name":"7-Eleven","address":{"road":"Far Road"}},
            {"lat":"51.7520","lon":"-0.3364","category":"shop","type":"convenience","name":"7-Eleven",
             "address":{"house_number":"12","road":"High Street","city":"St Albans"},"extratags":{"opening_hours":"24/7"}},
            {"lat":"51.7506","lon":"-0.3364","category":"building","type":"retail","name":"7-Eleven Building"},
            {"lat":"51.75205","lon":"-0.3364","category":"shop","type":"convenience","name":"7-ELEVEN"},
            {"lat":"51.7550","lon":"-0.3364","category":"shop","type":"convenience","name":"7-Eleven","address":{"road":"Mid Road","village":"Sandridge"}},
            {"lat":"51.7510","lon":"-0.3364","category":"shop","type":"convenience","name":""}
        ]"""
        val places = parseNominatim(json, stAlbans, 3, osmTag = "shop=convenience")
        assertEquals(listOf(1, 2, 3), places.map { it.number })
        assertEquals(listOf(51.7520, 51.7550, 51.7600), places.map { it.lat })
        assertEquals("12 High Street, St Albans", places[0].address)
        assertEquals("24/7", places[0].openingHours)
        assertEquals("Mid Road, Sandridge", places[1].address)
        assertTrue(places[0].distanceM in 170..190)

        // Without the kind, the nearer building comes first.
        assertEquals("7-Eleven Building", parseNominatim(json, stAlbans, 3).first().name)
    }

    @Test
    fun `only places with the name asked for are kept`() {
        assertTrue(nameMatches("7-Eleven", "7-Eleven"))
        assertTrue(nameMatches("7-11", "7-11 Express"))
        assertTrue(nameMatches("McDonalds", "McDonald's"))
        assertTrue(nameMatches("Tesco", "Tesco Express"))
        assertTrue(nameMatches("7-Eleven", "セブン-イレブン"))
        assertFalse(nameMatches("7-Eleven", "7 to Eleven Offy"))
        assertFalse(nameMatches("Starbucks", "Coffee & Real eating co."))
        val json = """[
            {"lat":"51.7520","lon":"-0.3364","name":"7 to Eleven Offy"},
            {"lat":"51.7550","lon":"-0.3364","name":"7-Eleven"}
        ]"""
        assertEquals(listOf("7-Eleven"), parseNominatim(json, stAlbans, 3, name = "7-Eleven").map { it.name })
    }

    @Test
    fun `an unreadable answer finds nothing rather than failing`() {
        assertTrue(parseNominatim("<html>busy</html>", stAlbans, 3).isEmpty())
        assertTrue(parseNominatim("""{"error":"limit"}""", stAlbans, 3).isEmpty())
    }

    @Test
    fun `the model is told the user sees a numbered map`() {
        val result = PlacesResult(
            "7-Eleven", stAlbans,
            listOf(Place(1, "7-Eleven", 51.752, -0.3364, 180, "12 High Street", "24/7"))
        )
        val json = JsonParser.parseString(placesJsonForModel(result)).asJsonObject
        assertEquals("the user's current location", json.get("measured_from").asString)
        val first = json.getAsJsonArray("places")[0].asJsonObject
        assertEquals("180 m", first.get("distance").asString)
        assertEquals("about 3 min walk", first.get("walk").asString)
        assertFalse(first.has("lat"))
        assertTrue(json.get("note").asString.contains("numbered pins"))

        val none = JsonParser.parseString(placesJsonForModel(result.copy(places = emptyList()))).asJsonObject
        assertTrue(none.get("note").asString.contains("don't invent"))
    }

    @Test
    fun `walking routes are asked of OSRM longitude first and read back`() {
        assertEquals(
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/100.872600,12.886200;100.873100,12.887500" +
                "?overview=full&geometries=geojson",
            osrmFootUrl(PlaceOrigin(12.8862, 100.8726), 12.8875, 100.8731)
        )
        val route = parseOsrmRoute(
            """{"code":"Ok","routes":[{"distance":205.3,"duration":148.2,
               "geometry":{"type":"LineString","coordinates":[[100.8726,12.8862],[100.8728,12.8868],[100.8731,12.8875]]}}]}"""
        )!!
        assertEquals(205, route.distanceM)
        assertEquals(12.8862 to 100.8726, route.points.first())
        assertEquals(3, route.points.size)
        assertEquals("Walk 210 m \u00B7 3 min", routeLabel(route))
        assertNull(parseOsrmRoute("""{"code":"NoRoute","routes":[]}"""))
        assertNull(parseOsrmRoute("<html>busy</html>"))
    }

    @Test
    fun `map zooms to fit every pin with room for the pins above them`() {
        val near = listOf(51.7504 to -0.3364, 51.7520 to -0.3364, 51.7490 to -0.3330)
        val z = fitZoom(near, 480, 264, 36)
        assertTrue("was $z", z in 14..17)
        val span = near.map { latToY(it.first, z) }.let { it.max() - it.min() }
        assertTrue(span <= 264 - 72 - 34)
        // Spread across a country, it zooms out instead.
        assertTrue(fitZoom(listOf(51.75 to -0.33, 55.95 to -3.19), 480, 264, 36) < 8)
        // One place on its own still shows the streets.
        assertEquals(17, fitZoom(listOf(51.75 to -0.33, 51.75 to -0.33), 480, 264, 36))
    }

    @Test
    fun `tiles cover the view and never go off the top of the world`() {
        assertEquals(256.0, lonToX(0.0, 0) * 2, 1e-9)
        assertEquals(128.0, latToY(0.0, 0), 1e-9)
        val tiles = tilesCovering(100.0, 100.0, 480, 264, 10)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1), tiles)
        assertTrue(tilesCovering(0.0, -50.0, 256, 100, 2).all { it.second >= 0 })
    }
}
