package pl.qus.wolin.it

import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.SourceFile
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rules for a global of another file: the same package makes it
 * visible, the file's own declaration shadows it, and its constant value
 * can be used in an initialiser.
 */
class MultiFileGlobalTest {

    @Test fun `a global of another file in the same package is visible`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile("lib.wtk", "package p\nvar shared: ubyte = 9"),
                SourceFile(
                    "app.wtk",
                    "package p\nfun main() {\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = shared\n" +
                        "    while (true) {}\n" +
                        "}",
                ),
            )
        )
        assertEquals(9, bytes[0].toInt() and 0xFF)
    }

    @Test fun `the file's own global shadows one of another file`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile("lib.wtk", "package p\nvar shared: ubyte = 1"),
                SourceFile(
                    "app.wtk",
                    "package p\nvar shared: ubyte = 9\n" +
                        "fun main() {\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = shared\n" +
                        "    while (true) {}\n" +
                        "}",
                ),
            )
        )
        assertEquals(9, bytes[0].toInt() and 0xFF)
    }

    @Test fun `an initialiser may use another file's constant global`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile("lib.wtk", "package p\nvar base: ubyte = 4"),
                SourceFile(
                    "app.wtk",
                    "package p\nvar derived: ubyte = base + 1\n" +
                        "fun main() {\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = derived\n" +
                        "    while (true) {}\n" +
                        "}",
                ),
            )
        )
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    /** Compiles [files] into one `.prg`, runs it under VICE, returns $0400-$040F. */
    private fun runProgramFiles(files: List<SourceFile>): ByteArray {
        val dir = Files.createTempDirectory("wolinc-multi")
        val inputs = files.map { file ->
            val path = dir.resolve(file.fileName)
            path.writeText(file.source)
            path
        }
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-multi-ir")
        try {
            val linked = compileToPrgFile(inputs, output, sdk, workDir)
            check(linked != null) { "the program did not compile" }
            return ViceRunner().run(output, 0x0400, 0x040F)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
