package dev.droidfiles.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssociationSettingsDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `custom association is normalized and saved as argument array`() {
        var saved: AppSettings? = null
        compose.setContent { MaterialTheme { AssociationSettingsDialog(AppSettings(), {}, { saved = it }) } }
        compose.onNodeWithTag("association-extension").performTextInput(".TXT")
        compose.onNodeWithTag("association-executable").performTextInput("C:\\Program Files\\Editor\\editor.exe")
        compose.onNodeWithTag("association-arguments").performTextInput("--reuse\n{file}")
        compose.onNodeWithTag("association-working-directory").performTextInput("C:\\Work")
        compose.onNodeWithTag("association-wait").performClick()
        compose.onNodeWithTag("association-save").performClick()
        val association = checkNotNull(saved).associations.getValue("txt")
        assertEquals(listOf("--reuse", "{file}"), association.arguments)
        assertEquals("C:\\Work", association.workingDirectory)
        assertTrue(association.waitForExit)
    }

    @Test
    fun `system default removes an existing association`() {
        var saved: AppSettings? = null
        val initial = AppSettings(associations = mapOf("txt" to ProgramAssociation("editor.exe")))
        compose.setContent { MaterialTheme { AssociationSettingsDialog(initial, {}, { saved = it }) } }
        compose.onNodeWithTag("association-default").performClick()
        assertTrue(checkNotNull(saved).associations.isEmpty())
    }
}
