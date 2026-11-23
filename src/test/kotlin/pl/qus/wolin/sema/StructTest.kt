package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertTrue

/** Struct declarations, layout, and the rule that a struct is storage, not a value. */
class StructTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { typeChecker(src) }
        assertTrue(e.message!!.contains(fragment), "expected message to contain '$fragment', got: ${e.message}")
    }

    @Test fun `a struct with fields is accepted`() {
        typeChecker("struct Point { var x: ubyte; var y: uword }\nvar p: Point\nfun main() {}")
    }

    @Test fun `an array of structs with a count is accepted`() {
        typeChecker("struct Point { var x: ubyte; var y: ubyte }\nvar points: Point[ubyte, 4]\nfun main() {}")
    }

    @Test fun `an array of structs with a uword index and a count is accepted`() {
        typeChecker("struct Point { var x: ubyte; var y: ubyte }\nvar points: Point[uword, 4]\nfun main() {}")
    }

    @Test fun `a field read and write is accepted`() {
        typeChecker("struct Point { var x: ubyte }\nvar p: Point\nfun main() { p.x = 2; var y: ubyte = p.x; while (true) {} }")
    }

    @Test fun `an array element field is accepted`() {
        typeChecker("struct Point { var x: ubyte; var y: ubyte }\nvar points: Point[ubyte, 4]\nfun main() { points[1].y = 2; while (true) {} }")
    }

    @Test fun `an unknown struct type is rejected`() {
        assertRejected("var p: Missing\nfun main() {}", "Unknown struct Missing")
    }

    @Test fun `a duplicate field is rejected`() {
        assertRejected("struct P { var x: ubyte; var x: ubyte }\nfun main() {}", "Duplicate field x")
    }

    @Test fun `an empty struct is rejected`() {
        assertRejected("struct P { }\nfun main() {}", "must have at least one field")
    }

    @Test fun `an array field is rejected`() {
        assertRejected("struct P { var a: ubyte[ubyte] }\nfun main() {}", "not a scalar type")
    }

    @Test fun `a field initialiser is rejected`() {
        assertRejected("struct P { var x: ubyte = 1 }\nfun main() {}", "field initialisers are not supported")
    }

    @Test fun `assigning a struct value is rejected`() {
        assertRejected("struct P { var x: ubyte }\nvar a: P\nvar b: P = a\nfun main() {}", "struct value is not first class")
    }

    @Test fun `assigning an array element struct is rejected`() {
        assertRejected(
            "struct P { var x: ubyte }\nvar a: P[ubyte, 2]\nvar b: P\nfun main() { a[0] = b }",
            "Cannot assign a struct value",
        )
    }

    @Test fun `a method call on a struct is rejected`() {
        assertRejected("struct P { var x: ubyte }\nvar p: P\nfun main() { p.bump() }", "has no method bump")
    }

    @Test fun `a struct array without a count is sized by its index domain`() {
        typeChecker("struct P { var x: ubyte }\nvar a: P[ubyte]\nfun main() {}")
    }

    @Test fun `a uword-indexed struct array without a count is rejected`() {
        assertRejected("struct P { var x: uword }\nvar a: P[uword]\nfun main() {}", "does not fit in the 64 KiB address space")
    }

    @Test fun `arrays of different structs are not assignable`() {
        assertRejected(
            "struct A { var x: ubyte }\n" +
                "struct B { var x: ubyte }\n" +
                "var a: A[ubyte, 2]\n" +
                "var b: B[ubyte, 2] = a\n" +
                "fun main() {}",
            "Attempt reassign",
        )
    }

    @Test fun `field compound assignment and increment are accepted`() {
        typeChecker(
            "struct P { var x: ubyte; var y: uword }\n" +
                "var p: P\n" +
                "fun main() { p.x++; p.y += 2; while (true) {} }",
        )
    }
}
