package com.pocketmartian.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pocketmartian.app.data.api.ResponsesSseParser
import com.pocketmartian.app.data.model.FunctionCallItem
import com.pocketmartian.app.data.model.FunctionCallOutputItem
import com.pocketmartian.app.data.model.InputMessage
import com.pocketmartian.app.data.model.ResponseTool
import com.pocketmartian.app.data.model.ResponsesRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RealtimeTrainsTest {

    /** A /gb-nr/location response shaped as the published OpenAPI specification describes. */
    private val board = """
    {"query":{"location":{"description":"St Albans City","shortCodes":["SAC"],"longCodes":["STALBCY"]},
              "timeFrom":"2026-10-04T17:30:00+01:00","timeTo":"2026-10-04T19:30:00+01:00"},
     "services":[
      {"temporalData":{"departure":{"scheduleAdvertised":"2026-10-04T17:32:00+01:00","realtimeForecast":"2026-10-04T17:32:00+01:00","realtimeAdvertisedLateness":0},"displayAs":"CALL"},
       "locationMetadata":{"platform":{"planned":"3"}},
       "origin":[{"location":{"description":"Bedford"}}],"destination":[{"location":{"description":"Brighton"}}],
       "scheduleMetadata":{"uniqueIdentity":"gb-nr:L01525:2026-10-04","operator":{"code":"TL","name":"Thameslink"},"modeType":"TRAIN"}},
      {"temporalData":{"departure":{"scheduleAdvertised":"2026-10-04T17:40:00+01:00","realtimeForecast":"2026-10-04T17:47:00+01:00","realtimeAdvertisedLateness":7},"displayAs":"CALL"},
       "locationMetadata":{"platform":{"planned":"2","actual":"4"}},
       "destination":[{"location":{"description":"Sevenoaks"}}],
       "scheduleMetadata":{"uniqueIdentity":"gb-nr:L01600:2026-10-04","operator":{"name":"Thameslink"}},
       "reasons":[{"type":"DELAY","code":"IA","shortText":"a signalling fault","longText":null}]},
      {"temporalData":{"departure":{"scheduleAdvertised":"2026-10-04T17:55:00+01:00","isCancelled":true},"displayAs":"CANCELLED"},
       "destination":[{"location":{"description":"London St Pancras International"}}],
       "scheduleMetadata":{"uniqueIdentity":"gb-nr:L01700:2026-10-04","operator":{"name":"East Midlands Railway"}},
       "reasons":[{"type":"CANCEL","shortText":"a shortage of train crew"}]},
      {"temporalData":{"departure":{"scheduleAdvertised":"2026-10-04T17:20:00+01:00","realtimeActual":"2026-10-04T17:21:30+01:00","realtimeAdvertisedLateness":1},"displayAs":"CALL"},
       "destination":[{"location":{"description":"Luton"}}],
       "scheduleMetadata":{"operator":{"name":"Thameslink"},"modeType":"REPLACEMENT_BUS"}}
     ]}
    """.trimIndent()

    @Test
    fun `a station board becomes a compact list Grok can read`() {
        val out = JsonParser.parseString(summariseLineUp(board)).asJsonObject
        assertEquals(RealtimeTrainsRepository.SOURCE, out["source"].asString)
        assertEquals("St Albans City (SAC)", out["station"].asString)
        val s = out["services"].asJsonArray.map { it.asJsonObject }
        assertEquals(4, s.size)

        assertEquals("17:32", s[0]["scheduled"].asString)
        assertEquals("on time", s[0]["status"].asString)
        assertEquals("3", s[0]["platform"].asString)
        assertEquals("Brighton", s[0]["to"].asString)
        assertEquals("Bedford", s[0]["from"].asString)
        assertEquals("Thameslink", s[0]["operator"].asString)
        assertEquals("gb-nr:L01525:2026-10-04", s[0]["id"].asString)
        assertNull(s[0]["mode"])

        assertEquals("late 7 min", s[1]["status"].asString)
        assertEquals("17:47", s[1]["expected"].asString)
        assertEquals("4", s[1]["platform"].asString) // the actual platform beats the planned one
        assertEquals("delay: a signalling fault", s[1]["reason"].asString)

        assertEquals("cancelled", s[2]["status"].asString)
        assertEquals("cancel: a shortage of train crew", s[2]["reason"].asString)

        assertEquals("departed 17:21", s[3]["status"].asString)
        assertEquals("REPLACEMENT_BUS", s[3]["mode"].asString)
    }

    @Test
    fun `a train's calling points skip the places it only passes`() {
        val service = """
        {"service":{"scheduleMetadata":{"operator":{"name":"Thameslink"}},
          "locations":[
           {"location":{"description":"St Albans City","shortCodes":["SAC"]},"temporalData":{"departure":{"scheduleAdvertised":"2026-10-04T17:40:00+01:00","realtimeForecast":"2026-10-04T17:47:00+01:00"},"displayAs":"CALL"},"locationMetadata":{"platform":{"planned":"4"}}},
           {"location":{"description":"Radlett"},"temporalData":{"pass":{"scheduleAdvertised":"2026-10-04T17:45:00+01:00"},"displayAs":"PASS"}},
           {"location":{"description":"London St Pancras International","shortCodes":["STP"]},"temporalData":{"arrival":{"scheduleAdvertised":"2026-10-04T18:02:00+01:00","realtimeForecast":"2026-10-04T18:08:00+01:00"},"displayAs":"CALL"},"locationMetadata":{"platform":{"planned":"A"}}}
          ]}}
        """.trimIndent()
        val out = JsonParser.parseString(summariseService(service)).asJsonObject
        val calls = out["calls"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("St Albans City (SAC)", "London St Pancras International (STP)"), calls.map { it["station"].asString })
        assertEquals("17:47", calls[0]["dep_expected"].asString)
        assertEquals("18:02", calls[1]["arr"].asString)
        assertEquals("18:08", calls[1]["arr_expected"].asString)
        assertEquals("A", calls[1]["platform"].asString)
    }

    @Test
    fun `times are shown in UK local time, summer and winter`() {
        assertEquals("17:32", ukTime("2026-10-04T17:32:00+01:00"))
        assertEquals("17:32", ukTime("2026-10-04T16:32:00Z"))      // BST
        assertEquals("17:32", ukTime("2026-12-04T17:32:00Z"))      // GMT
        assertEquals("17:32", ukTime("2026-10-04T17:32:00"))       // no zone: UK local
        assertEquals("17:32", ukTime("2026-10-04T17:32:00.000+01:00"))
        assertNull(ukTime("not a time"))
    }

    @Test
    fun `the time Grok asks for is sent as UK local time`() {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mmXXX", Locale.UK).parse("2026-10-04T15:05+00:00")!!.time // 16:05 BST
        assertEquals("2026-10-04T17:30:00", ukQueryTime("17:30", now))
        assertEquals("2026-10-05T08:15:00", ukQueryTime("2026-10-05T08:15", now))
        assertEquals("2026-10-04T16:05:00", ukQueryTime(null, now))
        assertEquals("2026-10-04T16:05:00", ukQueryTime("soon", now))
    }

    @Test
    fun `Grok's function calls are picked out of the stream`() {
        val sse = """
            data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"uk_train_departures","arguments":"{\"station\":\"SAC\",\"calling_at\":\"STP\"}"}}

            data: {"type":"response.completed","response":{"id":"resp_1","output":[{"type":"function_call","call_id":"call_1","name":"uk_train_departures","arguments":"{\"station\":\"SAC\",\"calling_at\":\"STP\"}"}]}}

        """.trimIndent()
        val parsed = ResponsesSseParser().parse(StringReader(sse))
        assertEquals(1, parsed.functionCalls.size) // the same call seen twice counts once
        val call = parsed.functionCalls.single()
        assertEquals("call_1", call.callId)
        assertEquals("uk_train_departures", call.name)
        assertEquals("SAC", JsonParser.parseString(call.arguments).asJsonObject["station"].asString)
    }

    @Test
    fun `the request carries the tools, the call and its result as xAI documents them`() {
        val request = ResponsesRequest(
            input = listOf(
                InputMessage(role = "user", content = "Next train to St Pancras?"),
                FunctionCallItem(callId = "call_1", name = "uk_train_departures", arguments = "{\"station\":\"SAC\"}"),
                FunctionCallOutputItem(callId = "call_1", output = "{\"services\":[]}")
            ),
            tools = listOf(ResponseTool(type = "web_search")) + TRAIN_TOOLS
        )
        val json = JsonParser.parseString(Gson().toJson(request)).asJsonObject
        val tools = json["tools"].asJsonArray.map { it.asJsonObject }
        assertEquals("""{"type":"web_search"}""", tools[0].toString()) // unchanged
        assertEquals("function", tools[1]["type"].asString)
        assertEquals("uk_train_departures", tools[1]["name"].asString)
        assertEquals("station", tools[1]["parameters"].asJsonObject["required"].asJsonArray[0].asString)
        assertEquals("uk_train_service", tools[2]["name"].asString)
        val input = json["input"].asJsonArray.map { it.asJsonObject }
        assertEquals("function_call", input[1]["type"].asString)
        assertEquals("call_1", input[1]["call_id"].asString)
        assertEquals("function_call_output", input[2]["type"].asString)
        assertEquals("call_1", input[2]["call_id"].asString)
        assertFalse(json.toString().contains("callId"))
    }

    @Test
    fun `the token is never sent anywhere but Realtime Trains`() {
        // Guard against the token ever being written into requests to xAI.
        val json = Gson().toJson(ResponsesRequest(input = emptyList(), tools = TRAIN_TOOLS))
        assertFalse(json.contains("rtt_token"))
        assertTrue(LIVE_TRAINS_INSTRUCTIONS.contains("Realtime Trains"))
    }
}
