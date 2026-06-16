package com.questloop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiDiagnosticsTest {

    @Test
    fun `records entries, keeps only the most recent, and clears`() {
        val diag = FileAiDiagnostics(RuntimeEnvironment.getApplication(), maxEntries = 3)
        diag.clear()

        repeat(5) { diag.record("model-$it", "error $it") }

        val lines = diag.dump().lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        assertTrue("keeps the newest", diag.dump().contains("error 4"))
        assertFalse("drops the oldest", diag.dump().contains("error 0"))
        assertTrue("records the model", diag.dump().contains("model-4"))

        diag.clear()
        assertEquals("", diag.dump())
    }

    @Test
    fun `flattens multi-line error messages to one entry per line`() {
        val diag = FileAiDiagnostics(RuntimeEnvironment.getApplication())
        diag.clear()
        diag.record("m", "line one\nline two")
        assertEquals(1, diag.dump().lines().filter { it.isNotBlank() }.size)
        diag.clear()
    }
}
