package dev.droidfiles.privilege

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import dev.droidfiles.protocol.ServerHello
import java.nio.file.Files

class PrivilegeTest {
    @Test
    fun `classifies maintained root implementations`() {
        assertEquals(PrivilegeBackend.MAGISK, SuClassifier.classify("MAGISKSU 28")); assertEquals(PrivilegeBackend.KERNEL_SU, SuClassifier.classify("KernelSU 1.0")); assertEquals(PrivilegeBackend.APATCH, SuClassifier.classify("APatch su")); assertEquals(PrivilegeBackend.TRADITIONAL_SU, SuClassifier.classify("3.1"))
    }

    @Test
    fun `shell quotes root command and custom template`() {
        val standard = PrivilegedCommandBuilder.wrap(PrivilegeConfig(PrivilegeBackend.MAGISK), listOf("app_process", "/", "a'b")); assertTrue(standard.startsWith("su -c '") && "'\\''" in standard);
        val custom = PrivilegedCommandBuilder.wrap(PrivilegeConfig(PrivilegeBackend.CUSTOM, listOf("doas", "--", "{command}")), listOf("id", "-u")); assertTrue(custom.startsWith("'doas' '--'"))
    }

    @Test
    fun `protected destructive paths require opt in`() {
        val policy = PrivilegeSafetyPolicy(); assertThrows(SecurityException::class.java) { policy.requireAllowed("/system/bin", true) }; policy.requireAllowed("/sdcard/test", true); PrivilegeSafetyPolicy(true).requireAllowed("/data/local/tmp", true)
    }

    @Test
    fun `custom backend requires command placeholder`() {
        assertThrows(IllegalArgumentException::class.java) { PrivilegeConfig(PrivilegeBackend.CUSTOM, listOf("su", "-c")) }
    }

    @Test
    fun `session validates reported identity and selinux`() {
        val shell = PrivilegeSessionPlan(PrivilegeConfig()); shell.validate(ServerHello(1, 0, "x", 35, "m", 2000, 2000, "u:r:shell:s0", emptySet(), 1, 1)); assertThrows(IllegalArgumentException::class.java) { shell.validate(ServerHello(1, 0, "x", 35, "m", 0, 0, "u:r:su:s0", emptySet(), 1, 1)) };
        val root = PrivilegeSessionPlan(PrivilegeConfig(PrivilegeBackend.MAGISK)); root.validate(ServerHello(1, 0, "x", 35, "m", 0, 0, "u:r:magisk:s0", emptySet(), 1, 1))
    }

    @Test
    fun `settings persist default and device override atomically`() {
        val dir = Files.createTempDirectory("privilege");
        val store = PrivilegeSettingsStore(dir.resolve("privilege.json"));
        val value = PrivilegePreferences(PrivilegeConfig(), mapOf("serial" to PrivilegeConfig(PrivilegeBackend.ADB_ROOT))); store.save(value); assertEquals(PrivilegeBackend.ADB_ROOT, store.load().forDevice("serial").backend); dir.toFile().deleteRecursively()
    }
}
