package dev.droidfiles.desktop

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.droidfiles.client.EntryType
import dev.droidfiles.client.RemoteEntry
import dev.droidfiles.protocol.RemotePath
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class PreviewVisibleRangeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `list is visible before previews and only composed rows request them`() {
        val calls = AtomicInteger()
        val provider = FilePreviewProvider { calls.incrementAndGet(); null }
        val entries = (1..100).map { RemoteEntry("$it.png", RemotePath.of("/$it.png"), EntryType.FILE, 10, Instant.EPOCH) }
        compose.setContent { MaterialTheme { LazyColumn(Modifier.width(700.dp).height(108.dp)) { items(entries, key = { it.path.value }) { entry -> ExplorerFileRow(entry, false, {}, {}, {}, {}, {}, previewProvider = provider) } } } }
        compose.onNodeWithTag("file-row-/1.png").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        assertTrue("visible rows requested previews", calls.get() > 0)
        assertTrue("off-screen rows did not request previews", calls.get() < 20)
    }
}
