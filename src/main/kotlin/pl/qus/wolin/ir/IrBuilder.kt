package pl.qus.wolin.ir

/**
 * Builds LLVM IR text.
 *
 * The builder owns the three things every emitter would otherwise repeat:
 *
 *  - SSA value names: `%v0`, `%v1`, … allocated in emission order and reset
 *    per function. Function parameters keep the names they were given.
 *  - Basic blocks: `startFunction` opens the `entry` block; [addBlock] opens
 *    the next one and makes it current.
 *  - Terminators: every block needs exactly one. [render] rejects a block
 *    that has none.
 *
 * Instruction text is rendered verbatim behind the result binding the
 * builder allocates, so `instr(IrType.I8, "add i8 %a, %b")` renders
 * `%v0 = add i8 %a, %b`. A [IrType.Void] result type means the instruction
 * produces no value, so nothing is bound and no SSA name is consumed:
 * `instr(IrType.Void, "ldy #0")` renders `ldy #0`.
 */
class IrBuilder {
    private class Block(val label: String) {
        /** Phi nodes come first in a block, so they are kept apart. */
        val phis = mutableListOf<String>()
        val instructions = mutableListOf<String>()
        var terminator: String? = null
    }

    private class Function(
        val name: String,
        val retType: IrType,
        val params: List<IrValue>,
        val linkage: String?,
    ) {
        val blocks = mutableListOf<Block>()
        val usedLabels = mutableSetOf<String>()
        var nextValue = 0
        var nextLabel = 1

        val current: Block get() = blocks.last()

        fun freshValue(): String = "%v${nextValue++}"

        /** The wanted label, or the next free `bbN` label when it is taken. */
        fun labelFor(wanted: String): String {
            if (wanted !in usedLabels) {
                usedLabels += wanted
                return wanted
            }
            while ("bb$nextLabel" in usedLabels) nextLabel++
            val label = "bb$nextLabel"
            nextLabel++
            usedLabels += label
            return label
        }
    }

    private val functions = mutableListOf<Function>()
    private var open: Function? = null

    /**
     * Opens a function and its `entry` block.
     *
     * [linkage] is the LLVM linkage, e.g. `internal` for a function that
     * stays inside the module. It is omitted when null.
     */
    fun startFunction(
        name: String,
        retType: IrType,
        params: List<IrValue> = emptyList(),
        linkage: String? = null,
    ) {
        check(open == null) { "function ${open?.name} is still open" }
        val fn = Function(name, retType, params, linkage)
        fn.blocks += Block("entry")
        fn.usedLabels += "entry"
        functions += fn
        open = fn
    }

    /** Closes the current function. Its blocks are checked by [render]. */
    fun endFunction() {
        check(open != null) { "no function is open" }
        open = null
    }

    /**
     * Opens the next block of the current function and makes it current.
     *
     * [name] is the label the block gets, so callers can branch to it and
     * name it as a phi predecessor. A label already taken in this function
     * falls back to the next free `bbN` label.
     */
    fun addBlock(name: String) {
        val fn = requireFunction()
        fn.blocks += Block(fn.labelFor(name))
    }

    /** The label of the current block. */
    fun block(): String = requireFunction().current.label

    /**
     * Materialises a value of [type] and returns its SSA name. The value is
     * defined by a zero constant, so the caller can use the name right away.
     */
    fun value(type: IrType): IrValue {
        val text = when (type) {
            IrType.I1, IrType.I8, IrType.I16, IrType.I32 -> "add ${type.llvmName()} 0, 0"
            IrType.Float -> "fadd float 0.0, 0.0"
            IrType.Ptr -> "getelementptr i8, ptr null, i32 0"
            IrType.Void -> throw IllegalArgumentException("void has no value")
            is IrType.Array -> throw IllegalArgumentException("array types have no value form")
        }
        return instr(type, text)
    }

    /** Allocates a stack slot of [type] and returns the pointer to it. */
    fun alloca(type: IrType): IrValue = instr(IrType.Ptr, "alloca ${type.llvmName()}")

    /**
     * Emits one instruction and returns the SSA name of its result.
     *
     * [text] is the instruction itself without the result binding — the
     * builder allocates the name and writes it in front of the text. [text]
     * is rendered verbatim, so a caller that spells the operands out in the
     * text gets exactly what it wrote.
     *
     * [operands] are the values the instruction reads. The builder does not
     * rewrite the text from them; they record what the instruction reads.
     */
    fun instr(type: IrType, text: String, operands: List<IrValue> = emptyList()): IrValue {
        val block = requireFunction().current
        check(block.terminator == null) { "block ${block.label} already has a terminator" }
        if (type == IrType.Void) {
            block.instructions += text
            return IrValue("", IrType.Void)
        }
        val name = requireFunction().freshValue()
        block.instructions += "$name = $text"
        return IrValue(name, type)
    }

    /**
     * Emits a phi node with one incoming value per predecessor, in the given
     * order. The second element of each pair is the predecessor's block label.
     */
    fun phi(type: IrType, incoming: List<Pair<IrValue, String>>): IrValue {
        val fn = requireFunction()
        val block = fn.current
        check(block.terminator == null) { "block ${block.label} already has a terminator" }
        val name = fn.freshValue()
        val entries = incoming.joinToString(", ") { (value, label) ->
            "[ ${value.name}, %${label.trimStart('%')} ]"
        }
        block.phis += "$name = phi ${type.llvmName()} $entries"
        return IrValue(name, type)
    }

    /** Terminates the current block with an unconditional branch. */
    fun branch(target: String) {
        terminate("br label %${target.trimStart('%')}")
    }

    /** Terminates the current block with a conditional branch. */
    fun condBranch(cond: IrValue, ifTrue: String, ifFalse: String) {
        terminate(
            "br i1 ${cond.name}, label %${ifTrue.trimStart('%')}, label %${ifFalse.trimStart('%')}"
        )
    }

    /** Terminates the current block with a return. */
    fun ret(value: IrValue?) {
        terminate(
            if (value == null || value.type == IrType.Void) "ret void"
            else "ret ${value.type.llvmName()} ${value.name}"
        )
    }

    /**
     * Emits a call whose result the caller does not use.
     *
     * Without [fnType] the call returns void: `call void @f(i8 %a)`. With a
     * non-void [fnType] the call still has a result, so it is bound to a
     * fresh SSA name that the caller may ignore.
     */
    fun callVoid(target: String, args: List<IrValue>, fnType: IrType? = null) {
        val type = fnType ?: IrType.Void
        emitCall(type, target, args)
    }

    /** Emits a call and returns the SSA name of its result. */
    fun call(retType: IrType, target: String, args: List<IrValue>): IrValue =
        emitCall(retType, target, args)

    /** The whole module text: every function, in the order they were opened. */
    fun render(): String {
        val out = StringBuilder()
        for ((index, fn) in functions.withIndex()) {
            if (index > 0) out.append('\n')
            val linkage = fn.linkage
            out.append("define ")
            if (linkage != null) out.append(linkage).append(' ')
            out.append(fn.retType.llvmName()).append(' ')
            out.append(renderTarget(fn.name)).append('(')
            out.append(fn.params.joinToString(", ") { "${it.type.llvmName()} ${it.name}" })
            out.append(") {\n")
            for (block in fn.blocks) {
                out.append(block.label).append(":\n")
                for (line in block.phis + block.instructions) {
                    for (part in line.split('\n')) out.append("  ").append(part).append('\n')
                }
                val terminator = block.terminator
                    ?: throw IllegalStateException("block ${block.label} has no terminator")
                out.append("  ").append(terminator).append('\n')
            }
            out.append("}\n")
        }
        return out.toString()
    }

    private fun requireFunction(): Function = open ?: throw IllegalStateException("no function is open")

    private fun emitCall(type: IrType, target: String, args: List<IrValue>): IrValue {
        val text = "call ${type.llvmName()} ${renderTarget(target)}(${renderArgs(args)})"
        return if (type == IrType.Void) instr(IrType.Void, text) else instr(type, text)
    }

    private fun terminate(text: String) {
        val block = requireFunction().current
        check(block.terminator == null) { "block ${block.label} already has a terminator" }
        block.terminator = text
    }

    private fun renderArgs(args: List<IrValue>): String =
        args.joinToString(", ") { "${it.type.llvmName()} ${it.name}" }

    /**
     * A symbol is `@name`, a function pointer `%ptr`, an inline asm `asm …`.
     * A name that is not a plain identifier — a mangled name with dots, for
     * instance — is quoted.
     */
    private fun renderTarget(target: String): String = when {
        target.startsWith("%") || target.startsWith("@") || target.startsWith("asm") -> target
        else -> "@${quoteIfNeeded(target)}"
    }

    /**
     * LLVM accepts a bare symbol only when it starts with a letter, `_`, `$`
     * or `-` and continues with letters, digits, `_`, `$` or `-`. A name
     * outside that set — a mangled name with dots, for instance — is quoted.
     */
    private fun quoteIfNeeded(name: String): String {
        if (name.length >= 2 && name.startsWith("\"") && name.endsWith("\"")) return name
        val plain = name.isNotEmpty() &&
            (name[0].isLetter() || name[0] == '_' || name[0] == '$' || name[0] == '-') &&
            name.all { it.isLetterOrDigit() || it in "_$-" }
        return if (plain) name else "\"$name\""
    }
}
