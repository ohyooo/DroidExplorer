package dev.droidfiles.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.droidfiles.protocol.RemotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExplorerTableUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `header click sorts and resize handle changes adjacent widths`() {
        var sorted: SortColumn? = null
        var resized: Pair<Int, androidx.compose.ui.unit.Dp>? = null
        compose.setContent {
            MaterialTheme {
                ExplorerTableHeader(
                    ExplorerColumnWidths(300.dp, 180.dp, 120.dp, 100.dp),
                    SortColumn.NAME,
                    SortDirection.ASCENDING,
                    onSort = { sorted = it },
                    onResize = { boundary, delta -> resized = boundary to delta }
                )
            }
        }
        compose.onNodeWithTag("column-size").performClick()
        assertEquals(SortColumn.SIZE, sorted)
        compose.onNodeWithTag("column-resizer-0").performMouseInput {
            moveTo(center); press(MouseButton.Primary); moveBy(Offset(20f, 0f)); moveBy(Offset(20f, 0f)); release(MouseButton.Primary)
        }
        assertEquals(0, resized?.first)
        assertTrue((resized?.second?.value ?: 0f) > 0f)
    }

    @Test
    fun `column resize preserves total width and minimums`() {
        val initial = ExplorerColumnWidths(300.dp, 180.dp, 120.dp, 100.dp)
        val resized = initial.resize(0, 500.dp)
        assertEquals(100.dp, resized.modified)
        assertEquals(initial.name + initial.modified, resized.name + resized.modified)
        val opposite = resized.resize(0, (-500).dp)
        assertEquals(160.dp, opposite.name)
        assertEquals(resized.name + resized.modified, opposite.name + opposite.modified)
    }

    @Test
    fun `address bar accepts enter and keeps compose cut shortcut`() {
        val navigations = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ExplorerNavigationBar(
                    path = RemotePath.of("/sdcard"), connected = true, canBack = false, canForward = false,
                    onNavigate = { navigations += it }, onBack = {}, onForward = {}, onUp = {}
                )
            }
        }
        val address = compose.onNodeWithTag("address-bar")
        address.performTextReplacement("/sdcard/Download")
        address.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("/sdcard/Download"), navigations)
        address.performTextReplacement("/cut-me")
        address.performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.A); keyUp(Key.CtrlLeft)
            keyDown(Key.CtrlLeft); pressKey(Key.X); keyUp(Key.CtrlLeft)
        }
        address.assertTextEquals("")
        assertEquals(1, navigations.size)
    }

    @Test
    fun `parent directory row opens parent with one click`() {
        var opened = false
        compose.setContent { MaterialTheme { ExplorerParentRow(ExplorerColumnWidths(300.dp, 180.dp, 120.dp, 100.dp)) { opened = true } } }
        compose.onNodeWithTag("parent-row").assertTextContains("..").performClick()
        assertTrue(opened)
    }
}
