package pl.qus.wolin.driver

import pl.qus.wolin.ir.ModuleEmitter
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.TypeChecker

/**
 * The compile pipeline: parse, build the typed AST, type-check, emit IR.
 *
 * [compileToIr] takes Wolin source and returns the whole LLVM IR module as
 * text. A parse failure fails with the collected diagnostics.
 */
class CompilerPipeline {
    fun compileToIr(source: String, fileName: String): String {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(source, fileName) ?: error(diag.messages.joinToString("\n"))
        val program = TypeChecker().check(AstBuilder().build(tree))
        return ModuleEmitter().emitFile(program, fileName.removeSuffix(".wtk").removeSuffix(".ktk"))
    }
}
