package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * The first end-to-end program: Wolin source → LLVM IR → `.prg` → the real
 * 6502 under VICE → the memory read back over the binary monitor.
 */
class EndToEndTest {

    @Test fun `ubyte addition runs on the real 6502`() {
        val src = """
            fun main() {
                var a: ubyte = 40
                var b: ubyte = 2
                var r: ubyte = a + b
                var out: ubyte^0x400 = r
                out = r
                while (true) {}
            }
        """.trimIndent()
        val prg = compileToPrg(src, "add_ubyte")
        val bytes = ViceRunner().run(prg, 0x0400, 0x0401)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }
}

/**
 * Compiles [source] through the driver's in-process pipeline and returns the
 * `.prg` path.
 *
 * The source is written to `<name>.wtk` in a temporary directory; the pipeline
 * emits the `.ll` modules, validates them and links the `.prg` next to it.
 */
fun compileToPrg(source: String, name: String): Path {
    val dir = Files.createTempDirectory("wolinc-it")
    val input = dir.resolve("$name.wtk")
    input.writeText(source)
    val output = dir.resolve("$name.prg").toAbsolutePath()
    val sdk = SdkLocator.locate(
        env = System.getenv(),
        globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
    )
    val workDir = Files.createTempDirectory("wolinc-it-ir")
    try {
        val linked = compileToPrgFile(listOf(input), output, sdk, workDir)
        if (linked == null) error("wolinc failed to compile $name")
    } finally {
        workDir.toFile().deleteRecursively()
    }
    return output
}
