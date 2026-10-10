package com.pocketmartian.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.pocketmartian.app.AppDefaults
import com.pocketmartian.app.data.api.GeneratedImageData
import com.pocketmartian.app.data.api.ResponsesSseParser
import com.pocketmartian.app.data.api.ResponsesStreamListener
import com.pocketmartian.app.data.api.XaiApiService
import com.pocketmartian.app.data.model.InputContent
import com.pocketmartian.app.data.model.InputMessage
import com.pocketmartian.app.data.model.Message
import com.pocketmartian.app.data.model.ResponseTool
import com.pocketmartian.app.data.model.ResponsesRequest
import com.pocketmartian.app.data.model.ResponsesUsage
import com.pocketmartian.app.data.model.FunctionCallOutputItem
import com.pocketmartian.app.data.model.FunctionCallItem
import com.pocketmartian.app.data.model.ResponsesResponse
import com.pocketmartian.app.data.model.ReasoningConfig
import com.pocketmartian.app.data.model.TextContent
import com.pocketmartian.app.data.model.Usage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import java.io.Reader
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** How many times a single send may hit the API before we give up (1 + retries). */
internal const val MAX_RESPONSES_ATTEMPTS = 3

/**
 * Tries when the connection could not be opened at all (no route, refused, DNS), and the
 * waits between them. Reported: "NoRouteToHostException. Retried 2x without luck" on a
 * phone with a VPN on. Three tries 0.5 s and 1.5 s apart gave up within two seconds,
 * sooner than a VPN takes to reconnect after the phone changes network; these ride out
 * about seven.
 */
internal const val MAX_CONNECT_ATTEMPTS = 4
internal val CONNECT_RETRY_WAITS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

/** Re-warm the pooled TLS connection if it has been idle longer than this. */
private const val WARMUP_INTERVAL_MS = 4 * 60 * 1_000L

/**
 * Thrown when the SSE connection drops before a single byte of the body arrived.
 * Nothing was generated/billed, so the caller may safely retry.
 */
internal class EarlyStreamFailure(cause: Throwable) :
    IOException("Stream dropped before any data arrived: ${cause.message}", cause)

/**
 * The stream broke after data had arrived. Never retried: a connection reset or broken
 * pipe mid-answer (Wi-Fi handing over to mobile data, say) used to count as "couldn't
 * connect" and the whole request was sent again, re-running its searches and billing it
 * a second time, while the text already shown was wiped.
 */
internal class StreamBrokeAfterData(cause: Throwable) :
    IOException("Stream dropped part-way through: ${cause.message}", cause)

/**
 * Live progress while a reply is being produced. The request already streams; without
 * surfacing it the user watches a motionless indicator through search, reasoning and
 * generation, then everything appears at once.
 */
sealed class ChatProgress {
    /** A web search turn started. */
    object Searching : ChatProgress()

    /** A chunk of answer text arrived. */
    data class Delta(val text: String) : ChatProgress()

    /** The attempt was abandoned and restarted; discard anything shown so far. */
    object Restarted : ChatProgress()

    /** Something worth telling the user while they wait, e.g. checking live train times. */
    data class Status(val text: String) : ChatProgress()
}

data class ChatResult(
    val assistantMessage: String,
    val usage: Usage? = null,
    val model: String = "",
    val usedWebSearch: Boolean = false,
    val citations: List<String> = emptyList(),
    /** Pictures Grok made for this answer, saved in the app's storage (see [GeneratedImages]). */
    val images: List<java.io.File> = emptyList()
)

/** Result of a lightweight GET /v1/models auth probe. */
sealed class ApiKeyCheckResult {
    data class Valid(
        val modelCount: Int,
        /** A few model ids for the UI (e.g. grok-4.7). */
        val sampleModels: List<String>
    ) : ApiKeyCheckResult()

    data class Invalid(val message: String) : ApiKeyCheckResult()
    data class NetworkError(val message: String) : ApiKeyCheckResult()
}

@Singleton
class ChatRepository @Inject constructor(
    private val apiService: XaiApiService,
    private val debugLogRepository: DebugLogRepository,
    private val okHttpClient: OkHttpClient,
    private val trains: RealtimeTrainsRepository,
    @ApplicationContext private val context: Context
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val TAG = "ChatRepository"
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmupInFlight = AtomicBoolean(false)
    private val lastWarmupMs = AtomicLong(0L)

    /**
     * Pre-resolve DNS and open (or refresh) the pooled HTTP/2 + TLS connection to
     * api.x.ai so the first real request skips the handshake (~0.5–1.5 s on mobile).
     * Fire-and-forget; failures are logged only. Unauthenticated HEAD → quick 401/404,
     * which still leaves the connection in OkHttp's pool.
     */
    fun warmUpConnection(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWarmupMs.get() < WARMUP_INTERVAL_MS) return
        if (!warmupInFlight.compareAndSet(false, true)) return
        warmupScope.launch {
            try {
                val request = Request.Builder()
                    .url("https://api.x.ai/v1/models")
                    .head()
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    Log.d(TAG, "Connection warm-up: HTTP ${resp.code} via ${resp.protocol}")
                }
                lastWarmupMs.set(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Connection warm-up failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                warmupInFlight.set(false)
            }
        }
    }

    /**
     * Fast local check so an offline device gets an instant, honest error instead of a
     * DNS stall. Advisory only: any failure (missing ACCESS_NETWORK_STATE, OEM quirks,
     * binder errors) returns true so the real request still runs — this must never
     * be the reason a send crashes.
     */
    /** Whether the phone's traffic is currently going through a VPN. */
    private fun isVpnActive(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // can't tell — let the request try
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity check unavailable (${e.javaClass.simpleName}); assuming online")
            true
        }
    }

    /**
     * Verify an xAI chat API key by calling GET /v1/models (no chat tokens billed).
     * Uses the key as typed — does not require it to be saved yet.
     */
    suspend fun checkApiKey(apiKey: String): ApiKeyCheckResult {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return ApiKeyCheckResult.Invalid("Enter an API key first.")
        }
        return try {
            val response = apiService.listModels(auth = "Bearer $key")
            val ids = response.data.orEmpty().mapNotNull { it.id?.takeIf(String::isNotBlank) }
            Log.d(TAG, "API key check OK: ${ids.size} models")
            ApiKeyCheckResult.Valid(
                modelCount = ids.size,
                sampleModels = ids.take(5)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Throwable) { "" }
            Log.e(TAG, "API key check HTTP ${e.code()}: ${body.take(500)}")
            // Reuse chat error mapping so credits vs bad-key is clear.
            ApiKeyCheckResult.Invalid(friendlyHttpError(e.code(), body, e.message()))
        } catch (e: Exception) {
            when {
                isTimeoutFailure(e) ->
                    ApiKeyCheckResult.NetworkError("Timed out contacting api.x.ai. Check network.")
                e.causeChain().any { it is UnknownHostException } ->
                    ApiKeyCheckResult.NetworkError(dnsFailureMessage("api.x.ai"))
                else ->
                    ApiKeyCheckResult.NetworkError("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            }
        }
    }

    suspend fun sendMessage(
        apiKey: String,
        text: String,
        imageBase64List: List<String> = emptyList(),
        history: List<Message>,
        debugMode: Boolean = false,
        responseFormat: String = "html",
        model: String = AppDefaults.DEFAULT_MODEL,
        /** Optional approximate location snippet already formatted for instructions. */
        locationContext: String? = null,
        /** Let Grok make and edit pictures with Grok Imagine when the prompt calls for it. */
        imagesEnabled: Boolean = true,
        /**
         * Pictures Grok made in its previous answer, sent again so that a follow-up such
         * as "now make it night" can edit them: requests are not stored at xAI, so the
         * picture is otherwise gone from the conversation.
         */
        previousImagesBase64: List<String> = emptyList(),
        /** Called as the reply streams in, off the main thread. */
        onProgress: ((ChatProgress) -> Unit)? = null,
        /** Where the time goes on this prompt; see [SendTiming]. */
        timing: SendTiming? = null
    ): Result<ChatResult> {
        if (!isNetworkAvailable()) {
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = "OFFLINE",
                    body = "No active network with internet capability; request not sent."
                )
            }
            return Result.failure(
                RuntimeException("No internet connection. Turn on Wi-Fi or mobile data and retry.")
            )
        }
        return try {
            val transitEnquiry = looksLikeTransitEnquiry(text)
            // Live UK train times, when the user has entered their own Realtime Trains token.
            val trainsAvailable = trains.hasToken()
            val formatInstructions = when (responseFormat) {
                "html" -> "Respond using valid HTML markup only. Use tags like <p>, <ul>, <ol>, <li>, <strong>, <em>, <code>, <pre>, <h1>-<h3>, <table>, <blockquote> where appropriate. Do not wrap in <html> or <body> tags. Do not use markdown."
                "markdown" -> "Respond using Markdown formatting. Use headers, bold, italic, code blocks, lists, tables where appropriate."
                else -> ""
            }
            val webInstructions = buildString {
                append(
                    "If you are unsure or the answer may rely on recent or factual information you don't " +
                        "reliably know, use the web_search tool to find accurate, up-to-date information " +
                        "instead of guessing. Prefer official primary sources when available."
                )
                // The rail playbook is only sent for transit-looking prompts. Sending it on
                // every turn added ~1k prompt tokens and nudged the model into web_search
                // (and slow answers) for unrelated questions.
                if (trainsAvailable) {
                    append(" ")
                    append(LIVE_TRAINS_INSTRUCTIONS)
                }
                if (transitEnquiry && trainsAvailable) {
                    append(" ")
                    append(
                        "THIS USER MESSAGE IS A TRANSIT/RAIL ENQUIRY. Before answering, call " +
                            "uk_train_departures for the live times that match it (and uk_train_service " +
                            "for arrival times), and answer from those. Use web_search only for what " +
                            "they cannot tell you. Include the National Rail link " +
                            "https://www.nationalrail.co.uk for journey planning."
                    )
                } else if (transitEnquiry) {
                    append(" ")
                    // Hard requirements first — soft "prefer" lists alone are ignored by the model.
                    append(
                    "UK RAIL / LIVE TIMES (mandatory when the user asks about trains, departures, arrivals, " +
                        "journeys, delays, disruptions, platforms, or transport from A/here to B):\n" +
                        "1. You MUST call web_search before giving any clock times, schedules, or disruption claims. " +
                        "Never invent live departures from memory.\n" +
                        "2. Your FIRST search queries MUST target National Rail live data explicitly, using " +
                        "site:www.nationalrail.co.uk and/or site:realtime.nationalrail.co.uk (and plain " +
                        "nationalrail.co.uk / realtime.nationalrail.co.uk). Example queries: " +
                        "\"site:nationalrail.co.uk live departures <station>\", " +
                        "\"site:nationalrail.co.uk journey planner <from> to <to>\", " +
                        "\"site:realtime.nationalrail.co.uk <station>\".\n" +
                        "3. Open a National Rail journey planner or live departure board result only " +
                        "when the search results do not already contain the times you need; quote times " +
                        "from whatever you open.\n" +
                        "4. Stop as soon as you can answer. Search operator sites (Thameslink, TfL, " +
                        "London Northwestern, etc.) only if National Rail did not cover it.\n" +
                        "5. Always include clickable source URLs (especially nationalrail.co.uk) in the answer. " +
                        "If web_search cannot retrieve live boards, say so clearly and still give the " +
                        "National Rail links the user can open.\n"
                    )
                    append(
                    "Preferred official sources — hubs: https://www.nationalrail.co.uk , " +
                        "https://realtime.nationalrail.co.uk , https://www.networkrail.co.uk . " +
                        "St Albans / nearby: Thameslink https://www.thameslinkrailway.com " +
                        "(St Albans City); London Northwestern " +
                        "https://www.londonnorthwesternrailway.co.uk (St Albans Abbey / Abbey Line); " +
                        "Great Northern https://www.greatnorthernrail.com ; " +
                        "East Midlands Railway https://www.eastmidlandsrailway.co.uk ; " +
                        "Intalink https://www.intalink.org.uk . " +
                        "London: https://tfl.gov.uk , https://citymapper.com . " +
                        "Other TOCs: greateranglia.co.uk, southeasternrailway.co.uk, southernrailway.com, " +
                        "gatwickexpress.com, southwesternrailway.com, c2c-online.co.uk, " +
                        "chilternrailways.co.uk, gwr.com, avantiwestcoast.co.uk, lner.co.uk, " +
                        "crosscountrytrains.co.uk, tpexpress.co.uk, northernrailway.co.uk, " +
                        "westmidlandsrailway.co.uk, scotrail.co.uk, tfw.wales, hulltrains.co.uk, " +
                        "grandcentralrail.com, lumo.co.uk, sleeper.scot. " +
                        "For non-UK transit use official operators; for other topics use open web_search."
                    )
                    append(" ")
                    append(
                        "THIS USER MESSAGE IS A TRANSIT/RAIL ENQUIRY. " +
                            "Before answering, you MUST web_search National Rail " +
                            "(www.nationalrail.co.uk and realtime.nationalrail.co.uk) for live " +
                            "departures and/or journey options that match the enquiry. " +
                            "Do not answer with only memorised timetable knowledge."
                    )
                }
                if (!locationContext.isNullOrBlank()) {
                    append(" ")
                    append(locationContext)
                    append(
                        " LOCATION RULES: The numeric GPS coordinates are the source of truth. " +
                            "When the user says \"here\", \"current location\", or \"near me\", " +
                            "resolve nearest stations/stops/places from those coordinates " +
                            "(and UK postcode if given), then search National Rail / TfL from those origins. " +
                            "Do NOT treat a reverse-geocoded town or district name as definitive if it " +
                            "conflicts with the coordinates — UK geocoders often name a neighbouring " +
                            "town or district HQ. " +
                            "If accuracy is marked coarse, only use coordinates, never invent a town."
                    )
                }
            }
            val imageInstructions = if (imagesEnabled) IMAGE_INSTRUCTIONS else ""
            val instructions = listOf(formatInstructions, webInstructions, imageInstructions)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            val earlier = if (imagesEnabled) previousImagesBase64 else emptyList()
            val input = buildList {
                // Prior turns as plain-text messages (Responses API accepts string content)
                history.forEach { msg ->
                    add(InputMessage(role = msg.role, content = flattenText(msg)))
                }
                // Current user turn: typed parts when images are attached, else plain text.
                // Responses API image format matches docs:
                // https://docs.x.ai/developers/model-capabilities/images/understanding
                // (POST https://api.x.ai/v1/responses, type=input_image, data-URL or https URL).
                if (imageBase64List.isNotEmpty() || earlier.isNotEmpty()) {
                    val promptText = text.ifBlank {
                        if (imageBase64List.size == 1) "Describe this image."
                        else "Describe these images."
                    }
                    val parts = buildList {
                        if (earlier.isNotEmpty()) {
                            add(InputContent(type = "input_text", text = PREVIOUS_IMAGES_NOTE))
                            for (imageBase64 in earlier) {
                                add(InputContent(type = "input_image", imageUrl = "data:image/jpeg;base64,$imageBase64"))
                            }
                        }
                        for (imageBase64 in imageBase64List) {
                            add(
                                InputContent(
                                    type = "input_image",
                                    imageUrl = "data:image/jpeg;base64,$imageBase64"
                                )
                            )
                        }
                        add(InputContent(type = "input_text", text = promptText))
                    }
                    add(InputMessage(role = "user", content = parts))
                } else {
                    add(InputMessage(role = "user", content = text))
                }
            }

            // Keep web_search open (no allowed_domains lock): transit still needs general
            // search for "nearest station" etc. National Rail is enforced via instructions.
            var request = ResponsesRequest(
                model = AppDefaults.normalizeChatModel(model),
                input = input,
                instructions = instructions,
                tools = listOf(ResponseTool(type = "web_search")) +
                    (if (imagesEnabled) listOf(ResponseTool(type = "image_generation")) else emptyList()) +
                    (if (trainsAvailable) TRAIN_TOOLS else emptyList()),
                // Slightly lower temperature for timetable / factual transit answers
                temperature = if (transitEnquiry) 0.3 else 0.7,
                // Thinking time dominates wall clock: the API defaults to "high", which
                // spends tens of seconds before the first token. Ordinary questions do
                // not need it; rail answers combine several sources and timetable
                // arithmetic, so they keep the full budget.
                reasoning = ReasoningConfig(
                    effort = if (transitEnquiry) AppDefaults.EFFORT_HIGH else AppDefaults.EFFORT_LOW
                ),
                // Stop the model looping through search after search on a vague question.
                maxTurns = if (transitEnquiry) {
                    AppDefaults.MAX_TURNS_TRANSIT
                } else {
                    AppDefaults.MAX_TURNS_DEFAULT
                },
                // Streaming is required for agent tools: web_search/reasoning can sit
                // silent for minutes; SSE events (and HTTP/2 pings) keep the socket alive.
                stream = true
            )

            if (debugMode) {
                // Never keep raw image bytes in the debug log — a single 1024px JPEG
                // is hundreds of KB of unbroken base64, which ANRs Compose Text layout.
                val requestJson = gson.toJson(redactImagesForLog(request))
                debugLogRepository.logOutgoing(
                    summary = "POST /v1/responses stream | model=${request.model} | turns=${input.size} | " +
                        "images=${imageBase64List.size}+${earlier.size} | tools=${request.tools?.joinToString(",") { it.name ?: it.type }} | transit=$transitEnquiry",
                    body = requestJson
                )
                Log.d(TAG, "REQUEST: ${sanitizeLogBody(requestJson, maxChars = 4000)}")
            }

            timing?.apply {
                note("model", request.model)
                note("effort", request.reasoning?.effort ?: "default")
                note("turns", request.maxTurns)
                note("rail", if (transitEnquiry) "yes" else "no")
                note("prompt", text.length)
                note("images", imageBase64List.size)
                if (earlier.isNotEmpty()) note("earlier images", earlier.size)
                note("history", "${history.size} msgs ${"%,d".format(history.sumOf { flattenText(it).length })} chars")
            }

            var call = try {
                executeResponses(apiKey, request, debugMode, onProgress, timing)
            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Throwable) { "" }
                if ((request.reasoning != null || request.maxTurns != null) &&
                    isUnsupportedParameterFailure(e.code(), body)
                ) {
                    // The API rejected a tuning field, not the prompt. Retry plainly so a
                    // server-side change can never take chat down.
                    Log.w(TAG, "Tuning parameters rejected (HTTP ${e.code()}); retrying without them")
                    if (debugMode) {
                        debugLogRepository.logIncoming(
                            summary = "RETRY without reasoning/max_turns",
                            body = body.take(500)
                        )
                    }
                    onProgress?.invoke(ChatProgress.Restarted)
                    request = request.copy(reasoning = null, maxTurns = null)
                    timing?.note("effort", "default (low rejected)")
                    executeResponses(apiKey, request, debugMode, onProgress, timing)
                } else if (request.model != AppDefaults.BACKUP_MODEL &&
                    isUnknownModelFailure(e.code(), body)
                ) {
                    if (debugMode) {
                        debugLogRepository.logIncoming(
                            summary = "MODEL FALLBACK ${request.model} → ${AppDefaults.BACKUP_MODEL}",
                            body = body.take(500)
                        )
                    }
                    onProgress?.invoke(ChatProgress.Restarted)
                    request = request.copy(model = AppDefaults.BACKUP_MODEL)
                    timing?.note("model", "${request.model} (fallback)")
                    executeResponses(apiKey, request, debugMode, onProgress, timing)
                } else {
                    Log.e(TAG, "HTTP ERROR ${e.code()}: ${body.take(2000)}")
                    if (debugMode) {
                        debugLogRepository.logIncoming(
                            summary = "HTTP ${e.code()}: ${e.message()}",
                            body = body.take(2000)
                        )
                    }
                    return Result.failure(
                        RuntimeException(friendlyHttpError(e.code(), body, e.message()))
                    )
                }
            }
            // Grok asked for live train times: fetch them, hand them back, let it carry on.
            // The request is resent whole with the calls and results appended, because
            // requests are not stored at xAI (store=false) to refer back to.
            val allCitations = LinkedHashSet<String>(call.citations)
            val madeImages = call.images.toMutableList()
            var searchedWeb = call.usedWebSearch
            val usages = mutableListOf<ResponsesUsage>()
            call.response?.usage?.let { usages += it }
            var rounds = 0
            while (call.functionCalls.isNotEmpty() && rounds < MAX_TOOL_ROUNDS) {
                rounds++
                onProgress?.invoke(ChatProgress.Status(TRAINS_STATUS))
                val outputs = call.functionCalls.map { fc ->
                    FunctionCallOutputItem(callId = fc.callId, output = runTool(fc, debugMode, timing))
                }
                request = request.copy(input = request.input + call.functionCalls + outputs)
                call = executeResponses(apiKey, request, debugMode, onProgress, timing)
                allCitations += call.citations
                madeImages += call.images
                searchedWeb = searchedWeb || call.usedWebSearch
                call.response?.usage?.let { usages += it }
            }
            val response = call.response
            // Written to files at once: the conversation keeps their locations, not megabytes of base64.
            val imageFiles = withContext(Dispatchers.IO) {
                madeImages.distinctBy { it.id }.mapNotNull { image ->
                    runCatching { GeneratedImages.save(context, image.id, image.base64) }
                        .onFailure { Log.w(TAG, "Couldn't save generated image ${image.id}: ${it.message}") }
                        .getOrNull()
                }
            }
            if (imageFiles.isNotEmpty()) timing?.note("images made", imageFiles.size)
            val baseMessage = response?.let { extractText(it) }.orEmpty()
                .ifBlank { call.accumulatedText }
                // A picture can be the whole answer.
                .ifBlank { if (imageFiles.isNotEmpty()) "" else "No response" }
            val citations = LinkedHashSet<String>().apply {
                if (response != null) addAll(extractCitations(response))
                addAll(allCitations)
            }.toList()
            val assistantMessage = if (responseFormat != "markdown") sanitizeHtml(baseMessage) else baseMessage
            val usedWebSearch = searchedWeb ||
                response?.output?.any { it.type == "web_search_call" } == true ||
                citations.isNotEmpty()

            // Every round is billed, so cost and timing count them all.
            if (usages.isNotEmpty()) {
                timing?.note("tokens.in", usages.sumOf { it.inputTokens })
                timing?.note("tokens.cached", usages.sumOf { it.inputDetails?.cachedTokens ?: 0 }.takeIf { it > 0 })
                timing?.note("tokens.out", usages.sumOf { it.outputTokens })
                timing?.note("tokens.reasoning", usages.sumOf { it.outputDetails?.reasoningTokens ?: 0 }.takeIf { it > 0 })
            }

            val usage = usages.takeIf { it.isNotEmpty() }?.let { all ->
                val inTokens = all.sumOf { it.inputTokens }
                val outTokens = all.sumOf { it.outputTokens }
                Usage(prompt_tokens = inTokens, completion_tokens = outTokens, total_tokens = inTokens + outTokens)
            }

            if (debugMode) {
                val responseJson = gson.toJson(response ?: mapOf("text" to call.accumulatedText))
                debugLogRepository.logIncoming(
                    summary = "HTTP 200 stream | usage=${response?.usage?.totalTokens ?: "N/A"} tokens | citations=${citations.size}",
                    body = responseJson
                )
                Log.d(TAG, "RESPONSE: ${sanitizeLogBody(responseJson, maxChars = 4000)}")
            }

            Result.success(
                ChatResult(
                    assistantMessage,
                    usage,
                    model = request.model,
                    usedWebSearch = usedWebSearch,
                    citations = citations,
                    images = imageFiles
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (_: Throwable) { "" }
            // Always log the raw body so credit false-positives can be diagnosed in logcat.
            Log.e(TAG, "HTTP ERROR ${e.code()}: ${body.take(2000)}")
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = "HTTP ${e.code()}: ${e.message()}",
                    body = body.take(2000)
                )
            }
            Result.failure(RuntimeException(friendlyHttpError(e.code(), body, e.message())))
        } catch (e: Exception) {
            // Stopped: the closed body surfaces here as an IOException, not as cancellation.
            currentCoroutineContext().ensureActive()
            if (debugMode) {
                debugLogRepository.logIncoming(
                    summary = when {
                        isTimeoutFailure(e) -> "TIMEOUT"
                        e.causeChain().any { it is UnknownHostException } -> "DNS ERROR"
                        else -> "EXCEPTION: ${e.javaClass.simpleName}"
                    },
                    body = e.message ?: "unknown"
                )
            }
            when {
                isTimeoutFailure(e) ->
                    Result.failure(
                        RuntimeException(
                            "Timed out contacting api.x.ai. The model may still be searching " +
                                "or reasoning — wait a moment and retry. Check network if this keeps happening."
                        )
                    )
                e.causeChain().any { it is UnknownHostException } ->
                    Result.failure(RuntimeException(dnsFailureMessage("api.x.ai")))
                isStreamInterruption(e) -> {
                    val tries = e.causeChain().filterIsInstance<ConnectAttemptsExhausted>().firstOrNull()?.attempts
                    val cause = e.causeChain().last().javaClass.simpleName
                    val how = if (tries != null && tries > 1) " after $tries tries" else ""
                    val vpn = if (isVpnActive()) {
                        " A VPN is on: if it was reconnecting, or does not route to api.x.ai, that is the likely cause."
                    } else {
                        " Check signal and try again."
                    }
                    Result.failure(
                        RuntimeException(
                            if (isTransientConnectFailure(e) && e.causeChain().none { it is StreamBrokeAfterData }) {
                                "Couldn't reach api.x.ai ($cause)$how.$vpn"
                            } else {
                                "Connection to api.x.ai dropped ($cause)$how.$vpn"
                            }
                        )
                    )
                }
                else ->
                    Result.failure(RuntimeException("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"))
            }
        }
    }

    private data class ResponsesCallResult(
        val response: ResponsesResponse?,
        val accumulatedText: String,
        val citations: List<String>,
        val usedWebSearch: Boolean,
        val functionCalls: List<FunctionCallItem> = emptyList(),
        val images: List<GeneratedImageData> = emptyList()
    )

    /**
     * Up to [MAX_RESPONSES_ATTEMPTS] attempts, but only for failures where nothing
     * has been generated yet: connect/DNS/TLS/reset, a stream that dropped before its
     * first byte, and 408/429/5xx statuses. A request that already spent minutes
     * streaming is never retried — that looks like "timed out repeatedly" and can
     * double-bill.
     */
    private suspend fun executeResponses(
        apiKey: String,
        request: ResponsesRequest,
        debugMode: Boolean,
        onProgress: ((ChatProgress) -> Unit)? = null,
        timing: SendTiming? = null
    ): ResponsesCallResult {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return readResponses(apiKey, request, debugMode, onProgress, timing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val canRetry = attempt < MAX_RESPONSES_ATTEMPTS && isRetryableHttpStatus(e.code())
                if (!canRetry) throw e
                val retryAfter = e.response()?.headers()?.get("Retry-After")
                val wait = retryDelayMs(attempt, retryAfter)
                logRetry(debugMode, attempt, MAX_RESPONSES_ATTEMPTS, wait, "HTTP ${e.code()} ${e.message()}", timing)
                onProgress?.invoke(ChatProgress.Restarted)
                delay(wait)
            } catch (e: Exception) {
                // A send that was stopped must not come back as "Retrying…".
                currentCoroutineContext().ensureActive()
                val cannotConnect = isTransientConnectFailure(e)
                val transient = isRetryableResponsesFailure(e)
                val limit = if (cannotConnect) MAX_CONNECT_ATTEMPTS else MAX_RESPONSES_ATTEMPTS
                if (!transient) throw e
                // Say how many tries it took, without hiding what the failure was.
                if (attempt >= limit) throw if (attempt > 1) ConnectAttemptsExhausted(e, attempt) else e
                val wait = if (cannotConnect) {
                    CONNECT_RETRY_WAITS_MS[(attempt - 1).coerceAtMost(CONNECT_RETRY_WAITS_MS.lastIndex)]
                } else {
                    retryDelayMs(attempt)
                }
                logRetry(debugMode, attempt, limit, wait, "${e.javaClass.simpleName}: ${e.message}", timing)
                onProgress?.invoke(ChatProgress.Restarted)
                delay(wait)
            }
        }
    }

    /** Run one of the app's own tools for the model and return its result as text. */
    private suspend fun runTool(call: FunctionCallItem, debugMode: Boolean, timing: SendTiming?): String {
        val args = runCatching { com.google.gson.JsonParser.parseString(call.arguments).asJsonObject }.getOrNull()
        fun arg(name: String) = args?.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val started = System.nanoTime()
        val result = when (call.name) {
            "uk_train_departures" -> arg("station")?.let { station ->
                trains.departures(
                    station = station,
                    callingAt = arg("calling_at"),
                    comingFrom = arg("coming_from"),
                    time = arg("time"),
                    windowMinutes = arg("window_minutes")?.toDoubleOrNull()?.toInt()
                )
            } ?: """{"error":"station is required"}"""
            "uk_train_service" -> arg("id")?.let { trains.service(it) } ?: """{"error":"id is required"}"""
            else -> """{"error":"unknown tool ${call.name}"}"""
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        timing?.trainLookup(ms)
        if (debugMode) {
            debugLogRepository.logOutgoing(summary = "TOOL ${call.name} (${ms}ms)", body = call.arguments)
            debugLogRepository.logIncoming(summary = "TOOL ${call.name} result", body = result.take(4000))
        }
        return result
    }

    private fun logRetry(
        debugMode: Boolean,
        attempt: Int,
        limit: Int,
        waitMs: Long,
        reason: String,
        timing: SendTiming?
    ) {
        Log.w(TAG, "Attempt $attempt failed ($reason); retrying in ${waitMs}ms")
        timing?.note("retried after", reason.substringBefore(':'))
        if (debugMode) {
            debugLogRepository.logIncoming(
                summary = "RETRY ${attempt + 1}/$limit in ${waitMs}ms",
                body = reason
            )
        }
    }

    /** Counts characters handed to the SSE parser so we know whether a drop was "early". */
    private class CountingReader(private val delegate: Reader) : Reader() {
        var charsRead: Long = 0
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val n = delegate.read(cbuf, off, len)
            if (n > 0) charsRead += n
            return n
        }

        override fun close() = delegate.close()
    }

    private suspend fun readResponses(
        apiKey: String,
        request: ResponsesRequest,
        debugMode: Boolean,
        onProgress: ((ChatProgress) -> Unit)? = null,
        timing: SendTiming? = null
    ): ResponsesCallResult = withContext(Dispatchers.IO) {
        timing?.attemptStarted()
        val call = apiService.responsesStreamCall(
            auth = "Bearer $apiKey",
            request = request
        )
        // Reading the SSE stream is a blocking call, which coroutine cancellation alone
        // cannot interrupt. This used to close the body from a completion handler on this
        // job, which runs only once the job has finished, and it cannot finish while
        // blocked in the read: Stop and New chat looked done while the answer kept
        // streaming, and billing, to the end. The watcher runs on another IO thread and
        // cancels the call the moment the send is cancelled, which ends the read at once
        // and tells the server to stop.
        val finished = AtomicBoolean(false)
        coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
        val http = call.execute()
        timing?.mark(SendTiming.Mark.HEADERS)
        if (!http.isSuccessful) {
            throw HttpException(http)
        }
        val body = http.body() ?: throw IllegalStateException("Empty body from api.x.ai")
        body.use { rb ->
            val contentType = rb.contentType()?.toString().orEmpty()
                .ifBlank { http.headers()["Content-Type"].orEmpty() }
                .lowercase()
            if (contentType.contains("json") && !contentType.contains("event-stream")) {
                val json = rb.string()
                if (debugMode) {
                    debugLogRepository.logIncoming(
                        summary = "JSON (non-SSE) responses body",
                        body = sanitizeLogBody(json, maxChars = 4000)
                    )
                }
                val response = gson.fromJson(json, ResponsesResponse::class.java)
                return@coroutineScope ResponsesCallResult(
                    response = response,
                    accumulatedText = "",
                    citations = emptyList(),
                    usedWebSearch = false
                )
            }
            val counting = CountingReader(rb.charStream())
            val listener = object : ResponsesStreamListener {
                override fun onSearchStarted() {
                    onProgress?.invoke(ChatProgress.Searching)
                }

                override fun onDelta(text: String) {
                    timing?.mark(SendTiming.Mark.FIRST_TEXT)
                    onProgress?.invoke(ChatProgress.Delta(text))
                }

                override fun onEvent(type: String) {
                    timing?.mark(SendTiming.Mark.FIRST_EVENT)
                }

                override fun onSearchCall() {
                    timing?.searchStarted()
                }

                override fun onImageStarted() {
                    onProgress?.invoke(ChatProgress.Status(IMAGE_STATUS))
                }
            }
            val parsed = try {
                ResponsesSseParser(gson, listener).parse(counting)
            } catch (e: IOException) {
                // Stopped by the user: the watcher closed the body. Not a failure.
                currentCoroutineContext().ensureActive()
                if (counting.charsRead == 0L && isStreamInterruption(e)) {
                    throw EarlyStreamFailure(e)
                }
                // Part of the answer had already arrived, so the request ran (searches
                // and all) and is being billed. Sending it again would pay twice and
                // throw away what was shown; see StreamBrokeAfterData.
                if (counting.charsRead > 0L) throw StreamBrokeAfterData(e)
                throw e
            }
            if (parsed.errorMessage != null) {
                throw RuntimeException(parsed.errorMessage)
            }
            if (parsed.completed == null && parsed.accumulatedText.isBlank()) {
                throw IllegalStateException("Empty stream from api.x.ai")
            }
            ResponsesCallResult(
                response = parsed.completed,
                accumulatedText = parsed.accumulatedText,
                citations = parsed.citations,
                usedWebSearch = parsed.usedWebSearch,
                functionCalls = parsed.functionCalls,
                images = parsed.images
            )
        }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
        }
    }

    /**
     * Map raw HTTP failures into something a user can act on.
     *
     * Always lead with the real status + API body. Only append a credits tip when
     * the response is clearly about prepaid/billing — not rate limits, quotas, or
     * generic "insufficient …" messages (those were previously mislabeled as
     * "out of credits" even with a healthy console balance).
     *
     * SuperGrok Heavy does not fund this app; only the API key's team prepaid
     * balance at console.x.ai does.
     */
    private fun friendlyHttpError(code: Int, body: String, fallback: String?): String {
        val snippet = body.take(500).ifBlank { fallback.orEmpty() }
        val lower = (body + " " + fallback.orEmpty()).lowercase()

        // Rate limit first — bodies often mention "quota" and must not be sold as credits.
        if (code == 429 ||
            "rate limit" in lower ||
            "rate_limit" in lower ||
            "too many requests" in lower ||
            "resource_exhausted" in lower
        ) {
            return "HTTP $code: rate limited by xAI (not the same as empty prepaid credits). " +
                "Wait and retry, or check team RPM/TPM limits.\n\n" +
                "Details: ${snippet.ifBlank { "rate limit" }}"
        }

        // Credits/billing before generic 401/403 — xAI often returns 403 permission-denied
        // with a body about exhausted credits or monthly spending limit (not a bad key).
        val looksLikeCredits = code == 402 || listOf(
            "out of credits",
            "used all available credits",
            "available credits",
            "insufficient credit",
            "insufficient credits",
            "no credits",
            "credit balance",
            "prepaid credit",
            "prepaid credits",
            "credits exhausted",
            "credits depleted",
            "spending limit",
            "spend limit",
            "monthly spending",
            "payment required",
            "billing hard",
            "out of funds"
        ).any { it in lower }

        if (looksLikeCredits) {
            return "HTTP $code: billing/credits blocked by xAI for this API key's team.\n\n" +
                "Details: $snippet\n\n" +
                "Notes: SuperGrok Heavy does not pay for this app. Balance on console.x.ai must " +
                "belong to the same team as the API key in Settings. " +
                "Top up: https://console.x.ai/team/default/billing"
        }

        if (code == 401 || code == 403) {
            return "HTTP $code: API key rejected or forbidden. " +
                "Confirm the key in Settings is from the same console.x.ai team that shows your balance " +
                "(keys are team-scoped).\n\n" +
                "Details: ${snippet.ifBlank { "unauthorized" }}"
        }

        if (code == 413 || "payload too large" in lower || "request entity too large" in lower) {
            return "HTTP $code: request too large. Try fewer or smaller photos.\n\n" +
                "Details: ${snippet.ifBlank { "payload too large" }}"
        }

        return "HTTP $code: ${snippet.ifBlank { fallback ?: "request failed" }}"
    }

    /** Collapse a chat-completions [Message] (list of content parts) into a plain text string. */
    private fun flattenText(message: Message): String =
        message.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }

    /** Pull the assistant text out of a Responses API result. */
    private fun extractText(response: ResponsesResponse): String {
        response.outputText?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = response.output
            ?.filter { it.type == null || it.type == "message" }
            ?.flatMap { it.content.orEmpty() }
            ?.mapNotNull { part ->
                if (part.type == null || part.type == "output_text") part.text else null
            }
            ?: emptyList()
        return parts.joinToString("\n").trim()
    }

    /**
     * Collect citation URLs whether the API returns them as a top-level array of
     * strings, an array of objects with a "url" field, or as inline annotations.
     */
    private fun extractCitations(response: ResponsesResponse): List<String> {
        val urls = LinkedHashSet<String>()
        response.citations?.forEach { el -> urlFrom(el)?.let { urls.add(it) } }
        response.output
            ?.flatMap { it.content.orEmpty() }
            ?.flatMap { it.annotations.orEmpty() }
            ?.forEach { el -> urlFrom(el)?.let { urls.add(it) } }
        return urls.toList()
    }

    private fun urlFrom(el: JsonElement): String? = try {
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.takeIf { it.isNotBlank() }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                (obj.get("url") ?: obj.get("uri"))?.takeIf { it.isJsonPrimitive }?.asString
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Grok sometimes returns hybrid content (HTML with embedded markdown-style
     * fragments) even when asked for pure HTML. This pass converts the most common
     * leakage patterns to their HTML equivalents so the WebView renders them correctly.
     *
     *  [[n]](url)  →  <sup><a href="url">[n]</a></sup>   (inline citation numbers)
     *  [text](url) →  <a href="url">text</a>              (plain markdown links)
     *  ### Heading  →  <h3>Heading</h3>   (and ## → h2, # → h1)
     *  **text**    →  <strong>text</strong>
     *  *text*      →  <em>text</em>
     */
    private fun sanitizeHtml(text: String): String {
        var out = text

        // Inline citation footnotes:  [[1]](https://...)  →  <sup><a href="...">[1]</a></sup>
        out = out.replace(Regex("""\[\[(\d+)\]\]\(([^)]+)\)""")) { m ->
            "<sup><a href=\"${m.groupValues[2]}\">[${m.groupValues[1]}]</a></sup>"
        }

        // Plain markdown links:  [label](url)  →  <a href="url">label</a>
        out = out.replace(Regex("""\[([^\]]+)\]\((https?://[^)]+)\)""")) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }

        // ATX headings (only if they start a line)
        out = out.replace(Regex("""(?m)^### (.+)$""")) { m -> "<h3>${m.groupValues[1]}</h3>" }
        out = out.replace(Regex("""(?m)^## (.+)$"""))  { m -> "<h2>${m.groupValues[1]}</h2>" }
        out = out.replace(Regex("""(?m)^# (.+)$"""))   { m -> "<h1>${m.groupValues[1]}</h1>" }

        // Bold and italic
        out = out.replace(Regex("""\*\*(.+?)\*\*""")) { m -> "<strong>${m.groupValues[1]}</strong>" }
        out = out.replace(Regex("""\*(.+?)\*"""))      { m -> "<em>${m.groupValues[1]}</em>" }

        return out
    }
}

/** Shown while the app fetches live train times for Grok. */
internal const val TRAINS_STATUS = "Checking live train times\u2026"

/** Shown while Grok Imagine is drawing; a picture takes several seconds. */
internal const val IMAGE_STATUS = "Creating an image\u2026"

/**
 * Why the model reached for the image tool before: asked to put a person in a car from
 * a photo, Grok replied that it can't redraw or generate images, because nothing told it
 * that it can. The tool runs at xAI; the picture is returned with the answer.
 */
internal const val IMAGE_INSTRUCTIONS =
    "You can create pictures, and edit pictures the user attaches, with the image_generation " +
        "tool (Grok Imagine). When the user asks for an image, a drawing, a photo of something, " +
        "or a change to a picture (adding or removing people or things, a new background, a " +
        "different style), call image_generation instead of saying you can't make or edit " +
        "images. Write the image prompt in detail. The picture is shown to the user with your " +
        "reply, so keep the reply to a sentence or two about it. Never claim to have made a " +
        "picture without calling the tool. Don't call it when the user only asks about a picture."

/** Put before pictures resent from the previous answer, so the model knows what they are. */
internal const val PREVIOUS_IMAGES_NOTE =
    "(For reference: the picture(s) you made in your previous reply, in case this message " +
        "asks for changes to them.)"

/** Rounds of tool calls allowed in one answer, so a confused model cannot loop. */
internal const val MAX_TOOL_ROUNDS = 4

internal const val LIVE_TRAINS_INSTRUCTIONS =
    "LIVE UK TRAIN TIMES: you have the tools uk_train_departures and uk_train_service, which read " +
        "Realtime Trains live data (Network Rail's own running data). For any UK train times, next " +
        "trains, delays, cancellations or platforms, call them instead of searching the web, and give " +
        "the times they return (UK local time). Use station CRS codes. Use web_search only for what " +
        "they cannot tell you (fares, engineering works notices, planning journeys with changes). " +
        "Name Realtime Trains as the source of live times."

private fun schema(json: String) = com.google.gson.JsonParser.parseString(json)

/** The app's own tools, offered when the user has a Realtime Trains token. */
internal val TRAIN_TOOLS = listOf(
    ResponseTool(
        type = "function",
        name = "uk_train_departures",
        description = "Live departures from a UK National Rail station from Realtime Trains: scheduled " +
            "and expected times, platform, delays, cancellations and their reasons. Stations are CRS " +
            "codes, e.g. SAC St Albans City, STP London St Pancras International, KGX London Kings " +
            "Cross, EUS London Euston, LUT Luton, HPD Harpenden. Each train has an id for uk_train_service.",
        parameters = schema(
            """{"type":"object","properties":{
                "station":{"type":"string","description":"CRS code of the station to depart from, e.g. SAC"},
                "calling_at":{"type":"string","description":"Only trains that later call at this CRS code, e.g. STP"},
                "coming_from":{"type":"string","description":"Only trains that earlier called at this CRS code"},
                "time":{"type":"string","description":"Start of the window in UK time: HH:mm today, or YYYY-MM-DDTHH:mm. Default now."},
                "window_minutes":{"type":"integer","description":"Length of the window in minutes, 15 to 720. Default 120."}
            },"required":["station"]}"""
        )
    ),
    ResponseTool(
        type = "function",
        name = "uk_train_service",
        description = "Every stop of one train from Realtime Trains, with scheduled and expected arrival " +
            "and departure times and platforms. Use with an id from uk_train_departures, e.g. to give " +
            "the arrival time at the destination.",
        parameters = schema(
            """{"type":"object","properties":{
                "id":{"type":"string","description":"The id of a train from uk_train_departures"}
            },"required":["id"]}"""
        )
    )
)

/** Replace image data-URLs with a length placeholder so debug logs stay small. */
internal fun redactImagesForLog(request: ResponsesRequest): ResponsesRequest {
    val redactedInput = request.input.map { item ->
        val msg = item as? InputMessage ?: return@map item
        val content = msg.content
        if (content is List<*>) {
            val parts = content.map { part ->
                if (part is InputContent && !part.imageUrl.isNullOrEmpty()) {
                    val url = part.imageUrl
                    part.copy(imageUrl = "data:image/jpeg;base64,<omitted ${url.length} chars>")
                } else {
                    part
                }
            }
            msg.copy(content = parts)
        } else {
            msg
        }
    }
    return request.copy(input = redactedInput)
}

/** The last failure of a request that was tried [attempts] times. */
internal class ConnectAttemptsExhausted(cause: Throwable, val attempts: Int) :
    java.io.IOException(cause.message, cause)
