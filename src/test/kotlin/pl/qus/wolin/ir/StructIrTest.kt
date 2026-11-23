package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertTrue

/** Packed struct blobs, array strides, and field addressing. */
class StructIrTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    @Test fun `a global struct is a byte blob`() {
        val out = ir("struct P { var x: ubyte; var y: uword }\nvar p: P\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.p\" = global [3 x i8] zeroinitializer"), out)
    }

    @Test fun `an array of structs is a nested byte blob`() {
        val out = ir("struct P { var x: ubyte; var y: ubyte }\nvar ps: P[ubyte, 4]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.ps\" = global [4 x [2 x i8]] zeroinitializer"), out)
    }

    @Test fun `a uword-indexed struct array uses the explicit count`() {
        val out = ir("struct P { var x: ubyte; var y: ubyte }\nvar ps: P[uword, 4]\nfun main() { ps[2].x = 1; while (true) {} }")
        assertTrue(out.contains("@\"t.ps\" = global [4 x [2 x i8]] zeroinitializer"), out)
        assertTrue(out.contains("getelementptr [2 x i8], ptr @t.ps, i16 2"), out)
    }

    @Test fun `a field write is a GEP into the blob`() {
        val out = ir("struct P { var x: ubyte; var y: uword }\nvar p: P\nfun main() { p.y = 300; while (true) {} }")
        assertTrue(out.contains("getelementptr i8, ptr @t.p, i16 1"), out)
        assertTrue(out.contains("store i16 300"), out)
    }

    @Test fun `an array element field is an element stride plus a field offset`() {
        val out = ir(
            "struct P { var x: ubyte; var y: ubyte }\n" +
                "var ps: P[ubyte, 4]\n" +
                "fun main() { ps[2].y = 7; while (true) {} }"
        )
        assertTrue(out.contains("getelementptr [2 x i8], ptr @t.ps, i16 2"), out)
        assertTrue(out.contains("getelementptr i8, ptr %"), out)
        assertTrue(out.contains("i16 1"), out)
    }

    @Test fun `a local struct is an alloca blob`() {
        val out = ir("struct P { var x: ubyte; var y: ubyte }\nfun main() { var p: P; p.x = 1; while (true) {} }")
        assertTrue(out.contains("alloca [2 x i8]"), out)
    }

    @Test fun `a fixed-address struct has no storage`() {
        val out = ir("struct P { var x: ubyte }\nvar p: P^0x3000\nfun main() { p.x = 1; while (true) {} }")
        assertTrue(out.contains("inttoptr i64 12288 to i8*"), out)
        assertTrue(out.contains("store volatile i8 1"), out)
    }

    @Test fun `a field compound assignment reads and writes the field`() {
        val out = ir("struct P { var y: uword }\nvar p: P\nfun main() { p.y += 2; while (true) {} }")
        assertTrue(out.contains("load i16, ptr %"), out)
        assertTrue(out.contains("add i16"), out)
        assertTrue(out.contains("store i16"), out)
    }
}
