package pl.qus.wolin.ir

import pl.qus.wolin.ast.FunctionLit
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.Param
import pl.qus.wolin.sema.Types

/**
 * Emits a function literal as a module-level function and returns a pointer
 * to it.
 *
 * The literal becomes its own function, named `lambda_function_<n>`; the
 * value of the literal in the enclosing code is that function's address, so
 * it can be stored in a function-pointer variable and called through it like
 * any other function.
 *
 * The literal is non-capturing: it may not refer to a variable of the
 * enclosing function, so it needs no closure.
 *
 * A module holds one function open at a time, so the literal's function
 * cannot be emitted while the enclosing one is open: [emit] collects the
 * literal and [emitCollected] writes the functions out once the enclosing
 * function is closed.
 */
class LambdaEmitter(
    private val b: IrBuilder,
    private val stmt: StmtEmitter,
    private val prefix: String,
) {
    private var counter = 0
    private val collected = mutableListOf<Pair<String, FunctionLit>>()

    /**
     * The address of [lit]'s function, which is emitted with the module.
     */
    fun emit(lit: FunctionLit, globals: List<GlobalDecl>): IrValue {
        val name = "${prefix}.lambda_function_$counter"
        counter++
        collected += name to lit
        return b.instr(IrType.Ptr, "ptrtoint i8* @$name to i64")
    }

    /** Emits the functions of the literals collected by [emit]. */
    fun emitCollected(globals: List<GlobalDecl>) {
        for ((name, lit) in collected) {
            emitFunction(name, lit, globals)
        }
    }

    /** Emits one literal's function under [name]. */
    private fun emitFunction(name: String, lit: FunctionLit, globals: List<GlobalDecl>) {
        val paramTypes = lit.paramTypes
        val params = lit.params.mapIndexed { index, param ->
            IrValue("%$param", TypeMapper.ir(paramTypes.getOrElse(index) { Types.unit }))
        }
        val retType = lit.type.retType ?: Types.unit
        b.startFunction(name, TypeMapper.ir(retType), params)
        val fn = pl.qus.wolin.ast.FunDecl(
            name = name,
            params = lit.params.mapIndexed { index, param ->
                Param(param, paramTypes.getOrElse(index) { Types.unit }, null)
            },
            retType = retType,
            body = lit.body,
            isInterrupt = false,
            fixedAddress = null,
            returnAt = emptyList(),
        )
        stmt.returnsLastExpression = true
        stmt.startFunction(prefix, params, globals, fn)
        stmt.returnsLastExpression = false
        b.endFunction()
    }
}
