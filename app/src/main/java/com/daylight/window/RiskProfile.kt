package com.daylight.window

/**
 * How much sun you are willing to collect in a day.
 *
 * Every figure here comes from a published source, never from a judgement made
 * inside this app. Each profile names the real population it corresponds to, so the
 * choice is between documented positions rather than between vague words like
 * "cautious" and "relaxed".
 */
enum class RiskProfile(
    /** The day's ceiling, in standard doses of sunlight. */
    val dailyDose: Double,
    val shortLabel: String,
    val plainDescription: String,
    val source: Evidence.Source
) {

    /**
     * The lower end of the international occupational limit. This is the default:
     * it is the strictest published figure, and it is the one explicitly set so that
     * long-term skin cancer risk does not rise meaningfully.
     */
    OCCUPATIONAL_LIMIT(
        dailyDose = 1.0,
        shortLabel = "Safest",
        plainDescription = "The international workplace safety limit. Set so that daily " +
            "sun exposure does not meaningfully raise your lifetime cancer risk. This is " +
            "the most cautious published figure and the one this app uses unless you " +
            "change it.",
        source = Evidence.ICNIRP
    ),

    /**
     * The upper end of the same limit. Still within the occupational guideline.
     */
    OCCUPATIONAL_UPPER(
        dailyDose = 1.33,
        shortLabel = "Still within the limit",
        plainDescription = "The top of the same international limit. A third more time " +
            "outside, still inside the published workplace guideline.",
        source = Evidence.ICNIRP
    ),

    /**
     * What outdoor workers measurably receive. Above the guideline, but it is a real
     * documented exposure rather than an invented compromise.
     */
    OUTDOOR_WORKER(
        dailyDose = 2.0,
        shortLabel = "Like an outdoor worker",
        plainDescription = "What British construction workers and Canadian outdoor " +
            "workers actually average per day. Twice the occupational limit. Real people " +
            "live this way for whole careers, but it is above what the guideline advises.",
        source = Evidence.OUTDOOR_WORKERS
    );

    companion object {
        val DEFAULT = OCCUPATIONAL_LIMIT

        fun fromName(name: String): RiskProfile =
            entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Unknown risk profile: $name")
    }
}
