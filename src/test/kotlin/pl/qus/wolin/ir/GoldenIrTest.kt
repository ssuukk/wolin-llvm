package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenIrTest {
    @ParameterizedTest
    @ValueSource(strings = ["add_ubyte", "global_const", "while_counter", "when_subject", "prefix_postfix", "array_init"])
    fun emitsExpectedIr(name: String) {
        val src = javaClass.getResource("/golden/$name.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        val expected = javaClass.getResource("/golden/$name.ll")!!.readText()
        assertEquals(expected.trim(), ir.trim())
    }

    @Test fun `a fixed address variable emits no storage`() {
        val src = javaClass.getResource("/golden/fixed_address_var.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertFalse(ir.contains("@border"), "fixed-address vars must not allocate storage:\n$ir")
        assertTrue(ir.contains("inttoptr (i64 53280 to i8*)"), ir)
    }

    @Test fun `a local fixed address aliases the memory`() {
        val src = """
            fun f(): ubyte {
                var out: ubyte^0x400 = 5
                out = 6
                var r: ubyte = out
                return r
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("inttoptr i64 1024 to i8*"), ir)
        assertTrue(ir.contains("store volatile i8"), ir)
        assertEquals(1, Regex("alloca").findAll(ir).count(), "only r takes a stack slot:\n$ir")
    }

    @Test fun `an else-only when followed by a statement compiles to valid IR`() {
        val src = """
            fun f(): ubyte {
                var r: ubyte = 0
                when {
                    else -> { r = 1 }
                }
                r += 1
                return r
            }

            fun g() {
                var r: ubyte = 0
                when {
                    else -> { r = 1 }
                }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        // the statement after the first when must have been emitted into a live block
        assertTrue(ir.contains("add i8"), "statement after the else-only when is missing:\n$ir")
        // every branch target must name a block that actually exists
        val defined = Regex("^(\\s*)([\\w.]+):\\s*$", RegexOption.MULTILINE)
            .findAll(ir).map { it.groupValues[2] }.toSet()
        val targets = Regex("label %([\\w.]+)").findAll(ir).map { it.groupValues[1] }.toSet()
        val missing = targets - defined
        assertTrue(missing.isEmpty(), "branches to undefined blocks: $missing\n$ir")
    }

    @Test fun `xor emits a bitwise xor instruction`() {
        val src = """
            fun f(): ubyte {
                var a: ubyte = 0x0F
                var b: ubyte = 0xF0
                var c = a xor b
                return c
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("xor i8"), ir)
    }

    @Test fun `a xor in a global initialiser folds to a constant`() {
        val src = """
            var c: ubyte = 0x0F xor 0xF0
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i8 255"), "the folded xor must be the bitwise result:\n$ir")
    }

    @Test fun `conversions emit the right extension instruction`() {
        val src = """
            fun f(): uword {
                var b: byte = -1
                var u: ubyte = 255
                var w: word = 300
                var x = b.toWord()
                var y = u.toUword()
                var z = w.toByte()
                return x.toUword() + y - z.toUbyte().toUword()
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("sext i8"), "sign extension missing:\n$ir")
        assertTrue(ir.contains("zext i8"), "zero extension missing:\n$ir")
        assertTrue(ir.contains("trunc i16"), "truncation missing:\n$ir")
    }

    @Test fun `a converted global initialiser folds to a constant`() {
        val src = """
            var b: byte = -1
            var w: word = b.toWord()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i16 -1"), "the folded global must be the sign extension:\n$ir")
    }

    @Test fun `a chained conversion folds to a constant`() {
        val src = """
            var b: byte = -1
            var u: uword = b.toUbyte().toUword()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i16 255"), "the folded chain must be the zero extension:\n$ir")
    }

    @Test fun `a narrowing conversion folds to a constant`() {
        val src = """
            var w: word = 300
            var b: byte = w.toByte()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i8 44"), "the folded narrowing must truncate:\n$ir")
    }

    @Test fun `a conversion around an if-expression keeps the branches' locals`() {
        val src = """
            fun f(c: bool): word {
                return (if (c) { var b: byte = -1; b } else { var d: byte = 0; d }).toWord()
            }
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("sext i8"), "the conversion must sign-extend:\n$ir")
    }

    // ---- labels -------------------------------------------------------------

    /** Every `br`/`condbr` target must name a block that actually exists. */
    private fun assertTargetsDefined(ir: String) {
        val defined = Regex("^(\\s*)([\\w.]+):\\s*$", RegexOption.MULTILINE)
            .findAll(ir).map { it.groupValues[2] }.toSet()
        val targets = Regex("label %([\\w.]+)").findAll(ir).map { it.groupValues[1] }.toSet()
        val missing = targets - defined
        assertTrue(missing.isEmpty(), "branches to undefined blocks: $missing\n$ir")
    }

    /** The label of the block that contains the first occurrence of [instr]. */
    private fun blockContaining(ir: String, instr: String): String {
        val idx = ir.indexOf(instr)
        require(idx >= 0) { "instruction not found: $instr\n$ir" }
        return Regex("^([\\w.]+):\\s*$", RegexOption.MULTILINE)
            .findAll(ir.substring(0, idx))
            .last()
            .groupValues[1]
    }

    @Test fun `a label emits a block that a forward jump targets`() {
        val src = """
            fun f() {
                while (true) {
                    if (true) break@out
                }
                out@
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^out:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the label's block is missing:\n$ir")
        assertTrue(ir.contains("br label %out"), "the break must target the label's block:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `consecutive labels fall through to each other`() {
        val src = """
            fun f() {
                a@
                b@
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^a:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the first label's block is missing:\n$ir")
        assertTrue(Regex("^b:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the second label's block is missing:\n$ir")
        assertTrue(ir.contains("br label %b"), "the first label must fall through to the second:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a label after a return still opens its block`() {
        val src = """
            fun f(): ubyte {
                if (true) break@x
                return 5
                x@
                return 6
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^x:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the dead label's block is missing:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `an interrupt handler with a labelled return keeps its attribute`() {
        val labelled = """
            interrupt fun h() {
                if (true) return@skip
                skip@
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(labelled, "t.wtk")
        assertTrue(
            ir.contains("attributes #0 = { \"interrupt\" }"),
            "a labelled return is in-function: the handler keeps its attribute:\n$ir",
        )

        val address = """
            interrupt fun h2() {
                return@0xea31
            }
        """.trimIndent()
        val ir2 = CompilerPipeline().compileToIr(address, "t.wtk")
        assertFalse(ir2.contains("attributes #0"), "an address-form return drops the attribute (today's rule):\n$ir2")
    }

    @Test fun `a label named like an internal block resolves`() {
        val src = """
            fun f() {
                loop@
                while (true) {
                    break@loop
                }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^loop:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the label's block is missing:\n$ir")
        assertTrue(ir.contains("br label %loop"), "break@loop must target the label's block:\n$ir")
        val cond = blockContaining(ir, "br i1")
        assertTrue(cond != "loop", "the loop's condition block must keep a different label:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `an unlabelled continue ignores a label at the top of the body`() {
        val src = """
            fun f() {
                while (true) {
                    top@
                    if (true) continue
                }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        val cond = blockContaining(ir, "br i1")
        assertTrue(cond != "top", "the label must not bind the loop:\n$ir")
        val thenLine = Regex("if\\.then:\\n(\\s+.*?)\\n").find(ir)!!.groupValues[1].trim()
        assertTrue(
            thenLine == "br label %$cond",
            "the unlabelled continue must target the condition block, got: $thenLine\n$ir",
        )
        assertTargetsDefined(ir)
    }

    @Test fun `a trailing label on a non-void function is the existing return error`() {
        val src = """
            fun f(): ubyte {
                x@
            }
        """.trimIndent()
        val e = assertFailsWith<IllegalStateException> { CompilerPipeline().compileToIr(src, "t.wtk") }
        assertTrue(e.message.orEmpty().contains("must return a value"), "the expected error is missing, got: ${e.message}")
    }

    // ---- phi predecessors -----------------------------------------------------

    @Test fun `a branch value produced after a loop keeps its phi predecessor`() {
        val src = """
            fun f(): ubyte {
                return if (true) { while (false) {}; 5 } else { 3 }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("[ 5, %end ]"), "the phi must name the loop's end block:\n$ir")
        assertFalse(ir.contains("[ 5, %if.then ]"), "the phi names a block that is not a predecessor:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a label in an if expression branch carries the value`() {
        val src = """
            fun f(): ubyte {
                return if (true) { p@; 5 } else { 3 }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^p:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the label's block is missing:\n$ir")
        assertTrue(ir.contains("[ 5, %p ]"), "the phi must name the label's block:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a label in a when expression branch carries the value`() {
        val src = """
            fun f(): ubyte {
                return when {
                    else -> { q@; 7 }
                }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^q:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the label's block is missing:\n$ir")
        assertTrue(ir.contains("[ 7, %q ]"), "the phi must name the label's block:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a branch leaving through a labelled jump has no value`() {
        val src = """
            fun f(): ubyte {
                return if (true) { break@p; 1 } else { 3 }
                p@
                return 9
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("ret i8 3"), "the live branch's value is used:\n$ir")
        assertFalse(ir.contains("phi"), "the jumping branch must not contribute a phi incoming:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a label in a declaration initialiser resolves`() {
        val src = """
            fun f(): ubyte {
                var x: ubyte = if (true) { y@; 5 } else { 3 }
                return x
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^y:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the label's block is missing:\n$ir")
        assertTrue(ir.contains("[ 5, %y ]"), "the phi must name the label's block:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a trailing label in an expression branch opens its block`() {
        val src = """
            fun f(): unit {
                return if (true) { break@p; p@ } else { }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(Regex("^p:\\s*$", RegexOption.MULTILINE).containsMatchIn(ir), "the trailing label's block is missing:\n$ir")
        assertTargetsDefined(ir)
    }

    @Test fun `a negated word literal emits a word subtraction`() {
        val src = """
            fun f(): bool {
                var w: word = 1
                return w == -1
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("sub i16 0, 1"), ir)
    }
}
