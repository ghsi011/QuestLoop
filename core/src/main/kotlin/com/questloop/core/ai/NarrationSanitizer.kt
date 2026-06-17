package com.questloop.core.ai

/**
 * Deterministic gate that sits AFTER an LLM call and decides whether the model's
 * short output is clean enough to show, or must be rejected in favour of a terse
 * hand-written fallback. This is the real guarantee against "AI slop": even a weak
 * free model can't ship flattery, motivational-poster lines, hedging, AI-isms,
 * cliché vocabulary, or developer/app-meta — the gate strips cosmetic noise, then
 * hard-rejects anything that reads as machine-generated.
 *
 * Pure and fully unit-tested. Pipeline: normalize -> strip-and-keep -> hard-reject.
 */
object NarrationSanitizer {

    enum class Mode { REVIEW, RATIONALE }

    /** [text] is the clean string to show; null means rejected (use a fallback). */
    data class Result(val text: String?, val reason: String?) {
        val accepted: Boolean get() = text != null
    }

    private const val REVIEW_MAX = 240
    private const val REVIEW_MIN = 12
    private const val RATIONALE_MAX = 130
    private const val RATIONALE_MIN = 8

    private val CI = setOf(RegexOption.IGNORE_CASE)

    // Cosmetic, leading throat-clear prefixes ("Here's your review:", "Sure —").
    private val LEADING_PREFIX = Regex(
        "^(here'?s|here is|here are|sure|certainly|of course|okay|ok|alright|" +
            "absolutely|got it|no problem|your review|your plan|today'?s review)\\b[^.\\n]{0,40}?[:\\-\\u2014\\u2013]\\s+",
        CI,
    )
    private val LEADING_BULLET = Regex("^\\s*[-*\\u2022]\\s+")
    private val EXCLAIM = Regex("[!]+")
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2190}-\\x{21FF}\\x{FE0F}\\x{200D}\\x{20E3}]",
    )
    private val ALLCAPS = Regex("\\b[A-Z]{3,}\\b")
    private val ALLCAPS_OK = setOf("XP", "HP", "AM", "PM", "API", "TODO")

    // Hard-reject blocklist: any match means the output is slop -> fall back.
    private val BLOCKLIST: List<Pair<String, Regex>> = listOf(
        // (a) flattery / cheerleading
        "FLATTERY" to Regex(
            "\\byou(?:'re| are)\\s+(?:so |really |truly |absolutely |seriously |genuinely )?" +
                "(amazing|incredible|awesome|fantastic|wonderful|brilliant|unstoppable|" +
                "killing it|crushing it|smashing it|nailing it|on fire)\\b",
            CI,
        ),
        "FLATTERY" to Regex("\\b(crushing|killing|smashing|nailing) it\\b", CI),
        "FLATTERY" to Regex(
            "\\b(great|amazing|awesome|fantastic|incredible|excellent|wonderful) (job|work|effort|progress)\\b",
            CI,
        ),
        "FLATTERY" to Regex(
            "\\b(way to go|keep it up|keep up the|you'?ve got this|you got this|proud of you|" +
                "well done|kudos|bravo|hats off|nice work|stay strong)\\b",
            CI,
        ),
        "FLATTERY" to Regex("\\b(so proud|you should be proud)\\b", CI),
        "FLATTERY" to Regex("\\b(superstar|rockstar|champ|champion|warrior|legend)\\b", CI),
        // (b) motivational-poster / grandiose
        "GRANDIOSE" to Regex(
            "\\b(believe in yourself|sky'?s the limit|anything is possible|never give up|dream big|" +
                "reach for the stars)\\b",
            CI,
        ),
        "GRANDIOSE" to Regex(
            "\\b(every (step|day|small win) (counts|matters)|small (wins|steps) add up|" +
                "progress over perfection|one (step|day) at a time|new day)\\b",
            CI,
        ),
        "GRANDIOSE" to Regex(
            "\\b(true potential|best self|next level|new heights|inner strength|greatness|" +
                "tomorrow is a new day)\\b",
            CI,
        ),
        "GRANDIOSE" to Regex("\\b(transformation|transformational|transform your|empower\\w*|inspir\\w*)\\b", CI),
        // (c) hedging / filler / transitions
        "FILLER" to Regex(
            "\\b(it'?s worth noting|worth mentioning|it should be noted|keep in mind|bear in mind)\\b",
            CI,
        ),
        "FILLER" to Regex(
            "(^|[\\s,])(that said|at the end of the day|all in all|in conclusion|needless to say)[,\\s]",
            CI,
        ),
        // "Remember," as a discourse marker — but allow the verb "remember the X".
        "FILLER" to Regex("(^|\\s)remember,", CI),
        // (h) compliment-the-person / motivational-poster registers small models love.
        "MOTIVATIONAL" to Regex("\\byour future self\\b", CI),
        "MOTIVATIONAL" to Regex(
            "\\b(dedication|consistency|hard work|effort|grind) (is |are |really )*(paying off|pays off|showing|shows)\\b",
            CI,
        ),
        "MOTIVATIONAL" to Regex(
            "\\b(trust the process|stay the course|onward and upward|keep showing up|the rest will follow|" +
                "results will come|on the right track|building something (real|great|special)|" +
                "you'?ve got what it takes|closer than you (feel|think)|that'?s what matters|you showed up)\\b",
            CI,
        ),
        "MOTIVATIONAL" to Regex("\\blike a (boss|champ|pro|machine)\\b", CI),
        "MOTIVATIONAL" to Regex("\\bthe numbers don'?t lie\\b", CI),
        // (d) AI-meta / assistant-isms
        "AI_META" to Regex("\\bas an ai\\b", CI),
        "AI_META" to Regex(
            "\\b(i hope this helps|hope that helps|happy to help|glad to help|let me know if)\\b",
            CI,
        ),
        "AI_META" to Regex("\\b(feel free to|don'?t hesitate to|please note|please be advised)\\b", CI),
        "AI_META" to Regex("\\b(let'?s (dive|jump|dig|get)|dive (in|into)|jump (in|into)|deep dive)\\b", CI),
        "AI_META" to Regex("(^|[\\s,])(let me|let'?s)\\s", CI),
        "AI_META" to Regex("\\bsure thing\\b", CI),
        // (e) flowery / cliché vocabulary
        "CLICHE" to Regex("\\b(journey|odyssey)\\b", CI),
        "CLICHE" to Regex(
            "\\b(embrace|unlock|unleash|elevate|nurture|cultivate|cultivating|harness|ignite|" +
                "amplify|supercharge|level up your life)\\b",
            CI,
        ),
        "CLICHE" to Regex("\\b(testament|cornerstone|bedrock|tapestry|beacon|catalyst)\\b", CI),
        "CLICHE" to Regex("\\bboost your\\b", CI),
        "CLICHE" to Regex("\\b(game[- ]?changer|game[- ]?changing|powerhouse)\\b", CI),
        "CLICHE" to Regex("\\b(thrive|thriving|flourish|flourishing|blossom|blossoming|soaring)\\b", CI),
        "CLICHE" to Regex(
            "\\b(delve|realm|navigate the|robust|seamless|seamlessly|holistic|vibrant|myriad|plethora)\\b",
            CI,
        ),
        // (f) developer / app-meta
        "DEV_META" to Regex(
            "\\b(fallback|config|configuration|mvp|safe defaults?|backend|frontend|" +
                "endpoint|database|null|undefined|boolean|deployed|deployment)\\b",
            CI,
        ),
        "DEV_META" to Regex("\\bfor now\\b", CI),
        "DEV_META" to Regex(
            "\\b(this feature|the app (will|now|can)|part of the system|under the hood|behind the scenes)\\b",
            CI,
        ),
        // safety: medical / financial ADVICE (not bare domain nouns — a habit app
        // legitimately mentions "invest 20 minutes" or a "symptom tracker").
        "UNSAFE" to Regex(
            "\\b(diagnos(e|is|ed) (you|your)|prescri(be|ption)|dosage|you have (a )?(disorder|disease|condition))\\b",
            CI,
        ),
        "UNSAFE" to Regex(
            "\\b(invest in|investing in|buy (stocks|crypto|shares?)|sell (stocks|crypto|shares?)|" +
                "guaranteed returns?|cryptocurrency)\\b",
            CI,
        ),
    )

    // The "it's not just X, it's Y" construction the product owner specifically dislikes.
    private val EM_DASH_NOT_JUST = Regex("\\bit'?s not (just )?[^.,\\u2014\\u2013-]+[,\\u2014\\u2013]\\s*it'?s\\b", CI)
    private val EM_EN_DASH = Regex("[\\u2014\\u2013]")

    private val VAGUE_HEDGE = Regex("\\b(perhaps|maybe|possibly|somewhat|kind of|sort of|a bit of)\\b", CI)
    private val INTENSIFIER = Regex(
        "\\b(very|really|so|truly|incredibly|amazingly|absolutely|totally|extremely|highly|super|" +
            "deeply|wildly|insanely|remarkably|exceptionally|wonderfully|beautifully)\\b",
        CI,
    )

    /**
     * Cosmetic cleanup only (normalize + strip markdown/quotes/leading prefix/!),
     * with no slop rejection. Used when the user has turned the slop filter off but
     * we still don't want raw markdown or wrapping quotes on screen.
     */
    fun cosmetic(raw: String?): String = if (raw == null) "" else stripAndKeep(normalize(raw))

    fun gate(raw: String?, mode: Mode): Result {
        if (raw == null) return Result(null, "EMPTY")
        val text = stripAndKeep(normalize(raw))

        val min = if (mode == Mode.REVIEW) REVIEW_MIN else RATIONALE_MIN
        val max = if (mode == Mode.REVIEW) REVIEW_MAX else RATIONALE_MAX
        if (text.length < min) return Result(null, "EMPTY")
        if (text.length > max) return Result(null, "TOO_LONG")

        // Count sentence boundaries only where a terminator is followed by an
        // uppercase letter or quote, so "7 a.m.", "e.g.", "vs." don't inflate the
        // count and wrongly reject a legitimate single-sentence line.
        val sentences = (1 + Regex("[.?]\\s+(?=[A-Z\"'])").findAll(text).count())
        val maxSentences = if (mode == Mode.REVIEW) 3 else 1
        if (sentences > maxSentences) return Result(null, "TOO_MANY_SENTENCES")

        BLOCKLIST.firstOrNull { it.second.containsMatchIn(text) }?.let { return Result(null, it.first) }
        if (EM_DASH_NOT_JUST.containsMatchIn(text)) return Result(null, "EM_DASH_CONSTRUCT")
        if (EM_EN_DASH.findAll(text).count() >= 2) return Result(null, "EM_DASH_CONSTRUCT")

        if (EXCLAIM.containsMatchIn(text)) return Result(null, "FORMAT")
        if (EMOJI.containsMatchIn(text)) return Result(null, "FORMAT")
        ALLCAPS.findAll(text).map { it.value }.firstOrNull { it !in ALLCAPS_OK }?.let {
            return Result(null, "FORMAT")
        }

        val hedges = VAGUE_HEDGE.findAll(text).map { it.value.lowercase() }.toSet().size
        if (mode == Mode.RATIONALE && hedges >= 1) return Result(null, "HEDGE_DENSITY")
        if (mode == Mode.REVIEW && hedges >= 2) return Result(null, "HEDGE_DENSITY")

        val intensifiers = INTENSIFIER.findAll(text).count()
        val intensifierCap = if (mode == Mode.REVIEW) 3 else 2
        if (intensifiers > intensifierCap) return Result(null, "ADJ_PILE")

        return Result(text, null)
    }

    private fun normalize(raw: String): String =
        raw.replace(' ', ' ')
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[`*_#>]"), "")
            .replace(Regex("^[\\s\"']+"), "")
            .replace(Regex("[\\s\"']+$"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /** Cosmetic repairs only: drop a leading prefix/bullet, soften "!" to ".", tidy. */
    private fun stripAndKeep(input: String): String {
        var t = input
        t = LEADING_PREFIX.replaceFirst(t, "")
        t = LEADING_BULLET.replaceFirst(t, "")
        t = EXCLAIM.replace(t, ".")
        t = t.replace(Regex("\\.{2,}"), ".")
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        t = t.replace(Regex("[\\s:;\\-\\u2014\\u2013]+$"), "")
        // Re-attach a terminal period if the last sentence lost it to the trim above.
        if (t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
        return t
    }
}
