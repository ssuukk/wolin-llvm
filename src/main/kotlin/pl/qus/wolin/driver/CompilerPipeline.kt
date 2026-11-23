package pl.qus.wolin.driver

import pl.qus.wolin.ir.ModuleEmitter

/**
 * The compile pipeline: parse, build the typed AST, type-check, emit IR.
 *
 * [compileToIr] takes Wolin source and returns the whole LLVM IR module as
 * text. A parse failure fails with the collected diagnostics.
 */
class CompilerPipeline {
    fun compileToIr(source: String, fileName: String): String {
        val program = ProgramBuilder().build(listOf(SourceFile(fileName, source)))
        val prefix = program.files.single().mangledPrefix
        return ModuleEmitter().emitFile(program, prefix)
    }
}
