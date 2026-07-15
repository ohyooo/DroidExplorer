package dev.droidfiles.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.droidfiles.privilege.PrivilegeBackend
import dev.droidfiles.privilege.PrivilegePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivilegeSettingsDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `custom per-device privilege settings can be completed and saved`() {
        var saved: PrivilegePreferences? = null
        compose.setContent {
            MaterialTheme {
                PrivilegeSettingsDialog(PrivilegePreferences(), "emulator-5554", {}, { saved = it })
            }
        }

        compose.onNodeWithText("Privilege settings").assertIsDisplayed()
        compose.onNodeWithTag("privilege-per-device").performClick()
        compose.onNodeWithTag("privilege-backend-CUSTOM").performClick()
        compose.onNodeWithTag("privilege-custom-arguments").performTextInput("su\n-c\n{command}")
        compose.onNodeWithTag("privilege-dangerous-paths").performScrollTo().performClick()
        compose.onNodeWithTag("privilege-save").performClick()

        val device = checkNotNull(saved).devices.getValue("emulator-5554")
        assertEquals(PrivilegeBackend.CUSTOM, device.backend)
        assertEquals(listOf("su", "-c", "{command}"), device.customArguments)
        assertTrue(device.allowDangerousSystemPaths)
    }
}
