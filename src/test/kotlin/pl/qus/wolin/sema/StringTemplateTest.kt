package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals

/** Type rules for string interpolation. */
class StringTemplateTest {

    private fun check(src: String): Ast.Program {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(src, "t.ktk")
        check(tree != null) { "syntax errors: ${diag.messages}" }
        return TypeChecker().check(AstBuilder().build(tree))
    }

    private fun rejected(src: String): String =
        assertThrows<SemaException> { check(src) }.message!!

    @Test fun `every scalar part type is accepted`() {
        check(
            """
            fun f(u: ubyte, b: byte, w: uword, d: word, q: bool, s: string, x: float) {
                val t = "${'$'}{u}${'$'}{b}${'$'}{w}${'$'}{d}${'$'}{q}${'$'}{s}${'$'}{x}"
            }
            """.trimIndent(),
        )
    }

    @Test fun `a unit part is rejected`() {
        val message = rejected("""fun g() {}
fun f() { val s = "${'$'}{g()}" }""")
        assertEquals("Unsupported construct: unit in string interpolation", message)
    }

    @Test fun `an array part is rejected`() {
        val message = rejected("""var a: ubyte[ubyte]
            fun f() { val s = "${'$'}{a}" }""")
        assertEquals("Unsupported construct: ubyte[ubyte] in string interpolation", message)
    }

    @Test fun `a function part is rejected`() {
        val message = rejected("""fun f(p: (ubyte)->ubyte) { val s = "${'$'}{p}" }""")
        assertEquals("Unsupported construct: (ubyte)->ubyte in string interpolation", message)
    }

    @Test fun `NUL in a literal part is rejected`() {
        val message = rejected("""fun f(n: ubyte) { val s = "a\0${'$'}{n}" }""")
        assertEquals("NUL is not allowed in a string template", message)
    }

    @Test fun `a fixed part over 255 bytes is rejected`() {
        val long = "A".repeat(256)
        val message = rejected("""fun f(n: ubyte) { val s = "$long${'$'}{n}" }""")
        assertEquals("String interpolation is limited to 255 bytes", message)
    }

    @Test fun `a plain 300-byte literal is still accepted`() {
        val long = "A".repeat(300)
        check("""fun f() { val s = "$long" }""")
    }
}
