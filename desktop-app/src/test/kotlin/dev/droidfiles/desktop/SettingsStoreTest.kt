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
}
