package pl.qus.wolin.ir

import kotlin.math.abs
import pl.qus.wolin.ast.ArrayAccess
import pl.qus.wolin.ast.Assign
import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.BoolLit
import pl.qus.wolin.ast.Call
import pl.qus.wolin.ast.CharLit
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FunctionLit
import pl.qus.wolin.ast.IfExpr
import pl.qus.wolin.ast.IntLit
import pl.qus.wolin.ast.NullLit
import pl.qus.wolin.ast.RealLit
import pl.qus.wolin.ast.StringLit
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.WolinType

/**
 * Where a variable lives.
 *
 * Locals and globals are memory: a [VarRef] always emits a fresh `load` from
 * the slot, so values that cross a merge need no per-variable phi. Fixed
 * addresses are not memory at all — every access goes through an `inttoptr`
 * of the constant address.
 */
sealed interface VarStorage {
    /** A function parameter: an SSA value, used directly. */
    data class Param(val value: IrValue) : VarStorage

    /** A file-scope variable: a module global, named [name]. */
    data class Global(val name: String) : VarStorage

    /** A fixed-address variable: the [address] itself, no storage. */
    data class Fixed(val address: Int) : VarStorage

    /** A local variable: the entry-block [ptr] of its `alloca`. */
    data class Local(val ptr: IrValue) : VarStorage
}

/**
 * A stack of name-to-[VarStorage] maps. An inner map shadows the outer ones,
 * so an inner `var x` hides the outer one until the block ends.
 */
class VarScope {
    private val levels = ArrayDeque<MutableMap<String, VarStorage>>()

    init { levels.addLast(mutableMapOf()) }

    fun reset() {
        levels.clear()
        levels.addLast(mutableMapOf())
    }

    fun push() { levels.addLast(mutableMapOf()) }
    fun pop() { levels.removeLast() }
    fun declare(name: String, storage: VarStorage) { levels.last()[name] = storage }

    fun lookup(name: String): VarStorage? {
        for (i in (levels.size - 1) downTo 0) {
            levels[i][name]?.let { return it }
        }
        return null
    }
}

/** Escapes one byte of an LLVM `c"…"` string literal. */
internal fun llvmEscapeByte(c: Int): String = when {
    c in 0x20..0x7e && c != '"'.code && c != '\\'.code -> c.toChar().toString()
    else -> "\\${"%02x".format(c)}"
}

/**
 * Emits expressions into the current function of [b].
 *
 * Literals are inline constants (see `IrValue`): they are not materialised
 * into SSA names. `RealLit` is the exception — a float constant lives in a
 * module-level `@floatConst_<n>` global, loaded through a `ptr`. String
 * literals work the same way, through `@str_<n>`; both are registered in
 * [constants], which the [ModuleEmitter] prints in the global section.
 */
class ExprEmitter(
    private val b: IrBuilder,
    private val stmt: StmtEmitter,
    private val constants: MutableList<String>,
    private val prefix: String,
    private val resolved: Map<VarRef, Symbol>,
) {
    private val floatConsts = LinkedHashMap<String, String>()
    private val stringConsts = LinkedHashMap<String, String>()

    fun emit(e: Expr): IrValue = when (e) {
        is IntLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is BoolLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is CharLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is NullLit -> IrValue("null", IrType.Ptr)
        is RealLit -> emitRealLit(e)
        is StringLit -> emitStringLit(e)
        is VarRef -> emitVarRef(e)
        is UnaryOp -> emitUnary(e)
        is BinaryOp -> emitBinary(e)
        is Assign -> emitAssign(e)
        is ArrayAccess -> emitArrayAccess(e)
        is Call -> emitCall(e)
        is IfExpr -> emitIfExpr(e)
        is FunctionLit -> throw error("Function literals are not supported in v1 IR emission")
    }

    // ---- literals ---------------------------------------------------------

    private fun emitRealLit(e: RealLit): IrValue {
        val spelling = e.value.toString()
        val name = floatConsts[spelling] ?: run {
            val global = "@floatConst_${floatConsts.size}"
            floatConsts[spelling] = global
            constants += "$global = private global float $spelling"
            global
        }
        return b.instr(IrType.Float, "load float, ptr $name")
    }

    private fun emitStringLit(e: StringLit): IrValue {
        val n = e.bytes.size
        val key = e.bytes.contentToString()
        val name = stringConsts[key] ?: run {
            val global = "@str_${stringConsts.size}"
            stringConsts[key] = global
            val chars = e.bytes.joinToString("") { llvmEscapeByte(it.toInt() and 0xFF) }
            constants += "$global = private unnamed_addr constant [${n + 1} x i8] c\"$chars\\00\""
            global
        }
        return b.instr(
            IrType.Ptr,
            "getelementptr inbounds [${n + 1} x i8], ptr $name, i32 0, i32 0"
        )
    }

    // ---- variables --------------------------------------------------------

    private fun emitVarRef(e: VarRef): IrValue {
        (resolved[e] as? Symbol.FunSymbol)?.let { symbol ->
            // A function name used as a value is its address, as a `uword`.
            return b.instr(IrType.I16, "ptrtoint i8* @\"${prefix}.${symbol.decl.name}\" to i16")
        }
        val ty = TypeMapper.ir(e.type)
        val addressed = e.type.kind == TypeKind.ARRAY || e.type.kind == TypeKind.STRING
        return when (val s = stmt.vars.lookup(e.name) ?: throw error("Unresolved variable ${e.name}")) {
            is VarStorage.Param -> s.value
            is VarStorage.Global ->
                if (addressed) IrValue("@${s.name}", IrType.Ptr)
                else b.instr(ty, "load ${ty.llvmName()}, ptr @${s.name}")
            is VarStorage.Local ->
                if (addressed) s.ptr
                else b.instr(ty, "load ${ty.llvmName()}, ptr ${s.ptr.name}")
            is VarStorage.Fixed -> {
                val element = if (addressed) IrType.I8 else ty
                val ptr = fixedPtr(s.address, element)
                if (addressed) ptr else b.instr(ty, "load ${ty.llvmName()}, ptr ${ptr.name}")
            }
        }
    }

    /** The `inttoptr` of a fixed address, spelled the way the backend folds it. */
    private fun fixedPtr(address: Int, element: IrType): IrValue =
        b.instr(IrType.Ptr, "inttoptr i64 $address to ${if (element == IrType.I16) "i16*" else "i8*"}")

    // ---- unary ------------------------------------------------------------

    private fun emitUnary(e: UnaryOp): IrValue = when (e.op) {
        "-" -> {
            val v = emit(e.operand)
            b.instr(v.type, "sub ${v.type.llvmName()} 0, ${v.name}")
        }
        "+" -> emit(e.operand)
        "!" -> {
            val v = emit(e.operand)
            b.instr(IrType.I1, "xor i1 ${v.name}, true")
        }
        "++", "--" -> emitIncDec(e, if (e.op == "++") 1L else -1L)
        else -> throw error("Unknown operator ${e.op}")
    }

    /**
     * `++`/`--`. Postfix (the default) is the read-modify-write with the old
     * value as the result: the loaded value is returned, the updated one is
     * stored. Prefix stores the updated value and returns it. A non-variable
     * operand has no storage: postfix returns it as is, prefix the update.
     */
    private fun emitIncDec(e: UnaryOp, delta: Long): IrValue {
        val ty = TypeMapper.ir(e.type)
        val op = if (delta > 0) "add" else "sub"
        val operand = e.operand
        if (operand is VarRef) {
            val s = stmt.vars.lookup(operand.name) ?: throw error("Unresolved variable ${operand.name}")
            require(s !is VarStorage.Param) { "Cannot increment a parameter ${operand.name}" }
            val t = loadVar(s, ty)
            val r = b.instr(ty, "$op ${ty.llvmName()} ${t.name}, ${abs(delta)}")
            storeVar(s, r, ty)
            return if (e.prefix) r else t
        }
        val v = emit(operand)
        return if (e.prefix) b.instr(ty, "$op ${ty.llvmName()} ${v.name}, ${abs(delta)}") else v
    }

    // ---- binary -----------------------------------------------------------

    private fun emitBinary(e: BinaryOp): IrValue {
        if (e.op == "&&" || e.op == "||") return emitShortCircuit(e)
        val l = emit(e.left)
        val r = emit(e.right)
        return when (e.op) {
            "==" -> compare("eq", l, r, e.left.type)
            "!=" -> compare("ne", l, r, e.left.type)
            "<" -> compare("lt", l, r, e.left.type)
            "<=" -> compare("le", l, r, e.left.type)
            ">" -> compare("gt", l, r, e.left.type)
            ">=" -> compare("ge", l, r, e.left.type)
            "&" -> bitwise("and", l, r, e.left.type)
            "|" -> bitwise("or", l, r, e.left.type)
            else -> {
                val ty = TypeMapper.ir(e.left.type)
                val name = when (e.op) {
                    "+" -> if (ty == IrType.Float) "fadd" else "add"
                    "-" -> if (ty == IrType.Float) "fsub" else "sub"
                    "*" -> if (ty == IrType.Float) "fmul" else "mul"
                    "/" -> when {
                        ty == IrType.Float -> "fdiv"
                        isUnsigned(e.left.type) -> "udiv"
                        else -> "sdiv"
                    }
                    "%" -> when {
                        ty == IrType.Float -> "frem"
                        isUnsigned(e.left.type) -> "urem"
                        else -> "srem"
                    }
                    else -> throw error("Unknown operator ${e.op}")
                }
                b.instr(ty, "$name ${ty.llvmName()} ${l.name}, ${r.name}")
            }
        }
    }

    private fun compare(pred: String, l: IrValue, r: IrValue, operandType: WolinType): IrValue {
        val ty = TypeMapper.ir(operandType)
        return if (ty == IrType.Float) {
            b.instr(IrType.I1, "fcmp o$pred float ${l.name}, ${r.name}")
        } else {
            val p = when (pred) {
                "eq", "ne" -> pred
                else -> (if (isUnsigned(operandType)) "u" else "s") + pred
            }
            b.instr(IrType.I1, "icmp $p ${ty.llvmName()} ${l.name}, ${r.name}")
        }
    }

    private fun bitwise(name: String, l: IrValue, r: IrValue, operandType: WolinType): IrValue {
        val ty = TypeMapper.ir(operandType)
        return b.instr(ty, "$name ${ty.llvmName()} ${l.name}, ${r.name}")
    }

    private fun isUnsigned(t: WolinType): Boolean =
        t.kind == TypeKind.UBYTE || t.kind == TypeKind.UWORD

    /**
     * Short-circuit `&&`/`||`: the left operand is tested, the right one is
     * evaluated only on the non-deciding side, and the result is an `i1` phi
     * at the merge — `true`/`false` from the short-circuit side, the right
     * operand from the evaluated side.
     */
    private fun emitShortCircuit(e: BinaryOp): IrValue {
        val c = emit(e.left)
        val trueL = stmt.reserveLabel("short.true")
        val falseL = stmt.reserveLabel("short.false")
        val endL = stmt.reserveLabel("short.end")
        stmt.conditionalBranch(c, trueL, falseL)
        val shortSide = if (e.op == "&&") falseL else trueL
        val evalSide = if (e.op == "&&") trueL else falseL
        stmt.openReserved(shortSide)
        stmt.unconditionalBranch(endL)
        stmt.openReserved(evalSide)
        val r = emit(e.right)
        stmt.unconditionalBranch(endL)
        stmt.openReserved(endL)
        val shortValue = if (e.op == "&&") "false" else "true"
        return b.phi(IrType.I1, listOf(IrValue(shortValue, IrType.I1) to shortSide, r to evalSide))
    }

    // ---- assignment -------------------------------------------------------

    private fun emitAssign(a: Assign): IrValue {
        return when (val target = a.target) {
            is VarRef -> {
                val s = stmt.vars.lookup(target.name)
                    ?: throw error("Unresolved variable ${target.name}")
                require(s !is VarStorage.Param) { "Cannot assign to parameter ${target.name}" }
                val ty = TypeMapper.ir(a.type)
                val v = widenIfNeeded(emit(a.value), a)
                if (a.op == "=") {
                    storeVar(s, v, ty)
                    return v
                }
                val t = loadVar(s, ty)
                val r = compoundResult(a, t, v)
                storeVar(s, r, ty)
                r
            }

            is ArrayAccess -> {
                val ety = TypeMapper.ir(a.type)
                val base = emitArrayBase(target.array)
                val idx = promoteIndex(emit(target.index), target.index.type)
                val p = b.instr(IrType.Ptr, "getelementptr ${ety.llvmName()}, ptr ${base.name}, i16 ${idx.name}")
                val v = widenIfNeeded(emit(a.value), a)
                if (a.op == "=") {
                    b.instr(IrType.Void, "store ${ety.llvmName()} ${v.name}, ptr ${p.name}")
                    return v
                }
                val t = b.instr(ety, "load ${ety.llvmName()}, ptr ${p.name}")
                val r = compoundResult(a, t, v)
                b.instr(IrType.Void, "store ${ety.llvmName()} ${r.name}, ptr ${p.name}")
                r
            }

            else -> throw error("Invalid assignment target")
        }
    }

    /**
     * The read-modify-write result of a compound assignment, given the loaded
     * target [t] and the value [v]. `.=` clears the mask's bits: `and` the
     * target with the `NOT` of the mask.
     */
    private fun compoundResult(a: Assign, t: IrValue, v: IrValue): IrValue {
        val ty = TypeMapper.ir(a.type)
        return when (a.op) {
            ".=" -> {
                val n = b.instr(ty, "xor ${ty.llvmName()} ${v.name}, ${allBits(ty)}")
                b.instr(ty, "and ${ty.llvmName()} ${t.name}, ${n.name}")
            }
            else -> b.instr(ty, "${assignOp(a)} ${ty.llvmName()} ${t.name}, ${v.name}")
        }
    }

    private fun assignOp(a: Assign): String = when (a.op) {
        "+=" -> "add"
        "-=" -> "sub"
        "*=" -> "mul"
        "/=" -> if (isUnsigned(a.type)) "udiv" else "sdiv"
        "%=" -> if (isUnsigned(a.type)) "urem" else "srem"
        "&=" -> "and"
        "|=", ":=" -> "or"
        else -> throw error("Unknown assignment operator ${a.op}")
    }

    private fun allBits(ty: IrType): String = if (ty == IrType.I16) "65535" else "-1"

    /**
     * The bit-assignment operators accept a mask narrower than the target;
     * the checker only guarantees that it fits the width, so extend it to the
     * target's type.
     */
    private fun widenIfNeeded(value: IrValue, a: Assign): IrValue {
        if (!a.value.type.isInteger || !a.type.isInteger) return value
        val vty = TypeMapper.ir(a.value.type)
        val tty = TypeMapper.ir(a.type)
        if (vty == tty) return value
        val dir = if (isUnsigned(a.value.type)) "zext" else "sext"
        return b.instr(tty, "$dir ${vty.llvmName()} ${value.name} to ${tty.llvmName()}")
    }

    // ---- storage ------------------------------------------------------------

    private fun loadVar(s: VarStorage, ty: IrType): IrValue = when (s) {
        is VarStorage.Param -> s.value
        is VarStorage.Global -> b.instr(ty, "load ${ty.llvmName()}, ptr @${s.name}")
        is VarStorage.Local -> b.instr(ty, "load ${ty.llvmName()}, ptr ${s.ptr.name}")
        is VarStorage.Fixed -> b.instr(ty, "load ${ty.llvmName()}, ptr ${fixedPtr(s.address, ty).name}")
    }

    private fun storeVar(s: VarStorage, v: IrValue, ty: IrType) {
        when (s) {
            is VarStorage.Param -> throw error("Cannot assign to a parameter")
            is VarStorage.Global -> b.instr(IrType.Void, "store ${ty.llvmName()} ${v.name}, ptr @${s.name}")
            is VarStorage.Local -> b.instr(IrType.Void, "store ${ty.llvmName()} ${v.name}, ptr ${s.ptr.name}")
            // A plain store to a hardware register would be optimised away.
            is VarStorage.Fixed ->
                b.instr(IrType.Void, "store volatile ${ty.llvmName()} ${v.name}, ptr ${fixedPtr(s.address, ty).name}")
        }
    }

    /** Store [v] to the fixed [address] — the same volatile store fixed-address variables use. */
    fun emitStoreFixed(address: Int, v: IrValue, ty: IrType) {
        storeVar(VarStorage.Fixed(address), v, ty)
    }

    // ---- arrays -------------------------------------------------------------

    private fun emitArrayAccess(a: ArrayAccess): IrValue {
        val ety = TypeMapper.ir(a.type)
        val base = emitArrayBase(a.array)
        val idx = promoteIndex(emit(a.index), a.index.type)
        val p = b.instr(IrType.Ptr, "getelementptr ${ety.llvmName()}, ptr ${base.name}, i16 ${idx.name}")
        return b.instr(ety, "load ${ety.llvmName()}, ptr ${p.name}")
    }

    /**
     * The base pointer of an indexed access.
     *
     * An addressed local — a `string` or an array — keeps its *pointer* in an
     * `alloca` slot, so the GEP must run on the pointer loaded out of the
     * slot, not on the slot itself. A fixed-address variable is already a
     * pointer to the data, and a string literal is a pointer to its first
     * character, so both are used as they are.
     */
    private fun emitArrayBase(array: Expr): IrValue {
        val slot = (array as? VarRef)?.let { stmt.vars.lookup(it.name) }
        if (slot is VarStorage.Local) {
            // The slot holds the pointer, so the GEP runs on the loaded value.
            return b.instr(IrType.Ptr, "load ptr, ptr ${slot.ptr.name}")
        }
        return emit(array)
    }

    /**
     * The GEP index is always a `uword`. A variable index is extended from
     * its type (unsignedly or signedly); an inline constant takes the
     * operand's type and needs no extension.
     */
    private fun promoteIndex(idx: IrValue, indexType: WolinType): IrValue = when {
        !idx.name.startsWith("%") -> idx
        indexType.kind == TypeKind.UWORD || indexType.kind == TypeKind.WORD -> idx
        else -> {
            val dir = if (isUnsigned(indexType)) "zext" else "sext"
            b.instr(IrType.I16, "$dir ${idx.type.llvmName()} ${idx.name} to i16")
        }
    }

    // ---- calls ---------------------------------------------------------------

    private fun emitCall(e: Call): IrValue {
        val callee = e.callee as? VarRef ?: throw error("Unsupported call target")
        val args = e.args.map { emit(it) }
        val retType = TypeMapper.ir(e.type)
        return when (val symbol = resolved[callee]) {
            is Symbol.FunSymbol -> b.call(retType, "$prefix.${symbol.decl.name}", args)
            // A function-typed variable is called through its pointer.
            else -> b.call(retType, emit(callee).name, args)
        }
    }

    // ---- if in expression position ---------------------------------------------

    private fun emitIfExpr(e: IfExpr): IrValue {
        val c = emit(e.cond)
        val thenL = stmt.reserveLabel("if.then")
        val elseL = if (e.otherwise.isNotEmpty()) stmt.reserveLabel("if.else") else null
        val endL = stmt.reserveLabel("if.end")
        stmt.conditionalBranch(c, thenL, elseL ?: endL)
        stmt.openReserved(thenL)
        val v1 = stmt.emitValue(e.then)
        stmt.ensureTerminatedOr(endL)
        if (elseL != null) {
            stmt.openReserved(elseL)
            val v2 = stmt.emitValue(e.otherwise)
            stmt.ensureTerminatedOr(endL)
            stmt.openReserved(endL)
            if (e.type.kind != TypeKind.UNIT && v1 != null && v2 != null) {
                return b.phi(TypeMapper.ir(e.type), listOf(v1 to thenL, v2 to elseL))
            }
            return IrValue("", IrType.Void)
        }
        stmt.openReserved(endL)
        return IrValue("", IrType.Void)
    }
}
