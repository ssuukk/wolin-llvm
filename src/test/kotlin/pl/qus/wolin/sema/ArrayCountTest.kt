package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertTrue

/**
 * Explicit array counts: `T[N]` uses a `ubyte` index, and
 * `T[indexType, N]` names both. Without a count, an array keeps the old
 * index-domain sizing.
 */
class ArrayCountTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { typeChecker(src) }
        assertTrue(e.message!!.contains(fragment), "expected message to contain '$fragment', got: ${e.message}")
    }

    @Test fun `an explicit count with the default index is accepted`() {
        typeChecker("var a: ubyte[4] = [1, 2, 3, 4]\nfun main() {}")
    }

    @Test fun `an explicit count with an index type is accepted`() {
        typeChecker("var a: ubyte[ubyte, 4] = [1, 2, 3, 4]\nfun main() {}")
    }

    @Test fun `a uword-indexed array with a count is accepted`() {
        typeChecker("var a: uword[uword, 4] = [0, 1, 2, 3]\nfun main() {}")
    }

    @Test fun `too many elements for the explicit count is rejected`() {
        assertRejected(
            "var a: ubyte[ubyte, 4] = [1, 2, 3, 4, 5]\nfun main() {}",
            "Too many array elements",
        )
    }

    @Test fun `a zero length is rejected`() {
        assertRejected("var a: ubyte[0]\nfun main() {}", "Array length must be at least 1")
    }

    @Test fun `a count larger than the index domain is rejected`() {
        assertRejected(
            "var a: ubyte[ubyte, 257]\nfun main() {}",
            "exceeds the ubyte index range",
        )
    }

    @Test fun `an array larger than the address space is rejected`() {
        assertRejected(
            "var a: uword[uword, 40000]\nfun main() {}",
            "does not fit in the 64 KiB address space",
        )
    }

    @Test fun `without a count the index domain still sizes the array`() {
        typeChecker("var a: ubyte[ubyte] = [1, 2, 3]\nfun main() {}")
    }
}
