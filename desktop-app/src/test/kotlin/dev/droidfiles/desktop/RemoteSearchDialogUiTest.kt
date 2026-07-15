package dev.droidfiles.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.droidfiles.client.EntryType
import dev.droidfiles.client.RemoteEntry
import dev.droidfiles.protocol.RemotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class RemoteSearchDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `search accepts a query and opens a result`() {
        var query by mutableStateOf("");
        var submitted = "";
        var opened = false
        val result = RemoteEntry("photo.jpg", RemotePath.of("/sdcard/DCIM/photo.jpg"), EntryType.FILE, 1, Instant.EPOCH)
        compose.setContent { MaterialTheme { RemoteSearchDialog(query, listOf(result), false, false, { query = it }, { submitted = it }, { opened = true }, {}) } }
        compose.onNodeWithTag("search-query").performTextInput("photo")
        compose.onNodeWithTag("search-start").performClick()
        assertEquals("photo", submitted)
        compose.onNodeWithTag("search-result-/sdcard/DCIM/photo.jpg").assertIsDisplayed().performClick()
        assertTrue(opened)
    }
}
