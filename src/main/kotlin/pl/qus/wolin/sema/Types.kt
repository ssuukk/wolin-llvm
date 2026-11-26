package pl.qus.wolin.sema

enum class TypeKind {
    UBYTE, BYTE, UWORD, WORD, BOOL, UNIT, FLOAT, STRING, ARRAY, FUNCTION, STRUCT, TRAIT,
}

data class WolinType(
    /**
     * The kind of the type. The checker retags a named type from [TypeKind.STRUCT]
     * to [TypeKind.TRAIT] once the name resolves to a visible trait.
     */
    var kind: TypeKind,
    val nullable: Boolean = false,
    val elementType: WolinType? = null,
    val indexType: WolinType? = null,
    /** An explicit array length, or null to size the array by the index domain. */
    val count: Int? = null,
    val params: List<WolinType> = emptyList(),
    val retType: WolinType? = null,
    /** The struct this type names, for [TypeKind.STRUCT]. */
    var structName: String? = null,
    /** The trait this type names, for [TypeKind.TRAIT]. */
    var traitName: String? = null,
) {
    /** True for the four integer types, which take part in arithmetic. */
    val isInteger: Boolean
        get() = kind == TypeKind.UBYTE || kind == TypeKind.BYTE ||
                kind == TypeKind.UWORD || kind == TypeKind.WORD

    /** True for the types that can be bit-manipulated with `&`, `|` and `xor`. */
    val isBitwise: Boolean
        get() = isInteger

    override fun toString(): String = when (kind) {
        TypeKind.ARRAY -> {
            val suffix = count?.let { ", $it" } ?: ""
            "${elementType ?: "?"}[${indexType ?: "?"}$suffix]"
        }
        TypeKind.FUNCTION -> "(${params.joinToString(", ")})->${retType ?: "unit"}"
        TypeKind.STRUCT -> structName ?: "struct?"
        TypeKind.TRAIT -> traitName ?: "trait?"
        else -> kind.name.lowercase() + if (nullable) "?" else ""
    }
}

/** The number of elements an array type names. */
fun arrayLength(t: WolinType): Int =
    t.count ?: if (t.indexType?.kind == TypeKind.UWORD || t.indexType?.kind == TypeKind.WORD) 65536 else 256

/** The size in bytes of a scalar type. */
fun sizeOf(t: WolinType): Int = when (t.kind) {
    TypeKind.UBYTE, TypeKind.BYTE, TypeKind.BOOL -> 1
    TypeKind.UWORD, TypeKind.WORD, TypeKind.STRING, TypeKind.FUNCTION -> 2
    TypeKind.FLOAT -> 4
    // A trait value is an addressed cell: a data pointer plus a vtable pointer.
    TypeKind.TRAIT -> 4
    else -> throw IllegalArgumentException("Type $t has no scalar size")
}

/** Whether two types are identical for assignment and call checking. */
fun sameType(a: WolinType, b: WolinType): Boolean =
    a.kind == b.kind && when (a.kind) {
        TypeKind.ARRAY -> sameType(a.elementType ?: Types.unit, b.elementType ?: Types.unit) &&
            sameType(a.indexType ?: Types.ubyte, b.indexType ?: Types.ubyte) &&
            a.count == b.count
        TypeKind.FUNCTION ->
            a.params.size == b.params.size &&
                a.params.zip(b.params).all { (x, y) -> sameType(x, y) } &&
                sameType(a.retType ?: Types.unit, b.retType ?: Types.unit)
        TypeKind.STRUCT -> a.structName == b.structName
        TypeKind.TRAIT -> a.traitName == b.traitName
        else -> true
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

    /** The array type `element[index]` or `element[index, count]`. */
    fun array(element: WolinType, index: WolinType, count: Int? = null): WolinType =
        WolinType(TypeKind.ARRAY, elementType = element, indexType = index, count = count)

    /** The function type `(params)->ret`. */
    fun function(params: List<WolinType>, ret: WolinType): WolinType =
        WolinType(TypeKind.FUNCTION, params = params, retType = ret)

    /** A named struct type. */
    fun struct(name: String): WolinType =
        WolinType(TypeKind.STRUCT, structName = name)
}
