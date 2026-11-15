package pl.qus.wolin.sema

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionLiteralTest {
    @Test fun `parameter types come from the assignment target`() {
        val program = compile("""
            var suma: (ubyte,ubyte)->ubyte
            fun main() {
                suma = { a, b -> a + b }
            }
        """)
        val lit = findFunctionLiteral(program)
        assertEquals(listOf(Types.ubyte, Types.ubyte), lit.paramTypes)
    }

    @Test fun `a literal with no expected type is rejected`() {
        assertFailsWith<SemaException> { compile("fun main() { val f = { a, b -> a + b } }") }
    }

    private fun compile(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    /** The first function literal anywhere in the program. */
    private fun findFunctionLiteral(program: Ast.Program): Ast.FunctionLit {
        var found: Ast.FunctionLit? = null
        lateinit var walkExpr: (Ast.Expr) -> Unit
        lateinit var walkStmt: (Ast.Stmt) -> Unit
        walkExpr = { e ->
            if (e is Ast.FunctionLit && found == null) { found = e }
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
        return found!!
    }
}
