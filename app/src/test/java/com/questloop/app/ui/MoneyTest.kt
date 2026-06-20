package com.questloop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MoneyTest {

    @Test
    fun `formats with the locale currency symbol and two decimals`() {
        assertEquals("$12.50", Money.format(12.5, Locale.US))
    }

    @Test
    fun `respects a non-US locale`() {
        // Japanese yen has no minor unit -> no decimals, yen sign.
        val formatted = Money.format(1200.0, Locale.JAPAN)
        assertTrue("expected a yen amount, got $formatted", formatted.contains("1,200"))
        assertTrue("expected a yen symbol, got $formatted", formatted.contains("￥") || formatted.contains("¥"))
    }

    @Test
    fun `zero is rendered, not blank`() {
        assertEquals("$0.00", Money.format(0.0, Locale.US))
    }
}
