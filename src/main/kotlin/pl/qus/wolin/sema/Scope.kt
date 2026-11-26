package pl.qus.wolin.sema

import pl.qus.wolin.ast.EnumDecl
import pl.qus.wolin.ast.Expr
import pl.qus.wolin.ast.FunDecl
import pl.qus.wolin.ast.Location
import pl.qus.wolin.ast.StructDecl

/**
 * A named declaration. `DataSymbol` is a variable (global, local or parameter),
 * `FunSymbol` a function.
 */
sealed class Symbol {
    data class DataSymbol(
        val type: WolinType,
        val location: Location?,
        val init: Expr?,
        /** `val` declarations cannot be assigned to. */
        val isVal: Boolean = false,
        /**
         * The mangled prefix of the file that declares the variable, when it
         * is not the file being emitted: a global of another file lives
         * under that file's prefix.
         */
        val prefix: String = "",
        /** The value of an anonymous enum entry, inlined instead of loaded. */
        val enumValue: Int? = null,
    ) : Symbol()

    /**
     * A function.
     *
     * [prefix] is the mangled prefix of the file the function is declared
     * in: the symbol is emitted as `@"<prefix>.<name>"`, so a call from
     * another file names the defining file's prefix, not the caller's.
     */
    data class FunSymbol(val decl: FunDecl, val prefix: String = "") : Symbol()

    /** A struct type declaration. */
    data class StructSymbol(val decl: StructDecl) : Symbol()

    /** A named enum: its name is a type and an entry receiver, not a value. */
    data class EnumSymbol(val decl: EnumDecl) : Symbol()
}

/**
 * A scope stack with shadowing. A block introduces a child scope; a declaration
 * in it shadows an outer declaration of the same name, and the shadow ends with
 * the block.
 */
class Scope(private val parent: Scope? = null) {
    private val names = mutableMapOf<String, Symbol>()

    fun declare(name: String, symbol: Symbol) { names[name] = symbol }
    fun lookup(name: String): Symbol? = names[name] ?: parent?.lookup(name)
    fun lookupLocal(name: String): Symbol? = names[name]

    fun child(): Scope = Scope(this)
}
