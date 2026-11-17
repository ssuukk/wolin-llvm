package pl.qus.wolin.it

import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The standard library on the real 6502: `wolin.print` walks the string
 * and sends each byte to CHROUT ($FFD2). The bytes land where the cursor
 * sits when the program starts: after the boot banner (rows 1/3/5), the
 * autostart's second `READY.` (row 10), and the `RUN` it types (row 11)
 * — row 12, column 0, $05E0.
 *
 * The screen bytes are C64 screen codes after the kernal's case handling:
 * with the shift state off (the boot default) CHROUT clears the $20 bit,
 * so lowercase input 0x68/0x69 lands as 0x48/0x49. Converting source
 * strings to PETSCII at compile time is a known gap (see TODO.md).
 */
class StdlibTest {

    @Test fun `print writes the string to the screen`() {
        val bytes = runStdlibProgram("print(\"hi\")")
        assertEquals('H'.code, bytes[0].toInt() and 0xFF)
        assertEquals('I'.code, bytes[1].toInt() and 0xFF)
    }

    @Test fun `printing an empty string emits nothing`() {
        val bytes = runStdlibProgram("print(\"\")\n    print(\"hi\")")
        assertEquals('H'.code, bytes[0].toInt() and 0xFF)
        assertEquals('I'.code, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a carriage return in the string starts the next row`() {
        val bytes = runStdlibProgram("print(\"a\\rb\")")
        // 'a' at $05E0, CHROUT $0D moves the cursor to column 0 of the
        // next row ($05E0 + 40), 'b' lands there.
        assertEquals('A'.code, bytes[0].toInt() and 0xFF)
        assertEquals('B'.code, bytes[40].toInt() and 0xFF)
    }

    /**
     * Compiles the two stdlib files plus a `main` file whose body is
     * [body], runs it under VICE, and returns screen memory
     * $05E0..$0621 (two rows from the program's first output cell).
     */
    private fun runStdlibProgram(body: String): ByteArray {
        val dir = Files.createTempDirectory("wolinc-stdlib")
        val main = dir.resolve("main.wtk")
        main.writeText(
            "package app\n" +
                "import wolin.print\n" +
                "fun main() {\n" +
                "    $body\n" +
                "    while (true) {}\n" +
                "}"
        )
        val stdlib = Path.of("stdlib")
        check(stdlib.resolve("c64-kernal.wtk").exists()) { "stdlib/c64-kernal.wtk is missing" }
        check(stdlib.resolve("std.wtk").exists()) { "stdlib/std.wtk is missing" }
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-stdlib-ir")
        try {
            val linked = compileToPrgFile(
                listOf(stdlib.resolve("c64-kernal.wtk"), stdlib.resolve("std.wtk"), main),
                output, sdk, workDir,
            )
            check(linked != null) { "the program did not compile" }
            return ViceRunner().run(output, 0x05E0, 0x0621)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
