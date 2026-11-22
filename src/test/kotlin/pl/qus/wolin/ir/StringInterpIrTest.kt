package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompiledFile
import pl.qus.wolin.driver.DriverModule
import pl.qus.wolin.it.emitIr
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The IR shape of a template: one static buffer per site, one helper call per part. */
class StringInterpIrTest {

    private fun ir(src: String): String = emitIr(src)

    @Test fun `a template gets a 256-byte private buffer`() {
        val text = ir("""fun f(n: ubyte) { val s = "n=${'$'}{n}" }""")
        assertTrue(text.contains("@__wolin_interp_0 = private global [256 x i8] zeroinitializer"), text)
    }

    @Test fun `literal and integer parts call their helpers`() {
        val text = ir("""fun f(n: ubyte) { val s = "n=${'$'}{n}" }""")
        assertTrue(text.contains("call i16 @__wolin_interp_append_string"), text)
        assertTrue(text.contains("call i16 @__wolin_interp_append_ubyte"), text)
        assertTrue(text.contains("declare i16 @__wolin_interp_append_string(ptr, i16, ptr)"), text)
        assertTrue(text.contains("declare i16 @__wolin_interp_append_ubyte(ptr, i16, i8)"), text)
    }

    @Test fun `the result is NUL-terminated after the last part`() {
        val text = ir("""fun f(n: ubyte) { val s = "n=${'$'}{n}" }""")
        assertTrue(text.contains("store i8 0, ptr"), text)
    }

    @Test fun `two template sites get distinct buffers`() {
        val text = ir(
            """
            fun f(n: ubyte) {
                val a = "a=${'$'}{n}"
                val b = "b=${'$'}{n}"
            }
            """.trimIndent(),
        )
        assertTrue(text.contains("@__wolin_interp_0"), text)
        assertTrue(text.contains("@__wolin_interp_1"), text)
    }

    @Test fun `a literal-only string has no interpolation buffer`() {
        val text = ir("""fun f() { val s = "abc" }""")
        assertFalse(text.contains("__wolin_interp"), text)
    }

    @Test fun `every scalar helper is selected by the part type`() {
        val text = ir(
            """
            fun f(a: byte, b: uword, c: word, d: bool, e: string) {
                val s = "${'$'}{a}${'$'}{b}${'$'}{c}${'$'}{d}${'$'}{e}"
            }
            """.trimIndent(),
        )
        assertTrue(text.contains("__wolin_interp_append_byte"), text)
        assertTrue(text.contains("__wolin_interp_append_uword"), text)
        assertTrue(text.contains("__wolin_interp_append_word"), text)
        assertTrue(text.contains("__wolin_interp_append_bool"), text)
        assertTrue(text.contains("__wolin_interp_append_string"), text)
    }

    @Test fun `no C library call is emitted`() {
        val text = ir("""fun f(n: ubyte) { val s = "n=${'$'}{n}" }""")
        assertFalse(text.contains("malloc"), text)
        assertFalse(text.contains("printf"), text)
        assertFalse(text.contains("snprintf"), text)
    }

    @Test fun `a float part marks the module`() {
        val program = pl.qus.wolin.driver.ProgramBuilder().build(
            listOf(pl.qus.wolin.driver.SourceFile("t.wtk", """fun f(x: float) { val s = "${'$'}{x}" }""")),
        )
        val scope = program.files[0]
        val emitter = ModuleEmitter()
        emitter.emitFile(
            pl.qus.wolin.ast.Ast.Program(
                globals = scope.globals,
                functions = scope.functions,
                imports = scope.imports,
                resolved = program.resolved,
                packageName = scope.packageName,
            ),
            scope.mangledPrefix,
        )
        assertTrue(emitter.usesFloatInterpolation)
        assertTrue(emitter.usedInterpHelpers.contains("__wolin_interp_append_float"))
    }

    @Test fun `the driver emits the float formatter only when a float is used`() {
        val file = CompiledFile(
            "t.wtk",
            "pkg.t",
            "define void @\"pkg.t.main\"() {\nentry:\n  ret void\n}",
            null,
            usedInterpHelpers = setOf("__wolin_interp_append_float"),
            usesFloatInterp = true,
        )
        val text = DriverModule(IrBuilder()).emit(listOf(file))
        assertTrue(text.contains("@__wolin_interp_float_digits"), text)
        assertTrue(text.contains("define i16 @__wolin_interp_append_float"), text)
        assertTrue(text.contains("llvm.fabs.f32"), text)
        assertFalse(text.contains("snprintf"), text)
        assertFalse(text.contains("printf"), text)
        assertFalse(text.contains("malloc"), text)
    }

    @Test fun `the driver emits no helper for a program without templates`() {
        val file = CompiledFile("t.wtk", "pkg.t", "define void @\"pkg.t.main\"() {\nentry:\n  ret void\n}", null)
        val text = DriverModule(IrBuilder()).emit(listOf(file))
        assertFalse(text.contains("__wolin_interp_append"), text)
    }

    @Test fun `the driver emits requested helpers and their dependencies`() {
        val file = CompiledFile(
            "t.wtk",
            "pkg.t",
            "define void @\"pkg.t.main\"() {\nentry:\n  ret void\n}",
            null,
            usedInterpHelpers = setOf("__wolin_interp_append_ubyte"),
        )
        val text = DriverModule(IrBuilder()).emit(listOf(file))
        assertTrue(text.contains("define i16 @__wolin_interp_append_ubyte"), text)
        assertTrue(text.contains("define i16 @__wolin_interp_append_uword"), text)
        assertTrue(text.contains("define i16 @__wolin_interp_append_char"), text)
        assertFalse(text.contains("__wolin_interp_append_bool"), text)
    }
}
