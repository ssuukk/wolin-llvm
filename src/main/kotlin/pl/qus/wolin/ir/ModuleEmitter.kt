package pl.qus.wolin.ir

import pl.qus.wolin.ast.BoolLit
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FixedAddress
import pl.qus.wolin.ast.FixedArray
import pl.qus.wolin.ast.Location
import pl.qus.wolin.ast.GlobalDecl
import pl.qus.wolin.ast.IntLit
import pl.qus.wolin.ast.NullLit
import pl.qus.wolin.ast.Program
import pl.qus.wolin.ast.RealLit
import pl.qus.wolin.sema.TypeKind
import pl.qus.wolin.sema.Types
import pl.qus.wolin.sema.WolinType

/**
 * Emits the whole module: the `target triple` line, the global section and
 * the functions, in that order.
 *
 * A file-scope variable without a fixed address becomes a module global
 * (D6). A fixed-address variable gets no value storage; the module carries
 * its address as a pointer constant in the `inttoptr` form every access uses.
 * Float and string constants that the [ExprEmitter] registers while the functions
 * are emitted join the same global section.
 */
class ModuleEmitter(private val b: IrBuilder = IrBuilder()) {

    /** Emits [program] with names mangled as `<mangledPrefix>.<symbol>`. */
    fun emitFile(program: Program, mangledPrefix: String): String {
        val constants = mutableListOf<String>()
        val stmt = StmtEmitter(b)
        stmt.expr = ExprEmitter(b, stmt, constants, mangledPrefix, program.resolved)

        emitGlobals(program, mangledPrefix, constants)

        var functions = 0
        for (fn in program.functions) {
            val params = fn.params.map { IrValue("%${it.name}", TypeMapper.ir(it.type)) }
            b.startFunction(
                "$mangledPrefix.${fn.name}",
                TypeMapper.ir(fn.retType ?: Types.unit),
                params
            )
            stmt.startFunction(mangledPrefix, params, program.globals, fn)
            b.endFunction()
            functions++
        }

        val sections = mutableListOf<String>()
        if (constants.isNotEmpty()) sections += constants.joinToString("\n")
        if (functions > 0) sections += b.render()
        return "target triple = \"mos-c64\"\n\n" + sections.joinToString("\n\n")
    }

    /** The module globals: every file-scope variable without a fixed address. */
    private fun emitGlobals(program: Program, prefix: String, constants: MutableList<String>) {
        for (g in program.globals) {
            val location = g.location
            if (location is FixedAddress || location is FixedArray) {
                constants += fixedGlobalLine(prefix, g, location)
                continue
            }
            if (g.type.kind == TypeKind.ARRAY) {
                if (g.init != null) throw error("Global array ${g.name} must be zero-initialised in v1")
                val ety = g.type.elementType ?: throw error("Array ${g.name} has no element type")
                constants +=
                    "@\"$prefix.${g.name}\" = global [${arrayCount(g.type)} x ${TypeMapper.ir(ety).llvmName()}] zeroinit"
                continue
            }
            val init = g.init?.let { constantSpelling(it, g.name) } ?: zeroFor(TypeMapper.ir(g.type))
            constants += "@\"$prefix.${g.name}\" = global ${TypeMapper.ir(g.type).llvmName()} $init"
        }
    }

    /**
     * A fixed-address variable owns no value storage; the module carries its
     * address as a pointer constant in the `inttoptr` form every access uses.
     */
    private fun fixedGlobalLine(prefix: String, g: GlobalDecl, location: Location): String {
        val element = if (g.type.kind == TypeKind.ARRAY)
            g.type.elementType ?: throw error("Array ${g.name} has no element type")
        else g.type
        val ptrTy = pointerType(element)
        val address = when (location) {
            is FixedAddress -> location.address
            is FixedArray -> location.address
            else -> throw error("Unsupported location $location")
        }
        return "@\"$prefix.${g.name}\" = global $ptrTy inttoptr (i64 $address to $ptrTy)"
    }

    /** The typed-pointer spelling of a type: `i8*`, `i16*`, … */
    private fun pointerType(t: WolinType): String = when (TypeMapper.ir(t)) {
        IrType.I8 -> "i8*"
        IrType.I16 -> "i16*"
        IrType.I1 -> "i1*"
        IrType.Float -> "float*"
        else -> throw error("Cannot form a pointer to $t")
    }

    /** The element count of a global array: how many the index type can name. */
    private fun arrayCount(t: WolinType): Int = when (t.indexType?.kind) {
        TypeKind.UBYTE, TypeKind.BYTE -> 256
        TypeKind.UWORD, TypeKind.WORD -> 65536
        else -> throw error("Global array index must be an integer type")
    }

    /** The constant spelling of a global initialiser, per the checker's guarantee. */
    private fun constantSpelling(init: Expr, name: String): String = when (init) {
        is IntLit -> init.value.toString()
        is RealLit -> init.value.toString()
        is BoolLit -> init.value.toString()
        is NullLit -> "null"
        else -> throw error("Global $name needs a constant initialiser in v1")
    }

    /** The zero value of a type, for an uninitialised global. */
    private fun zeroFor(ty: IrType): String = when (ty) {
        IrType.I1 -> "false"
        IrType.Float -> "0.0"
        IrType.Ptr -> "null"
        else -> "0"
    }
}
