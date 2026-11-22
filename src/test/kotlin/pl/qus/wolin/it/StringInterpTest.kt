package pl.qus.wolin.it

import pl.qus.wolin.sema.PetsciiConverter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * String interpolation on the real 6502. The program copies each result to
 * a fixed-address byte array at $3000; the test reads the bytes back.
 *
 * Source lowercase letters become the PETSCII $41-$5A range, so the raw
 * memory bytes spell the expected text in uppercase ASCII.
 */
class StringInterpTest {

    private fun assertText(memory: ByteArray, offset: Int, expected: String) {
        val want = expected.toByteArray()
        for (i in want.indices) {
            assertEquals(
                want[i].toInt() and 0xFF,
                memory[offset + i].toInt() and 0xFF,
                "byte $i of \"$expected\"",
            )
        }
        assertEquals(0, memory[offset + want.size].toInt() and 0xFF, "missing NUL after \"$expected\"")
    }

    /** Compares against the PETSCII bytes a source literal with this text would have. */
    private fun assertSourceText(memory: ByteArray, offset: Int, sourceText: String) {
        val want = sourceText.codePoints().toArray()
            .map { PetsciiConverter.codePointToPetscii(it) }
        for (i in want.indices) {
            assertEquals(
                want[i].toInt() and 0xFF,
                memory[offset + i].toInt() and 0xFF,
                "byte $i of \"$sourceText\"",
            )
        }
        assertEquals(0, memory[offset + want.size].toInt() and 0xFF, "missing NUL after \"$sourceText\"")
    }

    @Test fun `every integer bool and string part renders`() {
        val memory = runProgramRange(
            """
            var out: ubyte[ubyte]^0xC000

            fun copyAt(s: string, at: ubyte) {
                var i: ubyte = 0
                while (true) {
                    out[at + i] = s[i]
                    if (s[i] == 0) break
                    i++
                }
            }

            fun main() {
                var n: ubyte = 255
                var sb: byte = -128
                var w: uword = 65535
                var sw: word = -32768
                var ok: bool = true
                var s: string = "hi"
                copyAt("u=${'$'}{n}", 0)
                copyAt("b=${'$'}{sb}", 16)
                copyAt("w=${'$'}{w}", 32)
                copyAt("d=${'$'}{sw}", 48)
                copyAt("q=${'$'}{ok}", 64)
                copyAt("s=${'$'}{s}", 80)
                while (true) {}
            }
            """.trimIndent(),
            0xC000,
            0xC07F,
        )
        assertText(memory, 0, "U=255")
        assertText(memory, 16, "B=-128")
        assertText(memory, 32, "W=65535")
        assertText(memory, 48, "D=-32768")
        assertText(memory, 64, "Q=TRUE")
        assertText(memory, 80, "S=HI")
    }

    @Test fun `a runtime string is capped at 255 bytes`() {
        val long = "a".repeat(300)
        val memory = runProgramRange(
            """
            var out: ubyte[ubyte]^0xC000

            fun main() {
                var long: string = "$long"
                var n: ubyte = 7
                var i: ubyte = 0
                var s: string = "${'$'}{long}${'$'}{n}"
                while (true) {
                    out[i] = s[i]
                    if (s[i] == 0) break
                    i++
                }
                while (true) {}
            }
            """.trimIndent(),
            0xC000,
            0xC0FF,
        )
        for (i in 0 until 255) {
            assertEquals(0x41, memory[i].toInt() and 0xFF, "byte $i")
        }
        assertEquals(0, memory[255].toInt() and 0xFF)
    }

    @Test fun `float parts use the documented notation`() {
        val memory = runProgramRange(
            """
            var out: ubyte[ubyte]^0x6000

            fun copyAt(s: string, at: ubyte) {
                var i: ubyte = 0
                while (true) {
                    out[at + i] = s[i]
                    if (s[i] == 0) break
                    i++
                }
            }

            fun main() {
                var a: float = 1.5
                var b: float = 1.0 - 3.25
                var c: float = 3.1415927
                var d: float = 1000000.0
                var e: float = 10000000.0
                var f: float = 1.0e-3
                var g: float = 1.0e-4
                var h: float = 1.0e-5
                var i: float = 1.0e-20
                var j: float = 9.999999
                var k: float = 1.0e-1
                var z: float = 1.0 - 1.0
                var nan: float = z / z
                var inf: float = a / z
                var ninf: float = z - inf
                var nz: float = z / (1.0 - 2.0)
                copyAt("a=${'$'}{a}", 0)
                copyAt("b=${'$'}{b}", 16)
                copyAt("c=${'$'}{c}", 32)
                copyAt("d=${'$'}{d}", 48)
                copyAt("e=${'$'}{e}", 64)
                copyAt("f=${'$'}{f}", 80)
                copyAt("g=${'$'}{g}", 96)
                copyAt("h=${'$'}{h}", 112)
                copyAt("i=${'$'}{i}", 128)
                copyAt("j=${'$'}{j}", 144)
                copyAt("k=${'$'}{k}", 160)
                copyAt("nan=${'$'}{nan}", 176)
                copyAt("inf=${'$'}{inf}", 192)
                copyAt("ninf=${'$'}{ninf}", 208)
                copyAt("nz=${'$'}{nz}", 224)
                while (true) {}
            }
            """.trimIndent(),
            0x6000,
            0x60FF,
            8_000L,
        )
        assertSourceText(memory, 0, "a=1.5")
        assertSourceText(memory, 16, "b=-2.25")
        assertSourceText(memory, 32, "c=3.141593")
        assertSourceText(memory, 48, "d=1000000.0")
        assertSourceText(memory, 64, "e=1.0E7")
        assertSourceText(memory, 80, "f=0.001")
        assertSourceText(memory, 96, "g=1.0E-4")
        assertSourceText(memory, 112, "h=1.0E-5")
        assertSourceText(memory, 128, "i=1.0E-20")
        assertSourceText(memory, 144, "j=9.999999")
        assertSourceText(memory, 160, "k=0.1")
        assertSourceText(memory, 176, "nan=NaN")
        assertSourceText(memory, 192, "inf=Infinity")
        assertSourceText(memory, 208, "ninf=-Infinity")
        assertSourceText(memory, 224, "nz=-0.0")
    }

    @Test fun `re-evaluating one site overwrites its previous result`() {
        val memory = runProgramRange(
            """
            var out: ubyte[ubyte]^0xC000

            fun f(n: ubyte): string {
                return "n=${'$'}{n}"
            }

            fun main() {
                var a: string = f(1)
                var b: string = f(2)
                var i: ubyte = 0
                while (true) {
                    out[i] = a[i]
                    if (a[i] == 0) break
                    i++
                }
                while (true) {}
            }
            """.trimIndent(),
            0xC000,
            0xC00F,
        )
        assertText(memory, 0, "N=2")
    }
}
