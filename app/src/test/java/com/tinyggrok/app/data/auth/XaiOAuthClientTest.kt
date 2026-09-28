package com.tinyggrok.app.data.auth

import com.tinyggrok.app.data.repository.dnsFailureMessage
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class XaiOAuthClientTest {

    @Test
    fun deviceCodeUsesInjectedDns() {
        val lookedUp = mutableListOf<String>()
        val client = clientWithDns { hostname ->
            lookedUp += hostname
            throw UnknownHostException("stub")
        }

        try {
            client.requestDeviceCode()
            fail("expected DNS failure")
        } catch (e: IllegalStateException) {
            assertEquals(dnsFailureMessage("auth.x.ai"), e.message)
        }
        assertTrue(lookedUp.isNotEmpty())
        assertTrue(lookedUp.all { it == "auth.x.ai" })
    }

    @Test
    fun tokenPollReportsDnsFailure() {
        val client = clientWithDns { throw UnknownHostException("stub") }
        val result = client.pollToken("device")
        assertTrue(result is XaiOAuthClient.PollResult.Error)
        assertEquals(
            dnsFailureMessage("auth.x.ai"),
            (result as XaiOAuthClient.PollResult.Error).message
        )
    }

    private fun clientWithDns(lookup: (String) -> List<InetAddress>): XaiOAuthClient {
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = lookup(hostname)
            })
            .build()
        return XaiOAuthClient(http)
    }
}
