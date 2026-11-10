package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals

class TypeCheckerTest {
    /** The type of the first global declaration. */
    private fun typeOf(src: String): WolinType =
        typeChecker(src).globals[0].type

    /** The type of the first local declaration inside the first function. */
    private fun localTypeOf(src: String): WolinType =
        typeChecker(src).functions[0].body.filterIsInstance<Ast.LocalDecl>()
            .first().type

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `literal takes the expected type`() {
        assertEquals(Types.uword, typeOf("var i: uword = 0"))
        assertEquals(Types.ubyte, typeOf("var len: ubyte = 0"))
    }

    @Test fun `literal without an expected type uses the smallest type`() {
        assertEquals(Types.uword, typeOf("var x = 300"))
        assertEquals(Types.ubyte, typeOf("var x = 200"))
    }

    @Test fun `literal that does not fit is rejected`() {
        assertThrows<SemaException> { typeOf("var x: ubyte = 300") }
    }

    @Test fun `mixed operand types are rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var a: ubyte = 1; var b: uword = 2; var c = a + b }") }
    }

    @Test fun `arithmetic result type is the left operand type`() {
        assertEquals(Types.ubyte, localTypeOf("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a + b }"))
    }

    @Test fun `negative literal is a byte`() {
        assertEquals(Types.byte, typeOf("var x = -1"))
    }

    @Test fun `negative literal into an unsigned destination is rejected`() {
        assertThrows<SemaException> { typeOf("var x: ubyte = -1") }
    }

    @Test fun `comparison produces a bool`() {
        val program = typeChecker("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a == b }")
        val c = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last()
        assertEquals(Types.bool, c.type)
    }

    @Test fun `comparison of mixed operand types is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var a: ubyte = 1; var b: uword = 2; var c = a == b }")
        }
    }

    @Test fun `logical operators require bool operands`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a && b }")
        }
    }

    @Test fun `logical operators produce a bool`() {
        val program = typeChecker("fun f() { var a: bool = true; var b: bool = false; var c = a && b }")
        val c = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last()
        assertEquals(Types.bool, c.type)
    }

    @Test fun `bitwise operators accept integer operands`() {
        val program = typeChecker("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a & b }")
        val c = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last()
        assertEquals(Types.ubyte, c.type)
    }

    @Test fun `bitwise operators reject bool operands`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var a: bool = true; var b: bool = false; var c = a & b }")
        }
    }

    @Test fun `a declaration with neither type nor initialiser is rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x }") }
    }

    @Test fun `a val cannot be assigned to`() {
        assertThrows<SemaException> { typeChecker("fun f() { val x: ubyte = 1; x = 2 }") }
    }

    @Test fun `a var can be assigned to`() {
        typeChecker("fun f() { var x: ubyte = 1; x = 2 }")
    }

    @Test fun `a literal out of range is rejected`() {
        assertThrows<SemaException> { typeOf("var x = 100000") }
    }

    @Test fun `an unknown variable is rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = unknown }") }
    }

    @Test fun `a literal takes the declared type when it fits`() {
        assertEquals(Types.uword, typeOf("var x: uword = 1"))
        assertEquals(Types.ubyte, typeOf("var x: ubyte = 1"))
        assertEquals(Types.word, typeOf("var x: word = -1"))
    }

    @Test fun `an initialiser of a different type is rejected`() {
        assertThrows<SemaException> { typeChecker("var g: ubyte = 0; var x: uword = g") }
    }
}
