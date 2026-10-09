package com.pocketmartian.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** The API does not say what format a picture is in; the file is named by its own bytes. */
class GeneratedImagesTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun pngJpegAndWebpAreToldApart() {
        assertEquals("png", extensionFor(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("jpg", extensionFor(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("webp", extensionFor("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun anythingElseIsTreatedAsPng() {
        assertEquals("png", extensionFor(bytes(1, 2, 3)))
        assertEquals("png", extensionFor(ByteArray(0)))
    }
}
