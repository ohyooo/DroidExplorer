package dev.droidfiles.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class ComposeDropTargetUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `drop target wrapper keeps Explorer content visible`() {
        val target = object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent) = true
        }
        compose.setContent { Box(Modifier.dragAndDropTarget({ true }, target)) { Text("Explorer content") } }
        compose.onNodeWithText("Explorer content").assertIsDisplayed()
    }
}
