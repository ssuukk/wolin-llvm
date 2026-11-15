package pl.qus.wolin.it

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The CLI as a process: its exit code, the files it writes and its
 * diagnostics.
 *
 * The CLI runs as `java -cp <runtime classpath> pl.qus.wolin.driver.MainKt`,
 * so the test drives the shipped entry point rather than the pipeline in
 * process.
 */
class CliTest {

    @Test fun `emit ir writes a ll file and stops`() {
        val dir = tempDir()
        val src = dir.resolve("t.wtk")
        src.writeText("fun main() { }")
        val rc = runCli(listOf("--emit-ir", src.toString()))
        assertEquals(0, rc, errOutput)
        assertTrue(dir.resolve("t.ll").exists(), "the .ll file must be written next to the input")
    }

    @Test fun `a syntax error exits non-zero with a diagnostic`() {
        val dir = tempDir()
        val src = dir.resolve("bad.wtk")
        src.writeText("fun main( {")
        val rc = runCli(listOf(src.toString()))
        assertNotEquals(0, rc)
        assertTrue(errOutput.contains("error:"), errOutput)
    }

    @Test fun `compiling produces a prg`() {
        val dir = tempDir()
        val src = dir.resolve("t.wtk")
        src.writeText("fun main() { var out: ubyte^0x400 = 9; out = 9; while (true) {} }")
        val prg = dir.resolve("t.prg")
        val rc = runCli(listOf("-o", prg.toString(), src.toString()))
        assertEquals(0, rc, errOutput)
        assertTrue(prg.exists(), "the .prg must be written")
        assertTrue(Files.size(prg) > 0, "the .prg must not be empty")
    }

    @Test fun `no input file is an error`() {
        val rc = runCli(emptyList())
        assertNotEquals(0, rc)
        assertTrue(errOutput.contains("no input files"), errOutput)
    }

    /** A temporary directory for the test's files. */
    private fun tempDir(): Path = Files.createTempDirectory("wolinc-cli")

    /**
     * Runs the CLI with [args] and returns its exit code. The output lands
     * in [errOutput].
     */
    private fun runCli(args: List<String>): Int {
        val command = listOf("java", "-cp", runtimeClasspath, "pl.qus.wolin.driver.MainKt") + args
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        process.inputStream.readBytes()
        errOutput = process.errorStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return process.exitValue()
    }

    /** The runtime classpath the CLI runs with. */
    private val runtimeClasspath: String
        get() = System.getProperty("wolinc.runtimeClasspath")
            ?: error("the wolinc.runtimeClasspath property is not set")

    /** The CLI's stderr from the last run. */
    private var errOutput: String = ""
}
