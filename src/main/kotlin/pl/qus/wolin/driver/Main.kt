@file:OptIn(ExperimentalPathApi::class)

package pl.qus.wolin.driver

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import pl.qus.wolin.ir.IrBuilder
import pl.qus.wolin.ir.ModuleEmitter
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException
import pl.qus.wolin.sema.TypeChecker
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val USAGE = """
wolinc [options] <file.wtk>...
  -o, --output <file>     output .prg (default: first input's name)
  -d, --debug             keep intermediate .ll files (no link map in v1)
  -e, --emit-ir           write .ll and stop
  -S, --sdk <dir>         llvm-mos SDK path (overrides discovery)
      --target <name>     c64 (default)
      --keep-ir           keep the .ll next to the .prg
"""

private data class Options(
    val inputs: List<Path>,
    val output: Path?,
    val debug: Boolean,
    val emitIr: Boolean,
    val sdk: String?,
    val target: String,
    val keepIr: Boolean,
)

/**
 * wolinc: the Wolin → C64 toolchain driver.
 *
 * For each input file: read, parse, build the AST, type-check, emit one IR
 * module under the mangled package-file prefix. Then emit the driver module,
 * write each module's `.ll`, validate each, and link the `.prg`.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args) ?: run {
        System.err.println(USAGE)
        exitProcess(1)
    }
    exitProcess(run(opts))
}

private fun parseArgs(args: Array<String>): Options? {
    val inputs = mutableListOf<Path>()
    var output: Path? = null
    var debug = false
    var emitIr = false
    var sdk: String? = null
    var target = "c64"
    var keepIr = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when (arg) {
            "-o", "--output" -> { if (i + 1 >= args.size) return null; i++; output = Path.of(args[i]) }
            "-d", "--debug" -> debug = true
            "-e", "--emit-ir" -> emitIr = true
            "-S", "--sdk" -> { if (i + 1 >= args.size) return null; i++; sdk = args[i] }
            "--target" -> { if (i + 1 >= args.size) return null; i++; target = args[i] }
            "--keep-ir" -> keepIr = true
            else -> {
                if (arg.startsWith("-")) return null
                inputs.add(Path.of(arg))
            }
        }
        i++
    }
    return Options(inputs, output, debug, emitIr, sdk, target, keepIr)
}

private fun run(opts: Options): Int {
    if (opts.target != "c64") {
        System.err.println("error: only c64 target supported")
        return 1
    }
    if (opts.inputs.isEmpty()) {
        System.err.println("error: no input files")
        System.err.println(USAGE)
        return 1
    }
    val sdk = try {
        SdkLocator.locate(
            explicit = opts.sdk,
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
    } catch (e: IllegalStateException) {
        System.err.println("error: ${e.message}")
        return 1
    }
    val compiled = mutableListOf<CompiledFile>()
    for (input in opts.inputs) {
        val file = compileFile(input)
        if (file == null) return 1
        compiled += file
    }
    val output = (opts.output ?: defaultOutput(opts.inputs.first())).toAbsolutePath()
    output.parent?.let { Files.createDirectories(it) }
    val outDir = output.parent ?: Path.of(".")
    val workDir = if (opts.emitIr) outDir else Files.createTempDirectory("wolinc")
    val llFiles = mutableListOf<Path>()
    for (file in compiled) {
        val ll = workDir.resolve("${file.mangledPrefix}.ll")
        Files.writeString(ll, file.moduleIr)
        llFiles.add(ll)
    }
    val driverLl = workDir.resolve("driver.ll")
    Files.writeString(driverLl, DriverModule(IrBuilder()).emit(compiled))
        llFiles.add(driverLl)
    if (opts.emitIr) return 0
    val linker = Linker(sdk)
    for (ll in llFiles) {
        try {
            linker.validate(ll)
        } catch (e: IllegalStateException) {
            workDir.deleteRecursively()
            System.err.println(e.message)
            return 1
        } catch (e: IOException) {
            workDir.deleteRecursively()
            System.err.println("error: cannot run ${sdk.clang}: ${e.message}")
            return 1
        }
    }
    try {
        linker.link(llFiles, output, workDir)
    } catch (e: IllegalStateException) {
        workDir.deleteRecursively()
        System.err.println(e.message)
        return 1
    } catch (e: IOException) {
        workDir.deleteRecursively()
        System.err.println("error: cannot run ${sdk.clang}: ${e.message}")
        return 1
    }
    if (opts.keepIr || opts.debug) {
        for (ll in llFiles) Files.copy(ll, outDir.resolve(ll.fileName), StandardCopyOption.REPLACE_EXISTING)
    }
    if (opts.debug) {
        println("debug: intermediate files in $workDir")
    } else {
        workDir.deleteRecursively()
    }
    return 0
}

/** One file, in-process: read, parse, build the AST, type-check, emit IR. */
private fun compileFile(input: Path): CompiledFile? {
    val source = try {
        input.readText()
    } catch (e: IOException) {
        System.err.println("error: cannot read $input")
        return null
    }
    val diag = Diagnostics()
    val tree = ParserFacade(diag).parse(source, input.fileName.toString())
    if (tree == null) {
        diag.messages.forEach { System.err.println(it) }
        return null
    }
    val packageName = tree.preamble()?.packageHeader()?.identifier()?.text.orEmpty()
    val fileName = input.fileName.toString().removeSuffix(".wtk").removeSuffix(".ktk")
    val prefix = Mangler.mangle(packageName, fileName, "")
    val program = try {
        TypeChecker().check(AstBuilder().build(tree))
    } catch (e: SemaException) {
        System.err.println("error: ${e.message} (${input.fileName})")
        return null
    }
    return CompiledFile(
        sourceName = input.fileName.toString(),
        mangledPrefix = prefix,
        moduleIr = ModuleEmitter().emitFile(program, prefix),
        initFnName = null,
    )
}

/** The default output: the first input's name with a `.prg` suffix. */
private fun defaultOutput(input: Path): Path {
    val name = input.fileName.toString().removeSuffix(".wtk").removeSuffix(".ktk")
    return (input.parent ?: Path.of(".")).resolve("$name.prg")
}
