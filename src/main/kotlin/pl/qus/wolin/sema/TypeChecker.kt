package pl.qus.wolin.sema

import pl.qus.wolin.ast.*
import java.util.IdentityHashMap
import java.util.LinkedHashMap

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
    private val importResolver = ImportResolver()
    /** The imports of the file being checked. */
    private var fileImports: List<ImportDecl> = emptyList()
    /** The whole-program view the imports resolve against. */
    private var program: Ast.Program? = null

    fun check(file: Ast.Program): Ast.Program {
        resolved = IdentityHashMap()
        program = file
        // The whole-program view: each file is checked in its own scope, so
        // a name from another package is visible only through an import.
        val scopes = file.files.ifEmpty { listOf(singleFileScope(file)) }
        scopes.forEach { fileScope ->
            checkFile(fileScope, file)
        }
        return file.copy(resolved = resolved)
    }

    /** The scope of a program built from a single file. */
    private fun singleFileScope(file: Ast.Program): FileScope = FileScope(
        fileName = "",
        packageName = file.packageName,
        globals = file.globals,
        functions = file.functions,
        imports = file.imports,
        mangledPrefix = "",
    )

    /** Checks one file's declarations in its own scope. */
    private fun checkFile(fileScope: FileScope, program: Ast.Program) {
        val globals = Scope()
        scope = globals
        fileImports = fileScope.imports

        // The file's own functions are visible everywhere inside it, so they
        // are declared before anything is checked — a call may name a
        // function declared further down.
        fileScope.functions.forEach { fn ->
            globals.declare(fn.name, Symbol.FunSymbol(fn, fileScope.mangledPrefix))
        }
        // A declaration of another file in the same package is visible
        // without an import; one from another package is not. These are
        // declared first: the file's own declarations shadow them.
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other ->
                other.functions.forEach { fn ->
                    globals.declare(fn.name, Symbol.FunSymbol(fn, other.mangledPrefix))
                }
                other.globals.forEach { global ->
                    globals.declare(
                        global.name,
                        Symbol.DataSymbol(
                            global.type, global.location, global.init, global.isVal,
                            other.mangledPrefix,
                        ),
                    )
                }
            }
        fileScope.globals.forEach { global ->
            globals.declare(
                global.name,
                Symbol.DataSymbol(
                    global.type, global.location, global.init, global.isVal,
                    fileScope.mangledPrefix,
                ),
            )
        }
        // An import brings a name from another package into scope; without
        // the import the name does not resolve at all.
        fileScope.imports.forEach { import ->
            if (import.alias != null) {
                throw SemaException("import aliases are not supported in v1")
            }
            val path = import.path.removeSuffix(".*")
            val imported = path.substringAfterLast('.')
            if (imported.isEmpty() || imported == path) return@forEach
            val symbol = importResolver.resolve(imported, listOf(import), program)
                ?: throw SemaException("Cannot resolve import ${import.path}")
            globals.declare(imported, symbol)
        }
        // The constant value of every global checked so far: a global's
        // initialiser may reference only globals declared before it. The
        // constants of the other files of the same package come first: they
        // are initialised before this file's, in command-line order.
        val constSoFar = LinkedHashMap<String, ConstValue>()
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .flatMap { it.globals }
            .forEach { global ->
                global.constValue?.let { constSoFar[global.name] = it }
            }
        // The functions this file can name, with the prefix of the file that
        // declares each: a function of another file is emitted under that
        // file's prefix.
        val functionNames = mutableMapOf<String, String>()
        fileScope.functions.forEach { fn -> functionNames[fn.name] = fileScope.mangledPrefix }
        program.files.filter { it.packageName == fileScope.packageName && it !== fileScope }
            .forEach { other ->
                other.functions.forEach { fn -> functionNames[fn.name] = other.mangledPrefix }
            }
        fileScope.imports.forEach { import ->
            val imported = import.path.removeSuffix(".*").substringAfterLast('.', "")
            if (imported.isEmpty()) return@forEach
            val scope = program.files.firstOrNull {
                it.packageName == import.path.removeSuffix(".*").substringBeforeLast('.', "") &&
                    it.functions.any { fn -> fn.name == imported }
            }
            if (scope != null) functionNames[imported] = scope.mangledPrefix
        }
        fileScope.globals.forEachIndexed { index, global ->
            checkGlobal(global, fileScope, index, constSoFar, functionNames)
        }
        fileScope.functions.forEach { fn -> checkFunction(fn, globals) }
    }
    // ---- declarations -----------------------------------------------------

    private fun checkGlobal(
        global: GlobalDecl,
        fileScope: FileScope,
        index: Int,
        constSoFar: MutableMap<String, ConstValue>,
        functionNames: Map<String, String>,
    ) {
        val declared = global.declaredType
        val init = global.init
        if (init != null) {
            // The ordering error must beat the plain "unknown variable" the
            // reference check below would report for the same name.
            val laterNames = fileScope.globals.drop(index + 1).map { it.name }.toSet()
            varRefs(init).firstOrNull { it.name in laterNames }?.let {
                throw SemaException("initialiser references a global that is not yet initialised")
            }
        }
        val initType = init?.let { checkExpr(it, declared) }
        if (declared != null && initType != null && initType.kind != declared.kind) {
            throw SemaException("Attempt reassign $declared $initType")
        }
        val type = declared ?: initType
            ?: throw SemaException("Variable ${global.name} has no type!")
        global.type = type
        if (init != null) {
            global.constValue = ConstFolder.fold(init, constSoFar, functionNames)
                ?: throw SemaException("global initialiser must be a constant expression")
        }
        scope.declare(global.name, Symbol.DataSymbol(type, global.location, global.init, global.isVal))
        val constValue = global.constValue
        if (constValue != null) constSoFar[global.name] = constValue
    }

    /** Every variable reference in [expr], in source order. */
    private fun varRefs(expr: Expr, out: MutableList<VarRef> = mutableListOf()): List<VarRef> {
        when (expr) {
            is VarRef -> out += expr
            is UnaryOp -> varRefs(expr.operand, out)
            is BinaryOp -> { varRefs(expr.left, out); varRefs(expr.right, out) }
            is Call -> { varRefs(expr.callee, out); expr.args.forEach { varRefs(it, out) } }
            is ArrayAccess -> { varRefs(expr.array, out); varRefs(expr.index, out) }
            is Assign -> { varRefs(expr.target, out); varRefs(expr.value, out) }
            is IfExpr -> {
                varRefs(expr.cond, out)
                expr.then.forEach { walkStmtVarRefs(it, out) }
                expr.otherwise.forEach { walkStmtVarRefs(it, out) }
            }
            is WhenExpr -> {
                expr.subject?.let { varRefs(it, out) }
                expr.entries.forEach { entry ->
                    entry.conditions.forEach { varRefs(it, out) }
                    entry.body.forEach { walkStmtVarRefs(it, out) }
                }
            }
            is Convert -> varRefs(expr.value, out)
            else -> Unit
        }
        return out
    }

    private fun walkStmtVarRefs(stmt: Stmt, out: MutableList<VarRef>) {
        when (stmt) {
            is LocalDecl -> stmt.init?.let { varRefs(it, out) }
            is ExprStmt -> varRefs(stmt.expr, out)
            is BlockStmt -> stmt.body.forEach { walkStmtVarRefs(it, out) }
            is IfStmt -> {
                varRefs(stmt.cond, out)
                stmt.then.forEach { walkStmtVarRefs(it, out) }
                stmt.otherwise.forEach { walkStmtVarRefs(it, out) }
            }
            is WhileStmt -> {
                varRefs(stmt.cond, out)
                stmt.body.forEach { walkStmtVarRefs(it, out) }
            }
            is ReturnStmt -> stmt.value?.let { varRefs(it, out) }
            is WhenStmt -> {
                stmt.subject?.let { varRefs(it, out) }
                stmt.entries.forEach { entry ->
                    entry.conditions.forEach { varRefs(it, out) }
                    entry.body.forEach { walkStmtVarRefs(it, out) }
                }
            }
            else -> Unit
        }
    }

    private fun checkFunction(fn: FunDecl, globals: Scope) {
        if (fn.isInterrupt) {
            if (fn.params.isNotEmpty()) {
                throw SemaException("Arguments not allowed for interrupt function!")
            }
            if (fn.retType != null) {
                throw SemaException("Return not allowed for interrupt function!")
            }
        }
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

            is ReturnAtStmt -> Unit
            is BreakStmt, is ContinueStmt -> Unit
        }
    }

    private fun checkWhen(stmt: WhenStmt) {
        checkWhenEntries(stmt.subject, stmt.entries) { entry -> checkBranch(entry.body, null) }
    }

    private fun checkWhenExpr(expr: WhenExpr, expected: WolinType?): WolinType {
        val branchTypes = checkWhenEntries(expr.subject, expr.entries) { entry ->
            checkBranch(entry.body, expected)
        }
        expr.type = if (branchTypes.all { it.kind == branchTypes.firstOrNull()?.kind }) {
            branchTypes.firstOrNull() ?: Types.unit
        } else {
            Types.unit
        }
        return expr.type
    }

    /**
     * Checks a `when`'s subject and entries: the subject's comparability, the
     * else ordering, and each condition's type. [checkBranch] is called on
     * every entry body and its result collected; the statement form discards
     * them, the expression form unifies them into the value's type.
     */
    private fun checkWhenEntries(
        subject: Expr?,
        entries: List<WhenEntry>,
        checkBranch: (WhenEntry) -> WolinType,
    ): List<WolinType> {
        val subjectType = subject?.let { checkExpr(it, null) }
        if (subjectType != null && (subjectType.kind == TypeKind.UNIT || subjectType.kind == TypeKind.STRING)) {
            throw SemaException("when subject must be comparable, not $subjectType")
        }
        var seenElse = false
        val branchTypes = mutableListOf<WolinType>()
        entries.forEach { entry ->
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
            branchTypes += checkBranch(entry)
        }
        return branchTypes
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
        is Call -> checkCall(expr, expected)
        is FunctionLit -> checkFunctionLiteral(expr, expected)
        is IfExpr -> checkIfExpr(expr, expected)
        is WhenExpr -> checkWhenExpr(expr, expected)
        is ArrayAccess -> checkArrayAccess(expr)
        is Convert -> checkConvert(expr)
    }

    /**
     * The symbol [name] refers to: the scope first, then the imports.
     *
     * A wildcard import (`import a.b.*`) brings in names that are not known
     * until one is used, so the import is consulted when the scope has no
     * declaration of the name.
     */
    private fun lookup(name: String): Symbol? {
        val symbol = scope.lookup(name)
        if (symbol != null) return symbol
        val program = program ?: return null
        return importResolver.resolve(name, fileImports, program)
    }

    private fun checkVarRef(expr: VarRef): WolinType {
        val symbol = lookup(expr.name)
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

            "&", "|", "xor" -> {
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
                val symbol = lookup(target.name)
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

    private fun checkCall(expr: Call, expected: WolinType?): WolinType {
        val callee = expr.callee
        if (callee !is VarRef) {
            throw SemaException("Unsupported call target")
        }
        val symbol = lookup(callee.name)
            ?: throw SemaException("Unknown function ${callee.name}")
        when (symbol) {
            is Symbol.FunSymbol -> {
                resolved[callee] = symbol
                callee.type = functionTypeOf(symbol.decl)
                checkCallArgs(expr, callee.name, symbol.decl.params)
                expr.type = symbol.decl.retType ?: Types.unit
                return expr.type
            }
            // A `uword` holds a 16-bit code address; calling through it is an
            // indirect call. The signature is not known here, so the result
            // type is inferred from the expected type, and the arguments are
            // checked only for well-formedness.
            is Symbol.DataSymbol -> {
                // A function-pointer-typed variable is called through the
                // pointer: its signature is known, so the arguments are
                // checked against it.
                if (symbol.type.kind == TypeKind.FUNCTION) {
                    resolved[callee] = symbol
                    callee.type = symbol.type
                    val params = symbol.type.params
                    if (params.size != expr.args.size) {
                        throw SemaException(
                            "Function ${callee.name} expects ${params.size} arguments " +
                                "but got ${expr.args.size}"
                        )
                    }
                    expr.args.forEachIndexed { index, argument ->
                        val argumentType = checkExpr(argument, params[index])
                        if (argumentType.kind != params[index].kind) {
                            throw SemaException(
                                "Attempt reassign ${params[index]} $argumentType"
                            )
                        }
                    }
                    expr.type = symbol.type.retType ?: Types.unit
                    return expr.type
                }
                if (symbol.type.kind != TypeKind.UWORD) {
                    throw SemaException("${callee.name} not function")
                }
                resolved[callee] = symbol
                callee.type = symbol.type
                expr.args.forEach { checkExpr(it, null) }
                expr.type = expected ?: Types.ubyte
                return expr.type
            }
        }
    }

    private fun checkCallArgs(expr: Call, name: String, params: List<Param>) {
        if (params.size != expr.args.size) {
            throw SemaException(
                "Function $name expects ${params.size} arguments but got ${expr.args.size}"
            )
        }
        expr.args.forEachIndexed { index, argument ->
            val argumentType = checkExpr(argument, params[index].type)
            if (argumentType.kind != params[index].type.kind) {
                throw SemaException("Attempt reassign ${params[index].type} $argumentType")
            }
        }
    }

    private fun checkFunctionLiteral(expr: FunctionLit, expected: WolinType?): WolinType {
        // The parameter types come from the expected type at the literal's
        // site; without one there is nothing to infer them from.
        val paramTypes = FunctionLiteralInfer.infer(expr, expected)
        expr.paramTypes = paramTypes
        val retType = expected?.retType ?: Types.unit
        val outer = scope
        val literalScope = scope.child()
        scope = literalScope
        try {
            expr.params.forEachIndexed { index, name ->
                literalScope.declare(name, Symbol.DataSymbol(paramTypes[index], null, null))
            }
            currentReturnType = retType
            expr.body.forEach { checkStmt(it) }
            // The literal's value is its last expression, so that expression
            // must have the expected return type.
            val last = expr.body.lastOrNull()
            if (last is ExprStmt && retType.kind != TypeKind.UNIT) {
                val valueType = last.expr.type
                if (valueType.kind != retType.kind) {
                    throw SemaException("Attempt reassign $retType $valueType")
                }
            }
        } finally {
            scope = outer
        }
        expr.type = WolinType(
            TypeKind.FUNCTION,
            params = paramTypes,
            retType = retType
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

    /**
     * An explicit conversion: the operand must be an integer, the result is the
     * named target type. This is the only way a value changes integer type —
     * there is no promotion anywhere else.
     */
    private fun checkConvert(expr: Convert): WolinType {
        val valueType = checkExpr(expr.value, null)
        if (!valueType.isInteger) {
            throw SemaException("${expr.to} conversion requires an integer, not $valueType")
        }
        expr.type = expr.to
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
        value in -128..-1 -> Types.byte
        value in -32768..-129 -> Types.word
        else -> throw SemaException("Value out of range")
    }

    /** Whether `value` fits into `type`. */
    private fun fits(value: Long, type: WolinType): Boolean = when (type.kind) {
        TypeKind.UBYTE -> value in 0..255
        TypeKind.BYTE -> value in -128..127
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
