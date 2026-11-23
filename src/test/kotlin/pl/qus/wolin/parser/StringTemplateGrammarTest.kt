package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The grammar already splits a string literal into text, escapes, `$name`
 * and `${expr}`. The AST builder must preserve that structure instead of
 * taking the literal's source text.
 */
class StringTemplateGrammarTest {

    private fun build(src: String): Ast.Program {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(src, "t.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors: ${diag.messages}")
        return AstBuilder().build(tree!!)
    }

    private fun initOf(src: String): Ast.Expr =
        (build(src).functions[0].body[0] as Ast.LocalDecl).init!!

    private fun literal(bytes: ByteArray) = Ast.StringTemplate.Literal(bytes)

    @Test fun `a template with an expression builds literal and value parts`() {
        val expr = initOf("""fun f(n: ubyte) { val s = "a ${'$'}{n} b" }""") as Ast.StringTemplate
        assertEquals(3, expr.parts.size)
        assertEquals(listOf<Byte>(0x41, 0x20), (expr.parts[0] as Ast.StringTemplate.Literal).bytes.toList())
        assertTrue(expr.parts[1] is Ast.StringTemplate.Value)
        assertTrue((expr.parts[1] as Ast.StringTemplate.Value).expr is Ast.VarRef)
        assertEquals(listOf<Byte>(0x20, 0x42), (expr.parts[2] as Ast.StringTemplate.Literal).bytes.toList())
    }

    @Test fun `a dollar identifier builds a value part`() {
        val expr = initOf("""fun f(n: ubyte) { val s = "${'$'}n" }""") as Ast.StringTemplate
        assertEquals(1, expr.parts.size)
        val ref = (expr.parts[0] as Ast.StringTemplate.Value).expr as Ast.VarRef
        assertEquals("n", ref.name)
    }

    @Test fun `an expression with an operator builds as one value part`() {
        val expr = initOf("""fun f(n: ubyte) { val s = "${'$'}{n + 1}" }""") as Ast.StringTemplate
        val value = (expr.parts[0] as Ast.StringTemplate.Value).expr
        assertTrue(value is Ast.BinaryOp)
    }

    @Test fun `an indexed expression builds as one value part`() {
        val expr = initOf("""fun f(a: string) { val s = "${'$'}{a[0]}" }""") as Ast.StringTemplate
        assertTrue((expr.parts[0] as Ast.StringTemplate.Value).expr is Ast.ArrayAccess)
    }

    @Test fun `a nested string literal builds as a value part`() {
        val expr = initOf("""fun f() { val s = "${'$'}{"x"}" }""") as Ast.StringTemplate
        assertTrue((expr.parts[0] as Ast.StringTemplate.Value).expr is Ast.StringLit)
    }

    @Test fun `an escaped dollar is literal and not a template`() {
        val expr = initOf("""fun f() { val s = "a\${'$'}b" }""")
        assertTrue(expr is Ast.StringLit)
        assertEquals(listOf<Byte>(0x41, 0x24, 0x42), (expr as Ast.StringLit).bytes.toList())
    }

    @Test fun `a bare dollar before a digit is literal`() {
        val expr = initOf("""fun f() { val s = "price ${'$'}5" }""")
        assertTrue(expr is Ast.StringLit)
        assertEquals(listOf<Byte>(0x50, 0x52, 0x49, 0x43, 0x45, 0x20, 0x24, 0x35), (expr as Ast.StringLit).bytes.toList())
    }

    @Test fun `escape decoding still applies inside literal parts`() {
        val expr = initOf("""fun f() { val s = "a\nb" }""")
        assertTrue(expr is Ast.StringLit)
        assertEquals(listOf<Byte>(0x41, 0x0A, 0x42), (expr as Ast.StringLit).bytes.toList())
    }

    @Test fun `a unicode escape decodes four hex digits`() {
        val expr = initOf("""fun f() { val s = "\u0041" }""")
        assertTrue(expr is Ast.StringLit)
        assertEquals(listOf<Byte>(0xC1.toByte()), (expr as Ast.StringLit).bytes.toList())
    }

    @Test fun `a unicode escape decodes in a triple quoted string`() {
        val expr = initOf("fun f() { val s = \"\"\"\\u0041\"\"\" }")
        assertTrue(expr is Ast.StringLit)
        assertEquals(listOf<Byte>(0xC1.toByte()), (expr as Ast.StringLit).bytes.toList())
    }

    @Test fun `a unicode escape with fewer than four hex digits is rejected`() {
        assertFailsWith<SemaException> {
            build("""fun f() { val s = "\u12" }""")
        }
    }

    @Test fun `a plain literal stays a StringLit`() {
        assertTrue(initOf("""fun f() { val s = "abc" }""") is Ast.StringLit)
    }
}
