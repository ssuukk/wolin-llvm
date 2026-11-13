package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalCallTest {
    @Test fun `rom call pins arguments in their declared registers`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        ExternalCalls().emitCall(b, 0xffba, listOf(
            IrValue("%a", IrType.I8) to "CPU.A",
            IrValue("%y", IrType.I8) to "CPU.Y",
            IrValue("%x", IrType.I8) to "CPU.X",
        ))
        b.ret(null)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("jsr \$\$ffba"), ir)
        assertTrue(ir.contains("\"a,y,x\""), ir)
        assertFalse(ir.contains(": \"memory\""), "no clobber list may be emitted:\n$ir")
    }
}
