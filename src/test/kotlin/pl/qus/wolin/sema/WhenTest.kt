package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade

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
}
