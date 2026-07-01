package com.questloop.core.completion

import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPolicyTest {

    @Test
    fun `completed quests are dismissed regardless of style`() {
        for (style in CompletionStyle.entries) {
            assertTrue(CompletionPolicy.dismissedForToday(style, CompletionResult.COMPLETED))
        }
    }

    @Test
    fun `skipped and failed are dismissed`() {
        assertTrue(CompletionPolicy.dismissedForToday(CompletionStyle.BINARY, CompletionResult.SKIPPED))
        assertTrue(CompletionPolicy.dismissedForToday(CompletionStyle.QUANTITATIVE, CompletionResult.FAILED))
    }

    @Test
    fun `partial counting and timed quests stay visible for more progress`() {
        assertFalse(CompletionPolicy.dismissedForToday(CompletionStyle.QUANTITATIVE, CompletionResult.PARTIAL))
        assertFalse(CompletionPolicy.dismissedForToday(CompletionStyle.DURATION, CompletionResult.PARTIAL))
    }

    @Test
    fun `partial subjective and binary are one-shot for the day`() {
        assertTrue(CompletionPolicy.dismissedForToday(CompletionStyle.SUBJECTIVE, CompletionResult.PARTIAL))
        assertTrue(CompletionPolicy.dismissedForToday(CompletionStyle.BINARY, CompletionResult.PARTIAL))
    }

    @Test
    fun `rescheduled keeps the quest available`() {
        assertFalse(CompletionPolicy.dismissedForToday(CompletionStyle.BINARY, CompletionResult.RESCHEDULED))
    }

    @Test
    fun `an over-completion measured quest stays visible even when completed`() {
        assertFalse(
            CompletionPolicy.dismissedForToday(
                CompletionStyle.QUANTITATIVE, CompletionResult.COMPLETED, allowOverCompletion = true,
            ),
        )
        assertFalse(
            CompletionPolicy.dismissedForToday(
                CompletionStyle.DURATION, CompletionResult.COMPLETED, allowOverCompletion = true,
            ),
        )
    }

    @Test
    fun `over-completion only applies to measured styles`() {
        // A binary/subjective quest is still one-shot even with the flag set.
        assertTrue(
            CompletionPolicy.dismissedForToday(
                CompletionStyle.BINARY, CompletionResult.COMPLETED, allowOverCompletion = true,
            ),
        )
    }
}
