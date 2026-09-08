package com.daylight.window

/**
 * Every number this app uses that came from outside it, with its source.
 *
 * Nothing here was chosen for convenience. If a figure is not a published limit,
 * an international definition, or a measured median, it does not belong in this file
 * and it does not belong in the app.
 */
object Evidence {

    data class Source(
        val id: String,
        val title: String,
        val detail: String,
        val url: String
    )

    val ICNIRP = Source(
        id = "icnirp",
        title = "ICNIRP occupational exposure limit",
        detail = "The international radiation protection body sets 30 joules per square " +
            "metre of ultraviolet over an 8-hour day as the limit for unprotected skin. " +
            "That works out at 1.0 to 1.33 standard doses of sunlight per day. It is set " +
            "to keep long-term skin cancer risk from rising meaningfully, and it does not " +
            "depend on skin type.",
        url = "https://pmc.ncbi.nlm.nih.gov/articles/PMC9744745/"
    )

    val OUTDOOR_WORKERS = Source(
        id = "workers",
        title = "What outdoor workers actually get",
        detail = "Measured daily exposure in the same review: British construction workers " +
            "averaged 2.0 standard doses a day, Canadian outdoor workers 1.9, Danish masons " +
            "3.2. So the occupational limit is one that many people who work outside exceed " +
            "routinely, for whole careers.",
        url = "https://pmc.ncbi.nlm.nih.gov/articles/PMC9744745/"
    )

    val REDDENING_THRESHOLDS = Source(
        id = "young2018",
        title = "How much sun turns each skin type pink",
        detail = "Measured in 39 people, six or seven of each skin type, each person tested " +
            "against their own threshold. The same study looked for a dose low enough to " +
            "give benefit without DNA damage and did not find one: damage was detectable at " +
            "a fifth of a person's own burn threshold, the lowest dose tested.",
        url = "https://pmc.ncbi.nlm.nih.gov/articles/PMC6158343/"
    )

    val THRESHOLD_OVERLAP = Source(
        id = "sanchez2020",
        title = "Why skin type is only a rough guide",
        detail = "In 113 people, skin type predicted an individual's burn threshold only " +
            "moderately, and measured thresholds overlapped between types — two people " +
            "classified differently can burn at exactly the same dose. Treat the app's " +
            "figures as a cautious population guide, not a reading of your own skin.",
        url = "https://www.actasdermo.org/en-minimal-erythema-dose-correlation-with-articulo-S1578219020301505"
    )

    val UNITS = Source(
        id = "cie",
        title = "The units",
        detail = "UV index is defined as 40 times the sunburn-weighted power of sunlight in " +
            "watts per square metre, so UV 1 is 25 milliwatts. One standard dose of sunlight " +
            "is 100 joules per square metre. Both are international definitions used by the " +
            "World Meteorological Organization for every public UV forecast.",
        url = "https://cie.co.at/publications/erythema-reference-action-spectrum-and-standard-erythema-dose-0"
    )

    val FORECAST = Source(
        id = "openmeteo",
        title = "Where the forecast comes from",
        detail = "Open-Meteo, free and worldwide, with cloud cover already reflected in the " +
            "hourly UV figures.",
        url = "https://open-meteo.com/"
    )

    val ALL = listOf(ICNIRP, OUTDOOR_WORKERS, REDDENING_THRESHOLDS, THRESHOLD_OVERLAP, UNITS, FORECAST)
}
