package dev.droidfiles.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.asAwtTransferable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
class ComposeClipboardTest {
    @Test
    fun `file clipboard entry exposes the standard platform file list`() {
        val files = listOf(Path.of("C:/缓存/one file.txt"), Path.of("C:/缓存/two.txt"))
        val transferable = fileListClipEntry(files).asAwtTransferable!!
        assertEquals(files.map { it.toFile() }, transferable.getTransferData(DataFlavor.javaFileListFlavor))
    }
}
