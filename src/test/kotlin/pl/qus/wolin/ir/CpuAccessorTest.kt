package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuAccessorTest {
    @Test fun `read A uses sta so no flag is touched`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readA(b)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("ldy #0"), ir)
        assertTrue(ir.contains("sta (\$0),y"), ir)
        assertFalse(ir.contains("pha"), ir)
    }

    @Test fun `flag read preserves A and P`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readFlag(b, 0x01)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        for (op in listOf("php", "pha", "pla", "tax", "plp")) assertTrue(ir.contains(op), "$op missing in:\n$ir")
        assertTrue(ir.contains("\"=x\"()"))
        // the backend rejects clobber lists
        assertFalse(ir.contains(": \"memory\""), "no clobber list may be emitted:\n$ir")
    }

    @Test fun `negative flag mask is bit 7`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        CpuAccessors.readFlag(b, 0x80)
        b.ret(IrValue("%v0", IrType.I8))
        b.endFunction()
        assertTrue(b.render().contains("and #128"))
    }

    @Test fun `readAxLow reads A via pha pla`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readAxLow(b)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("pha\\0Apla"), ir)
        assertTrue(ir.contains("\"=a\"()"), ir)
    }

    @Test fun `readAxHigh reads X via txa tax`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readAxHigh(b)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("pha\\0Atxa\\0Atax\\0Apla"), ir)
        assertTrue(ir.contains("\"=x\"()"), ir)
    }

}
