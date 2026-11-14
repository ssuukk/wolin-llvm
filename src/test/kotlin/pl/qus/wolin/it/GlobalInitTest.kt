package pl.qus.wolin.it

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException
import pl.qus.wolin.sema.TypeChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobalInitTest {
    @Test fun `globals are initialised in source order before main`() {
        val bytes = runProgram("""
            var a: ubyte = 7
            var b: ubyte = a + 1
            fun main() {
                var out: ubyte^0x400 = 0
                out = b
                while (true) {}
            }
        """)
        assertEquals(8, bytes[0].toInt() and 0xFF)
    }

    /**
     * A global whose initialiser names a global declared later in the file
     * would read it before it is initialised. The order is a compile error,
     * not a runtime one.
     */
    @Test fun `a global initialiser that references a later global is rejected`() {
        val e = assertFailsWith<SemaException> {
            compile("""
                var a: ubyte = b
                var b: ubyte = 7
                fun main() { }
            """)
        }
        assertTrue(
            e.message!!.contains("initialiser references a global that is not yet initialised"),
            "message should name the ordering error, got: ${e.message}",
        )
    }

    private fun compile(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))
}
