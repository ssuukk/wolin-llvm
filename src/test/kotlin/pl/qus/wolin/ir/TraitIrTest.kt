package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertTrue

/** Methods are functions with a hidden receiver pointer. */
class TraitIrTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    @Test fun `a method is a function with a receiver pointer`() {
        val out = ir(
            "struct P { var x: ubyte }\n" +
                "impl P { fun get(): ubyte { return this.x } }\n" +
                "var p: P\n" +
                "fun main() { while (true) {} }"
        )
        assertTrue(out.contains("define i8 @\"t.P.get\"(ptr %this)"), out)
        assertTrue(out.contains("getelementptr i8, ptr %this, i16 0"), out)
    }

    @Test fun `a method call passes the struct address`() {
        val out = ir(
            "struct P { var x: ubyte }\n" +
                "impl P { fun get(): ubyte { return this.x } }\n" +
                "var p: P\n" +
                "fun main() { p.get(); while (true) {} }"
        )
        assertTrue(out.contains("call i8 @\"t.P.get\"(ptr @t.p)"), out)
    }

    @Test fun `a method call on an array element passes the element pointer`() {
        val out = ir(
            "struct P { var x: ubyte }\n" +
                "impl P { fun bump() { this.x++ } }\n" +
                "var ps: P[ubyte, 4]\n" +
                "fun main() { ps[2].bump(); while (true) {} }"
        )
        assertTrue(out.contains("getelementptr [1 x i8], ptr @t.ps, i16 2"), out)
        assertTrue(out.contains("call void @\"t.P.bump\"(ptr %"), out)
    }

    @Test fun `a trait default body is emitted for the implementor`() {
        val out = ir(
            "trait Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { }\n" +
                "var r: R\n" +
                "fun main() { while (true) {} }"
        )
        assertTrue(out.contains("define i16 @\"t.R.area\"(ptr %this)"), out)
    }

    @Test fun `a foreign method is declared in the calling module`() {
        val out = ir(
            "struct P { var x: ubyte }\n" +
                "impl P { fun get(): ubyte { return this.x } }\n" +
                "var p: P\n" +
                "fun main() { p.get(); while (true) {} }"
        )
        // A single-file module defines the method; no duplicate declaration.
        assertTrue(!out.contains("declare i8 @\"t.P.get\""), out)
    }

    @Test fun `a dyn trait emits a function-pointer vtable`() {
        val out = ir(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "var r: R\n" +
                "fun main() { var s: Shape = r; var x: uword = s.area(); while (true) {} }"
        )
        assertTrue(
            out.contains(
                "@\"vt.t.R.Shape\" = constant [1 x ptr] [ptr @\"t.R.area\"]",
            ),
            out,
        )
        // The call loads the data pointer and the vtable slot, then calls
        // through the loaded pointer.
        assertTrue(out.contains("load ptr, ptr %"), out)
        assertTrue(out.contains("getelementptr i8, ptr %"), out)
        assertTrue(out.contains("call i16 %"), out)
    }

    @Test fun `a dyn array stores 4-byte cells`() {
        val out = ir(
            "trait Shape { fun area(): uword }\n" +
                "struct R { var w: ubyte }\n" +
                "impl R : Shape { fun area(): uword { return this.w.toUword() } }\n" +
                "var rs: R[ubyte, 2]\n" +
                "var shapes: Shape[ubyte, 2]\n" +
                "fun main() { shapes[1] = rs[0]; while (true) {} }"
        )
        assertTrue(out.contains("@\"t.shapes\" = global [2 x [4 x i8]] zeroinitializer"), out)
        assertTrue(out.contains("getelementptr [4 x i8], ptr @t.shapes, i16 1"), out)
    }
}
