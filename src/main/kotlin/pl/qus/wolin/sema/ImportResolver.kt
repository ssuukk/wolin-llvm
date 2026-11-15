package pl.qus.wolin.sema

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.ImportDecl

/**
 * Resolves an imported name to the declaration it names.
 *
 * An import path is `package.symbol` — the last part is the name the symbol
 * becomes visible under, the rest is the package it comes from. A wildcard
 * import (`package.*`) brings every symbol of that package into scope.
 */
class ImportResolver {

    /**
     * The symbol [name] refers to through [imports], or null when no import
     * brings it in.
     */
    fun resolve(name: String, imports: List<ImportDecl>, program: Ast.Program): Symbol? {
        for (import in imports) {
            if (import.alias != null) {
                throw SemaException("import aliases are not supported in v1")
            }
            val path = import.path
            if (path.endsWith(".*")) {
                val packageName = path.removeSuffix(".*")
                val scope = program.files.firstOrNull {
                    it.packageName == packageName && it.functions.any { fn -> fn.name == name }
                }
                if (scope != null) {
                    val fn = scope.functions.first { it.name == name }
                    return Symbol.FunSymbol(fn, scope.mangledPrefix)
                }
                continue
            }
            val separator = path.lastIndexOf('.')
            if (separator < 0) continue
            val packageName = path.substring(0, separator)
            val importedName = path.substring(separator + 1)
            if (importedName != name) continue
            val scope = program.files.firstOrNull {
                it.packageName == packageName && it.functions.any { fn -> fn.name == importedName }
            }
            if (scope != null) {
                val fn = scope.functions.first { it.name == importedName }
                return Symbol.FunSymbol(fn, scope.mangledPrefix)
            }
        }
        return null
    }
}
