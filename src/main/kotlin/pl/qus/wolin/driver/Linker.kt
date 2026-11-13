package pl.qus.wolin.driver

import java.nio.file.Path

/**
 * Runs the SDK's `mos-c64-clang` to validate IR and link modules.
 */
class Linker(private val sdk: Sdk) {

    /**
     * Validates one module: compiles it to object code and discards the
     * result. Throws with the compiler's stderr on failure.
     */
    fun validate(ll: Path) {
        run(listOf("-fno-lto", "-O2", "-x", "ir", "-c", ll.toString(), "-o", "/dev/null"), workDir = null)
    }

    /**
     * Links the modules into the output `.prg`. The SDK's default
     * configuration enables `-flto`, which is what we want.
     */
    fun link(llFiles: List<Path>, output: Path, workDir: Path) {
        run(listOf("-O2") + llFiles.map { it.toString() } + listOf("-o", output.toString()), workDir)
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
