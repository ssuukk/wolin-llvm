package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScopeTest {
    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    /** Every `VarRef` in the program, in source order. */
    private fun varRefs(program: Ast.Program): List<VarRef> {
        val refs = mutableListOf<VarRef>()
        lateinit var walkStmt: (Ast.Stmt) -> Unit
        fun walkExpr(e: Ast.Expr) {
            when (e) {
                is Ast.VarRef -> refs += e
                is Ast.UnaryOp -> walkExpr(e.operand)
                is Ast.BinaryOp -> { walkExpr(e.left); walkExpr(e.right) }
                is Ast.Call -> { walkExpr(e.callee); e.args.forEach(::walkExpr) }
                is Ast.Assign -> { walkExpr(e.target); walkExpr(e.value) }
                is Ast.ArrayAccess -> { walkExpr(e.array); walkExpr(e.index) }
                is Ast.FunctionLit -> e.body.forEach { walkStmt(it) }
                is Ast.IfExpr -> {
                    walkExpr(e.cond); e.then.forEach { walkStmt(it) }; e.otherwise.forEach { walkStmt(it) }
                }
                else -> {}
            }
        }
        walkStmt = { s ->
            when (s) {
                is Ast.LocalDecl -> s.init?.let(::walkExpr)
                is Ast.ExprStmt -> walkExpr(s.expr)
                is Ast.BlockStmt -> s.body.forEach { walkStmt(it) }
                is Ast.IfStmt -> {
                    walkExpr(s.cond); s.then.forEach { walkStmt(it) }; s.otherwise.forEach { walkStmt(it) }
                }
                is Ast.WhileStmt -> { walkExpr(s.cond); s.body.forEach { walkStmt(it) } }
                is Ast.ReturnStmt -> s.value?.let(::walkExpr)
                is Ast.BreakStmt, is Ast.ContinueStmt -> {}
            }
        }
        program.functions.forEach { f -> f.body.forEach { walkStmt(it) } }
        program.globals.forEach { g -> g.init?.let(::walkExpr) }
        return refs
    }

    @Test fun `inner declaration shadows outer and is restored on exit`() {
        val src = "fun f() { var x: ubyte = 1; { var x: ubyte = 2; var y: ubyte = x }; var z: ubyte = x }"
        val program = typeChecker(src)

        val refs = varRefs(program)
        // the two references to `x`: one inside the inner block, one after it
        val xRefs = refs.filter { it.name == "x" }
        assertEquals(2, xRefs.size, "expected two references to x, got $xRefs")

        val innerSymbol = program.resolved[xRefs[0]]
        val outerSymbol = program.resolved[xRefs[1]]

        assertNotNull(innerSymbol, "inner reference to x did not resolve")
        assertNotNull(outerSymbol, "outer reference to x did not resolve")

        assertTrue(innerSymbol !== outerSymbol, "inner x must bind to a different declaration than outer x")

        // the inner declaration's initialiser is 2, the outer one is 1
        val innerInit = (innerSymbol as Symbol.DataSymbol).init as Ast.IntLit
        val outerInit = (outerSymbol as Symbol.DataSymbol).init as Ast.IntLit
        assertEquals(2L, innerInit.value)
        assertEquals(1L, outerInit.value)
    }

    @Test fun `a declaration in a block does not leak out of it`() {
        val src = "fun f() { { var hidden: ubyte = 7 }; var z: ubyte = hidden }"
        assertTrue(
            runCatching { typeChecker(src) }.isFailure,
            "a block-local declaration must not be visible after the block ends"
        )
    }

    @Test fun `a function body is a scope of its own`() {
        val src = "fun f() { var x: ubyte = 1 } fun g() { var y: ubyte = x }"
        assertTrue(
            runCatching { typeChecker(src) }.isFailure,
            "a local declaration must not be visible in another function"
        )
    }
}
