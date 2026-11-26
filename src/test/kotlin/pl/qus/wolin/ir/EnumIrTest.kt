package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.it.emitIr
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Enum entries are constants, not storage. */
class EnumIrTest {

    @Test fun `an anonymous entry is inlined and has no storage`() {
        val ir = emitIr("""
            enum { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: ubyte = RUNNING
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("store i8 5"), ir)
        assertFalse(ir.contains("RUNNING"), ir)
        assertFalse(ir.contains("ERROR"), ir)
    }

    @Test fun `a named entry is inlined and has no storage`() {
        val ir = emitIr("""
            enum Status { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: Status = Status.RUNNING
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("store i8 5"), ir)
        assertFalse(ir.contains("RUNNING"), ir)
        assertFalse(ir.contains("Status"), ir)
    }

    @Test fun `an entry initialises a global at compile time`() {
        val ir = emitIr("""
            enum Status { RUNNING = 5 }
            var state: ubyte = Status.RUNNING
            fun main() {
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("global i8 5"), ir)
    }
}
