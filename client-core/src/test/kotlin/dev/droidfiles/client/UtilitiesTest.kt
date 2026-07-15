package dev.droidfiles.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UtilitiesTest {
    @Test
    fun `natural numbers sort numerically`() {
        assertEquals(listOf("a1", "a2", "a10"), listOf("a10", "a2", "a1").sortedWith(NaturalOrder))
    };
    @Test
    fun `windows names are safe`() {
        assertEquals("_CON", WindowsNameMapper.map("CON")); assertEquals("a_b", WindowsNameMapper.map("a:b."))
    }
}
