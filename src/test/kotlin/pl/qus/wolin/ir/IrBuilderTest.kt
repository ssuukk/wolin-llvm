package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrBuilderTest {
    @Test fun `ssa names are deterministic and per function`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        val v1 = b.instr(IrType.I8, "add i8 %a, 1", listOf(IrValue("%a", IrType.I8)))
        val v2 = b.instr(IrType.I8, "add i8 %b, 1", listOf(IrValue("%b", IrType.I8)))
        b.endFunction()
        assertEquals(listOf("%v0", "%v1"), listOf(v1.name, v2.name))
    }

    @Test fun `every block gets a terminator`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.ret(IrValue("%v0", IrType.I32))
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("define i32 @f"))
        assertTrue(ir.contains("ret i32 %v0"))
    }

    @Test fun `a block without a terminator is rejected`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.addBlock("bb1")
        b.endFunction()
        assertThrows<IllegalStateException> { b.render() }
    }

    @Test fun `phi takes one incoming per predecessor`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.branch("merge")
        b.addBlock("merge")
        b.phi(IrType.I32, listOf(IrValue("%v0", IrType.I32) to "entry", IrValue("%v1", IrType.I32) to "else"))
        b.ret(IrValue("%v2", IrType.I32))
        b.endFunction()
        assertTrue(b.render().contains("phi i32 [ %v0, %entry ], [ %v1, %else ]"))
    }

    @Test fun `function parameters are rendered`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32, listOf(IrValue("%a", IrType.I8), IrValue("%b", IrType.I16)))
        b.ret(IrValue("%v0", IrType.I32))
        b.endFunction()
        assertTrue(b.render().contains("define i32 @f(i8 %a, i16 %b)"))
    }

    @Test fun `the entry block is opened automatically and blocks are labelled in order`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        assertEquals("entry", b.block())
        b.branch("bb1")
        b.addBlock("bb1")
        assertEquals("bb1", b.block())
        b.ret(null)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("entry:"), ir)
        assertTrue(ir.contains("bb1:"), ir)
    }

    @Test fun `a value names the constant it defines`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = b.value(IrType.I8)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("${v.name} = add i8 0, 0"), ir)
        assertTrue(ir.contains("ret i8 ${v.name}"), ir)
    }

    @Test fun `an alloca yields a pointer to a stack slot`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        val slot = b.alloca(IrType.I16)
        b.ret(null)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("${slot.name} = alloca i16"), ir)
        assertEquals(IrType.Ptr, slot.type)
    }

    @Test fun `calls render their result type and arguments`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.callVoid("puts", listOf(IrValue("%p", IrType.Ptr)))
        val r = b.call(IrType.I32, "helper", listOf(IrValue("%q", IrType.I32)))
        b.ret(r)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("call void @puts(ptr %p)"), ir)
        assertTrue(ir.contains("${r.name} = call i32 @helper(i32 %q)"), ir)
    }

    @Test fun `a conditional branch names both targets`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        b.condBranch(IrValue("%c", IrType.I1), "then", "else")
        b.addBlock("then")
        b.ret(null)
        b.addBlock("else")
        b.ret(null)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("br i1 %c, label %then, label %else"), ir)
    }

    @Test fun `value names restart in each function`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        val first = b.instr(IrType.I8, "add i8 %a, 1")
        b.endFunction()
        b.startFunction("g", IrType.Void)
        val second = b.instr(IrType.I8, "add i8 %b, 1")
        b.endFunction()
        assertEquals("%v0", first.name)
        assertEquals("%v0", second.name)
    }
}
