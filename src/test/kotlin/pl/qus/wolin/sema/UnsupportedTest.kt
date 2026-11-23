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

    @Test fun `external function modifier is rejected`() {
        assertRejected("external fun f() { }")
    }

    @Test fun `cc65 function modifier is rejected`() {
        assertRejected("cc65 fun f() { }")
    }

    @Test fun `suspend function modifier is rejected`() {
        assertRejected("suspend fun f() { }")
    }

    @Test fun `operator function modifier is rejected`() {
        assertRejected("operator fun f() { }")
    }

    @Test fun `infix function modifier is rejected`() {
        assertRejected("infix fun f() { }")
    }

    @Test fun `inline function modifier is rejected`() {
        assertRejected("inline fun f() { }")
    }

    @Test fun `tailrec function modifier is rejected`() {
        assertRejected("tailrec fun f() { }")
    }

    @Test fun `visibility function modifiers are rejected`() {
        assertRejected("public fun f() { }")
        assertRejected("private fun f() { }")
        assertRejected("internal fun f() { }")
        assertRejected("protected fun f() { }")
    }

    @Test fun `interrupt function modifier stays supported`() {
        check("interrupt fun f() { }")
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

    @Test fun `super is rejected`() {
        assertRejected("fun f() { val x = super.foo() }")
    }

    @Test fun `extension functions are rejected`() {
        assertRejected("fun ubyte.foo(): ubyte { return 1 }")
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

    @Test fun `an unlabelled break and continue stay supported`() {
        check("fun f() { while (true) { break } }")
        check("fun f() { while (true) { continue } }")
    }

    @Test fun `while and do while stay supported`() {
        check("fun f() { while (true) { } }")
        check("fun f() { var i: ubyte = 0; do { i = i + 1 } while (i < 5) }")
    }

    @Test fun `a non-xor infix call stays rejected`() {
        assertRejected("fun f() { var a: ubyte = 1; var b: ubyte = 2; val x = a and b }")
    }

    @Test fun `an unknown member call stays rejected`() {
        assertRejected("fun f() { var b: byte = 1; var x = b.other() }")
    }

    @Test fun `a conversion call with arguments stays rejected`() {
        assertRejected("fun f() { var b: byte = 1; var x = b.toWord(2) }")
    }

    @Test fun `a safe call on a conversion stays rejected`() {
        assertRejected("fun f(b: byte): word { return b?.toWord() }")
    }

    @Test fun `a conversion with a trailing lambda stays rejected`() {
        assertRejected("fun f(b: byte): word { return b.toWord { var q = 7 } }")
    }
}
