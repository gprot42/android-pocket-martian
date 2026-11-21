package com.pocketmartian.app

/** App-wide defaults surfaced in multiple places (requests + About screen). */
object AppDefaults {
    const val MODEL_GROK_4_7 = "grok-4.7"
    const val MODEL_GROK_4_6 = "grok-4.6"

    /**
     * There is no id here for "Grok 4.7 Fast". It is the same model on faster hardware at
     * twice the token rates, and xAI serves it only through Cursor and Grok Build, not on
     * the public API this app talks to. Offering it would mean sending a model id the API
     * rejects, which costs a round trip and silently lands the user on the backup model.
     */

    /** Default chat model (Grok 4.7). */
    const val DEFAULT_MODEL = MODEL_GROK_4_7

    /** Used when the chosen model is rejected as unknown/unavailable by the API. */
    const val BACKUP_MODEL = MODEL_GROK_4_6

    /**
     * Models the user can pick in Settings. Anything else that was saved before, such as
     * 4.5 or earlier, becomes the default (see [normalizeChatModel]).
     */
    val CHAT_MODELS: List<Pair<String, String>> = listOf(
        "4.7" to MODEL_GROK_4_7,
        "4.6" to MODEL_GROK_4_6
    )

    fun isKnownChatModel(id: String): Boolean =
        CHAT_MODELS.any { it.second == id }

    fun normalizeChatModel(id: String?): String =
        if (id != null && isKnownChatModel(id)) id else DEFAULT_MODEL

    /**
     * Reasoning effort sent with each chat request. The API default is "high", which
     * costs tens of seconds of thinking before the first token even on simple asks.
     * Ordinary prompts use [EFFORT_LOW]; rail/transit prompts keep [EFFORT_HIGH]
     * because they combine several searches with timetable arithmetic.
     */
    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"

    /**
     * Ceiling on agentic tool turns per request (a search or a page fetch is a turn).
     * Generous enough not to truncate normal research, low enough to stop a runaway.
     */
    const val MAX_TURNS_DEFAULT = 6
    const val MAX_TURNS_TRANSIT = 12

    /**
     * What one picture from Grok Imagine (grok-imagine-image-2.0) costs, in US dollars,
     * on top of the chat tokens. From xAI's price list, October 2026.
     */
    const val IMAGE_COST_USD = 0.04

    /**
     * Priority processing (the API's service_tier "priority"): xAI schedules the request
     * ahead of standard traffic, at twice the price of every token, charged only when the
     * reply confirms it was served at priority. A SuperGrok or SuperGrok Heavy plan does
     * not include it; it is a choice made per request and paid from API credits.
     *
     * [PRIORITY_WHEN_BUSY], the default, sends at the standard price and asks for priority
     * only on the retry after xAI answers "the model is currently at capacity", which used
     * to end the prompt with that error.
     */
    const val PRIORITY_OFF = "off"
    const val PRIORITY_WHEN_BUSY = "busy"
    const val PRIORITY_ALWAYS = "always"
    const val PRIORITY_PRICE_MULTIPLIER = 2.0

    fun normalizePriority(value: String?): String =
        when (value) {
            PRIORITY_OFF, PRIORITY_ALWAYS -> value
            else -> PRIORITY_WHEN_BUSY
        }

    const val FRAMEWORK = "Jetpack Compose + Material3, Hilt, Retrofit/OkHttp, DataStore"
}
