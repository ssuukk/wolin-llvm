package pl.qus.wolin.ir

/**
 * Emits the inline-asm sequences that read and write 6510 CPU registers.
 *
 * Every sequence here was verified against the real `mos-c64-clang` backend.
 * The backend rejects clobber lists (`error: expected instruction opcode`),
 * so the constraints alone carry the register requirements. A multi
 * instruction template is spelled on one line, the instructions separated
 * by the two character `\0A` escape.
 */
object CpuAccessors {

    /**
     * Reads A without touching the status register.
     *
     * `STA` writes no flag, so P survives. A is parked in a scratch `alloca`
     * slot — the `"r"` pointer operand, referenced in the template as `$0`
     * — and loaded back. Assumes the current block is the function's
     * `entry` block (the slot is an `alloca`).
     */
    fun readA(b: IrBuilder): IrValue {
        val slot = b.alloca(IrType.I8)
        b.instr(IrType.Void, "call void asm sideeffect \"ldy #0\\0Asta (\$0),y\", \"r\"(i8* ${slot.name})")
        return b.instr(IrType.I8, "load i8, ptr ${slot.name}")
    }

    /** Reads A via `pha`/`pla`. Clobbers the N and Z flags. */
    fun readAValue(b: IrBuilder): IrValue =
        b.instr(IrType.I8, "call i8 asm sideeffect \"pha\\0Apla\", \"=a\"()")

    /**
     * Reads the single status bit [mask] while preserving A and P.
     *
     * [mask] must be a single bit. `mask.trailingZeros()` `lsr` steps
     * normalise the bit to position 0, so the result is 0 or 1: none for
     * mask 1 (carry), one for 2 (zero), seven for 128 (negative).
     */
    fun readFlag(b: IrBuilder, mask: Int): IrValue {
        check(mask != 0 && (mask and (mask - 1)) == 0) { "flag mask must be a single bit, got: $mask" }
        val parts = mutableListOf("php", "pha", "php", "pla", "and #$mask")
        repeat(Integer.numberOfTrailingZeros(mask)) { parts.add("lsr") }
        parts += listOf("tax", "pla", "plp")
        val template = parts.joinToString("\\0A")
        return b.instr(IrType.I8, "call i8 asm sideeffect \"$template\", \"=x\"()")
    }

    /** Writes [value] to the register [reg] (`"CPU.A"`, `"CPU.X"`, `"CPU.Y"`). */
    fun writeRegister(b: IrBuilder, reg: String, value: IrValue) {
        val c = reg.constraintChar()
        b.instr(IrType.Void, "call void asm sideeffect \"\", \"$c\"(i8 ${value.name})")
    }

    /** Reads the low byte of AX (A) via `pha`/`pla`. Clobbers N and Z. */
    fun readAxLow(b: IrBuilder): IrValue =
        b.instr(IrType.I8, "call i8 asm sideeffect \"pha\\0Apla\", \"=a\"()")

    /** Reads the high byte of AX (X) via `pha`/`txa`/`tax`/`pla`. */
    fun readAxHigh(b: IrBuilder): IrValue =
        b.instr(IrType.I8, "call i8 asm sideeffect \"pha\\0Atxa\\0Atax\\0Apla\", \"=x\"()")

    /**
     * Reads X into an SSA value.
     *
     * `pha`/`txa`/`tax`/`pla` moves X through A and back: the value ends up
     * in the output register X, and A is left as it was. Clobbers N and Z.
     */
    fun readX(b: IrBuilder): IrValue =
        b.instr(IrType.I8, "call i8 asm sideeffect \"pha\\0Atxa\\0Atax\\0Apla\", \"=x\"()")

    /**
     * Reads Y into an SSA value.
     *
     * `pha`/`tya`/`tay`/`pla` moves Y through A and back, the same way
     * [readX] moves X. Clobbers N and Z.
     */
    fun readY(b: IrBuilder): IrValue =
        b.instr(IrType.I8, "call i8 asm sideeffect \"pha\\0Atya\\0Atay\\0Apla\", \"=y\"()")

    /**
     * Reads the register [reg] (`"CPU.A"`, `"CPU.X"`, `"CPU.Y"`) into an SSA
     * value.
     */
    fun readRegister(b: IrBuilder, reg: String): IrValue = when (reg) {
        "CPU.A" -> readAValue(b)
        "CPU.X" -> readX(b)
        "CPU.Y" -> readY(b)
        else -> throw error("No accessor for register $reg")
    }

    /**
     * Reads the register pair [reg] into an SSA value. The pair name lists
     * the registers in low-to-high byte order:
     *
     * - `"CPU.AX"`: A holds the low byte, X the high byte, the same layout
     *   the LLVM-MOS calling convention returns an `i16` in.
     * - `"CPU.XY"`: X holds the low byte, Y the high byte, as the C64
     *   KERNAL leaves 16-bit results (MEMBOT puts the address in X and Y).
     *
     * The two bytes are read one after the other and joined, so the value
     * does not depend on the register allocator keeping a register alive
     * across the two reads.
     */
    fun readRegisterPair(b: IrBuilder, reg: String): IrValue {
        val (lowReg, highReg) = when (reg) {
            "CPU.AX" -> "CPU.A" to "CPU.X"
            "CPU.XY" -> "CPU.X" to "CPU.Y"
            else -> throw error("No accessor for register pair $reg")
        }
        val low = readRegister(b, lowReg)
        val high = readRegister(b, highReg)
        val lowWide = b.instr(IrType.I16, "zext i8 ${low.name} to i16")
        val highWide = b.instr(IrType.I16, "zext i8 ${high.name} to i16")
        val shifted = b.instr(IrType.I16, "shl i16 ${highWide.name}, 8")
        return b.instr(IrType.I16, "or i16 ${shifted.name}, ${lowWide.name}")
    }

    /**
     * The status-register bit of a flag register: [flagMask]("CPU.I") is 0x40.
     *
     * A flag is a single bit in P; a variable pinned to one of these names
     * (`CPU.C`, `CPU.I`, `CPU.N`, `CPU.Z`) reads that bit as a `bool`.
     */
    fun flagMask(reg: String): Int = when (reg) {
        "CPU.C" -> 0x01
        "CPU.I" -> 0x40
        "CPU.N" -> 0x80
        "CPU.Z" -> 0x02
        else -> throw error("No flag accessor for register $reg")
    }

    /**
     * Reads a flag register into an [IrType.I1] value, `true` when the bit
     * is set.
     */
    fun flagRead(b: IrBuilder, reg: String): IrValue {
        val raw = readFlag(b, flagMask(reg))
        return b.instr(IrType.I1, "icmp ne i8 ${raw.name}, 0")
    }

    /**
     * Sets or clears a flag register from an [IrValue] of [IrType.I1].
     *
     * The value is an SSA condition, so a branch chooses the instruction:
     * `true` sets the flag, `false` clears it. A value that is already the
     * constant `true` or `false` (the spelling a [BoolLit] carries) skips
     * the branch and emits the one instruction directly, so
     * `maskInterrupts = true` lowers to a bare `sei`.
     */
    fun writeFlag(b: IrBuilder, reg: String, value: IrValue) {
        val set = setInsn(reg)
        val clear = clearInsn(reg)
        when (value.name) {
            "true" -> b.instr(IrType.Void, "call void asm sideeffect \"$set\", \"\"()")
            "false" -> b.instr(IrType.Void, "call void asm sideeffect \"$clear\", \"\"()")
            else -> {
                val setL = b.labelFor("flag.$reg.set")
                val clearL = b.labelFor("flag.$reg.clear")
                val endL = b.labelFor("flag.$reg.end")
                b.condBranch(value, setL, clearL)
                b.addBlockAt(setL)
                b.instr(IrType.Void, "call void asm sideeffect \"$set\", \"\"()")
                b.branch(endL)
                b.addBlockAt(clearL)
                b.instr(IrType.Void, "call void asm sideeffect \"$clear\", \"\"()")
                b.branch(endL)
                b.addBlockAt(endL)
            }
        }
    }

    /** The flag-set instruction of a register (`CPU.I` is `sei`, `CPU.C` is `sec`). */
    private fun setInsn(reg: String): String = when (reg) {
        "CPU.I" -> "sei"
        "CPU.C" -> "sec"
        else -> throw error("No set instruction for flag register $reg")
    }

    /** The flag-clear instruction of a register (`CPU.I` is `cli`, `CPU.C` is `clc`). */
    private fun clearInsn(reg: String): String = when (reg) {
        "CPU.I" -> "cli"
        "CPU.C" -> "clc"
        else -> throw error("No clear instruction for flag register $reg")
    }
}

/**
 * The register letter of a name like `"CPU.A"`, lowercased. Only the
 * single-letter registers the accessors pin are valid.
 */
internal fun String.constraintChar(): String {
    val c = removePrefix("CPU.").lowercase()
    check(c in setOf("a", "x", "y")) { "expected CPU.A/CPU.X/CPU.Y, got: $this" }
    return c
}
