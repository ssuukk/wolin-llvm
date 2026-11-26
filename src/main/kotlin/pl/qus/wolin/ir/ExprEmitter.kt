package pl.qus.wolin.ir

import java.util.IdentityHashMap
import kotlin.math.abs
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.ArrayAccess
import pl.qus.wolin.ast.ArrayLiteral
import pl.qus.wolin.ast.Assign
import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.BoolLit
import pl.qus.wolin.ast.Call
import pl.qus.wolin.ast.Convert
import pl.qus.wolin.ast.CharLit
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FunctionLit
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.FunDecl
import pl.qus.wolin.ast.IfExpr
import pl.qus.wolin.ast.IntLit
import pl.qus.wolin.ast.MemberAccess
import pl.qus.wolin.ast.NullLit
import pl.qus.wolin.ast.RealLit
import pl.qus.wolin.ast.StringLit
import pl.qus.wolin.ast.StringTemplate
import pl.qus.wolin.ast.StructDecl
import pl.qus.wolin.ast.ThisRef
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.ast.WhenEntry
import pl.qus.wolin.ast.WhenExpr
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

    /**
     * A CPU status-flag variable: the [reg] register (`CPU.I` in v1), no
     * storage. A read emits `CpuAccessors.flagRead`; a write emits `sei`/`cli`.
     */
    data class CpuFlag(val reg: String) : VarStorage
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

/** The mangled name of an impl method: `<file>.<struct>.<method>`. */
internal fun methodSymbol(prefix: String, decl: FunDecl): String =
    listOfNotNull(prefix.ifEmpty { null }, decl.owner, decl.name).joinToString(".")

/** The mangled name of the static vtable for `impl Struct : Trait`. */
internal fun vtableSymbol(prefix: String, structName: String, traitName: String): String =
    listOf("vt", prefix, structName, traitName)
        .filter { it.isNotEmpty() }
        .joinToString(".")

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
    /** The file's globals, so a function literal can see them. */
    private val globals: List<GlobalDecl> = emptyList(),
    /** The program's struct declarations, for layouts and field offsets. */
    private val structs: List<StructDecl> = emptyList(),
    /** The program's static vtables, for trait-to-struct coercions. */
    private val vtables: List<Ast.VTableInfo> = emptyList(),
) {
    private val floatConsts = LinkedHashMap<String, String>()
    private val stringConsts = LinkedHashMap<String, String>()
    private val interpBuffers = IdentityHashMap<StringTemplate, String>()
    private var nextInterp = 0
    internal val helperDeclarations = LinkedHashSet<String>()
    internal val usedInterpHelpers = LinkedHashSet<String>()
    var usesFloatInterpolation = false
        private set
    /** The function literals of this module, emitted after the functions. */
    internal val lambda = LambdaEmitter(b, stmt, prefix)

    fun emit(e: Expr): IrValue = when (e) {
        is IntLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is BoolLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is CharLit -> IrValue(e.value.toString(), TypeMapper.ir(e.type))
        is NullLit -> IrValue("null", IrType.Ptr)
        is RealLit -> emitRealLit(e)
        is StringLit -> emitStringLit(e)
        is StringTemplate -> emitStringTemplate(e)
        is VarRef -> emitVarRef(e)
        is ThisRef -> throw error("this is not a value; access a field or call a method")
        is UnaryOp -> emitUnary(e)
        is BinaryOp -> emitBinary(e)
        is Assign -> emitAssign(e)
        is ArrayAccess -> emitArrayAccess(e)
        is MemberAccess -> emitMemberAccess(e)
        is ArrayLiteral -> throw error("Array literal cannot be evaluated as an expression")
        is Call -> emitCall(e)
        is IfExpr -> emitIfExpr(e)
        is WhenExpr -> emitWhenExpr(e)
        is FunctionLit -> emitFunctionLit(e)
        is Convert -> emitConvert(e)
    }

    /**
     * The explicit conversion: same width is a reinterpretation (no code),
     * widening is `sext` for a signed source and `zext` for an unsigned one,
     * narrowing is `trunc` (the low bits, no range check).
     */
    private fun emitConvert(e: Convert): IrValue {
        val v = emit(e.value)
        val vty = v.type
        val tty = TypeMapper.ir(e.to)
        return when {
            vty == tty -> v
            tty == IrType.I16 ->
                b.instr(tty, "${if (isUnsigned(e.value.type)) "zext" else "sext"} ${vty.llvmName()} ${v.name} to ${tty.llvmName()}")
            else -> b.instr(tty, "trunc ${vty.llvmName()} ${v.name} to ${tty.llvmName()}")
        }
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

    private fun emitStringLit(e: StringLit): IrValue = stringPtr(e.bytes)

    /** The pointer to a module-level NUL-terminated constant for [bytes]. */
    private fun stringPtr(bytes: ByteArray): IrValue {
        val n = bytes.size
        val key = bytes.contentToString()
        val name = stringConsts[key] ?: run {
            val global = "@str_${stringConsts.size}"
            stringConsts[key] = global
            val chars = bytes.joinToString("") { llvmEscapeByte(it.toInt() and 0xFF) }
            constants += "$global = private unnamed_addr constant [${n + 1} x i8] c\"$chars\\00\""
            global
        }
        return b.instr(
            IrType.Ptr,
            "getelementptr inbounds [${n + 1} x i8], ptr $name, i32 0, i32 0"
        )
    }

    // ---- string interpolation ---------------------------------------------

    /**
     * A template writes literal runs and rendered values into its own static
     * 256-byte buffer and returns that buffer. The buffer is never freed: a
     * later evaluation of the same site overwrites it.
     */
    private fun emitStringTemplate(e: StringTemplate): IrValue {
        val buf = interpolationBuffer(e)
        var pos = IrValue("0", IrType.I16)

        for (part in e.parts) when (part) {
            is Ast.StringTemplate.Literal -> {
                if (part.bytes.isEmpty()) continue
                pos = appendInterp(
                    "__wolin_interp_append_string",
                    "declare i16 @__wolin_interp_append_string(ptr, i16, ptr)",
                    listOf(buf, pos, stringPtr(part.bytes)),
                )
            }
            is Ast.StringTemplate.Value -> {
                val value = emit(part.expr)
                when (part.expr.type.kind) {
                    TypeKind.UBYTE -> pos = appendInterp(
                        "__wolin_interp_append_ubyte",
                        "declare i16 @__wolin_interp_append_ubyte(ptr, i16, i8)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.BYTE -> pos = appendInterp(
                        "__wolin_interp_append_byte",
                        "declare i16 @__wolin_interp_append_byte(ptr, i16, i8)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.UWORD -> pos = appendInterp(
                        "__wolin_interp_append_uword",
                        "declare i16 @__wolin_interp_append_uword(ptr, i16, i16)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.WORD -> pos = appendInterp(
                        "__wolin_interp_append_word",
                        "declare i16 @__wolin_interp_append_word(ptr, i16, i16)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.BOOL -> pos = appendInterp(
                        "__wolin_interp_append_bool",
                        "declare i16 @__wolin_interp_append_bool(ptr, i16, i1)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.STRING -> pos = appendInterp(
                        "__wolin_interp_append_string",
                        "declare i16 @__wolin_interp_append_string(ptr, i16, ptr)",
                        listOf(buf, pos, value),
                    )
                    TypeKind.FLOAT -> {
                        usesFloatInterpolation = true
                        pos = appendInterp(
                            "__wolin_interp_append_float",
                            "declare i16 @__wolin_interp_append_float(ptr, i16, float)",
                            listOf(buf, pos, value),
                        )
                    }
                    else -> error("checker allowed ${part.expr.type} in a template")
                }
            }
        }

        val end = b.instr(
            IrType.Ptr,
            "getelementptr i8, ptr ${buf.name}, i16 ${pos.name}",
        )
        b.instr(IrType.Void, "store i8 0, ptr ${end.name}")
        return buf
    }

    private fun interpolationBuffer(e: StringTemplate): IrValue {
        val global = interpBuffers.getOrPut(e) {
            val name = "@__wolin_interp_${nextInterp++}"
            constants += "$name = private global [256 x i8] zeroinitializer"
            name
        }
        return b.instr(
            IrType.Ptr,
            "getelementptr inbounds [256 x i8], ptr $global, i32 0, i32 0",
        )
    }

    private fun appendInterp(
        helper: String,
        declaration: String,
        args: List<IrValue>,
    ): IrValue {
        usedInterpHelpers += helper
        helperDeclarations += declaration
        return b.call(IrType.I16, helper, args)
    }

    // ---- variables --------------------------------------------------------

    private fun emitVarRef(e: VarRef): IrValue {
        if (e.type.kind == TypeKind.STRUCT) {
            throw error("A struct value is not first class")
        }
        (resolved[e] as? Symbol.FunSymbol)?.let { symbol ->
            // A function name used as a value is its address, as a `uword`.
            return b.instr(IrType.I16, "ptrtoint i8* @\"${symbol.prefix}.${symbol.decl.name}\" to i16")
        }
        (resolved[e] as? Symbol.DataSymbol)?.enumValue?.let { value ->
            return IrValue(value.toString(), IrType.I8)
        }
        val ty = TypeMapper.ir(e.type)
        val addressed = e.type.kind == TypeKind.ARRAY || e.type.kind == TypeKind.STRING ||
            e.type.kind == TypeKind.TRAIT
        return when (val s = stmt.vars.lookup(e.name) ?: throw error("Unresolved variable ${e.name}")) {
            is VarStorage.Param -> s.value
            is VarStorage.Global -> when {
                // A string variable's slot holds the pointer; its value is
                // the pointed-to bytes, not the slot.
                e.type.kind == TypeKind.STRING -> b.instr(IrType.Ptr, "load ptr, ptr @${s.name}")
                addressed -> IrValue("@${s.name}", IrType.Ptr)
                else -> b.instr(ty, "load ${ty.llvmName()}, ptr @${s.name}")
            }
            is VarStorage.Local -> when {
                e.type.kind == TypeKind.STRING -> b.instr(IrType.Ptr, "load ptr, ptr ${s.ptr.name}")
                addressed -> s.ptr
                else -> b.instr(ty, "load ${ty.llvmName()}, ptr ${s.ptr.name}")
            }
            is VarStorage.Fixed -> when {
                // A fixed-address array is the data address itself; a string
                // is a pointer variable at the address, so its read loads
                // the pointer exactly like a global or local string.
                e.type.kind == TypeKind.ARRAY || e.type.kind == TypeKind.TRAIT ->
                    fixedPtr(s.address, IrType.I8)
                else -> loadVar(s, ty)
            }
            is VarStorage.CpuFlag -> CpuAccessors.flagRead(b, s.reg)
        }
    }
    private fun loadVar(s: VarStorage, ty: IrType): IrValue = when (s) {
        is VarStorage.Param -> s.value
        is VarStorage.Global -> b.instr(ty, "load ${ty.llvmName()}, ptr @${s.name}")
        is VarStorage.Local -> b.instr(ty, "load ${ty.llvmName()}, ptr ${s.ptr.name}")
        is VarStorage.Fixed -> b.instr(ty, "load ${ty.llvmName()}, ptr ${fixedPtr(s.address, ty).name}")
        is VarStorage.CpuFlag -> CpuAccessors.flagRead(b, s.reg)
    }

    private fun storeVar(s: VarStorage, v: IrValue, ty: IrType) {
        when (s) {
            is VarStorage.Param -> throw error("Cannot assign to a parameter")
            is VarStorage.Global -> b.instr(IrType.Void, "store ${ty.llvmName()} ${v.name}, ptr @${s.name}")
            is VarStorage.Local -> b.instr(IrType.Void, "store ${ty.llvmName()} ${v.name}, ptr ${s.ptr.name}")
            // A plain store to a hardware register would be optimised away.
            is VarStorage.Fixed ->
                b.instr(IrType.Void, "store volatile ${ty.llvmName()} ${v.name}, ptr ${fixedPtr(s.address, ty).name}")
            is VarStorage.CpuFlag -> CpuAccessors.writeFlag(b, s.reg, v)
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
        if (operand is MemberAccess) {
            val field = memberField(operand)
            val fty = TypeMapper.ir(field.type)
            val p = memberAddress(operand)
            val vol = if (isFixedStorage(operand.receiver)) "volatile " else ""
            val t = b.instr(fty, "load $vol${fty.llvmName()}, ptr ${p.name}")
            val r = b.instr(fty, "$op ${fty.llvmName()} ${t.name}, ${abs(delta)}")
            b.instr(IrType.Void, "store $vol${fty.llvmName()} ${r.name}, ptr ${p.name}")
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
            "xor" -> bitwise("xor", l, r, e.left.type)
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
                if (a.type.kind == TypeKind.TRAIT) {
                    check(a.op == "=") { "compound assignment is not defined for a trait value" }
                    val storage = stmt.vars.lookup(target.name)
                        ?: throw error("Unresolved variable ${target.name}")
                    require(storage !is VarStorage.Param) {
                        "Cannot assign to parameter ${target.name}"
                    }
                    val cell = traitCell(target)
                    storeTraitRef(cell, a.type, a.value)
                    return cell
                }
                val s = stmt.vars.lookup(target.name)
                    ?: throw error("Unresolved variable ${target.name}")
                require(s !is VarStorage.Param) { "Cannot assign to parameter ${target.name}" }
                val ty = TypeMapper.ir(a.type)
                val v = fitTargetWidth(emit(a.value), a)
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
                if (a.type.kind == TypeKind.TRAIT) {
                    check(a.op == "=") { "compound assignment is not defined for a trait value" }
                    val cell = traitElementPtr(target)
                    storeTraitRef(cell, a.type, a.value)
                    return cell
                }
                val ety = TypeMapper.ir(a.type)
                val base = emitArrayBase(target.array)
                val idx = promoteIndex(emit(target.index), target.index.type)
                val p = b.instr(IrType.Ptr, "getelementptr ${ety.llvmName()}, ptr ${base.name}, i16 ${idx.name}")
                val v = fitTargetWidth(emit(a.value), a)
                if (a.op == "=") {
                    b.instr(IrType.Void, "store ${ety.llvmName()} ${v.name}, ptr ${p.name}")
                    return v
                }
                val t = b.instr(ety, "load ${ety.llvmName()}, ptr ${p.name}")
                val r = compoundResult(a, t, v)
                b.instr(IrType.Void, "store ${ety.llvmName()} ${r.name}, ptr ${p.name}")
                r
            }

            is MemberAccess -> {
                val field = memberField(target)
                val ty = TypeMapper.ir(field.type)
                val p = memberAddress(target)
                val vol = if (isFixedStorage(target.receiver)) "volatile " else ""
                val v = fitTargetWidth(emit(a.value), a)
                if (a.op == "=") {
                    b.instr(IrType.Void, "store $vol${ty.llvmName()} ${v.name}, ptr ${p.name}")
                    return v
                }
                val t = b.instr(ty, "load $vol${ty.llvmName()}, ptr ${p.name}")
                val r = compoundResult(a, t, v)
                b.instr(IrType.Void, "store $vol${ty.llvmName()} ${r.name}, ptr ${p.name}")
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
     * A bit-assignment mask may differ from the target's width: a narrower
     * mask is extended (signedly or unsignedly, by its own type), and a wider
     * one is truncated to the target's low bits. Any other assignment is
     * type-checked to the same width, so it never reaches this conversion.
     */
    private fun fitTargetWidth(value: IrValue, a: Assign): IrValue {
        if (!a.value.type.isInteger || !a.type.isInteger) return value
        val vty = TypeMapper.ir(a.value.type)
        val tty = TypeMapper.ir(a.type)
        if (vty == tty) return value
        if (vty == IrType.I16 && tty == IrType.I8) {
            return b.instr(IrType.I8, "trunc i16 ${value.name} to i8")
        }
        val dir = if (isUnsigned(a.value.type)) "zext" else "sext"
        return b.instr(tty, "$dir ${vty.llvmName()} ${value.name} to ${tty.llvmName()}")
    }

    // ---- storage ------------------------------------------------------------

    /** Store [v] to the fixed [address] — the same volatile store fixed-address variables use. */
    fun emitStoreFixed(address: Int, v: IrValue, ty: IrType) {
        storeVar(VarStorage.Fixed(address), v, ty)
    }

    // ---- arrays -------------------------------------------------------------

    // ---- structs ------------------------------------------------------------

    /** The base pointer of a struct instance or array element. */
    private fun structBase(expr: Expr): IrValue = when (expr) {
        is VarRef -> {
            when (val storage = stmt.vars.lookup(expr.name)
                ?: throw error("Unresolved variable ${expr.name}")) {
                is VarStorage.Global -> IrValue("@${storage.name}", IrType.Ptr)
                is VarStorage.Local -> storage.ptr
                is VarStorage.Fixed -> fixedPtr(storage.address, IrType.I8)
                else -> throw error("Unsupported struct storage for ${expr.name}")
            }
        }
        is ArrayAccess -> structElementPtr(expr)
        is ThisRef -> stmt.thisValue ?: throw error("this outside an impl method")
        else -> throw error("Unsupported struct receiver")
    }

    /** The address of a struct array element. */
    private fun structElementPtr(a: ArrayAccess): IrValue {
        val owner = structOf(a.type)
        val base = emitArrayBase(a.array)
        val idx = promoteIndex(emit(a.index), a.index.type)
        val element = IrType.Array(IrType.I8, owner.size).llvmName()
        return b.instr(
            IrType.Ptr,
            "getelementptr $element, ptr ${base.name}, i16 ${idx.name}",
        )
    }

    /** The address of a 4-byte trait cell inside a trait array. */
    private fun traitElementPtr(a: ArrayAccess): IrValue {
        val base = emitArrayBase(a.array)
        val idx = promoteIndex(emit(a.index), a.index.type)
        val element = IrType.Array(IrType.I8, 4).llvmName()
        return b.instr(
            IrType.Ptr,
            "getelementptr $element, ptr ${base.name}, i16 ${idx.name}",
        )
    }

    /** The address of a trait value's 4-byte cell. */
    private fun traitCell(expr: Expr): IrValue {
        require(expr.type.kind == TypeKind.TRAIT) { "not a trait value: $expr" }
        return emit(expr)
    }

    /** The vtable symbol for the concrete [structName] as [traitName]. */
    private fun vtableFor(structName: String, traitName: String): String {
        val info = vtables.firstOrNull {
            it.structName == structName && it.traitName == traitName
        } ?: error("No vtable for $structName as $traitName")
        return vtableSymbol(info.prefix, info.structName, info.traitName)
    }

    /**
     * Stores a trait reference into [cell]. A struct value contributes its
     * address plus the vtable of `(struct, trait)`; another trait value
     * contributes a copy of its data and vtable pointers.
     */
    internal fun storeTraitRef(cell: IrValue, targetType: WolinType, value: Expr) {
        when (value.type.kind) {
            TypeKind.TRAIT -> {
                val src = traitCell(value)
                val data = b.instr(IrType.Ptr, "load ptr, ptr ${src.name}")
                val srcVtSlot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${src.name}, i16 2",
                )
                val vt = b.instr(IrType.Ptr, "load ptr, ptr ${srcVtSlot.name}")
                b.instr(IrType.Void, "store ptr ${data.name}, ptr ${cell.name}")
                val dstVtSlot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${cell.name}, i16 2",
                )
                b.instr(IrType.Void, "store ptr ${vt.name}, ptr ${dstVtSlot.name}")
            }
            TypeKind.STRUCT -> {
                val data = structBase(value)
                val structName = value.type.structName
                    ?: error("Struct value has no name")
                val traitName = targetType.traitName
                    ?: error("Trait type has no name")
                val symbol = vtableFor(structName, traitName)
                b.instr(IrType.Void, "store ptr ${data.name}, ptr ${cell.name}")
                val dstVtSlot = b.instr(
                    IrType.Ptr,
                    "getelementptr i8, ptr ${cell.name}, i16 2",
                )
                b.instr(IrType.Void, "store ptr @\"$symbol\", ptr ${dstVtSlot.name}")
            }
            else -> error("Value of type ${value.type} is not a trait reference")
        }
    }

    /** The address of a field inside a struct. */
    private fun memberAddress(e: MemberAccess): IrValue {
        val owner = e.owner ?: throw error("Member access has no owner")
        val offset = owner.fieldOffsets[e.name]
            ?: throw error("Unknown field ${e.name} in ${owner.name}")
        val base = structBase(e.receiver)
        return b.instr(IrType.Ptr, "getelementptr i8, ptr ${base.name}, i16 $offset")
    }

    private fun memberField(e: MemberAccess): GlobalDecl {
        val owner = e.owner ?: throw error("Member access has no owner")
        return owner.fields.firstOrNull { it.name == e.name }
            ?: throw error("Unknown field ${e.name} in ${owner.name}")
    }

    /** A field of a fixed-address struct is a hardware register: volatile. */
    private fun isFixedStorage(expr: Expr): Boolean = when (expr) {
        is VarRef -> stmt.vars.lookup(expr.name) is VarStorage.Fixed
        is ArrayAccess -> isFixedStorage(expr.array)
        // A method is shared by every instance, so it cannot know whether
        // its receiver is a fixed-address struct. Treat `this` as volatile:
        // a hardware field stays a hardware access, at the cost of the
        // optimiser treating ordinary struct fields conservatively too.
        is ThisRef -> true
        else -> false
    }

    private fun emitMemberAccess(e: MemberAccess): IrValue {
        e.enumValue?.let { return IrValue(it.toString(), IrType.I8) }
        val field = memberField(e)
        val ty = TypeMapper.ir(field.type)
        val p = memberAddress(e)
        val vol = if (isFixedStorage(e.receiver)) "volatile " else ""
        return b.instr(ty, "load $vol${ty.llvmName()}, ptr ${p.name}")
    }

    private fun structOf(type: WolinType): StructDecl {
        val name = type.structName ?: throw error("Struct type $type has no name")
        return structs.firstOrNull { it.name == name } ?: throw error("Unknown struct $name")
    }

    private fun emitArrayAccess(a: ArrayAccess): IrValue {
        if (a.type.kind == TypeKind.STRUCT) {
            throw error("A struct element is not a value; read a field")
        }
        if (a.type.kind == TypeKind.TRAIT) {
            return traitElementPtr(a)
        }
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

    /**
     * Emits a function literal as its own module-level function and returns
     * a pointer to it.
     *
     * The pointer is a `ptr`, the representation of a function-pointer-typed
     * value: the literal is called through the pointer, not by name.
     */
    private fun emitFunctionLit(e: FunctionLit): IrValue {
        val address = lambda.emit(e, globals)
        return b.instr(IrType.Ptr, "inttoptr i64 ${address.name} to i8*")
    }

    private fun emitCall(e: Call): IrValue {
        if (e.traitSlot >= 0) {
            val member = e.callee as? MemberAccess
                ?: throw error("Trait call without a receiver")
            val cell = traitCell(member.receiver)
            val data = b.instr(IrType.Ptr, "load ptr, ptr ${cell.name}")
            val vtSlot = b.instr(
                IrType.Ptr,
                "getelementptr i8, ptr ${cell.name}, i16 2",
            )
            val vtable = b.instr(IrType.Ptr, "load ptr, ptr ${vtSlot.name}")
            val entry = b.instr(
                IrType.Ptr,
                "getelementptr i8, ptr ${vtable.name}, i16 ${e.traitSlot * 2}",
            )
            val fn = b.instr(IrType.Ptr, "load ptr, ptr ${entry.name}")
            val args = listOf(data) + e.args.map { emit(it) }
            return b.call(TypeMapper.ir(e.type), fn.name, args)
        }
        val method = e.resolvedMethod
        if (method != null) {
            val member = e.callee as? MemberAccess ?: throw error("Method call without a receiver")
            val self = structBase(member.receiver)
            val args = listOf(self) + e.args.map { emit(it) }
            return b.call(TypeMapper.ir(e.type), methodSymbol(e.methodPrefix, method), args)
        }
        val callee = e.callee as? VarRef ?: throw error("Unsupported call target")
        val args = e.args.map { emit(it) }
        val retType = TypeMapper.ir(e.type)
        return when (val symbol = resolved[callee]) {
            is Symbol.FunSymbol -> b.call(retType, "${symbol.prefix}.${symbol.decl.name}", args)
            // A function-pointer-typed variable holds the pointer itself, so
            // the call goes through it directly.
            is Symbol.DataSymbol -> {
                if (symbol.type.kind != TypeKind.FUNCTION) {
                    val address = emit(callee)
                    val pointer = b.instr(
                        IrType.Ptr,
                        "inttoptr ${address.type.llvmName()} ${address.name} to i8*"
                    )
                    b.call(retType, pointer.name, args)
                } else {
                    val pointer = emit(callee)
                    b.call(retType, pointer.name, args)
                }
            }
            // A `uword` holding a function address is called through
            // `inttoptr` — the address is a plain 16-bit number, so it is
            // converted back to a pointer before the call.
            else -> {
                val address = emit(callee)
                val pointer = b.instr(IrType.Ptr, "inttoptr ${address.type.llvmName()} ${address.name} to i8*")
                b.call(retType, pointer.name, args)
            }
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
        // The value is produced in the current block: the branch may have
        // left `if.then` through a jump (a loop, a label), so the phi must
        // name the block that actually produced the value.
        val thenBlock = if (v1 != null) b.block() else ""
        stmt.ensureTerminatedOr(endL)
        if (elseL != null) {
            stmt.openReserved(elseL)
            val v2 = stmt.emitValue(e.otherwise)
            val elseBlock = if (v2 != null) b.block() else ""
            stmt.ensureTerminatedOr(endL)
            stmt.openReserved(endL)
            // A null side means that branch leaves through a terminator (a
            // `return@` or a `break`): the value of the expression is the
            // live side's, and no phi is needed.
            return when {
                e.type.kind == TypeKind.UNIT -> IrValue("", IrType.Void)
                v1 == null -> v2 ?: IrValue("", IrType.Void)
                v2 == null -> v1
                else -> b.phi(TypeMapper.ir(e.type), listOf(v1 to thenBlock, v2 to elseBlock))
            }
        }
        stmt.openReserved(endL)
        return IrValue("", IrType.Void)
    }

    private fun emitWhenExpr(e: WhenExpr): IrValue {
        val subject = e.subject?.let { emit(it) }
        val pairs = stmt.whenFlow(subject, e.entries) { entry, endL ->
            val v = stmt.emitValue(entry.body)
            stmt.ensureTerminatedOr(endL)
            v
        }
        return when {
            e.type.kind == TypeKind.UNIT -> IrValue("", IrType.Void)
            pairs.isNullOrEmpty() -> IrValue("", IrType.Void)
            else -> b.phi(TypeMapper.ir(e.type), pairs)
        }
    }
}
