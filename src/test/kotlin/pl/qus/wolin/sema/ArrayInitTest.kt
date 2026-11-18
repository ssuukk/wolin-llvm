package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrayInitTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { typeChecker(src) }
        assertTrue(e.message!!.contains(fragment), "expected message to contain '$fragment', got: ${e.message}")
    }

    // ---- accepted positions ------------------------------------------------

    @Test fun `global array from a list is accepted`() {
        typeChecker("var a: ubyte[ubyte] = [1, 2, 3]\nfun main() {}")
    }

    @Test fun `global fixed-address array from a list is accepted`() {
        typeChecker("var a: ubyte[ubyte]^0x3000 = [1, 2]\nfun main() {}")
    }

    @Test fun `local fixed-address array from a list is accepted`() {
        typeChecker("fun main() { var a: ubyte[ubyte]^0x3000 = [1, 2]; while (true) {} }")
    }

    @Test fun `uword elements are accepted`() {
        typeChecker("var w: uword[uword] = [0, 1024, 0x3000]\nfun main() {}")
    }

    @Test fun `empty list is accepted`() {
        typeChecker("var a: ubyte[ubyte] = []\nfun main() {}")
    }

    @Test fun `string initialiser on ubyte element array is accepted`() {
        typeChecker("var s: ubyte[ubyte] = \"hi\"\nfun main() {}")
    }

    @Test fun `string initialiser on ubyte uword-indexed array is accepted`() {
        typeChecker("var s: ubyte[uword] = \"hi\"\nfun main() {}")
    }

    // ---- rejected positions ------------------------------------------------

    @Test fun `array literal without a declared type is rejected`() {
        assertRejected("var x = [1, 2]\nfun main() {}", "Array literal needs an array type")
    }

    @Test fun `array literal in assignment position is rejected`() {
        assertRejected("fun main() { var a: ubyte[ubyte]^0x3000; a = [1, 2]; while (true) {} }", "Array literal can only initialise a declaration")
    }

    @Test fun `array literal in expression position is rejected`() {
        assertRejected("fun main() { var x: ubyte = [1, 2][0]; while (true) {} }", "Array literal can only initialise a declaration")
    }

    @Test fun `local non-fixed array initialiser is rejected`() {
        assertRejected("fun main() { var a: ubyte[ubyte] = [1, 2]; while (true) {} }", "local arrays need a fixed address in v1")
    }

    @Test fun `string initialiser on uword element array is rejected`() {
        assertRejected("var w: uword[uword] = \"hi\"\nfun main() {}", "String initialiser needs a ubyte element type")
    }

    // ---- count / domain ----------------------------------------------------

    @Test fun `too many elements for a ubyte-indexed array is rejected`() {
        val elems = (1..257).joinToString(", ")
        assertRejected("var a: ubyte[ubyte] = [$elems]\nfun main() {}", "Too many array elements")
    }

    @Test fun `a 256-element list on a ubyte index is accepted`() {
        val elems = (0..255).joinToString(", ")
        typeChecker("var a: ubyte[ubyte] = [$elems]\nfun main() {}")
    }

    @Test fun `a string of exactly 255 bytes plus NUL is accepted`() {
        val s = "x".repeat(255)
        typeChecker("var a: ubyte[ubyte] = \"$s\"\nfun main() {}")
    }

    @Test fun `a string of 256 bytes plus NUL is rejected`() {
        val s = "x".repeat(256)
        assertRejected("var a: ubyte[ubyte] = \"$s\"\nfun main() {}", "String initialiser too long")
    }

    // ---- element typing ----------------------------------------------------

    @Test fun `element that does not fit is rejected`() {
        assertRejected("var a: ubyte[ubyte] = [300]\nfun main() {}", "Value out of range")
    }

    @Test fun `element with the wrong type is rejected`() {
        assertRejected("fun f() {}\nvar a: ubyte[ubyte] = [f]\nfun main() {}", "global initialiser must be a constant expression")
    }

    @Test fun `global element referencing a later global is rejected`() {
        val src = "var a: ubyte[ubyte] = [b]\nvar b: ubyte = 7\nfun main() {}"
        assertRejected(src, "initialiser references a global that is not yet initialised")
    }

    // ---- varRefs walker ----------------------------------------------------

    @Test fun `an array initialiser that references a later global is rejected`() {
        val src = "var a: ubyte[ubyte] = [b]\nvar b: ubyte = 7\nfun main() {}"
        assertRejected(src, "not yet initialised")
    }
}
