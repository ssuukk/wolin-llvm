package pl.qus.wolin.it

import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

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

/** Compile [source] to a .prg, run it under VICE, and return $0400-$040F. */
fun runProgram(source: String): ByteArray {
    val prg = compileToPrg(source, "t")
    return ViceRunner().run(prg, 0x0400, 0x040F)
}
