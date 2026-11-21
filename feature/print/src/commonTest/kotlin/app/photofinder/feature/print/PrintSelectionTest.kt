package app.photofinder.feature.print

import app.photofinder.core.storage.PhotoId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrintSelectionTest {
    @Test
    fun respectsLimitAndAllowsDeselect() {
        val selection = PrintSelection(limit = 2)
        val (a, b, c) = listOf("a", "b", "c").map(::PhotoId)

        assertTrue(selection.toggle(a))
        assertTrue(selection.toggle(b))
        assertFalse(selection.toggle(c))
        assertEquals(0, selection.remaining)

        assertTrue(selection.toggle(a))
        assertTrue(selection.toggle(c))
        assertEquals(listOf(b, c), selection.photos)
    }
}
