package dev.droidfiles.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.droidfiles.client.EntryType
import dev.droidfiles.client.RemoteEntry
import dev.droidfiles.protocol.RemotePath
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class ExplorerFileRowUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `primary click selects and double click opens`() {
        var selected = false;
        var opened = false;
        val entry = RemoteEntry("file.txt", RemotePath.of("/file.txt"), EntryType.FILE, 12, Instant.EPOCH)
        compose.setContent { MaterialTheme { ExplorerFileRow(entry, false, { selected = true }, { opened = true }, {}, {}, {}) } }
        val row = compose.onNodeWithTag("file-row-/file.txt")
        row.performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("selection semantics action", selected)
        selected = false
        row.performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("primary pointer click selection", selected)
        row.performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertTrue("double pointer click open", opened)
    }

    @Test
    fun `secondary click exposes context actions and rename executes`() {
        var renamed = false;
        val entry = RemoteEntry("folder", RemotePath.of("/folder"), EntryType.DIRECTORY, 0, Instant.EPOCH)
        compose.setContent { MaterialTheme { ExplorerFileRow(entry, true, {}, {}, { renamed = true }, {}, {}) } }
        compose.onNodeWithTag("file-row-/folder").performMouseInput { click(button = MouseButton.Secondary) }
        compose.onNodeWithText("Rename").assertIsDisplayed().performClick(); assertTrue(renamed)
    }

    @Test
    fun `dragging a selected row starts native drag`() {
        var dragged = false;
        val entry = RemoteEntry("file.txt", RemotePath.of("/file.txt"), EntryType.FILE, 12, Instant.EPOCH)
        compose.setContent { MaterialTheme { ExplorerFileRow(entry, true, {}, {}, {}, {}, { dragged = true }) } }
        compose.onNodeWithTag("file-row-/file.txt").performTouchInput { swipeRight() }
        compose.waitForIdle(); assertTrue("selected row drag", dragged)
    }

    @Test
    fun `desktop mouse double click opens a directory`() {
        var opened = false;
        val entry = RemoteEntry("Download", RemotePath.of("/sdcard/Download"), EntryType.DIRECTORY, 0, Instant.EPOCH)
        compose.setContent { MaterialTheme { ExplorerFileRow(entry, true, {}, { opened = true }, {}, {}, {}) } }
        compose.onNodeWithTag("file-row-/sdcard/Download").performMouseInput { doubleClick(button = MouseButton.Primary) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle(); assertTrue("desktop mouse directory double click", opened)
    }

    @Test
    fun `long file name stays on one line and ellipsizes at end`() {
        val name = "a-very-long-file-name-that-must-not-wrap-onto-another-line.txt"
        val entry = RemoteEntry(name, RemotePath.of("/$name"), EntryType.FILE, 12, Instant.EPOCH)
        compose.setContent { MaterialTheme { ExplorerFileRow(entry, false, {}, {}, {}, {}, {}, columnWidths = ExplorerColumnWidths(160.dp, 100.dp, 80.dp, 70.dp)) } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("file-name-/$name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val layout = layouts.single()
        assertTrue("the visible single line must truncate the long name", layout.getLineEnd(0, true) < name.length)
    }
}
