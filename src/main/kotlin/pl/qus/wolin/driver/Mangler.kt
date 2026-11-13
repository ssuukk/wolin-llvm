package pl.qus.wolin.driver

/**
 * Mangles Wolin symbols as `<package>.<file>.<symbol>`, with empty parts
 * omitted — a file with no `package` declaration mangles to
 * `"<file>.<symbol>"`.
 *
 * The mangled name is emitted in LLVM as a quoted identifier,
 * e.g. `@"game.demo.main"`; quoting is the emitter's job.
 */
object Mangler {
    fun mangle(packageName: String, fileName: String, symbol: String): String =
        listOf(packageName, fileName, symbol).filter { it.isNotEmpty() }.joinToString(".")
}
