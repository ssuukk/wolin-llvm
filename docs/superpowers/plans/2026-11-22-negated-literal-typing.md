# Contextual Typing for Sign-Prefixed Literals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a sign-prefixed integer literal (`-1`, `+1`) take the other operand's type, so `word == -1` compiles and compares `word`s while `uword == -1` stays an error.

**Architecture:** The checker already contexts a bare `IntLit` from the other operand in `checkBinary` and from the subject in `checkWhenEntries`. It does not recognise `UnaryOp("-", IntLit)` as a literal, so `-1` falls back to the smallest-type rule (`byte`) and the comparison fails on mismatched kinds. Introduce one predicate, `isContextualIntLiteral`, use it at both contextual-typing sites, and let the `+` unary arm honour an expected type the same way `-` already does. No emitter, grammar, or const-folding change: once the literal's `type` is `word`, the existing unary lowering emits `sub i16 0, 1`.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), Gradle 8.13, ANTLR 4.13.1, JUnit 5.11.4, LLVM-MOS SDK, VICE 3.10 (`x64sc`) for the integration test.

**Spec:** `docs/superpowers/specs/2026-11-07-wolin-llvm-design.md` §6 "Numeric type rules" / contextual literal typing; the gap is recorded in `TODO.md` under "Known gaps moved to v2 (code audit 2026-11-15)".

## Global Constraints

- JDK 21; build and test with `./gradlew`.
- **No integer promotion.** Contextual typing applies only where a literal has an operand or destination to take its type from. Mixed variable types stay a compile error ("Attempt reassign X Y").
- Only a **single** sign prefix over an `IntLit` is contextual (`-1`, `+1`). Nested signs (`- -1`) and literal-vs-literal comparisons (`1 == -1`) keep their current behaviour and are out of scope.
- An unsigned destination keeps rejecting a negative literal: `uword == -1` must throw `SemaException("Value out of range")`, not wrap.
- Do not modify the ANTLR grammar, `ExprEmitter`, or `ConstFolder`; the literal's existing `type` field is the only carrier the emitter needs.
- Integration-test convention (spec §9.4): result block at `$0400`, program ends in `while (true) {}`; `runProgram` returns `$0400`–`$040F`.
- Conventional commits, as in `git log`: `fix(sema): …`.

## Review Focus

1. **Unsigned operand silently accepted.** `uword == -1` is not representable; a reader expects an error, not a zero-extended `0xFF`. → pinned by `a negated literal does not make an unsigned operand legal`.
2. **Silent width mismatch at runtime.** If `-1` keeps type `byte`, the comparison either fails to compile or, after a too-broad fix, emits an `i8` negation in an `i16` comparison. → pinned by the golden-IR test (`sub i16 0, 1`) and the 6502 test.
3. **The `when` path forgotten.** `checkWhenEntries` contextualises conditions separately from `checkBinary`; fixing only the binary path leaves `when (w) { -1 -> }` broken. → pinned by `a sign-prefixed literal in a when condition takes the subject's type`.
4. **Predicate overreach.** A helper that matches "any `UnaryOp`" would retype `-b` from a `byte` variable to the other operand's type and erase the no-promotion error. → pinned by `a negated literal does not retype a variable operand`.
5. **Left-hand symmetry.** `-1 == w` must work exactly like `w == -1`; the current dispatch has two symmetric arms and it is easy to patch only one. → pinned by `a sign-prefixed literal on the left takes the right operand's type`.

---

### Task 1: Contextual typing for sign-prefixed literals

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:704-726` (`checkUnary`, `+` arm)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:743-772` (`checkBinary`, literal dispatch)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:574-580` (`checkWhenEntries` condition typing)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (add `isContextualIntLiteral` above `checkUnary`)
- Modify: `REFERENCE.md:167` (literal typing note)
- Modify: `TODO.md` (tick the negated-literal gap)
- Test: `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/ComparisonTest.kt`

**Interfaces:**
- Consumes: `literalType(value: Long, expected: WolinType?): WolinType` (`TypeChecker.kt:1060`), `checkExpr(expr: Expr, expected: WolinType?): WolinType`, `Types.word`, `Types.byte`.
- Produces: `private fun isContextualIntLiteral(expr: Expr): Boolean` — true for an `IntLit` or a `+`/`-` `UnaryOp` whose operand is an `IntLit`; used by `checkBinary` and `checkWhenEntries`.

- [ ] **Step 1: Write the failing checker tests**

Add to `TypeCheckerTest`:

```kotlin
@Test fun `a negated literal takes the other operand's type`() {
    val program = typeChecker("fun f() { var w: word = 1; var r = w == -1 }")
    val cmp = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last().init as Ast.BinaryOp
    val neg = cmp.right as Ast.UnaryOp
    assertEquals(Types.word, neg.type)
    assertEquals(Types.word, neg.operand.type)
}

@Test fun `a sign-prefixed literal on the left takes the right operand's type`() {
    val program = typeChecker("fun f() { var w: word = 1; var r = -1 == w }")
    val cmp = program.functions[0].body.filterIsInstance<Ast.LocalDecl>().last().init as Ast.BinaryOp
    assertEquals(Types.word, (cmp.left as Ast.UnaryOp).type)
}

@Test fun `a plus-prefixed literal takes the other operand's type`() {
    typeChecker("fun f() { var w: word = 1; var r = w == +1 }")
}

@Test fun `a sign-prefixed literal in a when condition takes the subject's type`() {
    typeChecker("fun f(): bool { var w: word = 1; return when (w) { -1 -> true else -> false } }")
}

@Test fun `a negated literal does not make an unsigned operand legal`() {
    assertThrows<SemaException> {
        typeChecker("fun f() { var u: uword = 1; var r = u == -1 }")
    }
}

@Test fun `a negated literal does not retype a variable operand`() {
    assertThrows<SemaException> {
        typeChecker("fun f() { var w: word = 1; var b: byte = 1; var r = w == b }")
    }
}
```

- [ ] **Step 2: Run the checker tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.TypeCheckerTest'`
Expected: the first three new tests FAIL with `SemaException: Attempt reassign word byte`; the `when` test FAILS with `SemaException: when condition type byte does not match subject type word`; the unsigned and variable-operand tests PASS (they pin behaviour that must not change).

- [ ] **Step 3: Write the failing IR and 6502 tests**

Add to `GoldenIrTest`:

```kotlin
@Test fun `a negated word literal emits a word subtraction`() {
    val src = """
        fun f(): bool {
            var w: word = 1
            return w == -1
        }
    """.trimIndent()
    val ir = CompilerPipeline().compileToIr(src, "t.wtk")
    assertTrue(ir.contains("sub i16 0, 1"), ir)
}
```

Add to `ComparisonTest`:

```kotlin
@Test fun `a negated literal in a word comparison is a word`() {
    val bytes = runProgram("""
        fun main() {
            var w: word = 0
            w = w + -1
            var eq: bool = w == -1
            var neg: bool = w < 0
            var out: ubyte^0x400 = 0
            out = if (eq && neg) 1 else 0
            while (true) {}
        }
    """)
    assertEquals(1, bytes[0].toInt() and 0xFF)
}
```

- [ ] **Step 4: Run the IR and 6502 tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.GoldenIrTest' --tests 'pl.qus.wolin.it.ComparisonTest'`
Expected: both new tests FAIL during compilation with `Attempt reassign word byte` (the IR test never reaches its assertion; the VICE run never starts).

- [ ] **Step 5: Implement the predicate and the three call sites**

Add above `checkUnary` in `TypeChecker.kt`:

```kotlin
/**
 * Whether [expr] is an integer literal that takes its type from context: a
 * literal, or one with a single sign prefix. The `-`/`+` unary arms type such
 * an operand from the expected type, so `wordValue == -1` compares `word`s,
 * exactly like `wordValue == 1`.
 */
private fun isContextualIntLiteral(expr: Expr): Boolean =
    expr is IntLit ||
        (expr is UnaryOp && (expr.op == "-" || expr.op == "+") && expr.operand is IntLit)
```

Replace the `when` dispatch at the top of `checkBinary` with:

```kotlin
when {
    isContextualIntLiteral(left) && !isContextualIntLiteral(right) && right !is RealLit -> {
        rightType = checkExpr(right, null)
        leftType = checkExpr(left, rightType)
    }
    isContextualIntLiteral(right) && !isContextualIntLiteral(left) && left !is RealLit -> {
        leftType = checkExpr(left, null)
        rightType = checkExpr(right, leftType)
    }
    else -> {
        leftType = checkExpr(left, null)
        rightType = checkExpr(right, null)
    }
}
```

In `checkWhenEntries`, replace the condition typing with:

```kotlin
val condType = if (isContextualIntLiteral(condition) && subjectType != null) {
    checkExpr(condition, subjectType)
} else {
    checkExpr(condition, null)
}
```

In `checkUnary`, replace the `"+"` arm with a literal-aware version mirroring `"-"`:

```kotlin
"+" -> {
    val operand = expr.operand
    if (operand is IntLit) {
        val type = literalType(operand.value, expected)
        operand.type = type
        expr.type = type
    } else {
        val type = checkExpr(operand, null)
        requireNumeric(type, expr.op)
        expr.type = type
    }
}
```

- [ ] **Step 6: Run the three test suites to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.TypeCheckerTest' --tests 'pl.qus.wolin.ir.GoldenIrTest' --tests 'pl.qus.wolin.it.ComparisonTest'`
Expected: PASS, including the pre-existing `comparison of mixed operand types is rejected` and `mixed operand types are rejected` tests.

- [ ] **Step 7: Update the docs**

`REFERENCE.md`, negative-integer bullet — append one sentence:

> A sign-prefixed literal takes the other operand's type in a binary operation, so `w == -1` on a `word` compares `word`s; without a context `-1` is a `byte`.

`TODO.md`, the "Known gaps moved to v2" list — change the negated-literal item to `[x]` and add: `Fixed: a single-sign literal operand now takes the other operand's type (isContextualIntLiteral).`

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt \
        src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt \
        src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt \
        src/test/kotlin/pl/qus/wolin/it/ComparisonTest.kt \
        REFERENCE.md TODO.md
git commit -m "fix(sema): sign-prefixed literals take the operand's type"
```
