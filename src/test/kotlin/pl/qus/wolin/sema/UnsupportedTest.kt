package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The constructs v1 does not support must be rejected by the analyser with
 * a message that names the construct. The grammar keeps accepting them — the
 * reused grammar is a full Kotlin grammar, so almost everything parses.
 *
 * The list is a catalogue, not a sample: every entry is here so that a
 * change which silently accepts one of them fails this test.
 */
class UnsupportedTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String) {
        val e = assertFailsWith<SemaException>("should reject: $src") { check(src) }
        assertTrue(
            e.message!!.contains("Unsupported construct", ignoreCase = true),
            "message should name the construct, got: ${e.message}",
        )
    }

    @Test fun `exceptions are rejected`() {
        assertRejected("fun f() { try { } catch (e: word) { } }")
    }

    @Test fun `classes are rejected`() {
        assertRejected("class C { }")
    }

    @Test fun `objects are rejected`() {
        assertRejected("object O { }")
    }

    @Test fun `a val cannot be reassigned`() {
        assertFailsWith<SemaException> { check("fun f() { val x: ubyte = 1; x = 2 }") }
    }

    @Test fun `for loops are rejected`() {
        assertRejected("fun f() { for (i in 0..10) { } }")
    }

    @Test fun `type operations are rejected`() {
        assertRejected("fun f() { val x = 1 as ubyte }")
        assertRejected("fun f() { val x = 1 as? ubyte }")
    }

    @Test fun `nullable types are rejected`() {
        assertFailsWith<SemaException> { check("fun f() { val x: ubyte? = null }") }
    }

    @Test fun `the elvis operator is rejected`() {
        assertRejected("fun f() { val x = 1 ?: 2 }")
    }

    @Test fun `this and super are rejected`() {
        assertRejected("fun f() { val x = this }")
        assertRejected("fun f() { val x = super.foo() }")
    }

    @Test fun `the not-null assertion is rejected`() {
        assertRejected("fun f() { val x: ubyte = 1!! }")
    }

    @Test fun `suspend is rejected`() {
        assertFailsWith<SemaException> { check("fun f() { suspend { } }") }
    }

    @Test fun `unknown library functions are rejected`() {
        assertFailsWith<SemaException> { check("fun f() { val x = listOf(1) }") }
        assertFailsWith<SemaException> { check("fun f() { val x = intArrayOf(1) }") }
    }

    @Test fun `type aliases are rejected`() {
        assertFailsWith<SemaException> { check("typealias Foo = ubyte") }
    }

    /**
     * String interpolation was silently mis-compiled: the literal's source
     * text was taken verbatim, so `"a ${1} b"` became the eight literal
     * bytes `a`, ` `, `$`, `{`, `1`, `}`, ` `, `b`. The spec lists string
     * interpolation among the unsupported constructs, so it must be
     * rejected rather than compiled wrong.
     */
    @Test fun `string interpolation is rejected`() {
        assertRejected("""fun f() { val s = "a ${'$'}{1} b" }""")
    }

    @Test fun `string interpolation is rejected even when it is the whole literal`() {
        assertRejected("""fun f() { val s = "${'$'}{1}" }""")
    }

    @Test fun `a labelled break is rejected`() {
        assertRejected("fun f() { break@foo }")
    }

    @Test fun `a labelled continue is rejected`() {
        assertRejected("fun f() { continue@foo }")
    }

    /**
     * `return@<label>` is a label, not an address. It used to be accepted
     * and silently dropped, which would have made Task 10's tail jump do
     * nothing. The ADDRESS form — `return@0xea31` — stays supported and is
     * pinned by `ReturnAtGrammarTest`.
     */
    @Test fun `a labelled return is rejected`() {
        assertRejected("fun f() { return@foo }")
    }

    @Test fun `an unlabelled break and continue stay supported`() {
        check("fun f() { while (true) { break } }")
        check("fun f() { while (true) { continue } }")
    }

    @Test fun `while and do while stay supported`() {
        check("fun f() { while (true) { } }")
        check("fun f() { var i: ubyte = 0; do { i = i + 1 } while (i < 5) }")
    }
}
