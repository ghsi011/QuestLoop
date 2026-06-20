package com.questloop.app.ui.add

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the [NumberField] used by the Add-quest / suggestion editors, locking
 * in the fix for "deleting a digit doesn't work well": the field must hold its own
 * editable text (allow empty / partial input) and only push a clamped value up,
 * rather than snapping back to the model value on every keystroke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders a NumberField wired to real state (mirrors the screen's data flow). */
    private fun render(initial: Int, range: IntRange = 1..1440, onValue: (Int) -> Unit = {}) {
        composeRule.setContent {
            var v by remember { mutableStateOf(initial) }
            NumberField(
                value = v,
                onValue = { v = it; onValue(it) },
                label = "Minutes",
                range = range,
                modifier = Modifier.testTag("num"),
            )
        }
    }

    /** The field's editable value only (ignores the label, which shares the node's text). */
    private fun assertEditable(expected: String) {
        val node = composeRule.onNodeWithTag("num").fetchSemanticsNode()
        val actual = node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""
        assertEquals(expected, actual)
    }

    @Test
    fun `clearing all digits leaves the field empty and keeps the last value`() {
        var last = -1
        render(initial = 30, onValue = { last = it })
        composeRule.onNodeWithTag("num").performTextReplacement("")
        assertEditable("")
        // No value pushed for an empty field — the last value is retained.
        assertEquals("no update on empty", -1, last)
    }

    @Test
    fun `deleting one digit at a time works without snapping back`() {
        render(initial = 30)
        composeRule.onNodeWithTag("num").performTextReplacement("3") // 30 -> 3
        assertEditable("3")
        composeRule.onNodeWithTag("num").performTextReplacement("") // 3 -> empty
        assertEditable("")
    }

    @Test
    fun `typing a number pushes the parsed value`() {
        var last = -1
        render(initial = 30, onValue = { last = it })
        composeRule.onNodeWithTag("num").performTextReplacement("")
        composeRule.onNodeWithTag("num").performTextInput("45")
        assertEquals(45, last)
        assertEditable("45")
    }

    @Test
    fun `non-digits are ignored and the value is clamped to the range`() {
        var last = -1
        render(initial = 30, range = 1..1440, onValue = { last = it })
        composeRule.onNodeWithTag("num").performTextReplacement("12ab")
        assertEditable("12")
        assertEquals(12, last)
        // Above the max clamps down (and re-seeds the display to the clamped value).
        composeRule.onNodeWithTag("num").performTextReplacement("9999")
        assertEquals(1440, last)
        assertEditable("1440")
    }

    // Regression for the case the clamp result equals the current model value: onValue
    // doesn't change the state, so there's no re-seed — the field must still normalize
    // its own text so it never shows a number different from what will be saved.

    @Test
    fun `clamp at the min shows in the field when the value is already the min`() {
        var last = -1
        render(initial = 1, range = 1..1440, onValue = { last = it }) // already at min
        composeRule.onNodeWithTag("num").performTextReplacement("0") // clamps back to 1
        assertEditable("1")
        assertEquals(1, last)
    }

    @Test
    fun `clamp at the max shows in the field when the value is already the max`() {
        var last = -1
        render(initial = 1440, range = 1..1440, onValue = { last = it }) // already at max
        composeRule.onNodeWithTag("num").performTextReplacement("9999") // clamps back to 1440
        assertEditable("1440")
        assertEquals(1440, last)
    }
}
