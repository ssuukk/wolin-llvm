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

    @Test fun `byte literals cover the full two's complement range`() {
        assertEquals(Types.byte, typeOf("var x: byte = -128"))
        assertEquals(Types.byte, typeOf("var x = -128"))
    }

    @Test fun `byte literals outside the range are rejected`() {
        assertThrows<SemaException> { typeOf("var x: byte = 128") }
        assertThrows<SemaException> { typeOf("var x: byte = -129") }
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

    @Test fun `a bit assignment with a mask of a different type is accepted`() {
        // The mask is not a value of the target's type; it only has to be an
        // integer, so it must not be rejected for a type mismatch.
        typeChecker("fun f() { var x: ubyte = 0; x := 0xFF }")
        typeChecker("fun f() { var x: ubyte = 0; x .= 0xFF }")
        typeChecker("fun f() { var x: ubyte = 0; x &= 0xFF }")
        typeChecker("fun f() { var x: ubyte = 0; x |= 1 }")
        typeChecker("var m: uword = 15; fun f() { var x: ubyte = 0; x := m }")
        typeChecker("var m: byte = 1; fun f() { var x: ubyte = 0; x &= m }")
    }

    @Test fun `a bit assignment with a non-integer mask is rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = 0; x := true }") }
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = 0; x &= true }") }
    }

    @Test fun `a bit assignment mask must fit the target's width`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = 0; x := 100000 }") }
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = 0; x := 0xFFFF }") }
    }

    @Test fun `a mask fits the width of a signed target`() {
        typeChecker("fun f() { var x: byte = 0; x := 0xFF }")
        typeChecker("fun f() { var x: word = 0; x := 0xFFFF }")
    }

    @Test fun `an arithmetic compound assignment with a literal is accepted`() {
        typeChecker("fun f() { var x: ubyte = 1; x += 1 }")
        typeChecker("fun f() { var x: ubyte = 1; x -= 1 }")
        typeChecker("fun f() { var x: ubyte = 1; x *= 2 }")
        typeChecker("fun f() { var x: ubyte = 1; x /= 2 }")
        typeChecker("fun f() { var x: ubyte = 1; x %= 2 }")
    }

    @Test fun `an arithmetic compound assignment does not promote`() {
        assertThrows<SemaException> {
            typeChecker("var b: uword = 2; fun f() { var x: ubyte = 1; x += b }")
        }
    }

    @Test fun `a plain assignment still requires the same type`() {
        assertThrows<SemaException> {
            typeChecker("var b: uword = 2; fun f() { var x: ubyte = 1; x = b }")
        }
    }

    @Test fun `an arithmetic compound assignment on a float target is accepted`() {
        typeChecker("fun f() { var x: float = 1.5; x += 1.5 }")
        typeChecker("fun f() { var x: float = 1.5; x -= 2.5 }")
        typeChecker("fun f() { var x: float = 1.5; x *= 2.0 }")
        typeChecker("fun f() { var x: float = 1.5; x /= 2.0 }")
    }

    @Test fun `an arithmetic compound assignment on a float target does not promote`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x: float = 1.5; x += 1 }") }
    }

    @Test fun `a negated mask is accepted when it fits the target's width`() {
        // -1 is the bit pattern 0xFF, which fits an ubyte.
        typeChecker("fun f() { var x: ubyte = 0; x := -1 }")
        typeChecker("fun f() { var x: ubyte = 0; x := -128 }")
    }

    @Test fun `a negated mask that does not fit the target's width is rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var x: ubyte = 0; x := -256 }") }
    }

    @Test fun `a negated mask does not narrow the target`() {
        // The mask form never changes the target's type: -1 is a bit pattern,
        // not a value of -1.
        val program = typeChecker("fun f() { var x: ubyte = 0; x := -1 }")
        val x = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().first()
        assertEquals(Types.ubyte, x.type)
    }

    @Test fun `xor takes the left operand type`() {
        fun typeOfLast(src: String): WolinType =
            typeChecker(src).functions[0].body.filterIsInstance<Ast.LocalDecl>().last().type
        assertEquals(Types.ubyte, typeOfLast("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a xor b }"))
        assertEquals(Types.word, typeOfLast("fun f() { var a: word = 1; var b: word = 2; var c = a xor b }"))
    }

    @Test fun `xor rejects mixed operand types`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var a: ubyte = 1; var b: uword = 2; var c = a xor b }")
        }
    }

    @Test fun `xor rejects bool operands`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var a: bool = true; var b: bool = false; var c = a xor b }")
        }
    }

    @Test fun `conversion takes the target type`() {
        fun typeOfLast(src: String): WolinType =
            typeChecker(src).functions[0].body.filterIsInstance<Ast.LocalDecl>().last().type
        assertEquals(Types.word, typeOfLast("fun f() { var b: byte = -1; var w = b.toWord() }"))
        assertEquals(Types.ubyte, typeOfLast("fun f() { var b: byte = -1; var u = b.toUbyte() }"))
        assertEquals(Types.uword, typeOfLast("fun f() { var u: ubyte = 255; var w = u.toUword() }"))
        assertEquals(Types.byte, typeOfLast("fun f() { var w: word = -1; var b = w.toByte() }"))
    }

    @Test fun `conversion rejects non-integer operands`() {
        assertThrows<SemaException> { typeChecker("fun f() { var f: bool = true; var x = f.toByte() }") }
        assertThrows<SemaException> { typeChecker("fun f() { var s: string = \"\"; var x = s.toByte() }") }
    }

    @Test fun `a conversion of a later global reports the ordering error`() {
        // The ordering error must beat the plain "unknown variable" the
        // reference check reports for the same name.
        val e = assertThrows<SemaException> {
            typeChecker("var w: word = b.toWord(); var b: byte = -1")
        }
        assertEquals("initialiser references a global that is not yet initialised", e.message)
    }

    @Test fun `a negated literal takes the other operand's type`() {
        val program = typeChecker("fun f() { var w: word = 1; var r = w == -1 }")
        val cmp = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last().init as Ast.BinaryOp
        val neg = cmp.right as Ast.UnaryOp
        assertEquals(Types.word, neg.type)
        assertEquals(Types.word, neg.operand.type)
    }

    @Test fun `a sign-prefixed literal on the left takes the right operand's type`() {
        val program = typeChecker("fun f() { var w: word = 1; var r = -1 == w }")
        val cmp = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last().init as Ast.BinaryOp
        assertEquals(Types.word, (cmp.left as Ast.UnaryOp).type)
    }

    @Test fun `a plus-prefixed literal takes the other operand's type`() {
        typeChecker("fun f() { var w: word = 1; var r = w == +1 }")
    }

    @Test fun `a sign-prefixed literal in a when condition takes the subject's type`() {
        typeChecker("fun f(): bool { var w: word = 1; return when (w) { -1 -> true else -> false } }")
    }

    @Test fun `a negated literal does not make an unsigned operand legal`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var u: uword = 1; var r = u == -1 }")
        }
    }

    @Test fun `a negated literal does not retype a variable operand`() {
        assertThrows<SemaException> {
            typeChecker("fun f() { var w: word = 1; var b: byte = 1; var r = w == b }")
        }
    }
}
