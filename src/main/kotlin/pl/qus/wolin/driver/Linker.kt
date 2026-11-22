package pl.qus.wolin.driver

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the SDK's `mos-c64-clang` to validate IR and link modules.
 */
class Linker(private val sdk: Sdk) {

    /**
     * Validates the modules: compiles them to object code and discards the
     * result. Throws with the compiler's stderr on failure.
     *
     * All the modules are validated together, because a module may call into
     * another one: validating a file alone would reject every cross-module
     * call as an undefined value.
     */
    fun validate(llFiles: List<Path>) {
        if (llFiles.isEmpty()) return
        // Every module is compiled in one go, so the modules can call into
        // each other: the compiler resolves the cross-module references
        // while it validates. The object files land in a temporary
        // directory, which the compiler insists on for several inputs.
        val outDir = Files.createTempDirectory("wolinc-validate")
        try {
            run(
                listOf("-fno-lto", "-O2", "-x", "ir", "-c") + llFiles.map { it.toString() },
                workDir = outDir,
            )
        } finally {
            outDir.toFile().deleteRecursively()
        }
    }

    /**
     * Links the modules into the output `.prg`, writing the linker's map to
     * `<output>.map` beside it. The SDK's default configuration enables
     * `-flto`, which is what we want.
     */
    fun link(llFiles: List<Path>, output: Path, workDir: Path) {
        val map = output.resolveSibling(output.fileName.toString() + ".map")
        run(
            listOf("-O2") + llFiles.map { it.toString() } +
                listOf("-o", output.toString(), "-Wl,-Map=$map"),
            workDir,
        )
    }

    private fun run(args: List<String>, workDir: Path?) {
        val command = listOf(sdk.clang.toString()) + args
        val process = ProcessBuilder(command).apply { if (workDir != null) directory(workDir.toFile()) }.start()
        val stderr = StringBuilder()
        val pump = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        pump.start()
        val stdout = process.inputStream.bufferedReader().readText()
        pump.join()
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException("${command[0].substringAfterLast('/')} failed (exit $code):\n$stderr$stdout")
        }
    }
}
