package pl.qus.wolin.ir

import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.Convert
import pl.qus.wolin.ast.CpuRegister
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FixedAddress
import pl.qus.wolin.ast.FixedArray
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.Program
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.sema.ConstValue
import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.TypeKind

/**
 * Emits the per-file global init function, `__wolin_init_file_<mangled>`.
 *
 * Only globals whose initialiser references something else (an earlier
 * global, a function) need runtime code; a pure-literal initialiser is
 * static data in the global section. The stores run in source order, so a
 * global that references an earlier one sees its final value.
 *
 * The function is defined with the default (external) linkage: the driver
 * module is a separate input module that calls it, and a per-module LTO
 * compile would drop an `internal` definition before the cross-module call
 * could resolve. It is emitted even when it stores nothing, so the driver's
 * call list is uniform across files.
 */
class GlobalInitEmitter(private val b: IrBuilder) {

    /** Emits the init function into [b] and returns its name. */
    fun emitFile(program: Program, mangledName: String): String {
        val fnName = "__wolin_init_file_$mangledName"
        b.startFunction(fnName, IrType.Void, emptyList())
        for (g in program.globals) {
            if (g.type.kind == TypeKind.TRAIT) {
                if (g.init != null) storeTrait(g, program, mangledName)
                continue
            }
            val init = g.init
            if (init == null || g.constValue == null) continue
            if (g.location is FixedAddress) continue
            // A CPU flag variable has no storage: it is the register itself.
            if (g.location is CpuRegister) continue
            // A string global's pointer is static data, even when its folded
            // value came from an earlier global.
            if (g.type.kind == TypeKind.STRING) continue
            if (g.location is FixedArray) {
                val v = g.constValue
                if (v is ConstValue.ArrayValue && v.elements.isNotEmpty()) {
                    storeArray(g, v, mangledName)
                }
                continue
            }
            if (!referencesAnotherEntity(init)) continue
            store(g, mangledName)
        }
        b.ret(null)
        b.endFunction()
        return fnName
    }

    /**
     * A file-scope trait value initialised from a struct or from another
     * trait value. The cell is static storage; the two pointers are stored
     * by the init function.
     */
    private fun storeTrait(g: GlobalDecl, program: Program, mangledName: String) {
        val init = g.init ?: return
        val target = when (val loc = g.location) {
            is FixedAddress -> b.instr(
                IrType.Ptr,
                "inttoptr i64 ${loc.address} to i8*",
            )
            else -> IrValue("@\"$mangledName.${g.name}\"", IrType.Ptr)
        }
        val ref = init as? VarRef
            ?: throw IllegalArgumentException(
                "global trait initialiser must name a variable",
            )
        val source = program.resolved[ref] as? Symbol.DataSymbol
            ?: throw IllegalArgumentException("unresolved global trait initialiser")
        val prefix = source.prefix.ifEmpty { mangledName }
        val sourceCell = when (val loc = source.location) {
            is FixedAddress -> b.instr(
                IrType.Ptr,
                "inttoptr i64 ${loc.address} to i8*",
            )
            else -> IrValue("@\"$prefix.${ref.name}\"", IrType.Ptr)
        }
        when (init.type.kind) {
            TypeKind.STRUCT -> {
                val structName = init.type.structName
                    ?: throw IllegalArgumentException("struct value has no name")
                val traitName = g.type.traitName
                    ?: throw IllegalArgumentException("trait type has no name")
                val vt = program.vtables.firstOrNull {
                    it.structName == structName && it.traitName == traitName
                } ?: throw IllegalArgumentException(
                    "no vtable for $structName as $traitName",
                )
                b.instr(IrType.Void, "store ptr ${sourceCell.name}, ptr ${target.name}")
                val slot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${target.name}, i16 2",
                )
                val symbol = vtableSymbol(vt.prefix, vt.structName, vt.traitName)
                b.instr(IrType.Void, "store ptr @\"$symbol\", ptr ${slot.name}")
            }
            TypeKind.TRAIT -> {
                val data = b.instr(IrType.Ptr, "load ptr, ptr ${sourceCell.name}")
                val srcSlot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${sourceCell.name}, i16 2",
                )
                val vtable = b.instr(IrType.Ptr, "load ptr, ptr ${srcSlot.name}")
                b.instr(IrType.Void, "store ptr ${data.name}, ptr ${target.name}")
                val dstSlot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${target.name}, i16 2",
                )
                b.instr(IrType.Void, "store ptr ${vtable.name}, ptr ${dstSlot.name}")
            }
            else -> throw IllegalArgumentException(
                "global trait initialiser must be a struct or trait value",
            )
        }
    }

    private fun store(g: GlobalDecl, mangledName: String) {
        val target = "ptr @\"$mangledName.${g.name}\""
        when (val v = g.constValue) {
            is ConstValue.FunctionValue -> {
                // The function is emitted under the prefix of the file that
                // declares it, which is not necessarily this file's.
                val prefix = if (v.prefix.isEmpty()) mangledName else v.prefix
                val addr = b.instr(
                    IrType.I16,
                    "ptrtoint i8* @\"${prefix}.${v.name}\" to i16"
                )
                b.instr(IrType.Void, "store i16 ${addr.name}, $target")
            }
            else -> b.instr(IrType.Void, "store ${TypeMapper.ir(g.type).llvmName()} ${v!!.spelling}, $target")
        }
    }

    /**
     * A fixed-address global array: store each element to
     * `address + i × elementSize`. The stores are volatile, like every
     * other fixed-address store.
     */
    private fun storeArray(g: GlobalDecl, v: ConstValue.ArrayValue, mangledName: String) {
        val address = (g.location as FixedArray).address
        val ty = TypeMapper.ir(v.elementType)
        v.elements.forEachIndexed { i, elem ->
            val target = b.instr(
                IrType.Ptr,
                "inttoptr i64 ${address + i * if (ty == IrType.I16) 2 else 1} to ${ty.llvmName()}*"
            )
            b.instr(IrType.Void, "store volatile ${ty.llvmName()} ${elem.spelling}, ptr ${target.name}")
        }
    }
}

/**
 * True when the initialiser names an entity (a global or a function)
 * rather than being a pure literal over constants. Both the module emitter
 * (which decides static data versus zero-plus-store) and the init emitter
 * (which decides which stores to run) ask the same question of the same
 * expression.
 */
internal fun referencesAnotherEntity(e: Expr): Boolean = when (e) {
    is VarRef -> true
    is UnaryOp -> referencesAnotherEntity(e.operand)
    is BinaryOp -> referencesAnotherEntity(e.left) || referencesAnotherEntity(e.right)
    is Convert -> referencesAnotherEntity(e.value)
    else -> false
}
