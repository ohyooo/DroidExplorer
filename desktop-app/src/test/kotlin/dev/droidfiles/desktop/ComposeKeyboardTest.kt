package dev.droidfiles.desktop

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeKeyboardTest {
    @Test
    fun `explorer shortcuts are mapped from Compose keys`() {
        assertEquals(ExplorerShortcut.NEW_TAB, explorerShortcutFor(Key.T, control = true, shift = false, alt = false))
        assertEquals(ExplorerShortcut.RESTORE_TAB, explorerShortcutFor(Key.T, control = true, shift = true, alt = false))
        assertEquals(ExplorerShortcut.BACK, explorerShortcutFor(Key.DirectionLeft, control = false, shift = false, alt = true))
        assertEquals(ExplorerShortcut.REFRESH, explorerShortcutFor(Key.F5, control = false, shift = false, alt = false))
        assertNull(explorerShortcutFor(Key.T, control = false, shift = false, alt = false))
    }
}
