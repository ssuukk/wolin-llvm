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
import kotlin.test.assertFailsWith

/**
 * Several files compile into one program: the driver links them into a
 * single `.prg`, and a name from another package resolves through an import.
 */
class MultiFileTest {

    @Test fun `two files link into one program`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                SourceFile(
                    "app.wtk",
                    "package app\nimport lib.helper\nfun main() {\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = helper()\n" +
                        "    while (true) {}\n" +
                        "}",
                ),
            )
        )
        assertEquals(7, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method imported with its struct runs`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile(
                    "lib.wtk",
                    "package lib\n" +
                        "struct P { var x: ubyte }\n" +
                        "impl P { fun get(): ubyte { return this.x } }",
                ),
                SourceFile(
                    "app.wtk",
                    "package app\nimport lib.P\nvar p: P\nfun main() {\n" +
                        "    p.x = 21\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = p.get()\n" +
                        "    while (true) {}\n" +
                        "}",
                ),
            )
        )
        assertEquals(21, bytes[0].toInt() and 0xFF)
    }

    @Test fun `two files each defining main is a link error`() {
        assertFailsWith<IllegalStateException> {
            runProgramFiles(
                listOf(
                    SourceFile("a.wtk", "package a\nfun main() { }"),
                    SourceFile("b.wtk", "package b\nfun main() { }"),
                )
            )
        }
    }

    @Test fun `a struct coerced to a trait in another module runs`() {
        val bytes = runProgramFiles(
            listOf(
                SourceFile(
                    "lib.wtk",
                    "package lib\n" +
                        "trait Shape { fun area(): uword }\n" +
                        "struct R { var w: ubyte }\n" +
                        "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                        "var r: R",
                ),
                SourceFile(
                    "app.wtk",
                    "package app\nimport lib.Shape\nimport lib.R\nimport lib.r\n" +
                        "var shapes: Shape[ubyte, 1]\n" +
                        "fun main() {\n" +
                        "    r.w = 5\n" +
                        "    shapes[0] = r\n" +
                        "    var out: ubyte^0x400 = 0\n" +
                        "    out = shapes[0].area().toUbyte()\n" +
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
