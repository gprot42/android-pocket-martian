package com.pocketmartian.app.data.api

import com.pocketmartian.app.data.model.FunctionCallItem

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.pocketmartian.app.data.model.ResponsesResponse
import java.io.BufferedReader
import java.io.Reader

/**
 * Parse an xAI / OpenAI-style Responses API SSE stream (`stream: true`).
 *
 * Events we care about:
 * - `response.output_text.delta` / `response.text.delta` — token text
 * - `response.completed` — full response object (preferred source of text/usage)
 * - `response.failed` / `error` — terminal error
 * - `response.web_search_call.*` — search ran
 * - `response.output_text.annotation.added` — citation URLs
 * - `response.image_generation_call.*` and `image_generation_call` items — a picture
 *   made with Grok Imagine, delivered whole as base64 in the finished item
 * - chat.completion.chunk (`choices[0].delta.content`) — fallback shape
 *
 * SSE comments (`:` lines) are keepalives and reset the HTTP read timeout;
 * that is the main reason streaming stops "Timed out contacting api.x.ai"
 * during long `web_search` / reasoning gaps.
 */
/**
 * Live progress from the stream. Without this the whole reply lands at once after
 * the model has finished searching, reasoning and writing, so the UI can only show
 * an undifferentiated spinner for the entire wait.
 */
internal interface ResponsesStreamListener {
    /** The model started (or continued) a web search. */
    fun onSearchStarted() {}

    /** A chunk of answer text arrived. */
    fun onDelta(text: String) {}

    /** Any event arrived; its type, e.g. `response.created`. */
    fun onEvent(type: String) {}

    /** A web search call was added to the response: once per search. */
    fun onSearchCall() {}

    /** Grok started making or editing a picture. */
    fun onImageStarted() {}
}

/** A picture from the image_generation tool: [base64] is the image itself, no data-URL prefix. */
internal data class GeneratedImageData(
    val id: String,
    /** What the model asked Grok Imagine for. */
    val prompt: String?,
    val base64: String
)

internal data class ParsedResponsesStream(
    val completed: ResponsesResponse? = null,
    val accumulatedText: String = "",
    val citations: List<String> = emptyList(),
    val usedWebSearch: Boolean = false,
    val errorMessage: String? = null,
    /** Calls to the app's own tools; the model waits for their results. */
    val functionCalls: List<FunctionCallItem> = emptyList(),
    /** Pictures made during this response, in the order they finished. */
    val images: List<GeneratedImageData> = emptyList()
)

internal class ResponsesSseParser(
    private val gson: Gson = Gson(),
    private val listener: ResponsesStreamListener? = null
) {

    fun parse(reader: Reader): ParsedResponsesStream {
        val buffered = reader as? BufferedReader ?: BufferedReader(reader)
        val text = StringBuilder()
        val citations = LinkedHashSet<String>()
        var completed: ResponsesResponse? = null
        var usedWebSearch = false
        var errorMessage: String? = null
        val functionCalls = LinkedHashMap<String, FunctionCallItem>()
        val images = LinkedHashMap<String, GeneratedImageData>()
        val dataLines = ArrayList<String>()

        // The finished item carries the picture; the completed response repeats it, so
        // images are kept by id and each is taken once.
        fun collectImage(item: JsonObject?) {
            if (item?.str("type") != "image_generation_call") return
            val result = item.str("result")?.takeIf { it.isNotBlank() } ?: return
            val id = item.str("id") ?: "image${images.size + 1}"
            if (id !in images) images[id] = GeneratedImageData(id = id, prompt = item.str("prompt"), base64 = result)
        }

        fun collectFunctionCall(item: JsonObject?) {
            if (item?.str("type") != "function_call") return
            val callId = item.str("call_id") ?: return
            val name = item.str("name") ?: return
            functionCalls[callId] = FunctionCallItem(callId = callId, name = name, arguments = item.str("arguments") ?: "{}")
        }

        fun flushEvent() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n").trim()
            dataLines.clear()
            if (data.isEmpty() || data == "[DONE]") return
            val obj = try {
                gson.fromJson(data, JsonObject::class.java)
            } catch (_: Exception) {
                return
            } ?: return
            obj.str("type")?.let { listener?.onEvent(it) }
            when (val type = obj.str("type")) {
                "response.failed", "error" -> {
                    errorMessage = obj.errorText() ?: "Streaming response failed."
                }
                "response.completed", "response.done", "response.incomplete" -> {
                    val payload = obj.obj("response") ?: obj
                    completed = runCatching {
                        gson.fromJson(payload, ResponsesResponse::class.java)
                    }.getOrNull() ?: completed
                    collectCitations(payload, citations)
                    if (payloadHasWebSearch(payload)) usedWebSearch = true
                    payload.arr("output")?.forEach {
                        if (it.isJsonObject) {
                            collectFunctionCall(it.asJsonObject)
                            collectImage(it.asJsonObject)
                        }
                    }
                }
                "response.output_text.delta", "response.text.delta" -> {
                    obj.str("delta")?.let {
                        text.append(it)
                        listener?.onDelta(it)
                    }
                }
                "response.output_text.done", "response.text.done" -> {
                    // Prefer the finalized part text when no completed payload arrives.
                    val done = obj.str("text")
                    if (done != null && text.isEmpty()) text.append(done)
                }
                "response.output_text.annotation.added" -> {
                    urlFrom(obj.get("annotation"))?.let { citations.add(it) }
                    urlFrom(obj.get("url"))?.let { citations.add(it) }
                }
                else -> {
                    if (type?.startsWith("response.web_search_call") == true) {
                        usedWebSearch = true
                        listener?.onSearchStarted()
                    }
                    if (type?.startsWith("response.image_generation_call") == true) {
                        listener?.onImageStarted()
                    }
                    if (type == "response.output_item.added" || type == "response.output_item.done") {
                        val item = obj.obj("item")
                        // A function call arrives whole, not streamed in pieces.
                        if (type == "response.output_item.done") {
                            collectFunctionCall(item)
                            collectImage(item)
                        }
                        if (item?.str("type") == "image_generation_call" && type == "response.output_item.added") {
                            listener?.onImageStarted()
                        }
                        if (item?.str("type") == "web_search_call") {
                            usedWebSearch = true
                            listener?.onSearchStarted()
                            if (type == "response.output_item.added") listener?.onSearchCall()
                        }
                        // A picture's base64 holds no links, and walking megabytes of it would not be free.
                        if (item?.str("type") != "image_generation_call") collectCitations(item, citations)
                    }
                    // chat.completion.chunk fallback
                    val delta = obj.arr("choices")
                        ?.firstOrNull()
                        ?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.obj("delta")
                        ?.str("content")
                    if (!delta.isNullOrEmpty()) {
                        text.append(delta)
                        listener?.onDelta(delta)
                    }
                    collectCitations(obj, citations)
                }
            }
        }

        buffered.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> flushEvent()
                line.startsWith(":") -> Unit // keepalive comment
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                // event: / id: / retry: ignored; type lives in the JSON payload
            }
        }
        flushEvent()

        return ParsedResponsesStream(
            completed = completed,
            accumulatedText = text.toString(),
            citations = citations.toList(),
            usedWebSearch = usedWebSearch || citations.isNotEmpty(),
            errorMessage = errorMessage,
            functionCalls = functionCalls.values.toList(),
            images = images.values.toList()
        )
    }

    private fun collectCitations(el: JsonElement?, into: MutableSet<String>) {
        if (el == null || el.isJsonNull) return
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> {
                val s = el.asString
                if (s.startsWith("http://") || s.startsWith("https://")) into.add(s)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectCitations(it, into) }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                urlFrom(obj)?.let { into.add(it) }
                obj.entrySet().forEach { (key, value) ->
                    if (key == "citations" || key == "annotations" || key == "url" || key == "uri") {
                        collectCitations(value, into)
                    } else if (value.isJsonObject || value.isJsonArray) {
                        collectCitations(value, into)
                    }
                }
            }
        }
    }

    private fun payloadHasWebSearch(obj: JsonObject): Boolean {
        val output = obj.get("output") ?: return false
        if (!output.isJsonArray) return false
        return output.asJsonArray.any { el ->
            el.isJsonObject && el.asJsonObject.str("type") == "web_search_call"
        }
    }

    private fun urlFrom(el: JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    el.asString.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    listOf("url", "uri", "href")
                        .firstNotNullOfOrNull { key ->
                            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.errorText(): String? {
        obj("error")?.str("message")?.let { return it }
        str("message")?.let { return it }
        obj("response")?.obj("error")?.str("message")?.let { return it }
        return null
    }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String) =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
