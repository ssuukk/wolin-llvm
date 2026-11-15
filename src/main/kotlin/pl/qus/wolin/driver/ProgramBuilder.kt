package pl.qus.wolin.driver

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.FileScope
import pl.qus.wolin.ast.Program
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException

/** One input file: its name and its source text. */
data class SourceFile(val fileName: String, val source: String)

/**
 * Builds the whole-program view of a set of files.
 *
 * Every file is parsed and built on its own, then all of them are checked
 * together: a name declared in one file is visible in another when the
 * importing file imports it. A file's declarations stay in its own scope, so
 * each file is emitted into its own module.
 */
class ProgramBuilder {

    /**
     * Parses, builds and checks [files] as one program.
     *
     * Throws [SemaException] on a parse or type error, naming the file.
     */
    fun build(files: List<SourceFile>): Program {
        val scopes = mutableListOf<FileScope>()
        for (file in files) {
            val diag = Diagnostics()
            val tree = ParserFacade(diag).parse(file.source, file.fileName)
                ?: throw SemaException(
                    "parse error in ${file.fileName}: " + diag.messages.joinToString("; ")
                )
            val built = AstBuilder().build(tree)
            val packageName = tree.preamble()?.packageHeader()?.identifier()?.text.orEmpty()
            val stem = file.fileName.removeSuffix(".wtk").removeSuffix(".ktk")
            scopes += FileScope(
                fileName = file.fileName,
                packageName = packageName,
                globals = built.globals,
                functions = built.functions,
                imports = built.imports,
                mangledPrefix = Mangler.mangle(packageName, stem, ""),
            )
        }
        val merged = Program(
            globals = scopes.flatMap { it.globals },
            functions = scopes.flatMap { it.functions },
            imports = scopes.flatMap { it.imports },
            files = scopes,
        )
        return pl.qus.wolin.sema.TypeChecker().check(merged)
    }
}
