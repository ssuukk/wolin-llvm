package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.driver.ProgramBuilder
import pl.qus.wolin.driver.SourceFile
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertTrue

/** Static traits: method conformance, `this`, and receiver calls. */
class TraitTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { typeChecker(src) }
        assertTrue(e.message!!.contains(fragment), "expected message to contain '$fragment', got: ${e.message}")
    }

    @Test fun `a trait and its implementation are accepted`() {
        typeChecker(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte; var h: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return this.w.toUword() * this.h.toUword() } }\n" +
                "fun main() {}"
        )
    }

    @Test fun `an inherent impl is accepted`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P { fun bump() { this.x++ } }\n" +
                "fun main() { var p: P; p.bump(); while (true) {} }"
        )
    }

    @Test fun `a method with arguments is accepted`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P { fun add(n: ubyte): ubyte { this.x += n; return this.x } }\n" +
                "var p: P\n" +
                "fun main() { p.add(2); while (true) {} }"
        )
    }

    @Test fun `a method can call another method on this`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P {\n" +
                "    fun one(): ubyte { return 1 }\n" +
                "    fun two(): ubyte { return this.one() }\n" +
                "}\n" +
                "fun main() {}"
        )
    }

    @Test fun `a default method is used when the impl omits it`() {
        typeChecker(
            "trait Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { }\n" +
                "var r: R\n" +
                "fun main() { r.area(); while (true) {} }"
        )
    }

    @Test fun `a default method is overridden by the impl`() {
        typeChecker(
            "trait Shape { fun area(): uword { return 1 } }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return 2 } }\n" +
                "var r: R\n" +
                "fun main() { r.area(); while (true) {} }"
        )
    }

    @Test fun `a method is callable on an array element`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P { fun bump() { this.x++ } }\n" +
                "var ps: P[ubyte, 4]\n" +
                "fun main() { ps[2].bump(); while (true) {} }"
        )
    }

    @Test fun `a method is callable on a local struct`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P { fun bump() { this.x++ } }\n" +
                "fun main() { var p: P; p.bump(); while (true) {} }"
        )
    }

    @Test fun `a method is callable on a fixed-address struct`() {
        typeChecker(
            "struct P { var x: ubyte }\n" +
                "impl P { fun bump() { this.x++ } }\n" +
                "var p: P^0x3000\n" +
                "fun main() { p.bump(); while (true) {} }"
        )
    }

    @Test fun `a default method can call another trait method`() {
        typeChecker(
            "trait T { fun a(): ubyte { return this.b() } fun b(): ubyte }\n" +
                "struct R { var x: ubyte }\n" +
                "impl R : T { fun b(): ubyte { return this.x } }\n" +
                "var r: R\n" +
                "fun main() { r.a(); while (true) {} }"
        )
    }

    @Test fun `a missing trait method is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { }\n" +
                "fun main() {}",
            "missing method area"
        )
    }

    @Test fun `a trait method with the wrong return type is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): ubyte { return this.w } }\n" +
                "fun main() {}",
            "does not match trait Shape"
        )
    }

    @Test fun `an extra method in a trait impl is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return 1 } fun extra() { } }\n" +
                "fun main() {}",
            "does not declare method extra"
        )
    }

    @Test fun `two impls of the same trait are rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return 1 } }\n" +
                "impl R : Shape { fun area(): uword { return 2 } }\n" +
                "fun main() {}",
            "Duplicate impl of trait Shape"
        )
    }

    @Test fun `a duplicate method name across impls is rejected`() {
        assertRejected(
            "struct R { var w: ubyte }\n" +
                "impl R { fun area(): uword { return 1 } }\n" +
                "impl R { fun area(): uword { return 2 } }\n" +
                "fun main() {}",
            "Duplicate method area"
        )
    }

    @Test fun `a duplicate method name in one trait impl is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape {\n" +
                "    fun area(): uword { return 1 }\n" +
                "    fun area(): uword { return 2 }\n" +
                "}\n" +
                "fun main() {}",
            "Duplicate method area"
        )
    }

    @Test fun `a method name that collides with a field is rejected`() {
        assertRejected(
            "struct R { var w: ubyte }\n" +
                "impl R { fun w(): ubyte { return 1 } }\n" +
                "fun main() {}",
            "collides with a field"
        )
    }

    @Test fun `an unknown method is rejected`() {
        assertRejected(
            "struct R { var w: ubyte }\n" +
                "var r: R\n" +
                "fun main() { r.nope(); while (true) {} }",
            "has no method nope"
        )
    }

    @Test fun `this outside an impl is rejected`() {
        assertRejected(
            "struct R { var w: ubyte }\n" +
                "fun main() { this.w = 1 }",
            "this is only available"
        )
    }

    @Test fun `a trait name is a type`() {
        typeChecker(
            "trait Shape { fun area(): uword }\n" +
                "var s: Shape\n" +
                "fun main() {}"
        )
    }

    @Test fun `an impl method without a body is rejected`() {
        assertRejected(
            "struct R { var w: ubyte }\n" +
                "impl R { fun area(): uword }\n" +
                "fun main() {}",
            "needs a body"
        )
    }

    @Test fun `this is not available in a function literal`() {
        assertRejected(
            "var f: ()->ubyte\n" +
                "struct R { var w: ubyte }\n" +
                "impl R { fun m() { f = { this.w } } }\n" +
                "fun main() {}",
            "this is only available"
        )
    }

    @Test fun `a trait from another package is imported for an impl`() {
        ProgramBuilder().build(
            listOf(
                SourceFile("lib.wtk", "package lib\ntrait Shape { fun area(): uword }"),
                SourceFile(
                    "app.wtk",
                    "package app\nimport lib.Shape\n" +
                        "struct R { var w: ubyte }\n" +
                        "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                        "fun main() {}",
                ),
            )
        )
    }

    @Test fun `an impl in another file than its struct is rejected`() {
        val e = assertThrows<SemaException> {
            ProgramBuilder().build(
                listOf(
                    SourceFile("lib.wtk", "package lib\nstruct R { var w: ubyte }"),
                    SourceFile(
                        "app.wtk",
                        "package app\nimport lib.R\n" +
                            "impl R { fun area(): ubyte { return this.w } }\n" +
                            "fun main() {}",
                    ),
                )
            )
        }
        assertTrue(e.message!!.contains("same file as the struct"), e.message)
    }

    @Test fun `a dyn trait local dispatches through a call`() {
        typeChecker(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "var r: R\n" +
                "fun main() { var s: Shape = r; var x: uword = s.area(); while (true) {} }"
        )
    }

    @Test fun `a dyn trait array and parameter are accepted`() {
        typeChecker(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "var shapes: Shape[ubyte, 2]\n" +
                "fun use(s: Shape): uword { return s.area() }\n" +
                "fun main() { while (true) {} }"
        )
    }

    @Test fun `assigning a struct that does not implement the trait is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct A { var a: ubyte }\n" +
                "struct R { var w: ubyte }\n" +
                "impl A : Shape { fun area(): uword { return this.a.toUword() } }\n" +
                "var r: R\n" +
                "fun main() { var s: Shape = r; while (true) {} }",
            "does not implement trait",
        )
    }

    @Test fun `a trait return type is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "fun make(): Shape { }",
            "trait value cannot be returned",
        )
    }

    @Test fun `a trait field is rejected`() {
        assertRejected(
            "trait Shape { fun area(): uword }\n" +
                "struct Holder { var s: Shape }",
            "not a scalar type",
        )
    }
}
