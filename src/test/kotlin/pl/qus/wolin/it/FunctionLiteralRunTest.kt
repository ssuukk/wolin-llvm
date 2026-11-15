package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A function literal is compiled to its own function; the value of the
 * literal is that function's address, so it is stored in a function-pointer
 * variable and called through it.
 */
class FunctionLiteralRunTest {
    @Test fun `a function literal is called through its pointer`() {
        val bytes = runProgram("""
            var suma: (ubyte,ubyte)->ubyte
            fun main() {
                suma = { a, b -> a + b }
                var r: ubyte = suma(2, 3)
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }
}
