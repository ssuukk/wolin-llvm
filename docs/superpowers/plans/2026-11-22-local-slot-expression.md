# Local Slots in Expression Branches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the compiler crash `IllegalStateException: Local <name> has no slot` when a `LocalDecl` sits inside an `if`/`when` expression used in a declaration initialiser, statement condition, or `when` subject/condition.

**Architecture:** `StmtEmitter.startFunction` pre-scans every `LocalDecl` to place one `alloca` in the entry block. `collectLocalDecls`/`collectLocalDeclsInExpr` visit statement bodies and `IfExpr`/`WhenExpr` branch bodies, but not the expressions owned by statements (`LocalDecl.init`, `IfStmt.cond`, `WhileStmt.cond`, `WhenStmt.subject`, entry conditions) nor `IfExpr.cond`, `WhenExpr.subject` and `WhenExpr` entry conditions. Extend both walks to every position that can hold a nested expression, mirroring `collectLabelNamesIn` plus the two `when` condition lists. No emitter or slot-allocation change.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), Gradle 8.13, JUnit 5.11.4, LLVM-MOS SDK, VICE 3.10 (`x64sc`) for the integration test.

**Spec:** `TODO.md` "Known gaps moved to v2 (code audit 2026-11-15)" — the `Local b has no slot` item. Statement/expression shapes: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt:136-185`, emitter pre-scan: `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt:504-550`.

## Global Constraints

- Every local gets its `alloca` in the function's entry block (`StmtEmitter.startFunction`), in source order; this plan only widens the pre-scan, it does not move slot allocation.
- One `LocalDecl` node has exactly one parent, so the widened walk must still collect each node once. Tests assert the `alloca` count, not just compilation.
- Do not change `collectLabelNamesIn`/`collectLabelNamesInExpr`, `LocalDecl` emission, or the IR builder.
- Compilation must fail with `SemaException`, never `IllegalStateException`, for source the checker rejects; the tests below are all checker-accepted programs.
- Integration-test convention (spec §9.4): result block at `$0400`, program ends in `while (true) {}`.
- Conventional commits: `fix(ir): …`.

## Review Focus

1. **Reported initialiser case.** `var x = if (true) { var b: byte = -1; b } else { -2 }` must allocate both `x` and `b`; the missing scan is `LocalDecl.init`. → pinned by `a local in an if-expression initialiser gets a slot` and the VICE test.
2. **Statement conditions.** A local inside an `if` expression used as an `if` or `while` condition crashes today because `IfStmt.cond`/`WhileStmt.cond` are not scanned. → pinned by `a local in a statement condition gets a slot`.
3. **`when` positions.** `WhenStmt.subject` and entry conditions, and `WhenExpr.subject`/entry conditions, can all nest declarations; the label pre-scan misses entry conditions too, so copying it alone is not enough. → pinned by `a local in a when subject or entry condition gets a slot`.
4. **Nested expression conditions.** `IfExpr.cond` is itself an expression position: a local inside the condition's `if` branch is missed even when the outer branch bodies are scanned. → pinned by `a local in an if-expression condition gets a slot`.
5. **Already-working return branches and double allocation.** A local in a `return if (...) { … }` branch already compiles; the widened scan must not add its slot twice. → pinned by `a local in a return branch keeps one slot`.

---

### Task 1: Complete the local pre-scan

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt:504-550` (`collectLocalDecls`, `collectLocalDeclsInExpr`)
- Modify: `TODO.md` (tick the local-slot gap)
- Test: `src/test/kotlin/pl/qus/wolin/ir/LocalSlotTest.kt` (create)
- Test: `src/test/kotlin/pl/qus/wolin/it/ControlFlowTest.kt`

**Interfaces:**
- Consumes: `CompilerPipeline().compileToIr(source, "t.wtk")`; `LocalSlots` map populated in `startFunction` (`StmtEmitter.kt:116-119`); `Ast.IfExpr`, `Ast.WhenExpr`, `WhenEntry.conditions`.
- Produces: no new API; `collectLocalDecls` and `collectLocalDeclsInExpr` visit every expression position that can contain an `IfExpr`/`WhenExpr` branch.

- [ ] **Step 1: Write the failing IR tests**

Create `src/test/kotlin/pl/qus/wolin/ir/LocalSlotTest.kt`:

```kotlin
package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSlotTest {
    private fun ir(src: String): String =
        CompilerPipeline().compileToIr(src.trimIndent(), "t.wtk")

    private fun allocas(ir: String): Int = Regex("alloca i8").findAll(ir).count()

    @Test fun `a local in an if-expression initialiser gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                var x = if (true) { var b: byte = -1; b } else { -2 }
                return x
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("sub i8 0, 1"), ir)
    }

    @Test fun `a local in a statement condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                if (if (true) { var a: byte = 1; a == 1 } else { false }) { }
                while (if (false) { var b: byte = 1; b == 1 } else { false }) { }
                return 1
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("ret i8 1"), ir)
    }

    @Test fun `a local in a when subject or entry condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                when (if (true) { var a: byte = 1; a } else { -1 }) { 1 -> { } }
                when { if (true) { var b: byte = 1; b == 1 } else { false } -> { } }
                return 1
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("ret i8 1"), ir)
    }

    @Test fun `a local in an if-expression condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                var x = if (if (true) { var c: byte = 1; c == 1 } else { false }) { -1 } else { -2 }
                return x
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("sub i8 0, 1"), ir)
    }

    @Test fun `a local in a return branch keeps one slot`() {
        val ir = ir("""
            fun f(): byte {
                return if (true) { var b: byte = 1; b } else { 2 }
            }
        """)
        assertEquals(1, allocas(ir))
    }
}
```

- [ ] **Step 2: Run the IR tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.LocalSlotTest'`
Expected: the first four tests FAIL with `java.lang.IllegalStateException: Local <name> has no slot`; `a local in a return branch keeps one slot` PASSES (it pins behaviour that already works).

- [ ] **Step 3: Write the failing 6502 test**

Add to `ControlFlowTest`:

```kotlin
@Test fun `a local in an if-expression initialiser runs on the 6502`() {
    val bytes = runProgram("""
        fun main() {
            var x = if (true) { var b: byte = -1; b } else { -2 }
            var out: ubyte^0x400 = 0
            out = x
            while (true) {}
        }
    """)
    assertEquals(0xFF, bytes[0].toInt() and 0xFF)
}
```

- [ ] **Step 4: Run the 6502 test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.it.ControlFlowTest'`
Expected: FAIL during compilation with `IllegalStateException: Local b has no slot`; VICE never starts.

- [ ] **Step 5: Implement the widened pre-scan**

Replace `collectLocalDecls` in `StmtEmitter.kt` with:

```kotlin
private fun collectLocalDecls(stmts: List<Stmt>, out: MutableList<LocalDecl>) {
    for (s in stmts) {
        when (s) {
            is LocalDecl -> {
                out += s
                s.init?.let { collectLocalDeclsInExpr(it, out) }
            }
            is BlockStmt -> collectLocalDecls(s.body, out)
            is IfStmt -> {
                collectLocalDeclsInExpr(s.cond, out)
                collectLocalDecls(s.then, out)
                collectLocalDecls(s.otherwise, out)
            }
            is WhileStmt -> {
                collectLocalDeclsInExpr(s.cond, out)
                collectLocalDecls(s.body, out)
            }
            is WhenStmt -> {
                s.subject?.let { collectLocalDeclsInExpr(it, out) }
                s.entries.forEach { entry ->
                    entry.conditions.forEach { collectLocalDeclsInExpr(it, out) }
                    collectLocalDecls(entry.body, out)
                }
            }
            is ExprStmt -> collectLocalDeclsInExpr(s.expr, out)
            is ReturnStmt -> s.value?.let { collectLocalDeclsInExpr(it, out) }
            else -> Unit
        }
    }
}
```

Replace `collectLocalDeclsInExpr` with:

```kotlin
private fun collectLocalDeclsInExpr(e: Expr, out: MutableList<LocalDecl>) {
    when (e) {
        is IfExpr -> {
            collectLocalDeclsInExpr(e.cond, out)
            collectLocalDecls(e.then, out)
            collectLocalDecls(e.otherwise, out)
        }
        is WhenExpr -> {
            e.subject?.let { collectLocalDeclsInExpr(it, out) }
            e.entries.forEach { entry ->
                entry.conditions.forEach { collectLocalDeclsInExpr(it, out) }
                collectLocalDecls(entry.body, out)
            }
        }
        is UnaryOp -> collectLocalDeclsInExpr(e.operand, out)
        is BinaryOp -> {
            collectLocalDeclsInExpr(e.left, out)
            collectLocalDeclsInExpr(e.right, out)
        }
        is Call -> e.args.forEach { collectLocalDeclsInExpr(it, out) }
        is Assign -> {
            (e.target as? ArrayAccess)?.let { t ->
                collectLocalDeclsInExpr(t.array, out)
                collectLocalDeclsInExpr(t.index, out)
            }
            collectLocalDeclsInExpr(e.value, out)
        }
        is ArrayAccess -> {
            collectLocalDeclsInExpr(e.array, out)
            collectLocalDeclsInExpr(e.index, out)
        }
        is Convert -> collectLocalDeclsInExpr(e.value, out)
        else -> Unit
    }
}
```

- [ ] **Step 6: Run the targeted tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.LocalSlotTest' --tests 'pl.qus.wolin.it.ControlFlowTest'`
Expected: PASS, all five IR tests and the new 6502 test.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`; no compiler crash remains in the suite.

- [ ] **Step 8: Tick the TODO item**

`TODO.md`, the `Local b has no slot` item — change `[ ]` to `[x]` and append: `Fixed: the local pre-scan now visits declaration initialisers, statement conditions, when subjects/conditions, and nested expression conditions.`

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt \
        src/test/kotlin/pl/qus/wolin/ir/LocalSlotTest.kt \
        src/test/kotlin/pl/qus/wolin/it/ControlFlowTest.kt \
        TODO.md docs/superpowers/plans/2026-11-22-local-slot-expression.md
git commit -m "fix(ir): allocate slots for locals in expression branches"
```
