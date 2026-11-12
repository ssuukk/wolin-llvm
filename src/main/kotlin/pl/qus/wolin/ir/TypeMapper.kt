package pl.qus.wolin.ir

import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.WolinType

/**
 * Maps a Wolin type onto its IR type.
 *
 * `unit` has no value, so it maps to [IrType.Void]; `string` and arrays are
 * addressed, not first class, so they map to [IrType.Ptr].
 */
object TypeMapper {
    fun ir(t: WolinType): IrType = when (t.kind) {
        TypeKind.UBYTE, TypeKind.BYTE -> IrType.I8
        TypeKind.UWORD, TypeKind.WORD -> IrType.I16
        TypeKind.BOOL -> IrType.I1
        TypeKind.FLOAT -> IrType.Float
        TypeKind.UNIT -> IrType.Void
        TypeKind.STRING -> IrType.Ptr
        TypeKind.ARRAY -> IrType.Ptr
        TypeKind.FUNCTION -> IrType.Ptr
    }
}
