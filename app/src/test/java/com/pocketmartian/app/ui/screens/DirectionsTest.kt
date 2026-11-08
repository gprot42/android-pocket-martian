package com.pocketmartian.app.ui.screens

import com.pocketmartian.app.data.places.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionsTest {

    private val store = Place(1, "7-Eleven", 12.8875, 100.8731, 160)

    @Test
    fun `google maps gets walking directions to the place`() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=12.887500,100.873100",
            directionsUri(DirectionsVia.GOOGLE_MAPS, store)
        )
    }

    @Test
    fun `the walking route is drawn by the app, not linked`() {
        assertNull(directionsUri(DirectionsVia.ROUTE, store))
        assertEquals(DirectionsVia.ROUTE, DirectionsVia.entries.first())
    }

    @Test
    fun `other map apps get the place with its name`() {
        assertEquals("geo:12.887500,100.873100?q=12.887500,100.873100(7-Eleven)", directionsUri(DirectionsVia.OTHER_APP, store))
    }
}
