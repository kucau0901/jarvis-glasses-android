package app.jarvis.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsTest {
  @Test
  fun aFilesNameIsMadeSafeToSave() {
    assertEquals("report.pdf", safeName("report.pdf"))
    // Its last part only, and nothing that makes a path, a hidden file or a bad name.
    assertEquals("passwd", safeName("../../etc/passwd"))
    assertEquals("bashrc", safeName(".bashrc"))
    assertEquals("a_b_.txt", safeName("a:b?.txt"))
    assertEquals("file", safeName("/"))
    assertEquals(120, safeName("x".repeat(300)).length)
  }
}
