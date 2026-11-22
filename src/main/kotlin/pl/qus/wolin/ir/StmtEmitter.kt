package pl.qus.wolin.ir

import pl.qus.wolin.ast.ArrayAccess
import pl.qus.wolin.ast.ArrayLiteral
import pl.qus.wolin.ast.IntLit
import pl.qus.wolin.ast.StringLit
import pl.qus.wolin.ast.Assign
import pl.qus.wolin.ast.BinaryOp
import pl.qus.wolin.ast.BreakStmt
import pl.qus.wolin.ast.BlockStmt
import pl.qus.wolin.ast.Call
import pl.qus.wolin.ast.ContinueStmt
import pl.qus.wolin.ast.Convert
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.ExprStmt
import pl.qus.wolin.ast.ForeignGlobal
import pl.qus.wolin.ast.FixedAddress
import pl.qus.wolin.ast.CpuRegister
import pl.qus.wolin.ast.FixedArray
import pl.qus.wolin.ast.FunDecl
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.IfExpr
import pl.qus.wolin.ast.IfStmt
import pl.qus.wolin.ast.LabelStmt
import pl.qus.wolin.ast.LocalDecl
import pl.qus.wolin.ast.ReturnAtAddress
import pl.qus.wolin.ast.ReturnAtLabel
import pl.qus.wolin.ast.ReturnAtStmt
import pl.qus.wolin.ast.ReturnStmt
import pl.qus.wolin.ast.Stmt
import pl.qus.wolin.ast.UnaryOp
import pl.qus.wolin.ast.WhenEntry
import pl.qus.wolin.ast.WhenExpr
import pl.qus.wolin.ast.WhenStmt
import pl.qus.wolin.ast.WhileStmt
import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.WolinType
import java.util.IdentityHashMap

/**
 * Emits statements into the current function of [b].
 *
 * Owns the per-function state: the [vars] scope stack, the enclosing-loop
 * labels for `break`/`continue`, and a mirror of the builder's label table,
 * which lets a block label be reserved before the block that carries it is
 * opened (forward branches).
 */
class StmtEmitter(private val b: IrBuilder) {
    /** The expression emitter; wired by [ModuleEmitter] before any statement is emitted. */
    lateinit var expr: ExprEmitter

    /** Variable storage for the function being emitted. */
    val vars = VarScope()

    /** (condition-block, end-block) labels of the enclosing loops, innermost last. */
    private val loops = ArrayDeque<Pair<String, String>>()

    /** label name -> reserved block label, filled by [startFunction]. */
    private val labelBlocks = mutableMapOf<String, String>()

    /** Mirrors the builder's used labels so labels can be reserved ahead of time. */
    private val usedLabels = mutableSetOf("entry")
    private var nextLabel = 1

    /** Whether the current block already has its terminator. */
    private var terminated = false

    /** The entry-block `alloca` of every local declaration of the function. */
    private val localSlots = IdentityHashMap<LocalDecl, IrValue>()

    /**
     * Prepares the function: records file-scope variables and parameters,
     * allocates a stack slot for every local (all `alloca`s sit at the top of
     * `entry`, in source order), emits the body, and closes off the last
     * block.
     */
    fun startFunction(
        prefix: String,
        params: List<IrValue>,
        globals: List<GlobalDecl>,
        fn: FunDecl,
        foreignGlobals: List<ForeignGlobal> = emptyList(),
    ) {
        vars.reset()
        usedLabels.clear()
        usedLabels += "entry"
        nextLabel = 1
        terminated = false
        loops.clear()
        localSlots.clear()
        labelBlocks.clear()

        // A global of another file lives under that file's prefix, so the
        // reference names the file that declares it. These are declared
        // first: the file's own globals shadow them.
        for (foreign in foreignGlobals) {
            val storage = when (val loc = foreign.decl.location) {
                is FixedAddress -> VarStorage.Fixed(loc.address)
                is FixedArray -> VarStorage.Fixed(loc.address)
                is CpuRegister -> VarStorage.CpuFlag(loc.name)
                else -> VarStorage.Global("${foreign.prefix}.${foreign.decl.name}")
            }
            vars.declare(foreign.decl.name, storage)
        }
        for (g in globals) {
            val storage = when (val loc = g.location) {
                is FixedAddress -> VarStorage.Fixed(loc.address)
                is FixedArray -> VarStorage.Fixed(loc.address)
                is CpuRegister -> VarStorage.CpuFlag(loc.name)
                else -> VarStorage.Global("$prefix.${g.name}")
            }
            vars.declare(g.name, storage)
        }
        params.forEach { vars.declare(it.name.removePrefix("%"), VarStorage.Param(it)) }

        val decls = mutableListOf<LocalDecl>()
        collectLocalDecls(fn.body, decls)
        decls.filter { it.location !is FixedAddress && it.location !is FixedArray && it.location !is CpuRegister }
             .forEach { localSlots[it] = b.alloca(TypeMapper.ir(it.type)) }

        // One block per label, in source order, reserved before the body
        // emits so a forward jump can name its target.
        collectLabelNames(fn.body).forEach { name -> labelBlocks[name] = reserveLabel(name) }

        if (returnsLastExpression) {
            // A function literal's value is its last expression: the
            // expression is emitted once, and its value is returned.
            val last = fn.body.lastOrNull()
            val head = if (last is ExprStmt) fn.body.dropLast(1) else fn.body
            head.forEach { emit(it) }
            if (!terminated && last is ExprStmt) {
                b.ret(expr.emit(last.expr))
                terminated = true
            }
        } else {
            fn.body.forEach { emit(it) }
        }

        if (!terminated) {
            val void = fn.retType == null || fn.retType.kind == TypeKind.UNIT
            if (void) b.ret(null) else if (!returnsLastExpression) {
                throw error("Function ${fn.name} must return a value")
            }
        }
    }

    /**
     * Whether the current function returns the value of its last expression
     * instead of a `return` statement: a function literal does.
     */
    var returnsLastExpression: Boolean = false

    /** Emits one statement into the current block. */
    fun emit(s: Stmt) {
        // A label opens its block even where the block is already terminated:
        // a label after a `return` is dead code, but its block still exists.
        if (s is LabelStmt) {
            val target = labelBlocks[s.name]!!
            ensureTerminatedOr(target)
            openReserved(target)
            return
        }
        // A block that already carries its terminator cannot take more code:
        // statements after a `return@` or a `break` are dead. They still parse
        // and type-check; they are just not emitted.
        if (terminated) return
        when (s) {
            is LocalDecl -> emitLocalDecl(s)
            is ExprStmt -> {
                expr.emit(s.expr)
            }
            is BlockStmt -> {
                vars.push()
                try {
                    s.body.forEach { emit(it) }
                } finally {
                    vars.pop()
                }
            }
            is IfStmt -> emitIf(s)
            is WhileStmt -> emitWhile(s)
            is WhenStmt -> emitWhen(s)
            is ReturnStmt -> {
                b.ret(s.value?.let { expr.emit(it) })
                terminated = true
            }
            is ReturnAtStmt -> {
                when (val target = s.target) {
                    is ReturnAtAddress -> {
                        ReturnAtEmitter(b).emit(target.address)
                        terminated = true
                    }
                    is ReturnAtLabel -> {
                        b.branch(labelBlocks[target.name]!!)
                        terminated = true
                    }
                }
            }
            is BreakStmt -> {
                s.label?.let { name ->
                    b.branch(labelBlocks[name]!!)
                    terminated = true
                    return
                }
                val target = loops.lastOrNull() ?: throw error("break outside a loop")
                b.branch(target.second)
                terminated = true
            }
            is ContinueStmt -> {
                s.label?.let { name ->
                    b.branch(labelBlocks[name]!!)
                    terminated = true
                    return
                }
                val target = loops.lastOrNull() ?: throw error("continue outside a loop")
                b.branch(target.first)
                terminated = true
            }
            else -> throw error("Unknown statement ${s::class.simpleName}")
        }
    }

    private fun emitLocalDecl(d: LocalDecl) {
        when (val loc = d.location) {
            is FixedAddress -> {
                d.init?.let { init ->
                    val v = expr.emit(init)
                    expr.emitStoreFixed(loc.address, v, TypeMapper.ir(d.type))
                }
                vars.declare(d.name, VarStorage.Fixed(loc.address))
            }
            is FixedArray -> {
                d.init?.let { init -> emitArrayInit(init, loc.address, d.type) }
                vars.declare(d.name, VarStorage.Fixed(loc.address))
            }
            is CpuRegister -> {
                d.init?.let { init ->
                    CpuAccessors.writeFlag(b, loc.name, expr.emit(init))
                }
                vars.declare(d.name, VarStorage.CpuFlag(loc.name))
            }
            else -> {
                val ptr = localSlots[d] ?: throw error("Local ${d.name} has no slot")
                d.init?.let { init ->
                    val v = expr.emit(init)
                    b.instr(IrType.Void, "store ${TypeMapper.ir(d.type).llvmName()} ${v.name}, ptr ${ptr.name}")
                }
                vars.declare(d.name, VarStorage.Local(ptr))
            }
}
    }

    /**
     * The per-element stores of an array initialiser on a fixed-address
     * local. The checker has already typed every element and rewritten a
     * string initialiser into byte elements, so each element is an
     * [IntLit] / [BoolLit] / [RealLit] / [UnaryOp] / [BinaryOp] / [Convert]
     * / [VarRef] / [Call] — any expression the ExprEmitter can evaluate.
     */
    private fun emitArrayInit(init: Expr, address: Int, type: WolinType) {
        val ety = type.elementType ?: throw error("Array ${type} has no element type")
        val ity = TypeMapper.ir(ety)
        val elemSize = if (ity == IrType.I16) 2 else 1
        val elements: List<Expr> = when (init) {
            is ArrayLiteral -> init.elements
            is StringLit -> init.bytes.map { IntLit(it.toLong(), ety) } + IntLit(0, ety)
            else -> throw error("fixed array locals take no initialiser")
        }
        elements.forEachIndexed { i, e ->
            val v = expr.emit(e)
            expr.emitStoreFixed(address + i * elemSize, v, ity)
        }
    }

    private fun emitIf(s: IfStmt) {
        val c = expr.emit(s.cond)
        val thenL = reserveLabel("if.then")
        val elseL = if (s.otherwise.isNotEmpty()) reserveLabel("if.else") else null
        val endL = reserveLabel("if.end")
        conditionalBranch(c, thenL, elseL ?: endL)
        openReserved(thenL)
        s.then.forEach { emit(it) }
        ensureTerminatedOr(endL)
        if (elseL != null) {
            openReserved(elseL)
            s.otherwise.forEach { emit(it) }
            ensureTerminatedOr(endL)
        }
        openReserved(endL)
    }

    private fun emitWhile(s: WhileStmt) {
        if (!s.isDoWhile) {
            val loopL = reserveLabel("loop")
            val bodyL = reserveLabel("body")
            val endL = reserveLabel("end")
            unconditionalBranch(loopL)
            openReserved(loopL)
            loops.addLast(loopL to endL)
            try {
                conditionalBranch(expr.emit(s.cond), bodyL, endL)
                openReserved(bodyL)
                s.body.forEach { emit(it) }
                ensureTerminatedOr(loopL)
                openReserved(endL)
            } finally {
                loops.removeLast()
            }
        } else {
            val bodyL = reserveLabel("body")
            val loopL = reserveLabel("loop")
            val endL = reserveLabel("end")
            unconditionalBranch(bodyL)
            openReserved(bodyL)
            loops.addLast(loopL to endL)
            try {
                s.body.forEach { emit(it) }
                ensureTerminatedOr(loopL)
                openReserved(loopL)
                conditionalBranch(expr.emit(s.cond), bodyL, endL)
                openReserved(endL)
            } finally {
                loops.removeLast()
            }
        }
    }

    private fun emitWhen(s: WhenStmt) {
        val subject = s.subject?.let { expr.emit(it) }
        whenFlow(subject, s.entries) { entry, endL ->
            emitWhenEntryBody(entry, endL)
            null
        }
    }

    /**
     * Emits a `when`'s control flow, shared by the statement and the
     * expression forms. [emitBody] is called once per branch at that branch's
     * body label, given the end label the body falls through to; it returns
     * the value of the branch's last expression, or null when the branch does
     * not produce one. The result is each producing branch's value with its
     * body label, or null when no branch produced a value: the expression form
     * phi-merges those into the value, the statement form ignores it.
     */
    fun whenFlow(
        subject: IrValue?,
        entries: List<WhenEntry>,
        emitBody: (WhenEntry, String) -> IrValue?,
    ): List<Pair<IrValue, String>>? {
        val nonElse = entries.filter { !it.isElse }
        val elseEntry = entries.lastOrNull { it.isElse }

        if (nonElse.isEmpty()) {
            if (elseEntry == null) return null
            val elseL = reserveLabel("when.e")
            val endL = reserveLabel("when.end")
            unconditionalBranch(elseL)
            openReserved(elseL)
            val v = emitBody(elseEntry, endL)
            val block = b.block()
            openReserved(endL)
            return if (v != null) listOf(v to block) else null
        }

        val endL = reserveLabel("when.end")
        // The else body is the fall-through target of the last comparison.
        val elseL = if (elseEntry != null) reserveLabel("when.e") else null

        // The first comparison sits in the current block; the false target of
        // entry i is `when.n<i>`, where entry i + 1 is tested.
        val bodyLabels = mutableListOf<String>()
        val testLabels = mutableListOf<String>()
        for ((i, entry) in nonElse.withIndex()) {
            if (i > 0) openReserved(testLabels[i - 1])
            val m = matchValue(entry, subject)
            val bodyL = reserveLabel("when.b$i")
            bodyLabels += bodyL
            val nextL = if (i == nonElse.lastIndex) elseL ?: endL
            else reserveLabel("when.n$i").also { testLabels += it }
            conditionalBranch(m, bodyL, nextL)
        }
        val values = mutableListOf<Pair<IrValue, String>>()
        for ((i, entry) in nonElse.withIndex()) {
            openReserved(bodyLabels[i])
            // A branch can leave through a labelled jump, so the phi must
            // name the block that actually produced the value.
            emitBody(entry, endL)?.let { values += it to b.block() }
        }
        if (elseL != null) {
            openReserved(elseL)
            emitBody(elseEntry!!, endL)?.let { values += it to b.block() }
        }
        openReserved(endL)
        return values.ifEmpty { null }
    }

    /**
     * The match test of a non-`else` entry. Subject form: an `icmp eq` chain
     * against the subject, OR-ed when the entry has several conditions.
     * Condition form: the boolean conditions OR-ed as is.
     */
    private fun matchValue(entry: WhenEntry, subject: IrValue?): IrValue {
        var m: IrValue? = null
        for (condition in entry.conditions) {
            val c = if (subject != null) {
                val v = expr.emit(condition)
                b.instr(IrType.I1, "icmp eq ${subject.type.llvmName()} ${subject.name}, ${v.name}")
            } else {
                val v = expr.emit(condition)
                check(v.type == IrType.I1) { "when condition must be bool" }
                v
            }
            m = if (m == null) c else b.instr(IrType.I1, "or i1 ${m.name}, ${c.name}")
        }
        return m ?: throw error("when entry has no conditions")
    }

    private fun emitWhenEntryBody(entry: WhenEntry, endL: String) {
        vars.push()
        try {
            entry.body.forEach { emit(it) }
        } finally {
            vars.pop()
        }
        ensureTerminatedOr(endL)
    }

    /**
     * Emits a branch body and returns the value of its last expression, or
     * null when the body does not end in an expression (a jump, a block that
     * ends elsewhere, or an empty body).
     */
    fun emitValue(stmts: List<Stmt>): IrValue? {
        if (stmts.isEmpty()) return null
        stmts.dropLast(1).forEach { emit(it) }
        // A terminator among the earlier statements makes the whole value
        // position dead: the branch leaves through the jump, and nothing is
        // emitted for the last expression.
        if (terminated) return null
        return when (val last = stmts.last()) {
            is ExprStmt -> expr.emit(last.expr)
            is BlockStmt -> {
                vars.push()
                try {
                    emitValue(last.body)
                } finally {
                    vars.pop()
                }
            }
            else -> {
                emit(last)
                null
            }
        }
    }

    // ---- block plumbing -----------------------------------------------------

    /**
     * Reserves [name] as a block label: the name itself when it is free in
     * this function, the next free `bbN` when it is taken. The label can be
     * branched to before [openReserved] creates the block.
     */
    fun reserveLabel(name: String): String {
        if (name !in usedLabels) {
            usedLabels += name
            return name
        }
        while ("bb$nextLabel" in usedLabels) nextLabel++
        val label = "bb$nextLabel"
        nextLabel++
        usedLabels += label
        return label
    }

    /** Opens a previously reserved label as the next block. */
    fun openReserved(label: String) {
        b.addBlock(label)
        terminated = false
    }

    fun unconditionalBranch(target: String) {
        b.branch(target)
        terminated = true
    }

    fun conditionalBranch(cond: IrValue, ifTrue: String, ifFalse: String) {
        b.condBranch(cond, ifTrue, ifFalse)
        terminated = true
    }

    /** Branches to [target] unless the current block is already terminated. */
    fun ensureTerminatedOr(target: String) {
        if (!terminated) unconditionalBranch(target)
    }

    // ---- local pre-scan ------------------------------------------------------

    /** Collects every [LocalDecl] in source order, so its `alloca` lands in `entry`. */
    private fun collectLocalDecls(stmts: List<Stmt>, out: MutableList<LocalDecl>) {
        for (s in stmts) {
            when (s) {
                is LocalDecl -> out += s
                is BlockStmt -> collectLocalDecls(s.body, out)
                is IfStmt -> {
                    collectLocalDecls(s.then, out)
                    collectLocalDecls(s.otherwise, out)
                }
                is WhileStmt -> collectLocalDecls(s.body, out)
                is WhenStmt -> s.entries.forEach { collectLocalDecls(it.body, out) }
                is ExprStmt -> collectLocalDeclsInExpr(s.expr, out)
                is ReturnStmt -> s.value?.let { collectLocalDeclsInExpr(it, out) }
                else -> Unit
            }
        }
    }

    /** Only an `if` in expression position can nest statements. */
    private fun collectLocalDeclsInExpr(e: Expr, out: MutableList<LocalDecl>) {
        when (e) {
            is IfExpr -> {
                collectLocalDecls(e.then, out)
                collectLocalDecls(e.otherwise, out)
            }
            is WhenExpr -> e.entries.forEach { collectLocalDecls(it.body, out) }
            is UnaryOp -> collectLocalDeclsInExpr(e.operand, out)
            is BinaryOp -> {
                collectLocalDeclsInExpr(e.left, out)
                collectLocalDeclsInExpr(e.right, out)
            }
            is Call -> e.args.forEach { collectLocalDeclsInExpr(it, out) }
            is Assign -> {
                (e.target as? ArrayAccess)?.let { t ->
                    collectLocalDeclsInExpr(t.array, out)
                    collectLocalDeclsInExpr(t.index, out)
                }
                collectLocalDeclsInExpr(e.value, out)
            }
            is ArrayAccess -> {
                collectLocalDeclsInExpr(e.array, out)
                collectLocalDeclsInExpr(e.index, out)
            }
            is Convert -> collectLocalDeclsInExpr(e.value, out)
            else -> Unit
        }
    }

    // ---- label pre-scan ------------------------------------------------------

    /** Collects every label name in source order, so its block can be reserved up front. */
    private fun collectLabelNames(stmts: List<Stmt>): List<String> {
        val out = LinkedHashSet<String>()
        collectLabelNamesIn(stmts, out)
        return out.toList()
    }

    private fun collectLabelNamesIn(stmts: List<Stmt>, out: MutableSet<String>) {
        for (s in stmts) {
            when (s) {
                is LabelStmt -> out += s.name
                is BlockStmt -> collectLabelNamesIn(s.body, out)
                is IfStmt -> {
                    collectLabelNamesIn(s.then, out)
                    collectLabelNamesIn(s.otherwise, out)
                }
                is WhileStmt -> collectLabelNamesIn(s.body, out)
                is WhenStmt -> s.entries.forEach { collectLabelNamesIn(it.body, out) }
                is ExprStmt -> collectLabelNamesInExpr(s.expr, out)
                is ReturnStmt -> s.value?.let { collectLabelNamesInExpr(it, out) }
                else -> Unit
            }
        }
    }

    /** Only an `if` or `when` in expression position can nest statements. */
    private fun collectLabelNamesInExpr(e: Expr, out: MutableSet<String>) {
        when (e) {
            is IfExpr -> {
                collectLabelNamesIn(e.then, out)
                collectLabelNamesIn(e.otherwise, out)
            }
            is WhenExpr -> e.entries.forEach { collectLabelNamesIn(it.body, out) }
            else -> Unit
        }
    }
}
