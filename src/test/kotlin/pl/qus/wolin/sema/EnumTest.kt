package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simple enum values and diagnostics, before named resolution. */
class EnumTest {

    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { check(src) }
        assertTrue(e.message!!.contains(fragment), "expected '$fragment', got: ${e.message}")
    }

    @Test fun `anonymous autonumber starts at zero and increments`() {
        val p = check("enum { A, B, C }\nfun main() {}")
        assertEquals(listOf(0, 1, 2), p.globals.map { it.enumValue })
    }

    @Test fun `an explicit value resets the counter`() {
        val p = check("enum { A, B = 5, C }\nfun main() {}")
        assertEquals(listOf(0, 5, 6), p.globals.map { it.enumValue })
    }

    @Test fun `a named enum builds its entries`() {
        val e = check("enum Status { A, B = 5 }\nfun main() {}").enums.single()
        assertEquals("Status", e.name)
        assertEquals(listOf("A" to 0, "B" to 5), e.entries.map { it.name to it.value })
    }

    @Test fun `a duplicate entry is rejected`() {
        assertRejected("enum { A, A }\nfun main() {}", "Duplicate enum entry A")
    }

    @Test fun `a value above 255 is rejected`() {
        assertRejected("enum { A = 256 }\nfun main() {}", "Enum value out of range")
    }

    @Test fun `autonumber past 255 is rejected`() {
        assertRejected("enum { A = 255, B }\nfun main() {}", "Enum value out of range")
    }

    @Test fun `a non-literal value is rejected`() {
        assertRejected("enum { A = 1 + 1 }\nfun main() {}", "Enum value must be an integer literal")
    }

    @Test fun `an anonymous entry cannot be assigned`() {
        assertRejected("enum { A }\nfun main() { A = 1 }", "Cannot assign to val A")
    }
}
