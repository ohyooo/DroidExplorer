package dev.droidfiles.client

import java.util.Locale

object NaturalOrder : Comparator<String> {
    private val chunks = Regex("(\\d+)|(\\D+)");
    override fun compare(a: String, b: String): Int {
        val ac = chunks.findAll(a).map { it.value }.toList();
        val bc = chunks.findAll(b).map { it.value }.toList(); for (i in 0 until minOf(ac.size, bc.size)) {
            val x = ac[i];
            val y = bc[i];
            val c = if (x.first().isDigit() && y.first().isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.toBigInteger().compareTo(y.toBigInteger()) else x.lowercase(Locale.ROOT).compareTo(y.lowercase(Locale.ROOT)); if (c != 0) return c
        }; return ac.size.compareTo(bc.size)
    }
}

object WindowsNameMapper {
    private val invalid = Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]");
    private val reserved = Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");
    fun map(name: String): String {
        var value = name.replace(invalid, "_").trimEnd(' ', '.'); if (value.isEmpty()) value = "_"; if (reserved.matches(value)) value = "_$value"; return value
    }
}

