@file:OptIn(ExperimentalPathApi::class)

package pl.qus.wolin.driver

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ir.IrBuilder
import pl.qus.wolin.ir.ModuleEmitter
import pl.qus.wolin.sema.SemaException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val USAGE = """
wolinc [options] <file.wtk>...
  -o, --output <file>     output .prg (default: first input's name)
  -d, --debug             keep intermediate .ll files
  -e, --emit-ir           write .ll and stop
  -S, --sdk <dir>         llvm-mos SDK path (overrides discovery)
      --target <name>     c64 (default)
      --keep-ir           keep the .ll next to the .prg
      --rust-archive <f>  link a Rust archive into the program (repeatable)
      --rust-map <file>   symbol map: '<wolin symbol> = <rust symbol>' per line
"""

private data class Options(
    val inputs: List<Path>,
    val output: Path?,
    val debug: Boolean,
    val emitIr: Boolean,
    val sdk: String?,
    val target: String,
    val keepIr: Boolean,
    /** Rust archives linked into the program. */
    val rustArchives: List<Path> = emptyList(),
    /** The symbol map tying Wolin external functions to Rust symbols. */
    val rustMap: Path? = null,
)

/**
 * wolinc: the Wolin → C64 toolchain driver.
 *
 * For each input file: read, parse, build the AST, type-check, emit one IR
 * module under the mangled package-file prefix. Then emit the driver module,
 * write each module's `.ll`, validate each, and link the `.prg`.
 */
fun main(args: Array<String>) {
    exitProcess(runCli(args.asList()))
}

/**
 * The CLI's work, callable in-process: it returns the exit code instead of
 * calling [System.exit], so a test can drive it without killing the test
 * JVM.
 */
fun runCli(args: List<String>): Int {
    val opts = parseArgs(args) ?: run {
        System.err.println(USAGE)
        return 1
    }
    return run(opts)
}

private fun parseArgs(args: List<String>): Options? {
    val inputs = mutableListOf<Path>()
    val rustArchives = mutableListOf<Path>()
    var output: Path? = null
    var debug = false
    var emitIr = false
    var sdk: String? = null
    var target = "c64"
    var keepIr = false
    var rustMap: Path? = null
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
            "--rust-archive" -> {
                if (i + 1 >= args.size) return null
                i++
                rustArchives.add(Path.of(args[i]))
            }
            "--rust-map" -> { if (i + 1 >= args.size) return null; i++; rustMap = Path.of(args[i]) }
            else -> {
                if (arg.startsWith("-")) return null
                inputs.add(Path.of(arg))
            }
        }
        i++
    }
    return Options(inputs, output, debug, emitIr, sdk, target, keepIr, rustArchives, rustMap)
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
    val output = (opts.output ?: defaultOutput(opts.inputs.first())).toAbsolutePath()
    output.parent?.let { Files.createDirectories(it) }
    val outDir = output.parent ?: Path.of(".")
    if (opts.emitIr) {
        val compiled = compileFiles(opts.inputs) ?: return 1
        writeIrFiles(compiled, outDir)
        return 0
    }
    val workDir = Files.createTempDirectory("wolinc")
    val llFiles = compileToPrgFile(opts.inputs, output, sdk, workDir, opts.rustArchives, opts.rustMap)
    if (llFiles == null) {
        workDir.deleteRecursively()
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

/**
 * The compile pipeline, callable in-process: compiles [inputs], emits one LLVM
 * IR module per file plus the driver module, validates every module and links
 * them into [output]. The `.ll` files are written to [workDir].
 *
 * Returns the `.ll` files on success. On failure it returns null after
 * printing the diagnostic to stderr.
 */
internal fun compileToPrgFile(
    inputs: List<Path>,
    output: Path,
    sdk: Sdk,
    workDir: Path,
    rustArchives: List<Path> = emptyList(),
    rustMap: Path? = null,
): List<Path>? {
    val compiled = compileFiles(inputs) ?: return null
    val llFiles = writeIrFiles(compiled, workDir)
    val linker = Linker(sdk)
    try {
        // The modules are validated together: a module may call into
        // another one, which a single-module compile rejects.
        linker.validate(llFiles)
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        return null
    } catch (e: IOException) {
        System.err.println("error: cannot run ${sdk.clang}: ${e.message}")
        return null
    }
    // The map names the symbol each external function resolves to. A map
    // entry for a symbol the program does not declare is a mistake worth
    // reporting: the link would fail with a linker error instead.
    val map = if (rustMap != null) {
        try {
            RustMap.parse(rustMap)
        } catch (e: IllegalArgumentException) {
            System.err.println("error: ${e.message}")
            return null
        }
    } else {
        RustMap(emptyList())
    }
    val declared = compiled.flatMap { it.externalSymbols }.toSet()
    for ((wolin, _) in map.entries) {
        if (wolin !in declared) {
            System.err.println(
                "error: the symbol map names $wolin, which is not an external function of this program"
            )
            return null
        }
    }
    try {
        linker.link(llFiles, output, workDir, rustArchives, map)
    } catch (e: IllegalStateException) {
        System.err.println(e.message)
        return null
    } catch (e: IOException) {
        System.err.println("error: cannot run ${sdk.clang}: ${e.message}")
        return null
    }
    return llFiles
}

/**
 * Compiles every input file to an IR module.
 *
 * The files are checked together, so a name from another file resolves
 * through an import; each file is then emitted into its own module. Null on
 * failure.
 */
private fun compileFiles(inputs: List<Path>): List<CompiledFile>? {
    val sources = mutableListOf<SourceFile>()
    for (input in inputs) {
        val source = try {
            input.readText()
        } catch (e: IOException) {
            System.err.println("error: cannot read $input")
            return null
        }
        sources += SourceFile(input.fileName.toString(), source)
    }
    val program = try {
        ProgramBuilder().build(sources)
    } catch (e: SemaException) {
        val msg = e.line?.let { "${e.message} (line $it)" } ?: e.message
        System.err.println("error: $msg")
        return null
    }
    return emitFiles(program)
}

/**
 * Emits one module per file from the whole-program view.
 */
private fun emitFiles(program: Ast.Program): List<CompiledFile>? {
    val compiled = mutableListOf<CompiledFile>()
    for (scope in program.files) {
        val moduleEmitter = ModuleEmitter()
        val moduleIr = moduleEmitter.emitFile(
            Ast.Program(
                globals = scope.globals,
                functions = scope.functions,
                structs = program.structs,
                enums = scope.enums,
                traits = scope.traits,
                impls = scope.impls,
                imports = scope.imports,
                resolved = program.resolved,
                packageName = scope.packageName,
                visibleGlobals = visibleGlobalsFor(program, scope),
                methods = program.methods,
                vtables = program.vtables,
            ),
            scope.mangledPrefix,
        )
        compiled += CompiledFile(
            sourceName = scope.fileName,
            mangledPrefix = scope.mangledPrefix,
            moduleIr = moduleIr,
            initFnName = moduleEmitter.initFnName,
            usedInterpHelpers = moduleEmitter.usedInterpHelpers,
            usesFloatInterp = moduleEmitter.usesFloatInterpolation,
            externalSymbols = moduleEmitter.externalSymbols,
        )
    }
    return compiled
}

/**
 * Writes each compiled module's `.ll` file plus the driver module into [dir].
 * Returns the files written, the driver module last.
 */
private fun writeIrFiles(compiled: List<CompiledFile>, dir: Path): List<Path> {
    val llFiles = mutableListOf<Path>()
    for (file in compiled) {
        val ll = dir.resolve("${file.mangledPrefix}.ll")
        Files.writeString(ll, file.moduleIr)
        llFiles.add(ll)
    }
    val driverLl = dir.resolve("driver.ll")
    Files.writeString(driverLl, DriverModule(IrBuilder()).emit(compiled))
    llFiles.add(driverLl)
    return llFiles
}

/**
 * The globals of the other files that are visible in [scope]: the
 * same-package ones, and the imported ones.
 */
private fun visibleGlobalsFor(program: Ast.Program, scope: Ast.FileScope): List<Ast.ForeignGlobal> {
    val samePackage = program.files.filter { it.packageName == scope.packageName && it !== scope }
    val imported: List<Ast.GlobalDecl> = scope.imports.flatMap { import ->
        val path = import.path.removeSuffix(".*")
        val packageName = if (import.path.endsWith(".*")) {
            path
        } else {
            path.substringBeforeLast('.', "")
        }
        program.files.filter { it.packageName == packageName }.flatMap { it.globals }
    }
    val candidates: List<Ast.GlobalDecl> = samePackage.flatMap { it.globals } + imported
    return candidates.distinct().map { global ->
        val owner = program.files.firstOrNull { it.globals.contains(global) }
        Ast.ForeignGlobal(global, owner?.mangledPrefix ?: "")
    }
}

/** The default output: the first input's name with a `.prg` suffix. */
private fun defaultOutput(input: Path): Path {
    val name = input.fileName.toString().removeSuffix(".wtk").removeSuffix(".ktk")
    return (input.parent ?: Path.of(".")).resolve("$name.prg")
}
