package pl.qus.wolin.sema

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.SourceFile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportTest {
    @Test fun `an imported name is visible unqualified`() {
        val program = compileFiles(
            listOf(
                SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                SourceFile(
                    "main.wtk",
                    "package app\nimport lib.helper\nfun main() { val x = helper() }"
                ),
            )
        )
        // helper() resolved to lib's declaration, not to an unknown name.
        val call = findCall(program)
        assertTrue(call != null, "the call to helper() must be in the program")
        val callee = call!!.callee as Ast.VarRef
        val symbol = program.resolved[callee]
        assertTrue(symbol is Symbol.FunSymbol, "helper() must bind to lib's function, got $symbol")
        assertTrue(
            (symbol as Symbol.FunSymbol).decl.name == "helper",
            "the imported symbol must be lib's helper",
        )
    }

    @Test fun `a name from another package without an import does not resolve`() {
        assertFailsWith<SemaException> {
            compileFiles(
                listOf(
                    SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                    SourceFile("main.wtk", "package app\nfun main() { val x = helper() }"),
                )
            )
        }
    }

    @Test fun `an import of a name that does not exist is rejected`() {
        assertFailsWith<SemaException> {
            compileFiles(
                listOf(
                    SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                    SourceFile("main.wtk", "package app\nimport lib.missing\nfun main() { }"),
                )
            )
        }
    }

    private fun compileFiles(files: List<SourceFile>): Ast.Program =
        pl.qus.wolin.driver.ProgramBuilder().build(files)

    /** The first call expression in the program. */
    private fun findCall(program: Ast.Program): Ast.Call? {
        var found: Ast.Call? = null
        lateinit var walkExpr: (Ast.Expr) -> Unit
        lateinit var walkStmt: (Ast.Stmt) -> Unit
        walkExpr = { e ->
            if (e is Ast.Call && found == null) {
                found = e
            }

            when (e) {
                is Ast.BinaryOp -> { walkExpr(e.left); walkExpr(e.right) }
                is Ast.UnaryOp -> walkExpr(e.operand)
                is Ast.Call -> { walkExpr(e.callee); e.args.forEach { walkExpr(it) } }
                is Ast.Assign -> { walkExpr(e.target); walkExpr(e.value) }
                is Ast.ArrayAccess -> { walkExpr(e.array); walkExpr(e.index) }
                is Ast.IfExpr -> {
                    walkExpr(e.cond)
                    e.then.forEach { walkStmt(it) }
                    e.otherwise.forEach { walkStmt(it) }
                }
                else -> {}
            }
        }
        walkStmt = { s ->
            when (s) {
                is Ast.LocalDecl -> s.init?.let { walkExpr(it) }
                is Ast.ExprStmt -> walkExpr(s.expr)
                is Ast.BlockStmt -> s.body.forEach { walkStmt(it) }
                is Ast.IfStmt -> {
                    walkExpr(s.cond)
                    s.then.forEach { walkStmt(it) }
                    s.otherwise.forEach { walkStmt(it) }
                }
                is Ast.WhileStmt -> { walkExpr(s.cond); s.body.forEach { walkStmt(it) } }
                is Ast.ReturnStmt -> s.value?.let { walkExpr(it) }
                is Ast.WhenStmt -> s.entries.forEach { entry ->
                    entry.conditions.forEach { walkExpr(it) }
                    entry.body.forEach { walkStmt(it) }
                }
                else -> {}
            }
        }
        program.functions.forEach { f -> f.body.forEach { walkStmt(it) } }
        program.globals.forEach { g -> g.init?.let { walkExpr(it) } }
        return found
    }
}
