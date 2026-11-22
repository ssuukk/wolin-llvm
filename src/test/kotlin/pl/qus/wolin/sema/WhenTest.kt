package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals

/** The `when` type rules from the Task 4 design decisions (D2). */
class WhenTest {
    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `subject type mismatch is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte, b: bool) { when (x) { b -> { } } }")
        }
    }

    @Test fun `non-bool condition in condition form is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { when { x -> { } } }")
        }
    }

    @Test fun `else not last is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { when (x) { 1 -> { } else -> { } 2 -> { } } }")
        }
    }

    @Test fun `non-else entry after else is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(a: bool, b: bool) { when { a -> { } else -> { } b -> { } } }")
        }
    }

    @Test fun `in and is conditions are rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { when (x) { in 1..3 -> { } } }")
        }
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { when (x) { is ubyte -> { } } }")
        }
    }

    @Test fun `a when expression takes the taken branch's value type`() {
        // Consistent branch types: the value is that type.
        typeChecker("fun f(x: ubyte): ubyte { return when (x) { 1 -> 10 else -> 20 } }")
        // A float when expression is a float.
        typeChecker("fun f(x: ubyte): float { return when (x) { 1 -> 1.5 else -> 2.5 } }")
    }

    @Test fun `mismatched when expression branch types give no value`() {
        // Mismatched branch types (ubyte and bool) unify to unit, so the
        // expression carries no usable value: assigning it to a `ubyte` fails.
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { var r: ubyte = when (x) { 1 -> 10 else -> true } }")
        }
    }

    @Test fun `a when expression without else is rejected in value positions`() {
        val expected = "this when expression may not match any branch"
        val e = assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte): ubyte { return when (x) { 1 -> 10 } }")
        }
        assertEquals(expected, e.message)
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { var r = when (x) { 1 -> 10 } }")
        }
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { var r: ubyte = 0; r = when (x) { 1 -> 10 } }")
        }
        assertThrows<SemaException> {
            typeChecker("fun g(v: ubyte): ubyte { return v }\nfun f(x: ubyte): ubyte { return g(when (x) { 1 -> 10 }) }")
        }
    }

    @Test fun `a when statement without else is still allowed`() {
        typeChecker("fun f(x: ubyte) { when (x) { 1 -> { } } }")
    }

    @Test fun `an empty when expression is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f(x: ubyte) { var r = when (x) { } }")
        }
    }

    @Test fun `a when expression with else still works`() {
        typeChecker("fun f(x: ubyte): ubyte { return when (x) { 1 -> 10 else -> 20 } }")
        typeChecker("fun f(x: ubyte): ubyte { return when (x) { else -> 20 } }")
    }
}
