package pl.qus.wolin.ir

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.ArrayAccess
import pl.qus.wolin.ast.Assign
import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.BlockStmt
import pl.qus.wolin.ast.Call
import pl.qus.wolin.ast.CpuRegister
import pl.qus.wolin.ast.Convert
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.ExprStmt
import pl.qus.wolin.ast.FixedAddress
import pl.qus.wolin.ast.FixedArray
import pl.qus.wolin.ast.FunDecl
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.IfExpr
import pl.qus.wolin.ast.IfStmt
import pl.qus.wolin.ast.LocalDecl
import pl.qus.wolin.ast.Location
import pl.qus.wolin.ast.Program
import pl.qus.wolin.ast.ReturnStmt
import pl.qus.wolin.ast.Stmt
import pl.qus.wolin.ast.StructDecl
import pl.qus.wolin.ast.StringTemplate
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.ast.WhenExpr
import pl.qus.wolin.ast.WhenStmt
import pl.qus.wolin.ast.WhileStmt
import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.ConstValue
import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.Types
import pl.qus.wolin.sema.WolinType
import pl.qus.wolin.sema.arrayLength

/**
 * Emits the whole module: the `target triple` line, the global section and
 * the functions, in that order.
 *
 * A file-scope variable without a fixed address becomes a module global
 * (D6). A fixed-address variable gets no value storage; the module carries
 * its address as a pointer constant in the `inttoptr` form every access uses.
 * Float and string constants that the [ExprEmitter] registers while the functions
 * are emitted join the same global section.
 */
class ModuleEmitter(private val b: IrBuilder = IrBuilder()) {

    /** The name of the file's init function, set by [emitFile]. */
    var initFnName: String? = null
        private set

    /** Interpolation helpers this file calls. */
    var usedInterpHelpers: Set<String> = emptySet()
        private set

    /** Whether this file formats a float in a template. */
    var usesFloatInterpolation = false
        private set

    /** The program's struct declarations, for layouts and field offsets. */
    private var structs: List<StructDecl> = emptyList()

    /** Emits [program] with names mangled as `<mangledPrefix>.<symbol>`. */
    fun emitFile(program: Program, mangledPrefix: String): String {
        structs = program.structs
        val constants = mutableListOf<String>()
        val stmt = StmtEmitter(b, structs)
        stmt.expr = ExprEmitter(
            b, stmt, constants, mangledPrefix, program.resolved, program.globals, structs,
            program.vtables,
        )

        emitGlobals(program, mangledPrefix, constants)
        emitVtables(program, mangledPrefix, constants)
        emitForeignDeclarations(program, mangledPrefix, constants)
        emitForeignGlobals(program, mangledPrefix, constants)

        for (fn in program.functions) {
            val params = fn.params.map { IrValue("%${it.name}", TypeMapper.ir(it.type)) }
            val fixed = fn.fixedAddress
            if (fixed != null) {
                // A fixed-address function is a DECLARATION: the address
                // already belongs to something else — a KERNAL routine, for
                // instance. The spec emits it as a function whose body is the
                // ROM call, so a call site stays an ordinary call.
                emitFixedFunction(fn, fixed, params, mangledPrefix)
                continue
            }
            // Handlers that exit with a plain ret get the backend's
            // interrupt prologue + epilogue (RTI). Handlers that exit via
            // return@<address> emit a plain tail-jump themselves, so the
            // unbalanced prologue they would never pop is suppressed.
            val attributes =
                if (fn.isInterrupt && fn.returnAt.isEmpty()) listOf("interrupt") else emptyList()
            b.startFunction(
                "$mangledPrefix.${fn.name}",
                TypeMapper.ir(fn.retType ?: Types.unit),
                params,
                attributes = attributes,
            )
            stmt.startFunction(mangledPrefix, params, program.globals, fn, program.visibleGlobals)
            b.endFunction()
        }
        // A method is an ordinary function with a hidden receiver pointer.
        // The receiver is the first parameter, so a call site passes the
        // struct storage address and the body reaches its fields through it.
        for (impl in program.impls) {
            for (fn in impl.methods) {
                val self = IrValue("%this", IrType.Ptr)
                val params = listOf(self) + fn.params.map {
                    IrValue("%${it.name}", TypeMapper.ir(it.type))
                }
                b.startFunction(
                    methodSymbol(mangledPrefix, fn),
                    TypeMapper.ir(fn.retType ?: Types.unit),
                    params,
                )
                stmt.startFunction(
                    mangledPrefix, params, program.globals, fn, program.visibleGlobals, self,
                )
                b.endFunction()
            }
        }
        // The function literals are their own functions, so they are emitted
        // after the file's functions: a module holds one function open at a
        // time.
        stmt.expr.lambda.emitCollected(program.globals)

        // The init function is emitted into the same builder, so it renders
        // with the module; the driver calls it before main, even when it
        // stores nothing.
        initFnName = GlobalInitEmitter(b).emitFile(program, mangledPrefix)

        usedInterpHelpers = stmt.expr.usedInterpHelpers.toSet()
        usesFloatInterpolation = stmt.expr.usesFloatInterpolation

        val sections = mutableListOf<String>()
        if (constants.isNotEmpty()) sections += constants.joinToString("\n")
        if (stmt.expr.helperDeclarations.isNotEmpty()) {
            sections += stmt.expr.helperDeclarations.joinToString("\n")
        }
        sections += b.render()
        return "target triple = \"mos-c64\"\n\n" + sections.joinToString("\n\n")
    }

    /**
     * The static vtable of every trait impl in this file, plus an external
     * declaration for a vtable another file defines. A vtable is a plain
     * array of code pointers; its slot order is the trait's method order,
     * which is also the order [ImplDecl.methods] was filled in.
     */
    private fun emitVtables(program: Program, prefix: String, constants: MutableList<String>) {
        for (impl in program.impls) {
            val trait = impl.traitName ?: continue
            val symbol = vtableSymbol(prefix, impl.structName, trait)
            val entries = impl.methods.joinToString(", ") {
                "ptr @\"${methodSymbol(prefix, it)}\""
            }
            constants +=
                "@\"$symbol\" = constant [${impl.methods.size} x ptr] [$entries]"
        }
        for (vt in program.vtables) {
            if (vt.prefix == prefix) continue
            val symbol = vtableSymbol(vt.prefix, vt.structName, vt.traitName)
            constants += "@\"$symbol\" = external constant [${vt.arity} x ptr]"
        }
    }

    /** The module globals: every file-scope variable without a fixed address. */
    private fun emitGlobals(program: Program, prefix: String, constants: MutableList<String>) {
        // String literals referenced by global initialisers: one private
        // NUL-terminated array per distinct byte sequence.
        val stringConstants = LinkedHashMap<String, String>()
        fun stringPointer(bytes: ByteArray): String {
            val key = bytes.contentToString()
            return stringConstants.getOrPut(key) {
                val name = "@str_g_${stringConstants.size}"
                val chars = bytes.joinToString("") { llvmEscapeByte(it.toInt() and 0xFF) }
                constants +=
                    "$name = private unnamed_addr constant [${bytes.size + 1} x i8] c\"$chars\\00\""
                name
            }
        }
        for (g in program.globals) {
            if (g.enumValue != null) continue
            val location = g.location
            if (location is FixedAddress || location is FixedArray) {
                constants += fixedGlobalLine(prefix, g, location)
                continue
            }
            // A CPU flag variable has no storage: it is the register itself.
            if (location is CpuRegister) continue
            // A trait value is a 4-byte cell: data pointer + vtable pointer.
            if (g.type.kind == TypeKind.TRAIT) {
                constants += "@\"$prefix.${g.name}\" = global [4 x i8] zeroinitializer"
                continue
            }
            // A string global is a pointer slot; the pointer points at the
            // literal's private byte array. It is fully initialised in the
            // data section, even when the initialiser names an earlier
            // global: the folded value is the same pointer.
            if (g.type.kind == TypeKind.STRING) {
                val init = (g.constValue as? ConstValue.StringValue)
                    ?.let { stringPointer(it.bytes) } ?: "null"
                constants += "@\"$prefix.${g.name}\" = global ptr $init"
                continue
            }
            if (g.type.kind == TypeKind.STRUCT) {
                val size = structOf(g.type).size
                constants += "@\"$prefix.${g.name}\" = global [$size x i8] zeroinitializer"
                continue
            }
            if (g.type.kind == TypeKind.ARRAY) {
                val ety = g.type.elementType ?: throw error("Array ${g.name} has no element type")
                val count = arrayCount(g.type)
                if (ety.kind == TypeKind.STRUCT) {
                    val size = structOf(ety).size
                    constants +=
                        "@\"$prefix.${g.name}\" = global [$count x [$size x i8]] zeroinitializer"
                    continue
                }
                val ty = TypeMapper.ir(ety).llvmName()
                val body = when (val v = g.constValue) {
                    is ConstValue.ArrayValue -> if (v.elements.isNotEmpty()) v.spelling else "zeroinitializer"
                    else -> "zeroinitializer"
                }
                if (ety.kind == TypeKind.TRAIT) {
                    constants +=
                        "@\"$prefix.${g.name}\" = global [$count x [4 x i8]] zeroinitializer"
                    continue
                }
                constants += "@\"$prefix.${g.name}\" = global [$count x $ty] $body"
                continue
            }
            // A pure-literal initialiser is static data; one that references
            // something else (an earlier global, a function) cannot be a
            // constant expression, so the global is zero-initialised and the
            // file's init function stores the folded value before main.
            val init = g.init?.let {
                if (referencesAnotherEntity(it, program.resolved)) zeroFor(TypeMapper.ir(g.type))
                else g.constValue!!.spelling
            } ?: zeroFor(TypeMapper.ir(g.type))
            constants += "@\"$prefix.${g.name}\" = global ${TypeMapper.ir(g.type).llvmName()} $init"
        }
    }

    /**
     * The `declare` statements for the globals of other modules this one
     * uses: a global of another file is declared here, because its
     * definition lives in the other module.
     */
    private fun emitForeignGlobals(program: Program, prefix: String, constants: MutableList<String>) {
        for (foreign in program.visibleGlobals) {
            if (foreign.decl.enumValue != null) continue
            if (foreign.decl.location is FixedAddress || foreign.decl.location is FixedArray) continue
            // A CPU flag variable has no storage: it is the register itself.
            if (foreign.decl.location is CpuRegister) continue
            val name = "${foreign.prefix}.${foreign.decl.name}"
            val type = foreign.decl.type
            val ty = when {
                type.kind == TypeKind.STRUCT -> "[${structOf(type).size} x i8]"
                type.kind == TypeKind.TRAIT -> "[4 x i8]"
                type.kind == TypeKind.ARRAY && type.elementType?.kind == TypeKind.STRUCT -> {
                    val size = structOf(type.elementType!!).size
                    "[${arrayCount(type)} x [$size x i8]]"
                }
                type.kind == TypeKind.ARRAY && type.elementType?.kind == TypeKind.TRAIT ->
                    "[${arrayCount(type)} x [4 x i8]]"
                else -> TypeMapper.ir(type).llvmName()
            }
            constants += "@$name = external global $ty"
        }
    }

    /**
     * The `declare` statements for the functions of other modules this one
     * calls.
     *
     * A module that calls into another module declares the function it
     * calls: the definition lives in the other module's object file, and the
     * declaration gives the call a signature to compile against.
     */
    private fun emitForeignDeclarations(program: Program, prefix: String, constants: MutableList<String>) {
        val foreign = linkedMapOf<String, FunDecl>()
        val refs = mutableListOf<VarRef>()
        for (g in program.globals) g.init?.let { collectVarRefs(it, refs) }
        for (fn in program.functions) for (stmt in fn.body) collectStmtVarRefs(stmt, refs)
        for (impl in program.impls) for (fn in impl.methods) for (stmt in fn.body) collectStmtVarRefs(stmt, refs)
        for (ref in refs) {
            val symbol = program.resolved[ref] as? Symbol.FunSymbol ?: continue
            if (symbol.prefix == prefix || symbol.prefix.isEmpty()) continue
            foreign["${symbol.prefix}.${symbol.decl.name}"] = symbol.decl
        }
        for ((name, fn) in foreign) {
            val ret = TypeMapper.ir(fn.retType ?: Types.unit).llvmName()
            val params = fn.params.joinToString(", ") { p ->
                "${TypeMapper.ir(p.type).llvmName()} %${p.name}"
            }
            constants += "declare $ret @\"$name\"($params)"
        }
        // A method of another file needs a declaration with the hidden
        // receiver: its definition lives in the module that owns the struct.
        for (binding in program.methods) {
            if (binding.prefix == prefix || binding.prefix.isEmpty()) continue
            val fn = binding.decl
            val ret = TypeMapper.ir(fn.retType ?: Types.unit).llvmName()
            val params = (listOf("ptr %this") + fn.params.map { p ->
                "${TypeMapper.ir(p.type).llvmName()} %${p.name}"
            }).joinToString(", ")
            constants += "declare $ret @\"${methodSymbol(binding.prefix, fn)}\"($params)"
        }
    }

    /** Every variable reference in [expr]. */
    private fun collectVarRefs(expr: Expr, out: MutableList<VarRef>) {
        when (expr) {
            is VarRef -> out += expr
            is UnaryOp -> collectVarRefs(expr.operand, out)
            is BinaryOp -> { collectVarRefs(expr.left, out); collectVarRefs(expr.right, out) }
            is Call -> { collectVarRefs(expr.callee, out); expr.args.forEach { collectVarRefs(it, out) } }
            is ArrayAccess -> { collectVarRefs(expr.array, out); collectVarRefs(expr.index, out) }
            is Assign -> { collectVarRefs(expr.target, out); collectVarRefs(expr.value, out) }
            is IfExpr -> {
                collectVarRefs(expr.cond, out)
                expr.then.forEach { collectStmtVarRefs(it, out) }
                expr.otherwise.forEach { collectStmtVarRefs(it, out) }
            }
            is WhenExpr -> {
                expr.subject?.let { collectVarRefs(it, out) }
                expr.entries.forEach { entry ->
                    entry.conditions.forEach { collectVarRefs(it, out) }
                    entry.body.forEach { collectStmtVarRefs(it, out) }
                }
            }
            is Convert -> collectVarRefs(expr.value, out)
            is StringTemplate -> expr.parts.forEach {
                if (it is Ast.StringTemplate.Value) collectVarRefs(it.expr, out)
            }
            else -> Unit
        }
    }

    /** Every variable reference in [stmt]. */
    private fun collectStmtVarRefs(stmt: Stmt, out: MutableList<VarRef>) {
        when (stmt) {
            is LocalDecl -> stmt.init?.let { collectVarRefs(it, out) }
            is ExprStmt -> collectVarRefs(stmt.expr, out)
            is BlockStmt -> stmt.body.forEach { collectStmtVarRefs(it, out) }
            is IfStmt -> {
                collectVarRefs(stmt.cond, out)
                stmt.then.forEach { collectStmtVarRefs(it, out) }
                stmt.otherwise.forEach { collectStmtVarRefs(it, out) }
            }
            is WhileStmt -> {
                collectVarRefs(stmt.cond, out)
                stmt.body.forEach { collectStmtVarRefs(it, out) }
            }
            is ReturnStmt -> stmt.value?.let { collectVarRefs(it, out) }
            is WhenStmt -> {
                stmt.subject?.let { collectVarRefs(it, out) }
                stmt.entries.forEach { entry ->
                    entry.conditions.forEach { collectVarRefs(it, out) }
                    entry.body.forEach { collectStmtVarRefs(it, out) }
                }
            }
            else -> Unit
        }
    }

    /**
     * A fixed-address variable owns no value storage; the module carries its
     * address as a pointer constant in the `inttoptr` form every access uses.
     */
    private fun fixedGlobalLine(prefix: String, g: GlobalDecl, location: Location): String {
        val element = if (g.type.kind == TypeKind.ARRAY)
            g.type.elementType ?: throw error("Array ${g.name} has no element type")
        else g.type
        val ptrTy = pointerType(element)
        val address = when (location) {
            is FixedAddress -> location.address
            is FixedArray -> location.address
            else -> throw error("Unsupported location $location")
        }
        return "@\"$prefix.${g.name}\" = global $ptrTy inttoptr (i64 $address to $ptrTy)"
    }

    /**
     * A fixed-address function declaration.
     *
     * The spec emits it as a function whose body is the ROM call:
     *
     * ```
     * define void @chrout(i8 %c) {
     *   call void asm sideeffect "jsr $$ffd2", "a"(i8 %c)
     *   ret void
     * }
     * ```
     *
     * Each parameter's `^CPU.X` annotation says which register the argument
     * arrives in, and the constraint list follows the declaration order.
     *
     * A return type's `^CPU.X` annotation says which register the result
     * arrives in: the body loads it and returns it, so the call site gets
     * the value as an ordinary SSA value.
     */
    private fun emitFixedFunction(
        fn: pl.qus.wolin.ast.FunDecl,
        address: Int,
        params: List<IrValue>,
        prefix: String,
    ) {
        b.startFunction(
            "$prefix.${fn.name}",
            TypeMapper.ir(fn.retType ?: Types.unit),
            params,
        )
        val args = fn.params.mapIndexed { index, param ->
            val register = (param.location as? CpuRegister)?.name
                ?: throw error("Fixed address function ${fn.name} needs a CPU register parameter")
            params[index] to register
        }
        ExternalCalls().emitCall(b, address, args)
        val resultRegister = (fn.retLocation as? CpuRegister)?.name
        val retType = fn.retType
        when {
            // A bool result is the Carry flag: the kernal routine signals
            // success or failure in CPU.C, so read it back as a bool.
            retType?.kind == TypeKind.BOOL -> b.ret(CpuAccessors.flagRead(b, "CPU.C"))
            resultRegister == null -> b.ret(null)
            retType?.kind == TypeKind.UWORD -> b.ret(CpuAccessors.readRegisterPair(b, resultRegister))
            else -> b.ret(CpuAccessors.readRegister(b, resultRegister))
        }
        b.endFunction()
    }

    /** The typed-pointer spelling of a type: `i8*`, `i16*`, `ptr`, … */
    private fun pointerType(t: WolinType): String = when (t.kind) {
        TypeKind.UBYTE, TypeKind.BYTE -> "i8*"
        TypeKind.UWORD, TypeKind.WORD -> "i16*"
        TypeKind.BOOL -> "i1*"
        TypeKind.FLOAT -> "float*"
        // A string variable is already a pointer; its fixed address names
        // the slot that holds it. A struct is addressed as bytes, and a
        // trait value is a 4-byte cell.
        TypeKind.STRING, TypeKind.ARRAY, TypeKind.FUNCTION, TypeKind.STRUCT,
        TypeKind.TRAIT -> "ptr"
        else -> throw error("Cannot form a pointer to $t")
    }

    /** The struct a struct type names. */
    private fun structOf(type: WolinType): StructDecl {
        val name = type.structName ?: throw error("Struct type $type has no name")
        return structs.firstOrNull { it.name == name } ?: throw error("Unknown struct $name")
    }

    /** The element count of a global array: explicit, or the index type's domain. */
    private fun arrayCount(t: WolinType): Int {
        if (t.indexType?.kind !in setOf(TypeKind.UBYTE, TypeKind.BYTE, TypeKind.UWORD, TypeKind.WORD)) {
            throw error("Global array index must be an integer type")
        }
        return arrayLength(t)
    }

    /** The zero value of a type, for an uninitialised global. */
    private fun zeroFor(ty: IrType): String = when (ty) {
        IrType.I1 -> "false"
        IrType.Float -> "0.0"
        IrType.Ptr -> "null"
        else -> "0"
    }
}
