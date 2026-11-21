package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Labels are scoped per function: a `name@` statement defines a jump target
 * visible from every statement of the function, forward or backward, and a
 * labelled `break`/`continue`/`return` must name one. A function literal is
 * a separate function: its labels are its own, and the enclosing function's
 * labels stay visible after the literal.
 */
class LabelTest {
    private fun check(src: String) {
        val tree = ParserFacade(Diagnostics()).parse(src, "t.ktk")
        TypeChecker().check(AstBuilder().build(tree!!))
    }

    private fun assertRejected(src: String, message: String) {
        val e = assertFailsWith<SemaException>("should reject: $src") { check(src) }
        assertTrue(
            e.message!!.contains(message),
            "message should name the problem, got: ${e.message}",
        )
    }

    @Test fun `a labelled break continue and return to a defined label pass`() {
        check("fun f() { x@; break@x; continue@x; return@x }")
    }

    @Test fun `a forward reference passes`() {
        check("fun f() { while (true) { break@out }; out@ }")
    }

    @Test fun `a backward reference passes`() {
        check("fun f() { out@; while (true) { break@out } }")
    }

    @Test fun `a label in an if branch passes`() {
        check("fun f() { if (true) { x@ } }")
    }

    @Test fun `a label in a when entry passes`() {
        check("fun f() { when { else -> { x@ } } }")
    }

    @Test fun `a label in an if expression branch passes`() {
        check("fun f() { var x: ubyte = if (true) { y@; 5 } else { 3 } }")
    }

    @Test fun `an outer label stays visible after a function literal`() {
        check("fun f() { x@; val g: (ubyte, ubyte) -> ubyte = { a, b -> 0 }; break@x }")
    }

    @Test fun `an unknown label is rejected for break`() {
        assertRejected("fun f() { break@foo }", "Unknown label foo")
    }

    @Test fun `an unknown label is rejected for continue`() {
        assertRejected("fun f() { continue@foo }", "Unknown label foo")
    }

    @Test fun `an unknown label is rejected for return`() {
        assertRejected("fun f() { return@foo }", "Unknown label foo")
    }

    @Test fun `a duplicate label is rejected`() {
        assertRejected("fun f() { x@; x@ }", "Label x already defined")
    }

    @Test fun `a duplicate label in nested blocks is rejected`() {
        assertRejected("fun f() { if (true) { x@ }; x@ }", "Label x already defined")
    }

    @Test fun `an outer label is not visible inside a function literal`() {
        assertRejected(
            "fun f() { x@; val g: (ubyte, ubyte) -> ubyte = { a, b -> break@x; 0 } }",
            "Unknown label x",
        )
    }

    @Test fun `a literal's label is not visible outside`() {
        assertRejected(
            "fun f() { val g: (ubyte, ubyte) -> ubyte = { a, b -> x@; break@x; 0 }; break@x }",
            "Unknown label x",
        )
    }

    @Test fun `an if expression branch ending in a label is a unit branch`() {
        assertRejected(
            "fun f() { var x: ubyte = if (true) { y@ } else { 3 } }",
            "Attempt reassign ubyte unit",
        )
    }
}
