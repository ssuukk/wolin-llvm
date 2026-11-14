package pl.qus.wolin.sema

import pl.qus.wolin.ast.*
import pl.qus.wolin.parser.KotlinParser

/** A declaration's type together with the location it is pinned to, if any. */
private data class TypeInfo(val type: WolinType, val location: Location?)

/**
 * The Kotlin grammar puts jumps and loops in expression position. The builder
 * converts them back into statements and hands them to the caller through this
 * signal.
 */
private class StmtSignal(val stmt: Stmt) : RuntimeException(null, null, false, false)

/**
 * Walks a parse tree into the typed AST of `Ast.kt`.
 *
 * Every type slot is left unset — the [TypeChecker] fills them in.
 */
class AstBuilder {
    /** Collects the `return@<address>` sites of the function being built. */
    private var returnAtSink: MutableList<ReturnAt>? = null

    fun build(file: KotlinParser.KotlinFileContext): Ast.Program {
        val imports = mutableListOf<ImportDecl>()
        val globals = mutableListOf<GlobalDecl>()
        val functions = mutableListOf<FunDecl>()

        file.preamble()?.importList()?.importHeader()?.forEach { header ->
            imports += ImportDecl(importPath(header), header.importAlias()?.simpleIdentifier()?.text)
        }

        file.topLevelObject().forEach { top ->
            top.propertyDeclaration()?.let { globals += buildGlobal(it); return@forEach }
            top.functionDeclaration()?.let { functions += buildFunction(it); return@forEach }
            top.classDeclaration()?.let {
                unsupported("class ${it.simpleIdentifier()?.text ?: ""}")
            }
            top.objectDeclaration()?.let { unsupported("object declaration") }
            top.typeAlias()?.let { unsupported("type alias") }
            unsupported("declaration")
        }

        return Program(globals, functions, imports)
    }

    // ---- declarations -----------------------------------------------------

    private fun buildGlobal(ctx: KotlinParser.PropertyDeclarationContext): GlobalDecl {
        val decl = ctx.variableDeclaration() ?: unsupported("multiple variable declaration")
        val info = decl.type()?.let { buildType(it) }
        val init = ctx.expression()?.let { buildExpression(it) }
        checkAccessors(ctx)
        return GlobalDecl(
            name = decl.simpleIdentifier().text,
            type = info?.type ?: Types.unit,
            init = init,
            location = info?.location,
            isVal = ctx.VAL() != null,
            declaredType = info?.type
        )
    }

    private fun buildLocal(ctx: KotlinParser.PropertyDeclarationContext): LocalDecl {
        val decl = ctx.variableDeclaration() ?: unsupported("multiple variable declaration")
        val info = decl.type()?.let { buildType(it) }
        val init = ctx.expression()?.let { buildExpression(it) }
        checkAccessors(ctx)
        return LocalDecl(
            name = decl.simpleIdentifier().text,
            type = info?.type ?: Types.unit,
            init = init,
            isVal = ctx.VAL() != null,
            location = info?.location,
            declaredType = info?.type
        )
    }

    private fun buildFunction(ctx: KotlinParser.FunctionDeclarationContext): FunDecl {
        val name = ctx.identifier()?.let { qualifiedName(it) } ?: unsupported("anonymous function")
        val isInterrupt = ctx.modifierList()?.modifier().orEmpty()
            .any { it.functionModifier()?.INTERRUPT() != null }
        val fixedAddress = ctx.locationReference()?.let { locationAddress(it) }
        val params = ctx.functionValueParameters()?.functionValueParameter().orEmpty().map { buildParam(it) }
        val retType = ctx.type().lastOrNull { type ->
            val colon = ctx.COLON() ?: return@lastOrNull false
            type.start.tokenIndex > colon.symbol.tokenIndex
        }?.let { buildType(it).type }

        val returnAt = mutableListOf<ReturnAt>()
        val previousSink = returnAtSink
        returnAtSink = returnAt
        val body = try {
            buildFunctionBody(ctx.functionBody())
        } finally {
            returnAtSink = previousSink
        }

        return FunDecl(name, params, retType, body, isInterrupt, fixedAddress, returnAt)
    }

    private fun buildParam(ctx: KotlinParser.FunctionValueParameterContext): Param {
        val param = ctx.parameter() ?: unsupported("parameter")
        val info = buildType(param.type())
        return Param(param.simpleIdentifier().text, info.type, info.location)
    }

    private fun buildFunctionBody(ctx: KotlinParser.FunctionBodyContext?): List<Stmt> {
        if (ctx == null) return emptyList()
        ctx.block()?.let { return buildStatements(it.statements()) }
        ctx.expression()?.let { return listOf(ReturnStmt(buildExpression(it))) }
        return emptyList()
    }

    private fun checkAccessors(ctx: KotlinParser.PropertyDeclarationContext) {
        if (ctx.getter() != null || ctx.setter() != null) unsupported("property accessor")
        if (ctx.BY() != null) unsupported("delegated property")
    }

    // ---- statements -------------------------------------------------------

    private fun buildStatements(ctx: KotlinParser.StatementsContext?): List<Stmt> {
        if (ctx == null) return emptyList()
        return ctx.statement().map { buildStatement(it) }
    }

    private fun buildStatement(ctx: KotlinParser.StatementContext): Stmt {
        ctx.declaration()?.let { declaration ->
            declaration.propertyDeclaration()?.let { return buildLocal(it) }
            declaration.functionDeclaration()?.let { unsupported("nested function") }
            declaration.classDeclaration()?.let { unsupported("class declaration") }
            declaration.typeAlias()?.let { unsupported("type alias") }
        }
        val expression = ctx.blockLevelExpression()?.expression() ?: unsupported("statement")
        bareWhen(expression)?.let { return buildWhen(it) }
        return try {
            expressionToStatement(buildExpression(expression))
        } catch (signal: StmtSignal) {
            signal.stmt
        }
    }

    /** A bare `{ ... }` in statement position is a block, not a lambda. */
    private fun expressionToStatement(expr: Expr): Stmt = when (expr) {
        is IfExpr -> IfStmt(expr.cond, expr.then, expr.otherwise)
        is FunctionLit -> if (expr.params.isEmpty()) BlockStmt(expr.body) else ExprStmt(expr)
        else -> ExprStmt(expr)
    }

    /**
     * The body of an `if` or a loop. A block body stays a `BlockStmt`, so the
     * scope it introduces survives into the typed AST.
     *
     * A non-block body may be a jump — `if (c) return 5` — which the grammar
     * puts in expression position. The jump becomes the one statement of the
     * body, so the `if` around it is not lost.
     */
    private fun buildControlBody(ctx: KotlinParser.ControlStructureBodyContext?): List<Stmt> {
        if (ctx == null) return emptyList()
        ctx.block()?.let { return listOf(BlockStmt(buildStatements(it.statements()))) }
        ctx.expression()?.let { expression ->
            bareWhen(expression)?.let { return listOf(buildWhen(it)) }
            return try {
                listOf(expressionToStatement(buildExpression(expression)))
            } catch (signal: StmtSignal) {
                listOf(signal.stmt)
            }
        }
        return emptyList()
    }

    // ---- expressions ------------------------------------------------------

    private fun buildExpression(ctx: KotlinParser.ExpressionContext): Expr {
        val disjunctions = ctx.disjunction()
        if (ctx.assignmentOperator().isEmpty()) return buildDisjunction(disjunctions[0])

        var value = buildDisjunction(disjunctions[1])
        for (i in 2 until disjunctions.size) {
            value = Assign(buildDisjunction(disjunctions[i]), value, Types.unit, ctx.assignmentOperator()[i - 1].text)
        }
        return Assign(buildDisjunction(disjunctions[0]), value, Types.unit, ctx.assignmentOperator()[0].text)
    }

    private fun buildDisjunction(ctx: KotlinParser.DisjunctionContext): Expr =
        chain(ctx.conjunction(), ctx.DISJ().map { it.text }) { buildConjunction(it) }

    private fun buildConjunction(ctx: KotlinParser.ConjunctionContext): Expr =
        chain(ctx.equalityComparison(), ctx.CONJ().map { it.text }) { buildEquality(it) }

    private fun buildEquality(ctx: KotlinParser.EqualityComparisonContext): Expr =
        chain(ctx.comparison(), ctx.equalityOperation().map { it.text }) { buildComparison(it) }

    private fun buildComparison(ctx: KotlinParser.ComparisonContext): Expr {
        val operands = ctx.namedInfix()
        val operator = ctx.comparisonOperator()
        if (operator == null) return buildNamedInfix(operands[0])
        return BinaryOp(operator.text, buildNamedInfix(operands[0]), buildNamedInfix(operands[1]), Types.unit)
    }

    private fun buildNamedInfix(ctx: KotlinParser.NamedInfixContext): Expr {
        if (ctx.isOperator() != null) unsupported("is check")
        if (ctx.inOperator().isNotEmpty()) unsupported("in check")
        return buildElvis(ctx.elvisExpression()[0])
    }

    private fun buildElvis(ctx: KotlinParser.ElvisExpressionContext): Expr {
        if (ctx.ELVIS().isNotEmpty()) unsupported("elvis operator")
        return buildInfixCall(ctx.infixFunctionCall()[0])
    }

    private fun buildInfixCall(ctx: KotlinParser.InfixFunctionCallContext): Expr {
        if (ctx.simpleIdentifier().isNotEmpty()) unsupported("infix function call")
        return buildRange(ctx.rangeExpression()[0])
    }

    private fun buildRange(ctx: KotlinParser.RangeExpressionContext): Expr {
        if (ctx.RANGE().isNotEmpty()) unsupported("range expression")
        return buildAdditive(ctx.additiveExpression()[0])
    }

    private fun buildAdditive(ctx: KotlinParser.AdditiveExpressionContext): Expr =
        chain(ctx.multiplicativeExpression(), ctx.additiveOperator().map { it.text }) { buildMultiplicative(it) }

    private fun buildMultiplicative(ctx: KotlinParser.MultiplicativeExpressionContext): Expr =
        chain(ctx.typeRHS(), ctx.multiplicativeOperation().map { it.text }) { buildTypeRhs(it) }

    private fun buildTypeRhs(ctx: KotlinParser.TypeRHSContext): Expr {
        if (ctx.typeOperation().isNotEmpty()) unsupported("type operation")
        return buildPrefix(ctx.prefixUnaryExpression()[0])
    }

    private fun buildPrefix(ctx: KotlinParser.PrefixUnaryExpressionContext): Expr {
        var result = buildPostfix(ctx.postfixUnaryExpression())
        for (operation in ctx.prefixUnaryOperation().reversed()) {
            val label = operation.labelDefinition()?.text
            if (label != null) {
                if (label == "return@") {
                    recordReturnAt(result)
                    throw StmtSignal(ReturnStmt(null))
                }
                unsupported("label '$label'")
            }
            val op = when {
                operation.INCR() != null -> "++"
                operation.DECR() != null -> "--"
                operation.ADD() != null -> "+"
                operation.SUB() != null -> "-"
                operation.EXCL() != null -> "!"
                else -> unsupported("prefix operator")
            }
            result = UnaryOp(op, result, Types.unit, prefix = true)
        }
        return result
    }

    private fun buildPostfix(ctx: KotlinParser.PostfixUnaryExpressionContext): Expr {
        if (ctx.callableReference() != null) unsupported("callable reference")
        var result = buildAtomic(ctx.atomicExpression())
        for (operation in ctx.postfixUnaryOperation()) {
            result = when {
                operation.INCR() != null -> UnaryOp("++", result, Types.unit)
                operation.DECR() != null -> UnaryOp("--", result, Types.unit)
                operation.EXCL().isNotEmpty() -> unsupported("not-null assertion")
                operation.callSuffix() != null -> buildCall(result, operation.callSuffix())
                operation.arrayAccess() != null -> buildArrayAccess(result, operation.arrayAccess())
                operation.memberAccessOperator() != null -> unsupported("member access")
                else -> unsupported("postfix operator")
            }
        }
        return result
    }

    private fun buildCall(callee: Expr, ctx: KotlinParser.CallSuffixContext): Expr {
        val args = ctx.valueArguments()?.valueArgument().orEmpty().map { buildExpression(it.expression()) }
        return Call(callee, args, Types.unit)
    }

    private fun buildArrayAccess(array: Expr, ctx: KotlinParser.ArrayAccessContext): Expr {
        val index = ctx.expression().firstOrNull() ?: unsupported("array access")
        return ArrayAccess(array, buildExpression(index), Types.unit)
    }

    private fun buildAtomic(ctx: KotlinParser.AtomicExpressionContext): Expr {
        ctx.parenthesizedExpression()?.let { return buildExpression(it.expression()) }
        ctx.literalConstant()?.let { return buildLiteral(it) }
        ctx.functionLiteral()?.let { return buildFunctionLiteral(it) }
        ctx.simpleIdentifier()?.let { return VarRef(it.text, Types.unit) }
        ctx.conditionalExpression()?.let { conditional ->
            conditional.ifExpression()?.let { return buildIf(it) }
            conditional.whenExpression()?.let { unsupported("when expression") }
        }
        ctx.jumpExpression()?.let { return buildJump(it) }
        ctx.loopExpression()?.let { return buildLoop(it) }
        ctx.thisExpression()?.let { unsupported("this expression") }
        ctx.superExpression()?.let { unsupported("super expression") }
        ctx.tryExpression()?.let { unsupported("try expression") }
        ctx.objectLiteral()?.let { unsupported("object literal") }
        ctx.collectionLiteral()?.let { unsupported("collection literal") }
        unsupported("expression")
    }

    private fun buildIf(ctx: KotlinParser.IfExpressionContext): Expr {
        val cond = buildExpression(ctx.expression())
        val then = buildControlBody(ctx.controlStructureBody().getOrNull(0))
        val otherwise = buildControlBody(ctx.controlStructureBody().getOrNull(1))
        return IfExpr(cond, then, otherwise, Types.unit)
    }

    private fun buildWhen(ctx: KotlinParser.WhenExpressionContext): WhenStmt {
        val subject = ctx.expression()?.let { buildExpression(it) }
        val entries = ctx.whenEntry().map { buildWhenEntry(it) }
        return WhenStmt(subject, entries)
    }

    private fun buildWhenEntry(ctx: KotlinParser.WhenEntryContext): WhenEntry {
        val isElse = ctx.ELSE() != null
        val conditions = ctx.whenCondition().map { condition ->
            condition.expression()?.let { buildExpression(it) }
                ?: condition.rangeTest()?.let { unsupported("range test in when (v2)") }
                ?: condition.typeTest()?.let { unsupported("type test in when (v2)") }
                ?: unsupported("when condition")
        }
        return WhenEntry(conditions, buildControlBody(ctx.controlStructureBody()), isElse)
    }

    /**
     * The `when` a statement is built from, when the whole statement
     * expression is a bare `when` — no assignment, no operator, no wrapper
     * of any kind. A `when` anywhere else in an expression stays
     * unsupported (v2).
     */
    private fun bareWhen(expression: KotlinParser.ExpressionContext): KotlinParser.WhenExpressionContext? {
        if (expression.assignmentOperator().isNotEmpty()) return null
        val disjunctions = expression.disjunction()
        if (disjunctions.size != 1) return null
        val conjunctions = disjunctions[0].conjunction()
        if (conjunctions.size != 1) return null
        val equalities = conjunctions[0].equalityComparison()
        if (equalities.size != 1) return null
        val comparisons = equalities[0].comparison()
        if (comparisons.size != 1) return null
        val namedInfixes = comparisons[0].namedInfix()
        if (namedInfixes.size != 1) return null
        if (namedInfixes[0].isOperator() != null || namedInfixes[0].inOperator().isNotEmpty()) return null
        val elvises = namedInfixes[0].elvisExpression()
        if (elvises.size != 1 || elvises[0].ELVIS().isNotEmpty()) return null
        val infixCalls = elvises[0].infixFunctionCall()
        if (infixCalls.size != 1 || infixCalls[0].simpleIdentifier().isNotEmpty()) return null
        val ranges = infixCalls[0].rangeExpression()
        if (ranges.size != 1 || ranges[0].RANGE().isNotEmpty()) return null
        val additives = ranges[0].additiveExpression()
        if (additives.size != 1 || additives[0].additiveOperator().isNotEmpty()) return null
        val multiplications = additives[0].multiplicativeExpression()
        if (multiplications.size != 1 || multiplications[0].multiplicativeOperation().isNotEmpty()) return null
        val typeRHSes = multiplications[0].typeRHS()
        if (typeRHSes.size != 1 || typeRHSes[0].typeOperation().isNotEmpty()) return null
        val prefixes = typeRHSes[0].prefixUnaryExpression()
        if (prefixes.size != 1 || prefixes[0].prefixUnaryOperation().isNotEmpty()) return null
        val postfix = prefixes[0].postfixUnaryExpression() ?: return null
        if (postfix.callableReference() != null || postfix.postfixUnaryOperation().isNotEmpty()) return null
        val atomic = postfix.atomicExpression() ?: return null
        return atomic.conditionalExpression()?.whenExpression()
    }

    private fun buildJump(ctx: KotlinParser.JumpExpressionContext): Expr {
        if (ctx.THROW() != null) unsupported("throw expression")
        val value = ctx.expression()?.let { buildExpression(it) }
        val stmt = when {
            ctx.RETURN_AT() != null -> unsupported("return@<label>")
            ctx.BREAK_AT() != null -> unsupported("break@<label>")
            ctx.CONTINUE_AT() != null -> unsupported("continue@<label>")
            ctx.RETURN() != null -> ReturnStmt(value)
            ctx.BREAK() != null -> BreakStmt(null)
            ctx.CONTINUE() != null -> ContinueStmt(null)
            else -> unsupported("jump expression")
        }
        throw StmtSignal(stmt)
    }

    private fun buildLoop(ctx: KotlinParser.LoopExpressionContext): Expr {
        if (ctx.forExpression() != null) unsupported("for loop")
        ctx.whileExpression()?.let { whileExpression ->
            val cond = buildExpression(whileExpression.expression())
            val body = buildControlBody(whileExpression.controlStructureBody())
            throw StmtSignal(WhileStmt(cond, body, false))
        }
        ctx.doWhileExpression()?.let { doWhile ->
            val cond = buildExpression(doWhile.expression())
            val body = buildControlBody(doWhile.controlStructureBody())
            throw StmtSignal(WhileStmt(cond, body, true))
        }
        unsupported("loop expression")
    }

    private fun buildFunctionLiteral(ctx: KotlinParser.FunctionLiteralContext): Expr {
        val params = ctx.lambdaParameters()?.lambdaParameter().orEmpty().map { parameter ->
            parameter.variableDeclaration()?.simpleIdentifier()?.text
                ?: unsupported("lambda parameter")
        }
        return FunctionLit(params, buildStatements(ctx.statements()), Types.unit)
    }

    private fun buildLiteral(ctx: KotlinParser.LiteralConstantContext): Expr {
        ctx.BooleanLiteral()?.let { return BoolLit(it.text == "true", Types.bool) }
        ctx.RealLiteral()?.let { return RealLit(it.text.replace("_", "").toDouble(), Types.float) }
        ctx.CharacterLiteral()?.let { return CharLit(characterValue(it.text), Types.ubyte) }
        ctx.stringLiteral()?.let { return StringLit(stringBytes(it.text), Types.string) }
        ctx.NullLiteral()?.let { return NullLit(WolinType(TypeKind.UNIT, nullable = true)) }
        ctx.IntegerLiteral()?.let { return IntLit(integerValue(it.text), Types.unit) }
        ctx.HexLiteral()?.let { return IntLit(integerValue(it.text), Types.unit) }
        ctx.BinLiteral()?.let { return IntLit(integerValue(it.text), Types.unit) }
        ctx.LongLiteral()?.let { return IntLit(integerValue(it.text.removeSuffix("L")), Types.unit) }
        unsupported("literal")
    }

    private fun recordReturnAt(expr: Expr) {
        val address = (expr as? IntLit)?.value ?: return
        returnAtSink?.add(ReturnAt(address.toInt()))
    }

    // ---- types ------------------------------------------------------------

    private fun buildType(ctx: KotlinParser.TypeContext): TypeInfo {
        ctx.functionType()?.let { return buildFunctionType(it) }
        ctx.parenthesizedType()?.let { return buildType(it.type()) }
        ctx.nullableType()?.let { nullable ->
            val inner = nullable.typeReference()?.let { buildTypeReference(it) }
                ?: nullable.parenthesizedType()?.let { buildType(it.type()) }
                ?: unsupported("nullable type")
            return TypeInfo(inner.type.copy(nullable = true), inner.location)
        }
        ctx.typeReference()?.let { return buildTypeReference(it) }
        unsupported("type")
    }

    private fun buildTypeReference(ctx: KotlinParser.TypeReferenceContext): TypeInfo {
        if (ctx.DYNAMIC() != null) unsupported("dynamic type")
        val userType = ctx.userType() ?: unsupported("type")
        val base = baseType(qualifiedName(userType))
        val array = ctx.arrayDeclaration()
        val type = if (array == null) {
            base
        } else {
            val indexType = array.userType()?.let { baseType(qualifiedName(it)) } ?: Types.ubyte
            Types.array(base, indexType)
        }
        val location = ctx.locationReference()?.let { buildLocation(it, type) }
        return TypeInfo(type, location)
    }

    private fun buildFunctionType(ctx: KotlinParser.FunctionTypeContext): TypeInfo {
        val parameters = ctx.functionTypeParameters()
        val slots = parameters.parameter().map { it.type().let { type -> type.start.tokenIndex to type } } +
            parameters.type().map { it.start.tokenIndex to it }
        val params = slots.sortedBy { it.first }.map { buildType(it.second).type }
        val ret = ctx.type()?.let { buildType(it).type } ?: Types.unit
        return TypeInfo(Types.function(params, ret), null)
    }

    private fun baseType(name: String): WolinType {
        if (name == "CPU") throw SemaException("CPU is a reserved namespace")
        if (name.startsWith("CPU.")) unsupported("type '$name'")
        return when (name) {
            "ubyte" -> Types.ubyte
            "byte" -> Types.byte
            "uword" -> Types.uword
            "word" -> Types.word
            "bool" -> Types.bool
            "unit" -> Types.unit
            "float" -> Types.float
            "string" -> Types.string
            else -> throw SemaException("Unknown type $name")
        }
    }

    private fun buildLocation(ctx: KotlinParser.LocationReferenceContext, type: WolinType): Location {
        ctx.literalConstant()?.let { literal ->
            val address = literalAddress(literal)
            return if (type.kind == TypeKind.ARRAY) FixedArray(address) else FixedAddress(address)
        }
        ctx.userType()?.let { userType ->
            val name = qualifiedName(userType)
            if (name.startsWith("CPU.")) return CpuRegister(name)
            unsupported("location '$name'")
        }
        unsupported("location")
    }

    /** The fixed address of a function or a fixed-address declaration. */
    private fun locationAddress(ctx: KotlinParser.LocationReferenceContext): Int? {
        val literal = ctx.literalConstant() ?: return null
        return literalAddress(literal)
    }

    private fun literalAddress(ctx: KotlinParser.LiteralConstantContext): Int {
        val literal = ctx.IntegerLiteral() ?: ctx.HexLiteral() ?: ctx.BinLiteral()
            ?: unsupported("location literal")
        return integerValue(literal.text).toInt()
    }

    // ---- names and literals -----------------------------------------------

    private fun qualifiedName(ctx: KotlinParser.UserTypeContext): String =
        ctx.simpleUserType().joinToString(".") { it.simpleIdentifier().text }

    private fun qualifiedName(ctx: KotlinParser.IdentifierContext): String =
        ctx.simpleIdentifier().joinToString(".") { it.text }

    private fun importPath(ctx: KotlinParser.ImportHeaderContext): String {
        val path = qualifiedName(ctx.identifier())
        return if (ctx.DOT() != null && ctx.MULT() != null) "$path.*" else path
    }

    private fun integerValue(text: String): Long {
        val cleaned = text.replace("_", "")
        return when {
            cleaned.startsWith("0x") || cleaned.startsWith("0X") -> cleaned.substring(2).toLong(16)
            cleaned.startsWith("0b") || cleaned.startsWith("0B") -> cleaned.substring(2).toLong(2)
            else -> cleaned.toLongOrNull()
                ?: cleaned.toDoubleOrNull()?.toLong()
                ?: throw SemaException("Value out of range")
        }
    }

    private fun characterValue(text: String): Int {
        val body = text.removePrefix("'").removeSuffix("'")
        if (body.startsWith("\\")) {
            return when (body.getOrNull(1)) {
                'n' -> '\n'.code
                't' -> '\t'.code
                'r' -> '\r'.code
                'b' -> '\b'.code
                '\\' -> '\\'.code
                '\'' -> '\''.code
                '"' -> '"'.code
                '$' -> '$'.code
                'u' -> body.drop(2).toInt(16)
                else -> throw SemaException("Unsupported character literal $text")
            }
        }
        return body.firstOrNull()?.code ?: throw SemaException("Unsupported character literal $text")
    }

    /**
     * The bytes of a string literal, with the backslash escapes decoded.
     *
     * The standard Kotlin escapes are decoded into single bytes: `\n`, `\t`,
     * `\r`, `\0`, `\\`, `\"`, `\'` and `\uXXXX`. The decoded bytes are what
     * the IR constant spells, so the emitter never sees a backslash.
     */
    private fun stringBytes(text: String): ByteArray {
        val body = when {
            text.startsWith("\"\"\"") -> text.removePrefix("\"\"\"").removeSuffix("\"\"\"")
            else -> text.removePrefix("\"").removeSuffix("\"")
        }
        val out = ArrayList<Byte>(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '$' && i + 1 < body.length && body[i + 1] == '{') {
                unsupported("string interpolation")
            }
            if (c != '\\' || i + 1 >= body.length) {
                out += c.code.toByte()
                i++
                continue
            }
            when (val e = body[i + 1]) {
                'n' -> out += 0x0A.toByte()
                't' -> out += 0x09.toByte()
                'r' -> out += 0x0D.toByte()
                '0' -> out += 0x00.toByte()
                '\\' -> out += '\\'.code.toByte()
                '"' -> out += '"'.code.toByte()
                '\'' -> out += '\''.code.toByte()
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    val code = hex.toIntOrNull(16)
                        ?: throw SemaException("Unsupported escape \\u$hex in string literal")
                    out += code.toByte()
                    i += 4
                }
                else -> throw SemaException("Unsupported escape \\$e in string literal")
            }
            i += 2
        }
        return out.toByteArray()
    }

    // ---- helpers ----------------------------------------------------------

    /** Folds a left-associative operator chain into nested binary operations. */
    private inline fun <T> chain(operands: List<T>, operators: List<String>, build: (T) -> Expr): Expr {
        var result = build(operands[0])
        for (i in operators.indices) {
            result = BinaryOp(operators[i], result, build(operands[i + 1]), Types.unit)
        }
        return result
    }

    private fun unsupported(what: String): Nothing =
        throw SemaException("Unsupported construct: $what")
}
