package pl.qus.wolin.ir

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
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.VarRef
import pl.qus.wolin.ast.WhenExpr
import pl.qus.wolin.ast.WhenStmt
import pl.qus.wolin.ast.WhileStmt
import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.Types
import pl.qus.wolin.sema.WolinType

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

    /** Emits [program] with names mangled as `<mangledPrefix>.<symbol>`. */
    fun emitFile(program: Program, mangledPrefix: String): String {
        val constants = mutableListOf<String>()
        val stmt = StmtEmitter(b)
        stmt.expr = ExprEmitter(b, stmt, constants, mangledPrefix, program.resolved, program.globals)

        emitGlobals(program, mangledPrefix, constants)
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
            b.startFunction(
                "$mangledPrefix.${fn.name}",
                TypeMapper.ir(fn.retType ?: Types.unit),
                params,
                attributes = if (fn.isInterrupt) listOf("interrupt") else emptyList(),
            )
            stmt.startFunction(mangledPrefix, params, program.globals, fn, program.visibleGlobals)
            b.endFunction()
        }
        // The function literals are their own functions, so they are emitted
        // after the file's functions: a module holds one function open at a
        // time.
        stmt.expr.lambda.emitCollected(program.globals)

        // The init function is emitted into the same builder, so it renders
        // with the module; the driver calls it before main, even when it
        // stores nothing.
        initFnName = GlobalInitEmitter(b).emitFile(program, mangledPrefix)

        val sections = mutableListOf<String>()
        if (constants.isNotEmpty()) sections += constants.joinToString("\n")
        sections += b.render()
        return "target triple = \"mos-c64\"\n\n" + sections.joinToString("\n\n")
    }

    /** The module globals: every file-scope variable without a fixed address. */
    private fun emitGlobals(program: Program, prefix: String, constants: MutableList<String>) {
        for (g in program.globals) {
            val location = g.location
            if (location is FixedAddress || location is FixedArray) {
                constants += fixedGlobalLine(prefix, g, location)
                continue
            }
            if (g.type.kind == TypeKind.ARRAY) {
                if (g.init != null) throw error("Global array ${g.name} must be zero-initialised in v1")
                val ety = g.type.elementType ?: throw error("Array ${g.name} has no element type")
                constants +=
                    "@\"$prefix.${g.name}\" = global [${arrayCount(g.type)} x ${TypeMapper.ir(ety).llvmName()}] zeroinit"
                continue
            }
            // A pure-literal initialiser is static data; one that references
            // something else (an earlier global, a function) cannot be a
            // constant expression, so the global is zero-initialised and the
            // file's init function stores the folded value before main.
            val init = g.init?.let {
                if (referencesAnotherEntity(it)) zeroFor(TypeMapper.ir(g.type))
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
            val name = "${foreign.prefix}.${foreign.decl.name}"
            val ty = TypeMapper.ir(foreign.decl.type).llvmName()
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
        b.ret(null)
        b.endFunction()
    }

    /** The typed-pointer spelling of a type: `i8*`, `i16*`, … */
    private fun pointerType(t: WolinType): String = when (TypeMapper.ir(t)) {
        IrType.I8 -> "i8*"
        IrType.I16 -> "i16*"
        IrType.I1 -> "i1*"
        IrType.Float -> "float*"
        else -> throw error("Cannot form a pointer to $t")
    }

    /** The element count of a global array: how many the index type can name. */
    private fun arrayCount(t: WolinType): Int = when (t.indexType?.kind) {
        TypeKind.UBYTE, TypeKind.BYTE -> 256
        TypeKind.UWORD, TypeKind.WORD -> 65536
        else -> throw error("Global array index must be an integer type")
    }

    /** The zero value of a type, for an uninitialised global. */
    private fun zeroFor(ty: IrType): String = when (ty) {
        IrType.I1 -> "false"
        IrType.Float -> "0.0"
        IrType.Ptr -> "null"
        else -> "0"
    }
}
