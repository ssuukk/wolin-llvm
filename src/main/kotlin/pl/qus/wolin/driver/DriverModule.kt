package pl.qus.wolin.driver

import pl.qus.wolin.ir.InterpRuntime
import pl.qus.wolin.ir.IrBuilder
import pl.qus.wolin.ir.IrType
import pl.qus.wolin.ir.IrValue

/**
 * One input file, compiled.
 *
 * [mangledPrefix] is the package-file prefix, e.g. `game.demo`; the file's
 * functions are `@"<mangledPrefix>.<symbol>"`. [initFnName] is the file's
 * init function (`__wolin_init_file_<mangledPrefix>`) when the file has one,
 * null when it does not.
 */
data class CompiledFile(
    val sourceName: String,
    val mangledPrefix: String,
    val moduleIr: String,
    val initFnName: String?,
    /** Interpolation helpers the file's module calls. */
    val usedInterpHelpers: Set<String> = emptySet(),
    /** Whether the file formats a float in a template. */
    val usesFloatInterp: Boolean = false,
    /** The symbols the file's external functions are declared under. */
    val externalSymbols: Set<String> = emptySet(),
)

/**
 * Emits the driver module.
 *
 * It defines only `main` — no wrapper functions. `main` calls each file's
 * init function ([CompiledFile.initFnName]) in the order the files were
 * given, only where it is non-null, then calls the user's `main`
 * (`@"<mangledPrefix>.main"`). When no file declares `main`, `main` performs
 * the init calls (if any) and returns immediately.
 *
 * The called functions live in the file modules, so the driver also declares
 * each of them; without the declarations the module is invalid IR on its
 * own. The files' init functions must be visible to this module at LTO link
 * time; their linkage is the emitter's concern.
 */
class DriverModule(private val b: IrBuilder) {
    fun emit(files: List<CompiledFile>): String {
        b.startFunction("main", IrType.I32)
        for (file in files) {
            val init = file.initFnName ?: continue
            b.callVoid(init, emptyList())
        }
        val mains = files.filter { declaresMain(it) }
        check(mains.size <= 1) { "main must be defined in exactly one place" }
        val userMain = mains.firstOrNull()
        userMain?.let { b.callVoid("${it.mangledPrefix}.main", emptyList()) }
        b.ret(IrValue("0", IrType.I32))
        b.endFunction()

        val declares = files.mapNotNull { it.initFnName }.map { "declare void ${quoted(it)}()" } +
            (userMain?.let { "declare void ${quoted("${it.mangledPrefix}.main")}()" } ?: "")
        val runtime = InterpRuntime.emit(
            used = files.flatMap { it.usedInterpHelpers }.toSet(),
            includeFloat = files.any { it.usesFloatInterp },
        )
        val body = listOf(runtime, declares.joinToString("\n"), b.render())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        return "target triple = \"mos-c64\"\n\n" + body
    }

    /** Whether [file]'s module defines a user `main` under the mangled prefix. */
    private fun declaresMain(file: CompiledFile): Boolean =
        file.moduleIr.lineSequence().any {
            it.startsWith("define ") && it.contains("\"${file.mangledPrefix}.main\"")
        }

    /**
     * The `@name` or `@"name"` spelling of a function target, mirroring the
     * quoting rule the IR builder applies when rendering targets.
     */
    private fun quoted(name: String): String {
        val plain = name.isNotEmpty() &&
            (isAsciiLetter(name[0]) || name[0] == '_' || name[0] == '$' || name[0] == '-') &&
            name.all { isAsciiLetterOrDigit(it) || it in "_$-" }
        return if (plain) "@$name" else "@\"$name\""
    }

    private fun isAsciiLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
    private fun isAsciiLetterOrDigit(c: Char) = isAsciiLetter(c) || c in '0'..'9'
}
