package pl.qus.wolin.ir

/**
 * The IR type system.
 *
 * Deliberately small: it covers what the Wolin front end can express and
 * nothing more. Arrays are addressed, not first class, so [Array] exists for
 * the rare case where an array type has to be spelled out.
 */
sealed interface IrType {
    object I8 : IrType
    object I16 : IrType
    object I32 : IrType
    object I1 : IrType
    object Float : IrType
    object Void : IrType
    object Ptr : IrType
    data class Array(val of: IrType, val count: Int) : IrType
}

/** The LLVM spelling of this type, e.g. `i8`, `float`, `ptr`, `[4 x i8]`. */
fun IrType.llvmName(): String = when (this) {
    IrType.I8 -> "i8"
    IrType.I16 -> "i16"
    IrType.I32 -> "i32"
    IrType.I1 -> "i1"
    IrType.Float -> "float"
    IrType.Void -> "void"
    IrType.Ptr -> "ptr"
    is IrType.Array -> "[$count x ${of.llvmName()}]"
}
