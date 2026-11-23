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
     * A string literal's NUL-terminated bytes.
     *
     * A `string` global is a pointer slot, and a pointer has no constant
     * spelling in a module's constant pool: the module emitter emits a
     * private byte array and makes the global point at it. The value never
     * needs a runtime store, so [spelling] is empty.
     */
    class StringValue(val bytes: ByteArray) : ConstValue {
        override val spelling: String = ""
    }

    /**
     * A bare reference to a function: the function's 16-bit address, which
     * only exists after the link, so the init function computes it with a
     * `ptrtoint` at startup.
     *
     * The spelling is empty: a function address is never a constant in the
     * IR, so the init emitter stores a computed value instead.
     */
    data class FunctionValue(val name: String, val prefix: String = "") : ConstValue {
        override val spelling: String = ""
    }

    /**
     * An array's constant elements, each already folded.
     *
     * [spelling] is the full-domain LLVM constant-array body:
     * `[i8 12, i8 13, i8 0, …]` — padded to the index type's domain
     * with per-element-type zero spellings.
     */
    /**
     * A constant array: the elements a global array initialiser folds to,
     * padded with zeros to the full index-type domain. The [spelling] is the
     * LLVM constant-array body (`[i8 12, i8 0, …]`), ready to be placed after
     * the array type in a `@"… = global [N x T] …"` line.
     *
     * Each element is prefixed with its LLVM type name (`i8`, `i16`, `i1`,
     * `float`); the type name is spelled from the element [WolinType]'s kind
     * so the sema package does not depend on the `ir` package.
     */
    data class ArrayValue(
        val elements: List<ConstValue>,
        val elementType: WolinType,
        val indexType: WolinType,
        val count: Int? = null,
    ) : ConstValue {
        override val spelling: String
            get() {
                val ty = llvmName(elementType)
                val length = count ?: if (indexType.kind == TypeKind.UWORD || indexType.kind == TypeKind.WORD) 65536 else 256
                val padded = elements + List(length - elements.size) { zeroFor(elementType) }
                return "[" + padded.joinToString(", ") { "$ty ${it.spelling}" } + "]"
            }
    }

}

/** The LLVM spelling of a Wolin type's IR type, spelled without importing `ir`. */
internal fun llvmName(t: WolinType): String = when (t.kind) {
    TypeKind.UBYTE, TypeKind.BYTE -> "i8"
    TypeKind.UWORD, TypeKind.WORD -> "i16"
    TypeKind.BOOL -> "i1"
    TypeKind.FLOAT -> "float"
    else -> throw IllegalArgumentException("Array element type $t has no LLVM spelling")
}

/** The zero value of [t] in its LLVM spelling. */
internal fun zeroFor(t: WolinType): ConstValue = when (t.kind) {
    TypeKind.FLOAT -> ConstValue.FloatValue(0.0)
    TypeKind.BOOL -> ConstValue.BoolValue(false)
    else -> ConstValue.IntValue(0, t)
}
