package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnAtTest {
    /**
     * The `return@` site is a tail jump: the code after it never runs.
     *
     * The jump target `$EA31` is the KERNAL's IRQ handler (the default
     * `$0314-$0315` vector target), not a screen-init routine. Jumping
     * there from ordinary code is a known footgun: the handler ends in
     * `RTI`, and with no IRQ return data on the stack the `RTI` pops
     * garbage, so the landing address is unpredictable — in this setup
     * it ends in BASIC, leaving a cleared screen with a lone `READY.`.
     * The jump into the ROM is exactly as the brief says it may.
     *
     * The observation window is `$0800`, not `$0400`: it is outside the
     * screen (which the ROM path rewrites) and below the program load
     * address `$0801`, so it keeps the value the program wrote.
     *
     * `return@` to an `RTI`-terminated routine is only sound inside an
     * `interrupt fun`, where the CPU has pushed the IRQ return data.
     */
    @Test fun `return at a fixed address jumps instead of returning`() {
        val bytes = runProgramAt0800("""
            fun main() {
                var out: ubyte^0x800 = 0
                out = 1
                return@0xea31
                out = 2
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
