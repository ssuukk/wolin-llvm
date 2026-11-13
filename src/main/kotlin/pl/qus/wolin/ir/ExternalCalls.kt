package pl.qus.wolin.ir

/**
 * Emits ROM calls as `jsr` inline asm.
 *
 * Each argument is pinned to the CPU register it was declared in: the
 * constraint string lists the registers in declaration order and the
 * operands follow the same order. The `$$` escape is mandatory for the
 * backend to see a literal `$` address.
 */
class ExternalCalls {

    /**
     * Calls the ROM at [target] with [args], each pairing an argument value
     * with its CPU register name (e.g. `"CPU.A"`).
     */
    fun emitCall(b: IrBuilder, target: Int, args: List<Pair<IrValue, String>>) {
        val hex = target.toString(16).padStart(4, '0')
        val constraints = args.joinToString(",") { it.second.constraintChar() }
        val operands = args.joinToString(", ") { "${it.first.type.llvmName()} ${it.first.name}" }
        val text = "call void asm sideeffect \"jsr \$\$${hex}\", \"$constraints\"($operands)"
        b.instr(IrType.Void, text)
    }

}
