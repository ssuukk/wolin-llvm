package pl.qus.wolin.ir

import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FixedAddress
import pl.qus.wolin.ast.FixedArray
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.Program
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.sema.ConstValue

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
            if (g.init == null || g.constValue == null) continue
            if (g.location is FixedAddress || g.location is FixedArray) continue
            if (!referencesAnotherEntity(g.init)) continue
            store(g, mangledName)
        }
        b.ret(null)
        b.endFunction()
        return fnName
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
    else -> false
}
