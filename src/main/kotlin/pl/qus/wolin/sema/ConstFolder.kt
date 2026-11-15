package pl.qus.wolin.sema

import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.BoolLit
import pl.qus.wolin.ast.CharLit
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.IntLit
import pl.qus.wolin.ast.NullLit
import pl.qus.wolin.ast.RealLit
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef

/**
 * Folds a global initialiser to its compile-time [ConstValue], or returns
 * null when it is not a constant expression:
 *
 * - a literal (integer, character, real, boolean, `null`);
 * - a reference to a global checked earlier (its constant), or to a
 *   function (whose address the init function computes at startup);
 * - a unary or binary operation over constants, applied in the type's width
 *   exactly as the runtime instructions wrap.
 *
 * Division or remainder by zero folds to null: the value is not a
 * well-defined constant, so it is a compile error rather than a guess.
 */
object ConstFolder {

    fun fold(
        expr: Expr,
        constSoFar: Map<String, ConstValue>,
        functionNames: Map<String, String>,
    ): ConstValue? = when (expr) {
        is IntLit -> ConstValue.IntValue(expr.value, expr.type)
        is CharLit -> ConstValue.IntValue(expr.value.toLong(), expr.type)
        is RealLit -> const(expr.value)
        is BoolLit -> ConstValue.BoolValue(expr.value)
        is NullLit -> ConstValue.NullValue
        is VarRef -> when {
            functionNames.containsKey(expr.name) ->
                ConstValue.FunctionValue(expr.name, functionNames.getValue(expr.name))
            else -> constSoFar[expr.name]
        }
        is UnaryOp -> foldUnary(expr, constSoFar, functionNames)
        is BinaryOp -> foldBinary(expr, constSoFar, functionNames)
        else -> null
    }

    /** A float constant, rejecting the non-finite values LLVM cannot spell. */
    private fun const(value: Double): ConstValue? =
        if (value.isFinite()) ConstValue.FloatValue(value) else null

    private fun foldUnary(
        e: UnaryOp,
        constSoFar: Map<String, ConstValue>,
        functionNames: Map<String, String>,
    ): ConstValue? {
        val v = fold(e.operand, constSoFar, functionNames) ?: return null
        return when {
            e.op == "-" || e.op == "+" -> when (v) {
                is ConstValue.IntValue ->
                    ConstValue.IntValue(if (e.op == "-") -v.bits else v.bits, e.type)
                is ConstValue.FloatValue ->
                    const(if (e.op == "-") -v.value else v.value)
                else -> null
            }
            else -> null
        }
    }

    private fun foldBinary(
        e: BinaryOp,
        constSoFar: Map<String, ConstValue>,
        functionNames: Map<String, String>,
    ): ConstValue? {
        val l = fold(e.left, constSoFar, functionNames) ?: return null
        val r = fold(e.right, constSoFar, functionNames) ?: return null
        return when {
            l is ConstValue.IntValue && r is ConstValue.IntValue ->
                foldInt(e.op, l, r, e.type)
            l is ConstValue.FloatValue && r is ConstValue.FloatValue -> foldFloat(e.op, l, r)
            else -> null
        }
    }

    /**
     * Integer arithmetic in the result type's width: the operands are the
     * wrapped values the runtime sees, and the result wraps the same way the
     * `add`/`sdiv`/… instruction does.
     */
    private fun foldInt(op: String, l: ConstValue.IntValue, r: ConstValue.IntValue, resultType: WolinType): ConstValue? {
        val lw = mask(l.bits, l.type)
        val rw = mask(r.bits, r.type)
        val ls = if (isSigned(l.type)) signedView(lw, l.type) else lw
        val rs = if (isSigned(r.type)) signedView(rw, r.type) else rw
        val result = when (op) {
            "+" -> ls + rs
            "-" -> ls - rs
            "*" -> ls * rs
            "/" -> if (rs == 0L) return null else ls / rs
            "%" -> if (rs == 0L) return null else ls % rs
            "&" -> ls and rs
            "|" -> ls or rs
            "xor" -> ls xor rs
            else -> return null
        }
        return ConstValue.IntValue(mask(result, resultType), resultType)
    }

    private fun foldFloat(op: String, l: ConstValue.FloatValue, r: ConstValue.FloatValue): ConstValue? = when (op) {
        "+" -> const(l.value + r.value)
        "-" -> const(l.value - r.value)
        "*" -> const(l.value * r.value)
        "/" -> const(l.value / r.value)
        "%" -> const(l.value % r.value)
        else -> null
    }

    /** The bits of [v] masked to the width of [t]. */
    private fun mask(v: Long, t: WolinType): Long =
        v and ((1L shl (if (width(t) == 16) 16 else 8)) - 1)

    private fun width(t: WolinType): Int =
        if (t.kind == TypeKind.UWORD || t.kind == TypeKind.WORD) 16 else 8

    private fun isSigned(t: WolinType): Boolean =
        t.kind == TypeKind.BYTE || t.kind == TypeKind.WORD

    /** The signed view of [bits] of [t]'s width. */
    private fun signedView(bits: Long, t: WolinType): Long {
        val max = 1L shl width(t)
        return if (bits >= max / 2) bits - max else bits
    }
}
