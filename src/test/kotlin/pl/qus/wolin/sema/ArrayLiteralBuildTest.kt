package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.ArrayLiteral
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArrayLiteralBuildTest {

    private fun build(src: String): ArrayLiteral {
        val tree = ParserFacade(Diagnostics()).parse(src, "t.ktk")!!
        val program = AstBuilder().build(tree)
        val init = program.globals[0].init
        return assertIs<ArrayLiteral>(init, "expected an ArrayLiteral, got ${init?.javaClass?.simpleName}")
    }

    @Test fun `three elements in order`() {
        val lit = build("var a: ubyte[ubyte] = [1, 2, 3]")
        assertEquals(3, lit.elements.size)
        assertEquals(listOf(1L, 2L, 3L), lit.elements.map { (it as pl.qus.wolin.ast.IntLit).value })
    }

    @Test fun `empty list`() {
        val lit = build("var a: ubyte[ubyte] = []")
        assertEquals(0, lit.elements.size)
    }

    @Test fun `multi-line list`() {
        val src = "var a: ubyte[ubyte] = [\n1,\n2,\n3\n]"
        val lit = build(src)
        assertEquals(3, lit.elements.size)
    }
}
