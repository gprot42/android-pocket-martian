package com.pocketmartian.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShareLaunchModeTest {

    /**
     * Shared screenshots vanished when the app was already running: singleTop let the
     * share start a second MainActivity in a new task, and the hidden first one's
     * ChatViewModel took the share from the inbox. Only one instance may exist.
     */
    @Test
    fun mainActivityIsSingleTask() {
        // Unit tests run with the module directory as the working directory.
        val manifest = File("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(android, "launchMode"))
    }
}
