package com.pocketmartian.app.data.repository

/**
 * Whether a prompt is a public-transport question, which gets the rail playbook: high
 * reasoning effort, up to twelve search rounds, and an order to search National Rail
 * before answering. All of that is right for "next train to St Pancras" and ruinous for
 * anything else: at high effort the model thinks for tens of seconds before writing.
 *
 * The previous test fired on any "from … to", any "get/go … to", and words such as
 * "delay", "journey", "platform" and "victoria". Replayed on everyday prompts it sent 15
 * of 24 unrelated ones down the rail path — "Translate this from English to German",
 * "How do I get my dog to stop barking", "Convert 150 pounds from GBP to EUR", "I want
 * to go to the gym more often" — each told it MUST search National Rail first. That is
 * what "prompt results very slow" mostly was.
 *
 * So a transit question now needs a transit cue: a named network or operator, a train or
 * bus in a travelling sense ("next train", "trains to", "bus times"), or a route question
 * ("how do I get to …"). Words that are only sometimes about transport ("train" the verb,
 * "platform", "station" as in radio, "delay" in general) no longer count on their own.
 */
internal fun looksLikeTransitEnquiry(text: String): Boolean {
    val t = text.lowercase()
    if (t.isBlank()) return false
    return TRANSIT_CUES.any { it.containsMatchIn(t) }
}

/**
 * Whether answering the prompt depends on where the phone is, so it is worth waiting a
 * moment for a GPS fix. Anything else is sent at once with whatever location is already
 * known, instead of holding every prompt up to three seconds for satellites.
 */
internal fun wantsFreshLocation(text: String): Boolean {
    val t = text.lowercase()
    if (t.isBlank()) return false
    return looksLikeTransitEnquiry(t) || LOCATION_CUES.containsMatchIn(t)
}

private val TRANSIT_CUES = listOf(
    // Named networks, lines and operators: unambiguous.
    Regex(
        """\b(national rail|network rail|thameslink|great northern|greater anglia|avanti|lner|gwr|""" +
            """southeastern|southern railway|south western railway|c2c|chiltern railways|london northwestern|""" +
            """east midlands railway|crosscountry|scotrail|eurostar|elizabeth line|london overground|""" +
            """overground|london underground|dlr|tfl|abbey line|rail replacement)\b"""
    ),
    // Trains and buses as transport, not as the verb ("train a model", "trains at the gym").
    Regex(
        """\b(next|last|first|earliest|latest|direct|fast|slow|stopping|cancelled|canceled|delayed|""" +
            """catch|catching|by|the|a|any|which)\s+(trains?|bus(es)?|tube|tram)\b"""
    ),
    Regex(
        """\b(trains?|bus(es)?|tube|trams?)\s+(to|from|times?|timetable|tickets?|fares?|stations?|""" +
            """stops?|strikes?|running|services?|delays?|disruption|cancell\w*|departures?)\b"""
    ),
    Regex("""\b(train|railway|tube|bus|underground|nearest) (station|stop)s?\b"""),
    Regex("""\b(rail|train|tube) strikes?\b"""),
    Regex("""\b(live (times|departures|trains)|departure boards?|public transport|transport (to|from))\b"""),
    // Route questions. "Get to" is also "get to sleep", "get to know", "get to the point".
    Regex("""\bhow (do|can|should|would|could) (i|we|you) get (to (?!$NOT_PLACES)|from\b|there\b|home\b)"""),
    Regex("""\b(how|best way|fastest way|quickest way|cheapest way) to get (to (?!$NOT_PLACES)|from\b|there\b|home\b)"""),
    Regex("""\bfrom (here|my current location|where i am)\b""")
)

/** What follows "get to" when it is not a destination. */
private const val NOT_PLACES = """(sleep|know|grips|the point|the bottom|terms|level|rank|grip)\b"""

private val LOCATION_CUES = Regex(
    """\b(near me|nearby|near here|around here|around me|closest|nearest|where am i|my location|""" +
        """current location|from here|weather|forecast|sunrise|sunset|local)\b"""
)
