package dev.droidfiles.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.*
import java.time.Instant

object AppIdentity {
    const val NAME = "DroidFiles";
    const val ID = "dev.droidfiles.desktop"
}

@Serializable
data class ProgramAssociation(val executable: String, val arguments: List<String> = listOf("{file}"), val workingDirectory: String? = null, val waitForExit: Boolean = false)

@Serializable
data class DeviceNavigationSettings(val tabs: List<String> = listOf("/sdcard"), val activeTabIndex: Int = 0) { init {
    require(tabs.isNotEmpty() && tabs.size <= 32)
    require(activeTabIndex in tabs.indices)
} }

@Serializable
data class AppSettings(val adbPath: String? = null, val transferWorkers: Int = 3, val cacheMaxBytes: Long = 1024L * 1024 * 1024, val cacheMaxDays: Int = 30, val showHidden: Boolean = true, val associations: Map<String, ProgramAssociation> = emptyMap(), val navigationByDevice: Map<String, DeviceNavigationSettings> = emptyMap()) {init {
    require(transferWorkers in 1..8); require(cacheMaxBytes >= 0); require(cacheMaxDays >= 0); require(navigationByDevice.keys.none(String::isBlank))
}

    fun navigationFor(serial: String): DeviceNavigationSettings = navigationByDevice[serial] ?: DeviceNavigationSettings()
    fun withNavigation(serial: String, tabs: List<String>, activeTabIndex: Int): AppSettings = copy(navigationByDevice = navigationByDevice + (serial to DeviceNavigationSettings(tabs, activeTabIndex)))
}

class SettingsStore(private val file: Path = defaultFile()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): AppSettings {
        if (!Files.isRegularFile(file)) return AppSettings(); return runCatching { json.decodeFromString<AppSettings>(Files.readString(file)) }.getOrElse { Files.move(file, file.resolveSibling("settings.corrupt-${Instant.now().toEpochMilli()}.json"), StandardCopyOption.REPLACE_EXISTING); AppSettings() }
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(file.parent);
        val part = file.resolveSibling("settings.json.part"); Files.writeString(part, json.encodeToString(settings)); runCatching { Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }.getOrElse { Files.move(part, file, StandardCopyOption.REPLACE_EXISTING) }
    }

    companion object {
        fun defaultFile(): Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), AppIdentity.NAME, "settings.json")
    }
}
