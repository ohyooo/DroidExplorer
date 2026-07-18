package dev.droidfiles.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import dev.droidfiles.desktop.ExplorerShortcut.NEW_TAB
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeKeyboardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Compose preview key events drive explorer shortcuts`() {
        var received: ExplorerShortcut? = null
        compose.setContent {
            Box(
                Modifier.testTag("keyboard-target").focusable().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        received = explorerShortcutFor(event.key, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)
                    }
                    received != null
                }
            )
        }

        compose.onNodeWithTag("keyboard-target").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("keyboard-target").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.T)
            keyUp(Key.T)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(NEW_TAB, received)
    }
}
