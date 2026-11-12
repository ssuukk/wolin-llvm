package pl.qus.wolin.sema

import pl.qus.wolin.ast.*
import java.util.IdentityHashMap

/**
 * Walks the typed AST and enforces the type rules.
 *
 * The checker throws [SemaException] on the **first** error — it does not
 * accumulate.
 *
 * The returned [Program] carries a `resolved` map that binds every variable
 * reference to the declaration it resolved to. The map uses reference identity
 * for its keys, because two references to two different declarations of the
 * same name and type must stay distinguishable.
 */
class TypeChecker {
    private var scope: Scope = Scope()
    private var resolved: MutableMap<VarRef, Symbol> = IdentityHashMap()
    private var currentReturnType: WolinType? = null
    private var currentFunctionName: String = ""

    fun check(file: Ast.Program): Ast.Program {
        val globals = Scope()
        scope = globals
        resolved = IdentityHashMap()

        // Functions are visible everywhere, so they are declared before anything
        // is checked — a call may name a function declared further down.
        file.functions.forEach { fn -> globals.declare(fn.name, Symbol.FunSymbol(fn)) }
        file.globals.forEach { global -> checkGlobal(global) }
        file.functions.forEach { fn -> checkFunction(fn, globals) }

        return file.copy(resolved = resolved)
    }

    // ---- declarations -----------------------------------------------------

    private fun checkGlobal(global: GlobalDecl) {
        val declared = global.declaredType
        val initType = global.init?.let { checkExpr(it, declared) }
        if (declared != null && initType != null && initType.kind != declared.kind) {
            throw SemaException("Attempt reassign $declared $initType")
        }
        val type = declared ?: initType
            ?: throw SemaException("Variable ${global.name} has no type!")
        global.type = type
        scope.declare(global.name, Symbol.DataSymbol(type, global.location, global.init, global.isVal))
    }

    private fun checkFunction(fn: FunDecl, globals: Scope) {
        val outer = scope
        val functionScope = globals.child()
        scope = functionScope
        currentReturnType = fn.retType
        currentFunctionName = fn.name
        try {
            fn.params.forEach { param ->
                functionScope.declare(param.name, Symbol.DataSymbol(param.type, param.location, null))
            }
            fn.body.forEach { checkStmt(it) }
        } finally {
            scope = outer
            currentReturnType = null
            currentFunctionName = ""
        }
    }

    // ---- statements -------------------------------------------------------

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is LocalDecl -> {
                val declared = stmt.declaredType
                val initType = stmt.init?.let { checkExpr(it, declared) }
                if (declared != null && initType != null && initType.kind != declared.kind) {
                    throw SemaException("Attempt reassign $declared $initType")
                }
                val type = declared ?: initType
                    ?: throw SemaException("Variable ${stmt.name} has no type!")
                stmt.type = type
                scope.declare(stmt.name, Symbol.DataSymbol(type, stmt.location, stmt.init, stmt.isVal))
            }

            is ExprStmt -> checkExpr(stmt.expr, null)

            is BlockStmt -> {
                val outer = scope
                scope = scope.child()
                try {
                    checkBranch(stmt.body, null)
                } finally {
                    scope = outer
                }
            }

            is IfStmt -> {
                requireBool(checkExpr(stmt.cond, null), "if")
                checkBranch(stmt.then, null)
                checkBranch(stmt.otherwise, null)
            }

            is WhileStmt -> {
                requireBool(checkExpr(stmt.cond, null), "while")
                checkBranch(stmt.body, null)
            }

            is ReturnStmt -> {
                val expected = currentReturnType
                val value = stmt.value
                if (value == null) {
                    if (expected != null) {
                        throw SemaException("Function $currentFunctionName must return a value")
                    }
                } else {
                    val valueType = checkExpr(value, expected)
                    if (expected == null) {
                        throw SemaException("Unit function $currentFunctionName has no return type")
                    }
                    if (valueType.kind != expected.kind) {
                        throw SemaException("Attempt reassign $expected $valueType")
                    }
                }
            }

            is WhenStmt -> checkWhen(stmt)
            is WhenEntry -> throw SemaException("A when entry cannot appear outside a when")

            is BreakStmt, is ContinueStmt -> Unit
        }
    }

    private fun checkWhen(stmt: WhenStmt) {
        val subjectType = stmt.subject?.let { checkExpr(it, null) }
        if (subjectType != null && (subjectType.kind == TypeKind.UNIT || subjectType.kind == TypeKind.STRING)) {
            throw SemaException("when subject must be comparable, not $subjectType")
        }
        var seenElse = false
        stmt.entries.forEach { entry ->
            if (entry.isElse) {
                if (seenElse) throw SemaException("A when can have only one else branch")
                seenElse = true
            } else {
                if (seenElse) throw SemaException("The else branch of a when must be last")
                entry.conditions.forEach { condition ->
                    // A literal takes the subject's type, so `when (x: uword) { 1 -> }`
                    // compares `uword`s, exactly like `x == 1`.
                    val condType = if (condition is IntLit && subjectType != null) {
                        checkExpr(condition, subjectType)
                    } else {
                        checkExpr(condition, null)
                    }
                    if (subjectType != null) {
                        if (condType != subjectType) {
                            throw SemaException("when condition type $condType does not match subject type $subjectType")
                        }
                    } else {
                        requireBool(condType, "when condition")
                    }
                }
            }
            checkBranch(entry.body, null)
        }
    }

    /**
     * Checks a list of statements and returns the type of the value they
     * produce — the type of the last expression, or `unit` when the branch
     * does not end in an expression.
     */
    private fun checkBranch(stmts: List<Stmt>, expected: WolinType?): WolinType {
        if (stmts.isEmpty()) return Types.unit
        stmts.dropLast(1).forEach { checkStmt(it) }
        return when (val last = stmts.last()) {
            is ExprStmt -> checkExpr(last.expr, expected)
            is BlockStmt -> {
                val outer = scope
                scope = scope.child()
                try {
                    checkBranch(last.body, expected)
                } finally {
                    scope = outer
                }
            }
            else -> {
                checkStmt(last)
                Types.unit
            }
        }
    }

    // ---- expressions ------------------------------------------------------

    private fun checkExpr(expr: Expr, expected: WolinType?): WolinType = when (expr) {
        is IntLit -> literalType(expr.value, expected).also { expr.type = it }
        is RealLit -> Types.float.also { expr.type = it }
        is BoolLit -> Types.bool.also { expr.type = it }
        is CharLit -> Types.ubyte.also { expr.type = it }
        is StringLit -> Types.string.also { expr.type = it }
        is NullLit -> WolinType(TypeKind.UNIT, nullable = true).also { expr.type = it }
        is VarRef -> checkVarRef(expr)
        is UnaryOp -> checkUnary(expr, expected)
        is BinaryOp -> checkBinary(expr)
        is Assign -> checkAssign(expr)
        is Call -> checkCall(expr)
        is FunctionLit -> checkFunctionLiteral(expr)
        is IfExpr -> checkIfExpr(expr, expected)
        is ArrayAccess -> checkArrayAccess(expr)
    }

    private fun checkVarRef(expr: VarRef): WolinType {
        val symbol = scope.lookup(expr.name)
            ?: throw SemaException("Unknown variable ${expr.name}")
        when (symbol) {
            is Symbol.DataSymbol -> expr.type = symbol.type
            // A function name used as a value is its address, which is a `uword`.
            is Symbol.FunSymbol -> expr.type = Types.uword
        }
        resolved[expr] = symbol
        return expr.type
    }

    private fun checkUnary(expr: UnaryOp, expected: WolinType?): WolinType {
        when (expr.op) {
            "-" -> {
                val operand = expr.operand
                if (operand is IntLit) {
                    val type = literalType(-operand.value, expected)
                    operand.type = type
                    expr.type = type
                } else {
                    val type = checkExpr(operand, null)
                    requireNumeric(type, expr.op)
                    expr.type = type
                }
            }

            "+" -> {
                val type = checkExpr(expr.operand, null)
                requireNumeric(type, expr.op)
                expr.type = type
            }

            "!" -> {
                requireBool(checkExpr(expr.operand, null), expr.op)
                expr.type = Types.bool
            }

            "++", "--" -> {
                val type = checkExpr(expr.operand, null)
                if (!type.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $type")
                }
                expr.type = type
            }

            else -> throw SemaException("Unknown operator ${expr.op}")
        }
        return expr.type
    }

    private fun checkBinary(expr: BinaryOp): WolinType {
        val left = expr.left
        val right = expr.right

        // A literal takes the type of the operand it is combined with, so
        // `uwordValue + 1` is a `uword` operation and not a type mismatch.
        val leftType: WolinType
        val rightType: WolinType
        when {
            left is IntLit && right !is IntLit && right !is RealLit -> {
                rightType = checkExpr(right, null)
                leftType = checkExpr(left, rightType)
            }
            right is IntLit && left !is IntLit && left !is RealLit -> {
                leftType = checkExpr(left, null)
                rightType = checkExpr(right, leftType)
            }
            else -> {
                leftType = checkExpr(left, null)
                rightType = checkExpr(right, null)
            }
        }

        when (expr.op) {
            "&&", "||" -> {
                requireBool(leftType, expr.op)
                requireBool(rightType, expr.op)
                expr.type = Types.bool
            }

            "==", "!=" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                expr.type = Types.bool
            }

            "<", "<=", ">", ">=" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                requireNumeric(leftType, expr.op)
                requireNumeric(rightType, expr.op)
                expr.type = Types.bool
            }

            "+", "-", "*", "/", "%" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                requireNumeric(leftType, expr.op)
                requireNumeric(rightType, expr.op)
                expr.type = leftType
            }

            "&", "|" -> {
                if (leftType.kind != rightType.kind) {
                    throw SemaException("Attempt reassign $leftType $rightType")
                }
                if (!leftType.isBitwise) {
                    throw SemaException("Operator ${expr.op} is not defined for $leftType")
                }
                expr.type = leftType
            }

            else -> throw SemaException("Unknown operator ${expr.op}")
        }
        return expr.type
    }

    private fun checkAssign(expr: Assign): WolinType {
        when (val target = expr.target) {
            is VarRef -> {
                val symbol = scope.lookup(target.name)
                    ?: throw SemaException("Unknown variable ${target.name}")
                val data = symbol as? Symbol.DataSymbol
                    ?: throw SemaException("Cannot assign to function ${target.name}")
                if (data.isVal) {
                    throw SemaException("Cannot assign to val ${target.name}")
                }
                resolved[target] = symbol
                target.type = data.type

                checkAssignedValue(expr, data.type)
                expr.type = data.type
            }

            is ArrayAccess -> {
                val arrayType = checkExpr(target.array, null)
                if (arrayType.kind != TypeKind.ARRAY) {
                    throw SemaException("Cannot assign to a value that is not an array")
                }
                val indexType = checkExpr(target.index, null)
                if (!indexType.isInteger) {
                    throw SemaException("Array index must be an integer, not $indexType")
                }
                target.type = arrayType.elementType ?: Types.unit

                checkAssignedValue(expr, target.type)
                expr.type = target.type
            }

            else -> throw SemaException("Invalid assignment target")
        }
        return expr.type
    }

    /**
     * Checks the value of an assignment against the target's type, according to
     * the assignment operator:
     *
     * - `=` — the value's type must equal the target's type.
     * - `+=`, `-=`, `*=`, `/=`, `%=` — read-modify-write: the arithmetic rule
     *   applies, so the value has the target's type. An integer literal takes
     *   the target's type contextually; any other value of a different type is
     *   an error, because there is no promotion.
     * - `:=`, `.=`, `&=`, `|=` — a bit mask: the value is an integer, but it
     *   need not have the target's type; it only has to fit the target's width.
     */
    private fun checkAssignedValue(expr: Assign, targetType: WolinType) {
        when (expr.op) {
            "=" -> {
                val valueType = checkExpr(expr.value, targetType)
                if (valueType.kind != targetType.kind) {
                    throw SemaException("Attempt reassign $targetType $valueType")
                }
            }

            "+=", "-=", "*=", "/=", "%=" -> {
                requireNumeric(targetType, expr.op)
                // An integer literal takes an *integer* target's type
                // contextually. A float target does not take an integer
                // literal — that would be a promotion, and there is none.
                val expected = if (targetType.isInteger) targetType else null
                val valueType = checkExpr(expr.value, expected)
                if (valueType.kind != targetType.kind) {
                    throw SemaException("Attempt reassign $targetType $valueType")
                }
            }

            ":=", ".=", "&=", "|=" -> {
                if (!targetType.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $targetType")
                }
                val valueType = checkExpr(expr.value, null)
                if (!valueType.isInteger) {
                    throw SemaException("Operator ${expr.op} is not defined for $valueType")
                }
                val literal = maskLiteral(expr.value)
                if (literal != null && !fitsWidth(literal, targetType)) {
                    throw SemaException("Value out of range")
                }
            }

            else -> throw SemaException("Unknown assignment operator ${expr.op}")
        }
    }

    private fun checkCall(expr: Call): WolinType {
        val callee = expr.callee
        if (callee !is VarRef) {
            throw SemaException("Unsupported call target")
        }
        val symbol = scope.lookup(callee.name)
            ?: throw SemaException("Unknown function ${callee.name}")
        val function = symbol as? Symbol.FunSymbol
            ?: throw SemaException("${callee.name} is not a function")
        resolved[callee] = symbol
        callee.type = functionTypeOf(function.decl)

        val params = function.decl.params
        if (params.size != expr.args.size) {
            throw SemaException(
                "Function ${callee.name} expects ${params.size} arguments but got ${expr.args.size}"
            )
        }
        expr.args.forEachIndexed { index, argument ->
            val argumentType = checkExpr(argument, params[index].type)
            if (argumentType.kind != params[index].type.kind) {
                throw SemaException("Attempt reassign ${params[index].type} $argumentType")
            }
        }

        expr.type = function.decl.retType ?: Types.unit
        return expr.type
    }

    private fun checkFunctionLiteral(expr: FunctionLit): WolinType {
        val outer = scope
        val literalScope = scope.child()
        scope = literalScope
        try {
            expr.params.forEach { name ->
                literalScope.declare(name, Symbol.DataSymbol(Types.unit, null, null))
            }
            expr.body.forEach { checkStmt(it) }
        } finally {
            scope = outer
        }
        expr.type = WolinType(
            TypeKind.FUNCTION,
            params = expr.params.map { Types.unit },
            retType = Types.unit
        )
        return expr.type
    }

    private fun checkIfExpr(expr: IfExpr, expected: WolinType?): WolinType {
        requireBool(checkExpr(expr.cond, null), "if")
        val thenType = checkBranch(expr.then, expected)
        val elseType = checkBranch(expr.otherwise, expected)
        expr.type = if (thenType.kind == elseType.kind) thenType else Types.unit
        return expr.type
    }

    private fun checkArrayAccess(expr: ArrayAccess): WolinType {
        val arrayType = checkExpr(expr.array, null)
        val elementType = when (arrayType.kind) {
            TypeKind.ARRAY -> arrayType.elementType ?: Types.unit
            // A string is an array of bytes; indexing it yields a `ubyte`.
            TypeKind.STRING -> Types.ubyte
            else -> throw SemaException("Cannot index a value of type $arrayType")
        }
        val indexType = checkExpr(expr.index, null)
        if (!indexType.isInteger) {
            throw SemaException("Array index must be an integer, not $indexType")
        }
        expr.type = elementType
        return expr.type
    }

    // ---- type rules -------------------------------------------------------

    /**
     * The type of an integer literal: the expected type when the context
     * supplies one, the smallest type that holds the value otherwise.
     */
    private fun literalType(value: Long, expected: WolinType?): WolinType {
        if (expected != null && (expected.isInteger || expected.kind == TypeKind.FLOAT)) {
            if (expected.isInteger && !fits(value, expected)) {
                throw SemaException("Value out of range")
            }
            return expected
        }
        return smallestType(value)
    }

    /** The smallest type that holds `value`. */
    private fun smallestType(value: Long): WolinType = when {
        value in 0..255 -> Types.ubyte
        value in 256..65535 -> Types.uword
        value in -127..-1 -> Types.byte
        value in -32768..-128 -> Types.word
        else -> throw SemaException("Value out of range")
    }

    /** Whether `value` fits into `type`. */
    private fun fits(value: Long, type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE -> value in 0..255
        TypeKind.BYTE -> value in -127..127
        TypeKind.UWORD -> value in 0..65535
        TypeKind.WORD -> value in -32768..32767
        else -> false
    }

    /**
     * Whether `value` fits into the *width* of `type`. A bit mask is a bit
     * pattern, so `0xFF` fits an `ubyte` even though it does not fit a
     * `byte`.
     */
    private fun fitsWidth(value: Long, type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE -> value in 0..255
        TypeKind.BYTE -> value in -128..255
        TypeKind.UWORD -> value in 0..65535
        TypeKind.WORD -> value in -32768..65535
        else -> false
    }

    /**
     * The literal value of a bit mask, or null when the mask is not a literal.
     *
     * A negated literal is a `UnaryOp`, not an `IntLit`, so the width check
     * has to see through the negation. The value to check is the literal's own
     * value, not its negation: `-1` is the bit pattern `0xFF`, whose magnitude
     * `1` fits an `ubyte`.
     */
    private fun maskLiteral(value: Expr): Long? = when (value) {
        is IntLit -> value.value
        is UnaryOp -> if (value.op == "-") (value.operand as? IntLit)?.value else null
        else -> null
    }

    private fun requireBool(type: WolinType, what: String) {
        if (type.kind != TypeKind.BOOL) {
            throw SemaException("$what requires a bool condition, not $type")
        }
    }

    private fun requireNumeric(type: WolinType, op: String) {
        if (!type.isInteger && type.kind != TypeKind.FLOAT) {
            throw SemaException("Operator $op is not defined for $type")
        }
    }

    private fun functionTypeOf(decl: FunDecl): WolinType = WolinType(
        TypeKind.FUNCTION,
        params = decl.params.map { it.type },
        retType = decl.retType ?: Types.unit
    )
}
