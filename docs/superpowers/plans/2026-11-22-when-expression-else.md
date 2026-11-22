# `when` Expression `else` Requirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject a `when` **expression** with no `else` branch at the checker, with the spec's message `this when expression may not match any branch`, instead of emitting a phi with no incoming edge for the no-match path.

**Architecture:** The parser already separates the forms: a bare `when` in statement position becomes `WhenStmt` and is allowed to fall through; a `when` in value position becomes `WhenExpr`. `checkWhenExpr` is the only gate for the expression form, so require at least one `isElse` entry there, after `checkWhenEntries` has validated the subject and conditions. No emitter or AST change: `whenFlow`'s existing missing-`else` path becomes unreachable for expressions, while the statement form keeps using it.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), Gradle 8.13, JUnit 5.11.4. This change has no VICE test: it is a compile-time rejection.

**Spec:** `docs/superpowers/specs/2026-11-07-wolin-llvm-design.md` §6 "`else` must be last, and v1 enforces it" — "A final branch without an `else` is allowed — the `when` expression then has no value if no branch matches, which is an error at the use site ('this when expression may not match any branch')." Gap recorded in `TODO.md` line 100.

## Global Constraints

- Statement-form `when` without `else` stays legal and keeps its fall-through behaviour (`checkWhen` path).
- Expression-form `when` keeps its existing branch-type unification once `else` is present.
- Error type is `SemaException`; message exactly `this when expression may not match any branch`.
- Do not change `ExprEmitter.emitWhenExpr`, `StmtEmitter.whenFlow`, or the AST.
- Conventional commits: `fix(sema): …`.

## Review Focus

1. **Every value context rejects the missing `else`.** Return, declaration initialiser, assignment value, and call argument route through `checkWhenExpr`; a reader expects the same error in each. → pinned by `a when expression without else is rejected in value positions`.
2. **Statement-form `when` stays legal.** `when (x) { 1 -> { } }` as a statement is a fall-through `WhenStmt`, not an error. → pinned by `a when statement without else is still allowed`.
3. **Empty expression errors.** `when (x) { }` in value position has no entries at all and must not silently become `unit`. → pinned by `an empty when expression is rejected`.
4. **`else` present keeps working.** A one-branch `else` and a normal `else` expression still compile and type as before. → pinned by `a when expression with else still works`.
5. **No late IR failure.** The rejection happens in sema; `CompilerPipeline().compileToIr` must throw `SemaException`, not emit a malformed phi. → pinned by `the checker rejects before IR emission`.

---

### Task 1: Require `else` on `when` expressions

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:538-548` (`checkWhenExpr`)
- Modify: `REFERENCE.md:107-110` (expression note) and diagnostics table near line 560
- Modify: `TODO.md:100-103` (tick the gap)
- Test: `src/test/kotlin/pl/qus/wolin/sema/WhenTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt`

**Interfaces:**
- Consumes: `WhenExpr.entries: List<WhenEntry>`, `WhenEntry.isElse`, `checkWhenEntries(subject, entries, checkBranch): List<WolinType>`.
- Produces: `checkWhenExpr` throws `SemaException("this when expression may not match any branch")` when `entries.none { it.isElse }`.

- [ ] **Step 1: Write the failing checker tests**

Add `import kotlin.test.assertEquals` to `WhenTest`, then add:

```kotlin
@Test fun `a when expression without else is rejected in value positions`() {
    val expected = "this when expression may not match any branch"
    val e = assertThrows<SemaException> {
        typeChecker("fun f(x: ubyte): ubyte { return when (x) { 1 -> 10 } }")
    }
    assertEquals(expected, e.message)
    assertThrows<SemaException> {
        typeChecker("fun f(x: ubyte) { var r = when (x) { 1 -> 10 } }")
    }
    assertThrows<SemaException> {
        typeChecker("fun f(x: ubyte) { var r: ubyte = 0; r = when (x) { 1 -> 10 } }")
    }
    assertThrows<SemaException> {
        typeChecker("fun g(v: ubyte): ubyte { return v }\nfun f(x: ubyte): ubyte { return g(when (x) { 1 -> 10 }) }")
    }
}

@Test fun `a when statement without else is still allowed`() {
    typeChecker("fun f(x: ubyte) { when (x) { 1 -> { } } }")
}

@Test fun `an empty when expression is rejected`() {
    assertThrows<SemaException> {
        typeChecker("fun f(x: ubyte) { var r = when (x) { } }")
    }
}

@Test fun `a when expression with else still works`() {
    typeChecker("fun f(x: ubyte): ubyte { return when (x) { 1 -> 10 else -> 20 } }")
    typeChecker("fun f(x: ubyte): ubyte { return when (x) { else -> 20 } }")
}
```

- [ ] **Step 2: Run the checker tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.WhenTest'`
Expected: `a when expression without else is rejected in value positions` and `an empty when expression is rejected` FAIL because nothing is thrown; `a when statement without else is still allowed` and `a when expression with else still works` PASS.

- [ ] **Step 3: Write the failing IR-level test**

Add to `GoldenIrTest`:

```kotlin
@Test fun `the checker rejects a when expression without else before IR emission`() {
    val src = """
        fun f(x: ubyte): ubyte {
            return when (x) { 1 -> 10 }
        }
    """.trimIndent()
    val e = assertFailsWith<SemaException> {
        CompilerPipeline().compileToIr(src, "t.wtk")
    }
    assertEquals("this when expression may not match any branch", e.message)
}
```

Add `import pl.qus.wolin.sema.SemaException` and `import kotlin.test.assertEquals` if absent.

- [ ] **Step 4: Run the IR test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.GoldenIrTest'`
Expected: the new test FAILS: `compileToIr` returns IR instead of throwing, exposing the undefined-phi path.

- [ ] **Step 5: Implement the requirement**

In `checkWhenExpr`, after the `checkWhenEntries` call and before assigning `expr.type`, add:

```kotlin
        if (expr.entries.none { it.isElse }) {
            throw SemaException("this when expression may not match any branch")
        }
```

- [ ] **Step 6: Run the affected suites to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.WhenTest' --tests 'pl.qus.wolin.ir.GoldenIrTest'`
Expected: PASS; existing `when` expression tests (`a when expression takes the taken branch's value`, `when as an expression takes the taken branch's value`) still pass.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Update the docs**

`REFERENCE.md`, the `if`/`when` expression bullet — append: "A `when` expression must have an `else` branch; without one it may not match and is rejected." Add a diagnostics-table row:

```markdown
| `this when expression may not match any branch` | a `when` expression with no `else` |
```

`TODO.md`, the `when` expression item — change `[ ]` to `[x]` and append: `Fixed: checkWhenExpr requires an else branch; statement-form when still falls through.`

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt \
        src/test/kotlin/pl/qus/wolin/sema/WhenTest.kt \
        src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt \
        REFERENCE.md TODO.md docs/superpowers/plans/2026-11-22-when-expression-else.md
git commit -m "fix(sema): require else on when expressions"
```
