package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Named enums: `Status.PENDING`, and `Status` as a ubyte alias. */
class NamedEnumTest {

    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { check(src) }
        assertTrue(e.message!!.contains(fragment), "expected '$fragment', got: ${e.message}")
    }

    @Test fun `a named entry is a ubyte constant`() {
        check("enum Status { PENDING, RUNNING = 5 }\nfun main() { var s: ubyte = Status.RUNNING }")
    }

    @Test fun `the enum name is a ubyte type alias`() {
        check(
            "enum Status { PENDING }\n" +
                "fun main() { var s: Status = Status.PENDING; var u: ubyte = s; s = 7 }"
        )
    }

    @Test fun `entries work in a when`() {
        check(
            "enum Status { PENDING, RUNNING }\n" +
                "fun main() { var s: Status = Status.PENDING; " +
                "when (s) { Status.PENDING -> { }; Status.RUNNING -> { } } }"
        )
    }

    @Test fun `an entry initialises a global constant`() {
        val p = check("enum Status { RUNNING = 5 }\nvar s: ubyte = Status.RUNNING\nfun main() {}")
        val bits = (p.globals.single { it.name == "s" }.constValue as ConstValue.IntValue).bits
        assertEquals(5L, bits)
    }

    @Test fun `an enum type works on a global`() {
        val p = check("enum Status { IDLE = 0, READY = 1 }\nvar phase: Status = Status.READY\nfun main() {}")
        val global = p.globals.single { it.name == "phase" }
        assertEquals(TypeKind.UBYTE, global.type.kind)
        assertEquals(1L, (global.constValue as ConstValue.IntValue).bits)
    }

    @Test fun `an enum name that is also a struct is rejected`() {
        assertRejected(
            "struct Status { var x: ubyte }\nenum Status { A }\nfun main() {}",
            "Type name Status is both an enum and a struct or trait",
        )
    }

    @Test fun `an unknown entry is rejected`() {
        assertRejected(
            "enum Status { A }\nfun main() { var s: ubyte = Status.B }",
            "Unknown enum entry B in Status",
        )
    }

    @Test fun `the enum name is not a value`() {
        assertRejected(
            "enum Status { A }\nfun main() { var s: ubyte = Status }",
            "Enum Status is a type, not a value",
        )
    }

    @Test fun `an entry cannot be assigned`() {
        assertRejected(
            "enum Status { A }\nfun main() { Status.A = 1 }",
            "Cannot assign to enum entry Status.A",
        )
    }

    @Test fun `a duplicate visible enum name is rejected`() {
        assertRejected("enum Status { A }\nenum Status { B }\nfun main() {}", "Duplicate enum Status")
    }

    @Test fun `a reserved enum name is rejected`() {
        assertRejected("enum ubyte { A }\nfun main() {}", "Enum name ubyte is reserved")
    }

    @Test fun `an entry of the wrong enum is rejected`() {
        assertRejected(
            "enum A { X }\nenum B { Y }\nfun main() { var s: ubyte = B.X }",
            "Unknown enum entry X in B",
        )
    }
}
