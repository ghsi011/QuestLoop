package com.questloop.core.completion

import com.questloop.core.model.CompletionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionScalingTest {

    @Test
    fun `quantitative full target completes`() {
        val s = CompletionScaling.quantitative(progress = 8, target = 8)
        assertEquals(CompletionResult.COMPLETED, s.result)
        assertEquals(1.0, s.fraction)
    }

    @Test
    fun `quantitative partial is partial not failed`() {
        val s = CompletionScaling.quantitative(progress = 6, target = 8)
        assertEquals(CompletionResult.PARTIAL, s.result)
        assertEquals(0.75, s.fraction)
    }

    @Test
    fun `quantitative over target caps at one`() {
        val s = CompletionScaling.quantitative(progress = 12, target = 8)
        assertEquals(CompletionResult.COMPLETED, s.result)
        assertEquals(1.0, s.fraction)
    }

    @Test
    fun `quantitative zero progress is partial zero never failed`() {
        val s = CompletionScaling.quantitative(progress = 0, target = 8)
        assertEquals(CompletionResult.PARTIAL, s.result)
        assertEquals(0.0, s.fraction)
    }

    @Test
    fun `duration scales by minutes`() {
        val s = CompletionScaling.duration(actualMinutes = 25, targetMinutes = 50)
        assertEquals(CompletionResult.PARTIAL, s.result)
        assertEquals(0.5, s.fraction)
    }

    @Test
    fun `subjective rating gives proportional credit`() {
        assertEquals(1.0, CompletionScaling.subjective(5).fraction)
        assertEquals(0.6, CompletionScaling.subjective(3).fraction)
        val low = CompletionScaling.subjective(1)
        assertTrue(low.fraction > 0.0, "low honest rating still earns some credit")
        assertEquals(CompletionResult.PARTIAL, low.result)
    }

    @Test
    fun `subjective clamps out-of-range ratings`() {
        assertEquals(1.0, CompletionScaling.subjective(9, max = 5).fraction)
        assertEquals(0.0, CompletionScaling.subjective(-2, max = 5).fraction)
    }
}
