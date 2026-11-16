package pl.qus.wolin.sema

enum class TypeKind { UBYTE, BYTE, UWORD, WORD, BOOL, UNIT, FLOAT, STRING, ARRAY, FUNCTION }

data class WolinType(
    val kind: TypeKind,
    val nullable: Boolean = false,
    val elementType: WolinType? = null,
    val indexType: WolinType? = null,
    val params: List<WolinType> = emptyList(),
    val retType: WolinType? = null
) {
    /** True for the four integer types, which take part in arithmetic. */
    val isInteger: Boolean
        get() = kind == TypeKind.UBYTE || kind == TypeKind.BYTE ||
                kind == TypeKind.UWORD || kind == TypeKind.WORD

    /** True for the types that can be bit-manipulated with `&`, `|` and `xor`. */
    val isBitwise: Boolean
        get() = isInteger

    override fun toString(): String = when (kind) {
        TypeKind.ARRAY -> "${elementType ?: "?"}[${indexType ?: "?"}]"
        TypeKind.FUNCTION -> "(${params.joinToString(", ")})->${retType ?: "unit"}"
        else -> kind.name.lowercase() + if (nullable) "?" else ""
    }
}

object Types {
    val ubyte = WolinType(TypeKind.UBYTE)
    val byte = WolinType(TypeKind.BYTE)
    val uword = WolinType(TypeKind.UWORD)
    val word = WolinType(TypeKind.WORD)
    val bool = WolinType(TypeKind.BOOL)
    val unit = WolinType(TypeKind.UNIT)
    val float = WolinType(TypeKind.FLOAT)
    val string = WolinType(TypeKind.STRING)

    /** The array type `element[index]`, e.g. `ubyte[ubyte]`. */
    fun array(element: WolinType, index: WolinType): WolinType =
        WolinType(TypeKind.ARRAY, elementType = element, indexType = index)

    /** The function type `(params)->ret`. */
    fun function(params: List<WolinType>, ret: WolinType): WolinType =
        WolinType(TypeKind.FUNCTION, params = params, retType = ret)
}
