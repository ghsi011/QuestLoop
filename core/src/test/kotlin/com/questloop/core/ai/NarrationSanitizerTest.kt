package com.questloop.core.ai

import com.questloop.core.ai.NarrationSanitizer.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NarrationSanitizerTest {

    private fun pass(mode: Mode, input: String) {
        val r = NarrationSanitizer.gate(input, mode)
        assertTrue(r.accepted, "expected PASS but rejected (${r.reason}): $input")
        assertEquals(input, r.text, "clean input must pass through unchanged")
    }

    private fun stripped(mode: Mode, input: String, expected: String) {
        val r = NarrationSanitizer.gate(input, mode)
        assertTrue(r.accepted, "expected STRIP+PASS but rejected (${r.reason}): $input")
        assertEquals(expected, r.text)
    }

    private fun reject(mode: Mode, input: String) {
        val r = NarrationSanitizer.gate(input, mode)
        assertFalse(r.accepted, "expected REJECT but passed as: ${r.text}")
    }

    // ---- REVIEW: clean passes through ----

    @Test fun `review clean multi-sentence passes`() =
        pass(Mode.REVIEW, "Hit 4 of 5 days. Wednesday slipped after the late meeting. Mornings worked better than nights.")

    @Test fun `review clean with semicolon passes`() =
        pass(Mode.REVIEW, "Missed 2 sessions; both were Fridays. Worth moving the Friday slot earlier.")

    // ---- REVIEW: cosmetic repairs (strip and keep) ----

    @Test fun `review strips wrapping quotes`() = stripped(
        Mode.REVIEW,
        "\"You logged 6 of 7 runs. The skipped day followed a 2am bedtime.\"",
        "You logged 6 of 7 runs. The skipped day followed a 2am bedtime.",
    )

    @Test fun `review strips leading throat-clear prefix`() = stripped(
        Mode.REVIEW,
        "Here's your review: 5 of 7 done, weekends were the gap.",
        "5 of 7 done, weekends were the gap.",
    )

    @Test fun `review softens exclamation marks`() = stripped(
        Mode.REVIEW,
        "Solid week!! 4 of 5 workouts, only Tuesday missed.",
        "Solid week. 4 of 5 workouts, only Tuesday missed.",
    )

    @Test fun `review strips markdown`() = stripped(
        Mode.REVIEW,
        "**3 of 5.** Late nights cost you the Thursday session.",
        "3 of 5. Late nights cost you the Thursday session.",
    )

    // ---- REVIEW: hard rejects ----

    @Test fun `review rejects flattery`() {
        val r = NarrationSanitizer.gate("You're absolutely crushing it this week — keep it up, superstar!", Mode.REVIEW)
        assertFalse(r.accepted)
    }

    @Test fun `review rejects cliche vocabulary`() =
        reject(Mode.REVIEW, "This week was a testament to your journey. Embrace the momentum and unlock your true potential.")

    @Test fun `review rejects hedging transitions`() =
        reject(Mode.REVIEW, "It's worth noting you did well. That said, remember every step counts.")

    @Test fun `review rejects the not-just-X construction`() =
        reject(Mode.REVIEW, "It's not just consistency — it's a transformation of who you are.")

    @Test fun `review rejects developer and app-meta`() =
        reject(Mode.REVIEW, "Synced your data with safe defaults for now; the app will retry the sync later tonight.")

    @Test fun `review rejects piled hedges`() =
        reject(Mode.REVIEW, "You hit 4 of 5. Maybe you could possibly push a bit of harder next week if you want.")

    @Test fun `review rejects over-length`() = reject(
        Mode.REVIEW,
        "You did fine this week and there is honestly quite a lot more that could be written here about " +
            "each and every category in turn, padding the summary well past any reasonable length so that it " +
            "clearly exceeds the cap the gate enforces on a short review block.",
    )

    // ---- RATIONALE: clean passes ----

    @Test fun `rationale clean passes`() =
        pass(Mode.RATIONALE, "Front-load runs early; you skipped 3 evening slots last week.")

    @Test fun `rationale clean because-clause passes`() =
        pass(Mode.RATIONALE, "Two sessions today because last week's single-session days all held.")

    @Test fun `rationale strips leading prefix and dash`() = stripped(
        Mode.RATIONALE,
        "Sure — start with the 10-minute walk since mornings stuck best.",
        "start with the 10-minute walk since mornings stuck best.",
    )

    // ---- RATIONALE: hard rejects ----

    @Test fun `rationale rejects cliche and emoji`() =
        reject(Mode.RATIONALE, "Embrace today's journey and unlock your best self! 🚀")

    @Test fun `rationale rejects ai-isms and flattery`() =
        reject(Mode.RATIONALE, "Let's dive into your plan and keep crushing those goals, champ!")

    @Test fun `rationale rejects over-length`() = reject(
        Mode.RATIONALE,
        "Lighter load today because you've stayed consistent for nine straight days, so this is a deliberate " +
            "recovery day to protect the streak you have carefully built up.",
    )

    @Test fun `rationale rejects two sentences`() =
        reject(Mode.RATIONALE, "You logged 5 of 7. Keep the 6am block.")

    // ---- false-positive guards (must PASS) ----

    @Test fun `adjust does not trip the just hedge`() =
        pass(Mode.RATIONALE, "Adjust the start time to 7am; that window held all week.")

    @Test fun `cornering does not trip cornerstone`() =
        pass(Mode.REVIEW, "Cornering on the new route cost you time, not effort.")

    @Test fun `sparring does not trip spark`() =
        pass(Mode.REVIEW, "Sparring sessions ran long twice this week.")

    @Test fun `bare second person is allowed`() =
        pass(Mode.RATIONALE, "You logged 5 of 7, so keep the 6am block.")
}
