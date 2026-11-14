package pl.qus.wolin.sema

/**
 * The value a global initialiser folds to at compile time, and its LLVM
 * spelling in a constant position (a global initialiser or a store operand).
 *
 * Integer values keep the raw two's-complement [IntValue.bits] of the
 * type's width; the signedness is read from [IntValue.type] when the value
 * is spelled, so `0xFF` spells `255` in a `ubyte` and `-1` in a `byte`.
 */
sealed interface ConstValue {
    val spelling: String

    /** An integer of [type]'s width (`ubyte`, `byte`, `uword` or `word`). */
    data class IntValue(val bits: Long, val type: WolinType) : ConstValue {
        override val spelling: String
            get() {
                val width = if (type.kind == TypeKind.UBYTE || type.kind == TypeKind.BYTE) 8 else 16
                val max = 1L shl width
                var v = bits and (max - 1)
                if ((type.kind == TypeKind.BYTE || type.kind == TypeKind.WORD) && v >= max / 2) v -= max
                return v.toString()
            }
    }

    data class FloatValue(val value: Double) : ConstValue {
        override val spelling: String get() = value.toString()
    }

    data class BoolValue(val value: Boolean) : ConstValue {
        override val spelling: String get() = value.toString()
    }

    /** The `null` pointer of a unit-nullable type. */
    data object NullValue : ConstValue {
        override val spelling: String get() = "null"
    }

    /**
     * A bare reference to a function: the function's 16-bit address, which
     * only exists after the link, so the init function computes it with a
     * `ptrtoint` at startup.
     *
     * The spelling is empty: a function address is never a constant in the
     * IR, so the init emitter stores a computed value instead.
     */
    data class FunctionValue(val name: String) : ConstValue {
        override val spelling: String = ""
    }
}
