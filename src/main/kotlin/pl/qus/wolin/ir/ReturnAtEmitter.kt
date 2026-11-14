package pl.qus.wolin.ir

/**
 * Emits a `return@<address>` site as a tail jump to a fixed address.
 *
 * The jump is emitted at the site, not in the function's epilogue: a
 * function may carry several sites, each with its own address, alongside
 * ordinary returns, which stay untouched. The `unreachable` terminator
 * makes the site a terminator of its block, so for an `interrupt` fun the
 * handler's normal exit (the `rti` the backend emits for it) is simply
 * never reached from that path.
 *
 * The `$$` escape is mandatory for the backend to see a literal `$`
 * address; the empty constraint list carries no register.
 */
class ReturnAtEmitter(private val b: IrBuilder) {
    fun emit(address: Int) {
        val hex = address.toString(16).padStart(4, '0')
        b.instr(IrType.Void, "call void asm sideeffect \"jmp \$\$${hex}\", \"\"()")
        b.unreachable()
    }
}
