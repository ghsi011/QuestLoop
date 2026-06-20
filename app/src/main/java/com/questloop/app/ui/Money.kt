package com.questloop.app.ui

import java.text.NumberFormat
import java.util.Locale

/**
 * Formats reward amounts for display. QuestLoop never moves real money (see the
 * Rewards disclaimers), so it has no currency setting — we render amounts in the
 * device locale's currency, which is a sensible default and far clearer than a
 * bare "12.50". The user's own budget is "in their currency"; the symbol is
 * cosmetic, not a claim that the app handles funds.
 */
object Money {
    fun format(amount: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).format(amount)
}
