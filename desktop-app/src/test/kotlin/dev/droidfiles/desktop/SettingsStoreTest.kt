package dev.droidfiles.desktop

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SettingsStoreTest {
    @Test
    fun `round trip and corrupt recovery`() {
        val dir = Files.createTempDirectory("settings");
        val file = dir.resolve("settings.json");
        val store = SettingsStore(file);
        val value = AppSettings(adbPath = "C:/adb.exe", transferWorkers = 5, associations = mapOf("txt" to ProgramAssociation("editor.exe"))); store.save(value); assertEquals(value, store.load()); Files.writeString(file, "not json"); assertEquals(AppSettings(), store.load()); assertTrue(Files.list(dir).use { it.anyMatch { p -> p.fileName.toString().startsWith("settings.corrupt-") } }); dir.toFile().deleteRecursively()
    }

    @Test
    fun `navigation history is isolated by device serial and legacy global fields are ignored`() {
        val first = AppSettings().withNavigation("device-a", listOf("/sdcard/A", "/sdcard/A2"), 1)
        val both = first.withNavigation("device-b", listOf("/sdcard/B"), 0)
        assertEquals(listOf("/sdcard/A", "/sdcard/A2"), both.navigationFor("device-a").tabs)
        assertEquals(1, both.navigationFor("device-a").activeTabIndex)
        assertEquals(listOf("/sdcard/B"), both.navigationFor("device-b").tabs)
        assertEquals(DeviceNavigationSettings(), both.navigationFor("new-device"))

        val dir = Files.createTempDirectory("legacy-settings")
        val file = dir.resolve("settings.json")
        Files.writeString(file, """{"lastTabs":["/from-another-device"],"activeTabIndex":0}""")
        assertEquals(DeviceNavigationSettings(), SettingsStore(file).load().navigationFor("new-device"))
        dir.toFile().deleteRecursively()
    }
}
