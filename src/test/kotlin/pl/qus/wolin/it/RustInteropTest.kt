package pl.qus.wolin.it

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import pl.qus.wolin.test.ViceRunner

/**
 * A Wolin program calling a function whose body is in a Rust archive.
 *
 * The archive is a `no_std` staticlib built for the MOS C64 target. The
 * Wolin program declares the function `external`, and the symbol map ties
 * the declared name to the symbol the archive exports. The test runs the
 * linked program under VICE and reads the result back from memory.
 */
class RustInteropTest {

    /**
     * The Rust library: two functions, one adding and one storing through a
     * pointer, so both the value and the pointer calling conventions are
     * exercised.
     *
     * The pointer parameter is a `*mut u8`. Wolin has no pointer type of
     * its own: an array at a fixed address is passed as an address, which
     * is the `ptr` the Rust side expects.
     */
    private val rustSource = """
        #![no_std]

        #[panic_handler]
        fn panic(_: &core::panic::PanicInfo) -> ! {
            loop {}
        }

        #[no_mangle]
        pub extern "C" fn wol_add(a: u16, b: u16) -> u16 {
            a.wrapping_add(b)
        }

        #[no_mangle]
        pub extern "C" fn wol_store(p: *mut u8, v: u8) -> u8 {
            unsafe { *p = v; *p }
        }
    """.trimIndent()

    @Test fun `a wolin program calls a rust function`() {
        assumeTrue(mosRustc() != null, "the MOS Rust toolchain is not installed")
        val dir = Files.createTempDirectory("wolinc-rust-it")
        val archive = buildRustArchive(dir)
        val map = dir.resolve("map.txt")
        map.writeText("t.wol_add = wol_add\nt.wol_store = wol_store\n")
        val src = dir.resolve("t.wtk")
        src.writeText(
            """
            external fun wol_add(a: uword, b: uword): uword
            external fun wol_store(p: ubyte[ubyte]^0x3000, v: ubyte): ubyte

            fun main() {
                var out: ubyte^0x3000 = 0
                var sum: uword = wol_add(20, 22)
                out = sum.toUbyte()
                var buffer: ubyte[ubyte]^0x3000 = [0, 0, 0]
                var stored: ubyte = wol_store(buffer, 99)
                out = stored
                while (true) {}
            }
            """.trimIndent(),
        )
        val prg = dir.resolve("t.prg").toAbsolutePath()
        val rc = runCli(
            listOf(
                "--rust-archive", archive.toString(),
                "--rust-map", map.toString(),
                "-o", prg.toString(),
                src.toString(),
            ),
        )
        assertEquals(0, rc, errOutput)
        val bytes = runPrg(prg, 0x3000, 0x3000)
        assertEquals(99, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a map entry for an unknown symbol is an error`() {
        val dir = Files.createTempDirectory("wolinc-rust-it")
        val map = dir.resolve("map.txt")
        map.writeText("t.nosuch = wol_add\n")
        val src = dir.resolve("t.wtk")
        src.writeText(
            """
            external fun wol_add(a: uword, b: uword): uword
            fun main() {
                var out: ubyte^0x800 = 0
                out = wol_add(1, 2).toUbyte()
                while (true) {}
            }
            """.trimIndent(),
        )
        val prg = dir.resolve("t.prg").toAbsolutePath()
        val rc = runCli(
            listOf("--rust-map", map.toString(), "-o", prg.toString(), src.toString()),
        )
        assertTrue(rc != 0, "a map entry for an undeclared symbol must fail the build")
        assertTrue(errOutput.contains("t.nosuch"), errOutput)
    }

    /**
     * Builds the Rust archive for the MOS C64 target and returns its path.
     *
     * The test needs the MOS Rust toolchain; it is skipped when it is not
     * installed, so the suite still runs on a machine without it.
     */
    private fun buildRustArchive(dir: Path): Path {
        val rustc = mosRustc() ?: error("the MOS Rust toolchain is not installed")
        val lib = dir.resolve("wollib.rs")
        lib.writeText(rustSource)
        val archive = dir.resolve("libwollib.a")
        val process = ProcessBuilder(
            listOf(
                rustc.toString(),
                "--target", "mos-c64-none",
                "--crate-type", "staticlib",
                "-O",
                "--emit=obj",
                "-o", archive.toString(),
                lib.toString(),
            ),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        assertEquals(0, process.exitValue(), "rustc failed:\n$output")
        return archive
    }

    /**
     * The MOS Rust compiler, or null when it is not installed.
     *
     * The toolchain lives beside the SDK under `~/.local/share/rust-mos`.
     * The newest one is taken: the directories are versioned, and the
     * version number is compared group by group, as the SDK locator does.
     */
    private fun mosRustc(): Path? {
        val home = System.getProperty("user.home")
        val root = Path.of(home, ".local/share/rust-mos")
        if (!Files.isDirectory(root)) return null
        val candidates = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { Files.isRegularFile(it.resolve("bin/rustc")) }
                .toList()
        }
        val newest = candidates.maxWithOrNull { a, b ->
            val va = versionKey(a.fileName.toString())
            val vb = versionKey(b.fileName.toString())
            for (i in 0 until minOf(va.size, vb.size)) {
                val c = va[i].compareTo(vb[i])
                if (c != 0) return@maxWithOrNull c
            }
            va.size - vb.size
        } ?: return null
        return newest.resolve("bin/rustc")
    }

    /** The numeric version of a toolchain directory name, e.g. `v1.99.1` → [1, 99, 1]. */
    private fun versionKey(name: String): List<Int> =
        Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()

    /** Runs the CLI with [args]; its stderr lands in [errOutput]. */
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

    /** Runs [prg] under VICE and returns memory [start]..[end]. */
    private fun runPrg(prg: Path, start: Int, end: Int): ByteArray =
        ViceRunner().run(prg, start, end)
}
