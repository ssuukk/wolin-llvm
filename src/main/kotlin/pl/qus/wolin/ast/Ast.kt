package pl.qus.wolin.ast

import pl.qus.wolin.sema.ConstValue
import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.WolinType

/**
 * The typed AST.
 *
 * Every node is nested under [Ast] so that it is reachable as `Ast.Program`,
 * `Ast.LocalDecl` and so on; the top-level type aliases below are the same
 * types under their plain names.
 */
sealed interface Ast {
    sealed interface Expr : Ast { val type: WolinType }
    sealed interface Stmt : Ast

    data class Program(
        val globals: List<GlobalDecl>,
        val functions: List<FunDecl>,
        val imports: List<ImportDecl>,
        /** Which declaration each variable reference bound to. Filled by the checker. */
        val resolved: MutableMap<VarRef, Symbol> = mutableMapOf(),
        /**
         * The files the program is made of, in command-line order. Empty for
         * a program built from a single file.
         *
         * The whole-program view keeps the files apart: a name from another
         * package resolves only through an import, and each file's
         * declarations are emitted into its own module.
         */
        val files: List<FileScope> = emptyList(),
        /** The package the file declares, for a program built from one file. */
        val packageName: String = "",
    ) : Ast

    /**
     * One file's declarations inside the whole-program view.
     *
     * [mangledPrefix] is the prefix the file's symbols are emitted under:
     * `<package>.<file>`, the same prefix the driver module uses.
     */
    data class FileScope(
        val fileName: String,
        val packageName: String,
        val globals: List<GlobalDecl>,
        val functions: List<FunDecl>,
        val imports: List<ImportDecl>,
        val mangledPrefix: String,
    ) : Ast

    data class ImportDecl(val path: String, val alias: String?) : Ast

    /**
     * A file-scope variable. `type` is the declared type, or the inferred one
     * when the declaration has no type annotation; `declaredType` tells the two
     * apart.
     */
    data class GlobalDecl(
        val name: String,
        var type: WolinType,
        val init: Expr?,
        val location: Location?,
        /** `val` declarations cannot be assigned to. */
        val isVal: Boolean = false,
        /** The type as written in the source, or null when there was none. */
        val declaredType: WolinType? = null,
        /** The constant the initialiser folds to; filled by the checker, null without an initialiser. */
        var constValue: ConstValue? = null,
    ) : Ast

    data class FunDecl(
        val name: String,
        val params: List<Param>,
        val retType: WolinType?,
        val body: List<Stmt>,
        val isInterrupt: Boolean,
        val fixedAddress: Int?,
        val returnAt: List<ReturnAt>
    ) : Ast

    data class Param(val name: String, val type: WolinType, val location: Location?) : Ast
    data class ReturnAt(val address: Int) : Ast

    sealed class Location : Ast
    data class FixedAddress(val address: Int) : Location()
    data class CpuRegister(val name: String) : Location()      // "CPU.A", "CPU.X", "CPU.Y", "CPU.C", "CPU.I", "CPU.N", "CPU.Z", "CPU.AX", "CPU.XY"
    data class FixedArray(val address: Int) : Location()

    data class IntLit(val value: Long, override var type: WolinType) : Expr
    data class RealLit(val value: Double, override var type: WolinType) : Expr
    data class BoolLit(val value: Boolean, override var type: WolinType) : Expr
    data class CharLit(val value: Int, override var type: WolinType) : Expr
    data class StringLit(val bytes: ByteArray, override var type: WolinType) : Expr
    data class VarRef(val name: String, override var type: WolinType) : Expr
    data class UnaryOp(val op: String, val operand: Expr, override var type: WolinType, val prefix: Boolean = false) : Expr
    data class BinaryOp(val op: String, val left: Expr, val right: Expr, override var type: WolinType) : Expr
    data class Call(val callee: Expr, val args: List<Expr>, override var type: WolinType) : Expr
    data class Assign(val target: Expr, val value: Expr, override var type: WolinType, val op: String = "=") : Expr
    data class FunctionLit(
        val params: List<String>,
        val body: List<Stmt>,
        override var type: WolinType,
        /**
         * The parameter types, filled by the checker from the expected type at
         * the literal's site. Empty until the literal is checked.
         */
        var paramTypes: List<WolinType> = emptyList(),
    ) : Expr

    /** The statement types the checker walks. */
    data class LocalDecl(
        val name: String,
        var type: WolinType,
        val init: Expr?,
        val isVal: Boolean,
        val location: Location? = null,
        /** The type as written in the source, or null when there was none. */
        val declaredType: WolinType? = null
    ) : Stmt
    data class ExprStmt(val expr: Expr) : Stmt
    data class IfStmt(val cond: Expr, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt
    data class WhileStmt(val cond: Expr, val body: List<Stmt>, val isDoWhile: Boolean) : Stmt
    data class BreakStmt(val label: String?) : Stmt
    data class ContinueStmt(val label: String?) : Stmt
    data class ReturnStmt(val value: Expr?) : Stmt
    /**
     * A `return@<address>` site. The address lives with the statement, so
     * each site of a function carries its own target; the function-level
     * [FunDecl.returnAt] list mirrors the same sites in source order.
     */
    data class ReturnAtStmt(val address: Int) : Stmt
    data class WhenEntry(val conditions: List<Expr>, val body: List<Stmt>, val isElse: Boolean) : Ast.Stmt

    /**
     * A `when` in statement position. With a [subject] the subject is compared
     * for equality against each condition in order; without, each condition is
     * a boolean tested directly. The first matching entry runs.
     */
    data class WhenStmt(val subject: Expr?, val entries: List<WhenEntry>) : Ast.Stmt

    /**
     * A nested block. Every block that is not a function body appears as
     * exactly one `BlockStmt` in its parent's statement list, so the block
     * boundary — and with it the scope the block introduces — survives into the
     * typed AST.
     */
    data class BlockStmt(val body: List<Stmt>) : Stmt

    /**
     * `if` in expression position. The branches keep their statement
     * structure, so a block branch still introduces its own scope; the value of
     * the branch is the value of its last expression.
     */
    data class IfExpr(
        val cond: Expr,
        val then: List<Stmt>,
        val otherwise: List<Stmt>,
        override var type: WolinType
    ) : Expr

    /** `array[index]`. The result type is the element type of the array. */
    data class ArrayAccess(val array: Expr, val index: Expr, override var type: WolinType) : Expr

    /** The `null` literal. */
    data class NullLit(override var type: WolinType) : Expr
}

typealias Program = Ast.Program
typealias ImportDecl = Ast.ImportDecl
typealias GlobalDecl = Ast.GlobalDecl
typealias FunDecl = Ast.FunDecl
typealias Param = Ast.Param
typealias ReturnAt = Ast.ReturnAt
typealias Location = Ast.Location
typealias FixedAddress = Ast.FixedAddress
typealias CpuRegister = Ast.CpuRegister
typealias FixedArray = Ast.FixedArray
typealias Expr = Ast.Expr
typealias IntLit = Ast.IntLit
typealias RealLit = Ast.RealLit
typealias BoolLit = Ast.BoolLit
typealias CharLit = Ast.CharLit
typealias StringLit = Ast.StringLit
typealias VarRef = Ast.VarRef
typealias UnaryOp = Ast.UnaryOp
typealias BinaryOp = Ast.BinaryOp
typealias Call = Ast.Call
typealias Assign = Ast.Assign
typealias FunctionLit = Ast.FunctionLit
typealias Stmt = Ast.Stmt
typealias LocalDecl = Ast.LocalDecl
typealias ExprStmt = Ast.ExprStmt
typealias IfStmt = Ast.IfStmt
typealias WhileStmt = Ast.WhileStmt
typealias BreakStmt = Ast.BreakStmt
typealias ContinueStmt = Ast.ContinueStmt
typealias ReturnStmt = Ast.ReturnStmt
typealias ReturnAtStmt = Ast.ReturnAtStmt
typealias BlockStmt = Ast.BlockStmt
typealias IfExpr = Ast.IfExpr
typealias ArrayAccess = Ast.ArrayAccess
typealias NullLit = Ast.NullLit
typealias WhenEntry = Ast.WhenEntry
typealias WhenStmt = Ast.WhenStmt
typealias FileScope = Ast.FileScope
