# Array Initialisers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Wolin array declaration initialise itself from a `[v, v, …]` list literal or a `string` literal (for `ubyte`-element arrays), for globals (data section), globals at a fixed address (init function), and locals at a fixed address (function entry).

**Architecture:** The Kotlin grammar already has a `collectionLiteral` production; Wolin gives it exactly one meaning: array values. The plan extends that production to tolerate newlines (multi-line literals), builds a new `ArrayLiteral` AST node, types and validates it in `TypeChecker` (element typing, count vs index domain, string-to-byte expansion, constant folding via a new `ConstValue.ArrayValue`), and emits the value as static data in the module globals (data-section arrays) or as volatile element stores in the file's init function (fixed-address globals) and at function entry (fixed-address locals).

**Tech Stack:** Kotlin, ANTLR4 (`KotlinParser.g4`), LLVM-IR text emitter (`ModuleEmitter`, `StmtEmitter`, `ExprEmitter`, `GlobalInitEmitter`), JUnit 5, VICE (`ViceRunner`), Gradle (`./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-11-17-array-initialiser-design.md`

## Global Constraints

- No array *value* semantics: no assignment, no passing arrays as values, no array literal outside a declaration initialiser.
- Prefix fill only: element *i* of the list goes to index *i*; the rest stays zero. No dynamic sizing, no repeat syntax.
- Global array initialisers must be constant expressions (`ConstFolder`); `FunctionValue` / `NullValue` elements are rejected via the existing "global initialiser must be a constant expression" error.
- Local fixed-address arrays: elements are arbitrary runtime expressions, stored at function entry.
- `StringLit` initialiser: `ubyte`-element arrays only (any index type); the string's bytes plus the NUL terminator, prefix fill, remainder zero.
- `[]` is legal = zero-init. No trailing comma (rejected in every form).
- The grammar change is one line: `collectionLiteral` extended to `LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE`. On newline-free input it is token-identical to today's rule.
- Do NOT touch the user's in-progress kernal-return-values work: `REFERENCE.md`, `TODO.md` are modified by the user, and `stdlib/c64-kernal.wtk`, `stdlib/std.wtk`, `stdlib/vic.wtk`, `src/main/kotlin/pl/qus/wolin/ir/CpuAccessors.kt`, `src/test/kotlin/pl/qus/wolin/ir/CpuAccessorTest.kt`, `src/test/kotlin/pl/qus/wolin/it/CpuRegisterTest.kt`, `src/test/kotlin/pl/qus/wolin/sema/FixedAddressFunctionTest.kt` are user-modified/untracked. Read-only for this plan.
- Scalar `FixedAddress` globals with initialisers stay silently dropped (the "adjacent gap"); this plan un-skips only `FixedArray` globals carrying an `ArrayValue`.
- Commits go through `eval` `commit` namespace (`d = await commit.draft(); await d.commit("subject", ["- body"])`), never `git add`/`git commit`.

## Review Focus

1. **Trailing comma rejection** — `[1, 2, 3,]` must be a parse error in every form (single-line and multi-line). Pinned by Task 1 tests.
2. **Count/domain check** — a list longer than 256 (for `ubyte`/`byte` index) or 65536 (for `uword`/`word` index) must be rejected with "Too many array elements". A string of exactly 255 bytes plus NUL fits; 256 bytes plus NUL does not. Pinned by Task 3 tests.
3. **String initialiser element type** — `"hi"` is accepted for `ubyte[ubyte]` and `ubyte[uword]`, rejected for `uword[uword]` with "String initialiser needs a ubyte element type". Pinned by Task 3 tests.
4. **Global array initialiser constness** — an element that is a runtime expression (e.g. `a + 1` where `a` is a local) must be rejected at compile time with "global initialiser must be a constant expression"; an element referencing an earlier global must fold and produce static data. Pinned by Task 4 tests.
5. **Local fixed-address array initialiser elements are runtime** — `[a + 1, 7]` with `a` a local must emit two volatile stores at function entry, not a static-data section entry. Pinned by Task 5 tests.
6. **Data-section constant array spelling** — `var p: ubyte[ubyte] = [12, 13]` must emit `@"…p" = global [256 x i8] [i8 12, i8 13, i8 0, …]` (zero-padded to the full domain); `var w: uword[uword] = [0, 1024]` must emit a `[65536 x i16] […]` constant array. Pinned by Task 6 golden tests.
7. **`[]` is zero-init** — an empty list must be indistinguishable from no initialiser: `zeroinit` for data-section arrays, no stores for fixed-address arrays. Pinned by Task 3 and Task 6 tests.
8. **`var x = [1, 2]`** — no declared type → "Array literal needs an array type"; assignment `arr = [1, 2]` → "Array literal can only initialise a declaration"; local non-fixed array initialiser → "local arrays need a fixed address in v1". Pinned by Task 3 tests.

---

### Task 1: Grammar — extend `collectionLiteral` to tolerate newlines

**Files:**
- Modify: `src/main/antlr/KotlinParser.g4:487-489`

**Interfaces:**
- Consumes: nothing (isolated grammar change).
- Produces: `collectionLiteral : LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE ;` — multi-line literals parse; trailing commas still rejected; single-line behavior unchanged.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/parser/ArrayLiteralGrammarTest.kt`:

```kotlin
package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grammar rule `collectionLiteral` is the Wolin array literal. It must
 * parse single-line and multi-line forms, and reject trailing commas in
 * every form (single-line and multi-line).
 */
class ArrayLiteralGrammarTest {

    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `single-line array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = [1, 2, 3]"))
    }

    @Test fun `multi-line array literal parses`() {
        val src = """
            var x: ubyte[ubyte] = [
                1,
                2,
                3
            ]
        """.trimIndent()
        assertTrue(parse(src))
    }

    @Test fun `empty array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = []"))
    }

    @Test fun `multi-line empty array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = [\n]"))
    }

    @Test fun `trailing comma single-line is a parse error`() {
        assertFalse(parse("var x: ubyte[ubyte] = [1, 2, 3,]"))
    }

    @Test fun `trailing comma multi-line is a parse error`() {
        val src = """
            var x: ubyte[ubyte] = [
                1,
                2,
            ]
        """.trimIndent()
        assertFalse(parse(src))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.ArrayLiteralGrammarTest'`
Expected: `multi-line array literal parses` and `multi-line empty array literal parses` FAIL (parse error on newline); the rest pass (today's behavior already rejects trailing commas and parses single-line).

- [ ] **Step 3: Edit `KotlinParser.g4`**

Replace lines 487-489:

```
collectionLiteral
    : LSQUARE expression? (COMMA expression)* RSQUARE
    ;
```

with:

```
collectionLiteral
    : LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE
    ;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.ArrayLiteralGrammarTest'`
Expected: all six tests PASS.

- [ ] **Step 5: Run the full build to verify no regression**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL (ANTLR regenerates the parser; no compile errors).

- [ ] **Step 6: Commit**

```javascript
const d = await commit.draft();
await d.commit("feat(grammar): collectionLiteral tolerates newlines", ["- NL* groups added around expression separators and brackets", "- single-line and multi-line literals parse; trailing commas still rejected"]);
```

---

### Task 2: AST node `ArrayLiteral` and builder

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt:198` (add `ArrayLiteral` node after `ArrayAccess`, add `typealias ArrayLiteral` near line 244)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:348` (replace `unsupported("collection literal")` with `buildCollection(ctx.collectionLiteral()!!)`; add `buildCollection` helper)

**Interfaces:**
- Consumes: `KotlinParser.CollectionLiteralContext` (ANTLR-generated).
- Produces: `Ast.ArrayLiteral(elements: List<Expr>, type: WolinType)` — `type` is `Types.unit` at build time (filled by the checker); `typealias ArrayLiteral = Ast.ArrayLiteral`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt` (or a new `ArrayLiteralTest.kt`):

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.ArrayLiteral
import pl.qus.wolin.ast.AstBuilder
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArrayLiteralBuildTest {

    private fun build(src: String): ArrayLiteral {
        val tree = ParserFacade(Diagnostics()).parse(src, "t.ktk")!!
        val program = AstBuilder().build(tree)
        val init = program.globals[0].init
        return assertIs<ArrayLiteral>(init, "expected an ArrayLiteral, got ${init?.javaClass?.simpleName}")
    }

    @Test fun `three elements in order`() {
        val lit = build("var a: ubyte[ubyte] = [1, 2, 3]")
        assertEquals(3, lit.elements.size)
        assertEquals(listOf(1, 2, 3), lit.elements.map { (it as pl.qus.wolin.ast.IntLit).value })
    }

    @Test fun `empty list`() {
        val lit = build("var a: ubyte[ubyte] = []")
        assertEquals(0, lit.elements.size)
    }

    @Test fun `multi-line list`() {
        val src = "var a: ubyte[ubyte] = [\n1,\n2,\n3\n]"
        val lit = build(src)
        assertEquals(3, lit.elements.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ArrayLiteralBuildTest'`
Expected: compilation error — `Unsupported construct: collection literal` thrown from `AstBuilder.buildAtomic` (line 348).

- [ ] **Step 3: Add the `ArrayLiteral` node to `Ast.kt`**

Insert after the `ArrayAccess` line (198):

```kotlin
    /** `[v1, v2, …]` — an array initialiser. The type is filled by the checker. */
    data class ArrayLiteral(val elements: List<Expr>, override var type: WolinType) : Expr
```

Insert the typealias after `typealias ArrayAccess = Ast.ArrayAccess` (line 244):

```kotlin
typealias ArrayLiteral = Ast.ArrayLiteral
```

- [ ] **Step 4: Replace the `unsupported` call and add `buildCollection` in `AstBuilder.kt`**

Replace line 348:

```kotlin
        ctx.collectionLiteral()?.let { unsupported("collection literal") }
```

with:

```kotlin
        ctx.collectionLiteral()?.let { return buildCollection(it) }
```

Add the `buildCollection` helper near `buildAtomic` (after `buildArrayAccess`, ~line 332):

```kotlin
    private fun buildCollection(ctx: KotlinParser.CollectionLiteralContext): Expr {
        val elements = ctx.expression().map { buildExpression(it) }
        return ArrayLiteral(elements, Types.unit)
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ArrayLiteralBuildTest'`
Expected: all three tests PASS.

- [ ] **Step 6: Commit**

```javascript
const d = await commit.draft();
await d.commit("feat(ast): ArrayLiteral node and builder", ["- new Ast.ArrayLiteral(elements, type)", "- AstBuilder builds it from the collectionLiteral grammar rule"]);
```

---

### Task 3: TypeChecker — array-initialiser check, domain helper, position rejection

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (add `arrayDomain` helper, `checkArrayInit` dedicated check in `checkGlobal` and the `LocalDecl` branch of `checkStmt`, `ArrayLiteral` case in `checkExpr` throwing the position error, `ArrayLiteral` value rejection in `checkAssign`, `ArrayLiteral` case in `varRefs`)

**Interfaces:**
- Consumes: `Ast.ArrayLiteral`, `Types.array(element, index)`, `ConstFolder.fold`.
- Produces: `ArrayLiteral.type` filled with the declared array type; elements typed against the element type; a `StringLit` initialiser converted to `ArrayLiteral` of `CharLit`/`IntLit` byte elements (the checker rewrites the `LocalDecl.init` / `GlobalDecl.init` field); the diagnostics listed in the design doc §Diagnostics.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/sema/ArrayInitTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.ast.AstBuilder
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrayInitTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { typeChecker(src) }
        assertTrue(e.message!!.contains(fragment), "expected message to contain '$fragment', got: ${e.message}")
    }

    // ---- accepted positions ------------------------------------------------

    @Test fun `global array from a list is accepted`() {
        typeChecker("var a: ubyte[ubyte] = [1, 2, 3]\nfun main() {}")
    }

    @Test fun `global fixed-address array from a list is accepted`() {
        typeChecker("var a: ubyte[ubyte]^0x3000 = [1, 2]\nfun main() {}")
    }

    @Test fun `local fixed-address array from a list is accepted`() {
        typeChecker("fun main() { var a: ubyte[ubyte]^0x3000 = [1, 2]; while (true) {} }")
    }

    @Test fun `uword elements are accepted`() {
        typeChecker("var w: uword[uword] = [0, 1024, 0x3000]\nfun main() {}")
    }

    @Test fun `empty list is accepted`() {
        typeChecker("var a: ubyte[ubyte] = []\nfun main() {}")
    }

    @Test fun `string initialiser on ubyte element array is accepted`() {
        typeChecker("var s: ubyte[ubyte] = \"hi\"\nfun main() {}")
    }

    @Test fun `string initialiser on ubyte uword-indexed array is accepted`() {
        typeChecker("var s: ubyte[uword] = \"hi\"\nfun main() {}")
    }

    // ---- rejected positions ------------------------------------------------

    @Test fun `array literal without a declared type is rejected`() {
        assertRejected("var x = [1, 2]\nfun main() {}", "Array literal needs an array type")
    }

    @Test fun `array literal in assignment position is rejected`() {
        assertRejected("fun main() { var a: ubyte[ubyte]^0x3000; a = [1, 2]; while (true) {} }", "Array literal can only initialise a declaration")
    }

    @Test fun `array literal in expression position is rejected`() {
        assertRejected("fun main() { var x: ubyte = [1, 2][0]; while (true) {} }", "Array literal can only initialise a declaration")
    }

    @Test fun `local non-fixed array initialiser is rejected`() {
        assertRejected("fun main() { var a: ubyte[ubyte] = [1, 2]; while (true) {} }", "local arrays need a fixed address in v1")
    }

    @Test fun `string initialiser on uword element array is rejected`() {
        assertRejected("var w: uword[uword] = \"hi\"\nfun main() {}", "String initialiser needs a ubyte element type")
    }

    // ---- count / domain ----------------------------------------------------

    @Test fun `too many elements for a ubyte-indexed array is rejected`() {
        val elems = (1..257).joinToString(", ")
        assertRejected("var a: ubyte[ubyte] = [$elems]\nfun main() {}", "Too many array elements")
    }

    @Test fun `a 256-element list on a ubyte index is accepted`() {
        val elems = (1..256).joinToString(", ")
        typeChecker("var a: ubyte[ubyte] = [$elems]\nfun main() {}")
    }

    @Test fun `a string of exactly 255 bytes plus NUL is accepted`() {
        val s = "x".repeat(255)
        typeChecker("var a: ubyte[ubyte] = \"$s\"\nfun main() {}")
    }

    @Test fun `a string of 256 bytes plus NUL is rejected`() {
        val s = "x".repeat(256)
        assertRejected("var a: ubyte[ubyte] = \"$s\"\nfun main() {}", "String initialiser too long")
    }

    // ---- element typing ----------------------------------------------------

    @Test fun `element that does not fit is rejected`() {
        assertRejected("var a: ubyte[ubyte] = [300]\nfun main() {}", "Value out of range")
    }

    @Test fun `element with the wrong type is rejected`() {
        assertRejected("fun f() {}\nvar a: ubyte[ubyte] = [f]\nfun main() {}", "global initialiser must be a constant expression")
    }

    @Test fun `global element referencing a later global is rejected`() {
        val src = "var a: ubyte[ubyte] = [b]\nvar b: ubyte = 7\nfun main() {}"
        assertRejected(src, "initialiser references a global that is not yet initialised")
    }

    // ---- varRefs walker ----------------------------------------------------

    @Test fun `an array initialiser that references a later global is rejected`() {
        val src = "var a: ubyte[ubyte] = [b]\nvar b: ubyte = 7\nfun main() {}"
        assertRejected(src, "not yet initialised")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ArrayInitTest'`
Expected: most tests FAIL with `Unsupported construct: collection literal` (the builder still rejects the literal before the checker sees it) — Task 2 fixed the builder, so now the tests reach the checker, which has no `ArrayLiteral` handling and either ignores it or throws a type error.

- [ ] **Step 3: Add the `arrayDomain` helper and `checkArrayInit` in `TypeChecker.kt`**

Add near `literalType` (~line 871):

```kotlin
    /** The index type's domain: 256 for `ubyte`/`byte`, 65536 for `uword`/`word`. */
    private fun arrayDomain(indexType: WolinType): Int =
        if (indexType.kind == TypeKind.UWORD || indexType.kind == TypeKind.WORD) 65536 else 256
```

Add a dedicated `checkArrayInit` method (near `checkExpr`):

```kotlin
    /**
     * Types and validates an array initialiser against its declared array
     * type. A string initialiser is converted into byte elements here, so
     * every later stage sees an [ArrayLiteral] of integer elements.
     *
     * Returns the (possibly rewritten) initialiser expression.
     */
    private fun checkArrayInit(init: Expr, type: WolinType): Expr {
        val ety = type.elementType ?: throw SemaException("Array literal needs an array type")
        val ity = type.indexType ?: Types.ubyte
        val elements: List<Expr> = when (init) {
            is Ast.ArrayLiteral -> {
                if (init.elements.size > arrayDomain(ity)) {
                    throw SemaException("Too many array elements: ${init.elements.size} for a ${ity} index")
                }
                init.elements.forEachIndexed { i, e ->
                    val t = checkExpr(e, ety)
                    if (t.kind != ety.kind) throw SemaException("Attempt reassign $ety $t")
                }
                init.elements
            }
            is StringLit -> {
                if (ety.kind != TypeKind.UBYTE) {
                    throw SemaException("String initialiser needs a ubyte element type")
                }
                val bytes = init.bytes
                if (bytes.size + 1 > arrayDomain(ity)) {
                    throw SemaException("String initialiser too long: ${bytes.size + 1} for a ${ity} index")
                }
                bytes.map { IntLit(it.toLong(), ety) } + IntLit(0, ety)
            }
            else -> throw SemaException("Array literal needs an array type")
        }
        return Ast.ArrayLiteral(elements, type)
    }
```

- [ ] **Step 4: Wire `checkArrayInit` into `checkGlobal`**

In `checkGlobal` (around line 146-160), replace the `g.init?.let { checkExpr(it, g.type) }` line with:

```kotlin
            g.init = g.init?.let { init ->
                if (init is Ast.ArrayLiteral || init is StringLit) checkArrayInit(init, g.type)
                else checkExpr(init, g.type)
            }
```

- [ ] **Step 5: Wire `checkArrayInit` into the `LocalDecl` branch of `checkStmt`**

In `checkStmt` (around line 329-335), replace the `d.init?.let { checkExpr(it, d.type) }` line with:

```kotlin
                    d.init = d.init?.let { init ->
                        if (init is Ast.ArrayLiteral || init is StringLit) {
                            if (d.location !is FixedArray) {
                                throw SemaException("local arrays need a fixed address in v1")
                            }
                            checkArrayInit(init, d.type)
                        } else checkExpr(init, d.type)
                    }
```

- [ ] **Step 6: Add the `ArrayLiteral` case to `checkExpr`**

In `checkExpr` (the `when` starting ~line 480), add:

```kotlin
            is ArrayLiteral -> throw SemaException("Array literal can only initialise a declaration")
```

- [ ] **Step 7: Reject an `ArrayLiteral` value in `checkAssign`**

In `checkAssign` (~line 634), add an early check after the `val target` / `val value` split:

```kotlin
        if (value is Ast.ArrayLiteral) throw SemaException("Array literal can only initialise a declaration")
```

- [ ] **Step 8: Teach `varRefs` about `ArrayLiteral`**

In `varRefs` (~line 181), add:

```kotlin
            is ArrayLiteral -> varRefs(e.elements)
```

(and add a `varRefs(elements: List<Expr>)` overload or inline the loop).

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ArrayInitTest'`
Expected: all tests PASS.

- [ ] **Step 10: Run the full test suite to verify no regression**

Run: `./gradlew test`
Expected: all tests PASS (the existing `UnsupportedTest` "collection literal" entry, if any, must be removed — verify by grepping `collection literal` in `UnsupportedTest.kt` and deleting the line if present).

- [ ] **Step 11: Commit**

```javascript
const d = await commit.draft();
await d.commit("feat(sema): array initialiser typing and validation", ["- dedicated checkArrayInit in checkGlobal and LocalDecl", "- ArrayLiteral position rejection in checkExpr and checkAssign", "- varRefs walker learns ArrayLiteral", "- domain helper (256 / 65536)"]);
```

---

### Task 4: `ConstValue.ArrayValue` and `ConstFolder` — constant folding

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ConstValue.kt` (add `ArrayValue` data class)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt:33-48` (add `is ArrayLiteral ->` case)

**Interfaces:**
- Consumes: `Ast.ArrayLiteral`, `ConstValue.IntValue`, `ConstValue.FloatValue`, `ConstValue.BoolValue`.
- Produces: `ConstValue.ArrayValue(elements: List<ConstValue>, elementType: WolinType, indexType: WolinType)` — `spelling` returns the full-domain LLVM constant-array body (`[i8 12, i8 0, …]`), padded with per-type zero spellings.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/kotlin/pl/qus/wolin/sema/ConstFolderTest.kt` (create if missing):

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.AstBuilder
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConstFolderArrayTest {

    private fun foldInit(src: String): ConstValue? {
        val tree = ParserFacade(Diagnostics()).parse(src, "t.ktk")!!
        val program = AstBuilder().build(tree)
        val g = program.globals[0]
        return ConstFolder.fold(g.init!!, mapOf(g.name to ConstValue.IntValue(0, Types.ubyte)), emptyMap())
    }

    @Test fun `a list of integer literals folds to an ArrayValue`() {
        val v = foldInit("var a: ubyte[ubyte] = [12, 13]")
        assertNotNull(v)
        assertIs<ConstValue.ArrayValue>(v)
        assertEquals(2, v.elements.size)
        assertEquals(12, (v.elements[0] as ConstValue.IntValue).spelling.toInt())
        assertEquals(13, (v.elements[1] as ConstValue.IntValue).spelling.toInt())
    }

    @Test fun `a string folds to its bytes plus NUL`() {
        val v = foldInit("var a: ubyte[ubyte] = \"hi\"")
        assertNotNull(v)
        assertIs<ConstValue.ArrayValue>(v)
        // The checker rewrites the StringLit into an ArrayLiteral of IntLits
        // before folding, so the folder sees [104, 105, 0].
        assertEquals(3, v.elements.size)
    }

    @Test fun `an empty list folds to an ArrayValue with no elements`() {
        val v = foldInit("var a: ubyte[ubyte] = []")
        assertNotNull(v)
        assertIs<ConstValue.ArrayValue>(v)
        assertEquals(0, v.elements.size)
    }

    @Test fun `a non-constant element makes the fold return null`() {
        // `b` is a local, not a global: the fold cannot see it.
        val v = foldInit("var a: ubyte[ubyte] = [b]\nfun main() { var b: ubyte = 1 }")
        assertNull(v)
    }

    @Test fun `the spelling is the full-domain constant array`() {
        val v = foldInit("var a: ubyte[ubyte] = [12, 13]")
        assertIs<ConstValue.ArrayValue>(v)
        val spelling = v.spelling
        assertTrue(spelling.startsWith("[i8 12, i8 13, i8 0"))
        // 256 elements total
        assertEquals(256, Regex("i8 ").findAll(spelling).count())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ConstFolderArrayTest'`
Expected: compilation error — `ConstValue.ArrayValue` unresolved.

- [ ] **Step 3: Add `ConstValue.ArrayValue`**

In `ConstValue.kt`, after the `FunctionValue` class (before the closing `}`):

```kotlin
    /**
     * A constant array: the elements a global array initialiser folds to,
     * padded with zeros to the full index-type domain. The [spelling] is the
     * LLVM constant-array body (`[i8 12, i8 0, …]`), ready to be placed after
     * the array type in a `@"… = global [N x T] …"` line.
     *
     * Each element is prefixed with its LLVM type name (`i8`, `i16`, `i1`,
     * `float`); the type name is spelled from the element [WolinType]'s kind
     * so the sema package does not depend on the `ir` package.
     */
    data class ArrayValue(
        val elements: List<ConstValue>,
        val elementType: WolinType,
        val indexType: WolinType
    ) : ConstValue {
        override val spelling: String
            get() {
                val ty = llvmName(elementType)
                val domain = if (indexType.kind == TypeKind.UWORD || indexType.kind == TypeKind.WORD) 65536 else 256
                val padded = elements + List(domain - elements.size) { zeroFor(elementType) }
                return "[" + padded.joinToString(", ") { "$ty ${it.spelling}" } + "]"
            }
    }
```

Add the `llvmName` and `zeroFor` helpers in the `ConstValue` companion or as private top-level functions:

```kotlin
    /** The LLVM spelling of a Wolin type's IR type, spelled without importing `ir`. */
    private fun llvmName(t: WolinType): String = when (t.kind) {
        TypeKind.UBYTE, TypeKind.BYTE -> "i8"
        TypeKind.UWORD, TypeKind.WORD -> "i16"
        TypeKind.BOOL -> "i1"
        TypeKind.FLOAT -> "float"
        else -> throw IllegalArgumentException("Array element type $t has no LLVM spelling")
    }

    /** The zero value of [t] in its LLVM spelling. */
    private fun zeroFor(t: WolinType): ConstValue = when (t.kind) {
        TypeKind.FLOAT -> ConstValue.FloatValue(0.0)
        TypeKind.BOOL -> ConstValue.BoolValue(false)
        else -> ConstValue.IntValue(0, t)
    }
```

- [ ] **Step 4: Add the `ArrayLiteral` case to `ConstFolder.fold`**

In `ConstFolder.kt`, in the `when (expr)` block (line 33-48), add before `else -> null`:

```kotlin
        is ArrayLiteral -> foldArray(expr, constSoFar, functionNames)
```

Add the `foldArray` helper:

```kotlin
    /**
     * A list of constant expressions: each element folds, and any
     * [ConstValue.FunctionValue] or [ConstValue.NullValue] element makes the
     * whole list non-constant (the same rule a scalar initialiser follows).
     */
    private fun foldArray(
        e: ArrayLiteral,
        constSoFar: Map<String, ConstValue>,
        functionNames: Map<String, String>
    ): ConstValue? {
        if (e.type.kind != TypeKind.ARRAY) return null
        val ety = e.type.elementType ?: return null
        val ity = e.type.indexType ?: return null
        val elements = e.elements.map { fold(it, constSoFar, functionNames) ?: return null }
        if (elements.any { it is ConstValue.FunctionValue || it is ConstValue.NullValue }) return null
        return ConstValue.ArrayValue(elements, ety, ity)
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ConstFolderArrayTest'`
Expected: all five tests PASS.

- [ ] **Step 6: Commit**

```javascript
const d = await commit.draft();
await d.commit("feat(sema): ConstValue.ArrayValue and array folding", ["- ArrayValue spells the full-domain LLVM constant-array body", "- ConstFolder folds ArrayLiteral to ArrayValue when every element is constant"]);
```

---

### Task 5: Emitters — data-section constant arrays, fixed-address init stores, local fixed-address stores

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt:103-108` (spell the constant array from `ArrayValue`; remove the throw at line 104; keep `zeroinit` for empty)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/GlobalInitEmitter.kt:34-39` (un-skip `FixedArray` globals carrying an `ArrayValue`; emit volatile element stores)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt:187-190` (emit per-element volatile stores for `FixedArray` locals)

**Interfaces:**
- Consumes: `Ast.ArrayLiteral`, `ConstValue.ArrayValue`, `ExprEmitter.emitStoreFixed(address, value, irType)`, `TypeMapper.ir`.
- Produces: `@"…p" = global [256 x i8] [i8 12, i8 0, …]` for data-section arrays; volatile `store` instructions for fixed-address globals (init function) and locals (function entry).

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/ir/ArrayInitIrTest.kt`:

```kotlin
package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertTrue

class ArrayInitIrTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    @Test fun `a data-section array from a list spells a constant array`() {
        val out = ir("var a: ubyte[ubyte] = [12, 13]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [256 x i8] [i8 12, i8 13, i8 0"), out)
    }

    @Test fun `a uword-element array spells an i16 constant array`() {
        val out = ir("var w: uword[uword] = [0, 1024]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.w\" = global [65536 x i16] [i16 0, i16 1024, i16 0"), out)
    }

    @Test fun `an empty list spells zeroinit`() {
        val out = ir("var a: ubyte[ubyte] = []\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [256 x i8] zeroinit"), out)
    }

    @Test fun `a global fixed-address array emits volatile stores in the init function`() {
        val out = ir("var a: ubyte[ubyte]^0x3000 = [1, 2]\nfun main() { while (true) {} }")
        assertTrue(out.contains("store volatile i8"), out)
        // The init function stores each element to address + i × elementSize.
        assertTrue(out.contains("inttoptr i64 12288 to i8*"), out)   // 0x3000
        assertTrue(out.contains("inttoptr i64 12289 to i8*"), out)   // 0x3001
    }

    @Test fun `a local fixed-address array emits volatile stores at function entry`() {
        val src = """
            fun main() {
                var a: ubyte[ubyte]^0x3000 = [1, 2]
                while (true) {}
            }
        """.trimIndent()
        val out = ir(src)
        assertTrue(out.contains("store volatile i8"), out)
        assertTrue(out.contains("inttoptr i64 12288 to i8*"), out)
        assertTrue(out.contains("inttoptr i64 12289 to i8*"), out)
    }

    @Test fun `a string initialiser emits stores for the bytes plus NUL`() {
        val src = "var s: ubyte[ubyte]^0x3000 = \"hi\"\nfun main() { while (true) {} }"
        val out = ir(src)
        assertTrue(out.contains("store volatile i8"), out)
        // 'h' = 104, 'i' = 105, NUL = 0
        assertTrue(out.contains("store volatile i8 104"), out)
        assertTrue(out.contains("store volatile i8 105"), out)
        assertTrue(out.contains("store volatile i8 0"), out)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.ArrayInitIrTest'`
Expected: all tests FAIL — `ModuleEmitter` still throws "Global array a must be zero-initialised in v1"; `GlobalInitEmitter` skips `FixedArray` globals; `StmtEmitter` throws "fixed array locals take no initialiser".

- [ ] **Step 3: Fix `ModuleEmitter.emitGlobals`**

Replace lines 103-108:

```kotlin
            if (g.type.kind == TypeKind.ARRAY) {
                if (g.init != null) throw error("Global array ${g.name} must be zero-initialised in v1")
                val ety = g.type.elementType ?: throw error("Array ${g.name} has no element type")
                constants +=
                    "@\"$prefix.${g.name}\" = global [${arrayCount(g.type)} x ${TypeMapper.ir(ety).llvmName()}] zeroinit"
                continue
            }
```

with:

```kotlin
            if (g.type.kind == TypeKind.ARRAY) {
                val ety = g.type.elementType ?: throw error("Array ${g.name} has no element type")
                val count = arrayCount(g.type)
                val ty = TypeMapper.ir(ety).llvmName()
                val body = when (val v = g.constValue) {
                    is ConstValue.ArrayValue -> v.spelling
                    else -> "zeroinit"
                }
                constants += "@\"$prefix.${g.name}\" = global [$count x $ty] $body"
                continue
            }
```

- [ ] **Step 4: Fix `GlobalInitEmitter.emitFile`**

In the loop at lines 34-39, replace the `FixedAddress || FixedArray` skip with a conditional:

```kotlin
        for (g in program.globals) {
            if (g.init == null || g.constValue == null) continue
            // Scalar fixed-address globals are left alone (the adjacent gap:
            // their initialisers are silently dropped today). Fixed-address
            // arrays with a constant array initialiser store their elements
            // here; other fixed-address globals keep today's skip.
            if (g.location is FixedAddress) continue
            if (g.location is FixedArray) {
                val v = g.constValue
                if (v is ConstValue.ArrayValue && v.elements.isNotEmpty()) {
                    storeArray(g, v, mangledName)
                }
                continue
            }
            if (!referencesAnotherEntity(g.init)) continue
            store(g, mangledName)
        }
```

Add the `storeArray` helper:

```kotlin
    /**
     * A fixed-address global array: store each element to
     * `address + i × elementSize`. The stores are volatile, like every
     * other fixed-address store.
     */
    private fun storeArray(g: GlobalDecl, v: ConstValue.ArrayValue, mangledName: String) {
        val address = (g.location as FixedArray).address
        val ty = TypeMapper.ir(v.elementType)
        v.elements.forEachIndexed { i, elem ->
            val target = b.instr(
                IrType.Ptr,
                "inttoptr i64 ${address + i * if (ty == IrType.I16) 2 else 1} to ${ty.llvmName()}*"
            )
            b.instr(IrType.Void, "store volatile ${ty.llvmName()} ${elem.spelling}, ptr ${target.name}")
        }
    }
```

- [ ] **Step 5: Fix `StmtEmitter.emitLocalDecl`**

Replace lines 187-190:

```kotlin
            is FixedArray -> {
                if (d.init != null) throw error("fixed array locals take no initialiser")
                vars.declare(d.name, VarStorage.Fixed(loc.address))
            }
```

with:

```kotlin
            is FixedArray -> {
                d.init?.let { init -> emitArrayInit(init, loc.address, d.type) }
                vars.declare(d.name, VarStorage.Fixed(loc.address))
            }
```

Add the `emitArrayInit` helper in `StmtEmitter`:

```kotlin
    /**
     * The per-element stores of an array initialiser on a fixed-address
     * local. The checker has already typed every element and rewritten a
     * string initialiser into byte elements, so each element is an
     * [IntLit] / [BoolLit] / [RealLit] / [UnaryOp] / [BinaryOp] / [Convert]
     * / [VarRef] / [Call] — any expression the ExprEmitter can evaluate.
     */
    private fun emitArrayInit(init: Expr, address: Int, type: WolinType) {
        val ety = type.elementType ?: throw error("Array ${type} has no element type")
        val ity = TypeMapper.ir(ety)
        val elemSize = if (ity == IrType.I16) 2 else 1
        val elements: List<Expr> = when (init) {
            is Ast.ArrayLiteral -> init.elements
            is StringLit -> init.bytes.map { Ast.IntLit(it.toLong(), ety) } + Ast.IntLit(0, ety)
            else -> throw error("fixed array locals take no initialiser")
        }
        elements.forEachIndexed { i, e ->
            val v = expr.emit(e)
            expr.emitStoreFixed(address + i * elemSize, v, ity)
        }
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.ArrayInitIrTest'`
Expected: all six tests PASS.

- [ ] **Step 7: Run the full test suite to verify no regression**

Run: `./gradlew test`
Expected: all tests PASS.

- [ ] **Step 8: Commit**

```javascript
const d = await commit.draft();
await d.commit("feat(ir): array initialiser emission", ["- data-section arrays spell the constant array from ConstValue.ArrayValue", "- fixed-address global arrays emit volatile element stores in the init function", "- fixed-address local arrays emit volatile element stores at function entry", "- string initialisers emit byte stores plus NUL"]);
```

---

### Task 6: Golden IR tests

**Files:**
- Create: `src/test/resources/golden/array_init.wtk`
- Create: `src/test/resources/golden/array_init.ll`
- Modify: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt:13` (add `"array_init"` to the `@ValueSource`)

**Interfaces:**
- Consumes: Task 5's emitters.
- Produces: a golden pair that pins the exact IR for a data-section array initialised from a list.

- [ ] **Step 1: Write the `.wtk` golden input**

Create `src/test/resources/golden/array_init.wtk`:

```wtk
var pattern: ubyte[ubyte] = [12, 12, 13]
var empty: ubyte[ubyte] = []

fun main() {
    while (true) {}
}
```

- [ ] **Step 2: Write the `.ll` golden output (will fail until Step 4)**

Run `CompilerPipeline().compileToIr` on the input and capture the output as the initial `.ll` content (or write a placeholder and fix it in Step 4). The expected output contains:

```
@\"t.pattern\" = global [256 x i8] [i8 12, i8 12, i8 13, i8 0, …]
@\"t.empty\" = global [256 x i8] zeroinit
```

- [ ] **Step 3: Add `"array_init"` to the `@ValueSource` in `GoldenIrTest.kt`**

Change line 13:

```kotlin
    @ValueSource(strings = ["add_ubyte", "global_const", "while_counter", "when_subject", "prefix_postfix"])
```

to:

```kotlin
    @ValueSource(strings = ["add_ubyte", "global_const", "while_counter", "when_subject", "prefix_postfix", "array_init"])
```

- [ ] **Step 4: Run the test and capture the actual IR**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.GoldenIrTest. emitsExpectedIr'`
The `array_init` case fails with a mismatch. Copy the actual IR from the assertion message into `src/test/resources/golden/array_init.ll` (or use `CompilerPipeline().compileToIr` directly in a scratch script).

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.GoldenIrTest'`
Expected: all cases PASS.

- [ ] **Step 6: Commit**

```javascript
const d = await commit.draft();
await d.commit("test(ir): golden array_init IR", ["- data-section constant array from a list", "- zeroinit for an empty list"]);
```

---

### Task 7: Integration test — array initialisers on the real 6502

**Files:**
- Create: `src/test/kotlin/pl/qus/wolin/it/ArrayInitTest.kt`

**Interfaces:**
- Consumes: `pl.qus.wolin.it.runProgram(source): ByteArray` (returns `$0400-$040F` bytes), `pl.qus.wolin.driver.SdkLocator`, `pl.qus.wolin.driver.compileToPrgFile`, `pl.qus.wolin.test.ViceRunner`.
- Produces: a VICE integration test that initialises a fixed-address array from a list and from a string, and reads the bytes back through the `^0x400` out-pattern.

- [ ] **Step 1: Write the test**

Create `src/test/kotlin/pl/qus/wolin/it/ArrayInitTest.kt`:

```kotlin
package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Array initialisers on the real 6502. The program initialises a
 * fixed-address array at $3000 from a list and from a string, then copies
 * the first four bytes to $0400..$0403, which `runProgram` reads back.
 */
class ArrayInitTest {

    @Test fun `a fixed-address array from a list`() {
        val bytes = runProgram(
            """
            var pattern: ubyte[ubyte]^0x3000 = [1, 2, 3, 4]

            fun main() {
                var out: ubyte[ubyte]^0x0400
                out[0] = pattern[0]
                out[1] = pattern[1]
                out[2] = pattern[2]
                out[3] = pattern[3]
                while (true) {}
            }
            """
        )
        assertEquals(1, bytes[0].toInt() and 0xFF)
        assertEquals(2, bytes[1].toInt() and 0xFF)
        assertEquals(3, bytes[2].toInt() and 0xFF)
        assertEquals(4, bytes[3].toInt() and 0xFF)
    }

    @Test fun `a fixed-address array from a string`() {
        val bytes = runProgram(
            """
            var greeting: ubyte[ubyte]^0x3000 = "HI"

            fun main() {
                var out: ubyte[ubyte]^0x0400
                out[0] = greeting[0]
                out[1] = greeting[1]
                out[2] = greeting[2]
                while (true) {}
            }
            """
        )
        assertEquals('H'.code, bytes[0].toInt() and 0xFF)
        assertEquals('I'.code, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt() and 0xFF)   // the NUL terminator
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew test --tests 'pl.qus.wolin.it.ArrayInitTest'`
Expected: both tests PASS. If a test fails, read the actual byte values out of the assertion message; a wrong offset means the `$0400` out-pattern assumption moved — correct the offsets, and say so in the commit message. A failure that is not an offset (e.g. a compile error, a wrong value) is a real defect — fix the emitter, not the test.

- [ ] **Step 3: Commit**

```javascript
const d = await commit.draft();
await d.commit("test(it): array initialisers on the real 6502", ["- fixed-address array from a list", "- fixed-address array from a string (bytes + NUL)"]);
```

---

### Task 8: Docs — REFERENCE.md and TODO.md

**Files:**
- Modify: `REFERENCE.md` (Arrays section, ~line 183-196; Diagnostics table, ~line 427-437)
- Modify: `TODO.md` (add a landed entry under `## This cycle — landed`)

**Interfaces:**
- Consumes: the shipped feature (Tasks 1-7).
- Produces: user-facing docs that match the implementation.

- [ ] **Step 1: Update the Arrays section in `REFERENCE.md`**

Replace the current Arrays section (lines 183-196) with:

```markdown
## Arrays

```kotlin
var arr: ubyte[ubyte]^0x3000
arr[3] = 42
var v: ubyte = arr[3]
```

- The index may be any integer type; there are **no bounds checks**.
- `uword`-element arrays are scaled by the GEP; `ubyte` arrays are not.
- Global arrays without a fixed address are zero-initialised in the data
  section; their size is the index type's domain (`ubyte` → 256, `uword` →
  65536).

### Array initialisers

A declaration may give an array its values with a list literal or, for
`ubyte`-element arrays, with a string.

```kotlin
var pattern: ubyte[ubyte] = [12, 12, 12, 13]          // global, data section
var screen: ubyte[ubyte]^0x0400 = [176, 176]          // global, fixed address
var w: uword[uword] = [0, 1024, 0x3000]               // 16-bit elements
var line: ubyte[ubyte] = "HI\r\n"                     // 4 bytes + NUL
var empty: ubyte[ubyte] = []                          // explicit zero-fill

fun main() {
    var local: ubyte[ubyte]^0x2000 = [a + 1, 7]       // local fixed address
    while (true) {}
}
```

- **Prefix fill.** Element *i* of the list goes to index *i*; the rest of
  the array stays zero. The list does not name the array's length.
- **Constant rule (globals).** Every element of a global array initialiser
  must fold at compile time; an element that does not (a function address,
  a non-constant expression) is a compile error.
- **Local fixed-address arrays** may use arbitrary runtime expressions;
  the values are stored at function entry.
- **String initialisers** (`ubyte`-element arrays only): the string's bytes
  plus the NUL terminator, prefix fill, remainder zero.
- **Count check.** The list (or string + NUL) may not exceed the index
  type's domain; longer lists are a compile error.
- **Accepted positions:** global array initialiser (constant elements),
  global fixed-address array initialiser (constant elements), local
  fixed-address array initialiser (runtime expressions). Anywhere else —
  `var x = [1, 2]` with no declared type, `arr = [1, 2]` in assignment
  position, a call argument — is a compile error.
```

- [ ] **Step 2: Update the Diagnostics table in `REFERENCE.md`**

Replace line 437:

```
| `Global array X must be zero-initialised in v1` | a global array with an initialiser |
```

with:

```
| `Array literal needs an array type` | `var x = [1, 2]` with no declared array type |
| `Array literal can only initialise a declaration` | an array literal in assignment or expression position |
| `Too many array elements` (with the count and the domain) | a list longer than the index type's domain |
| `String initialiser needs a ubyte element type` | a string initialiser on a `uword`-element array |
| `String initialiser too long` (with the length and the domain) | a string whose bytes + NUL exceed the index domain |
| `local arrays need a fixed address in v1` | an array initialiser on a local non-fixed array |
```

- [ ] **Step 3: Add the landed entry to `TODO.md`**

Under `## This cycle — landed`, add a new entry above the existing `### 2026-11-17 — kernal routines return a value`:

```markdown
### 2026-11-17 — array initialisers

Design: `docs/superpowers/specs/2026-11-17-array-initialiser-design.md`.

- [x] A global, global fixed-address, or local fixed-address array
      declaration can initialise itself from a `[v, v, …]` list literal or,
      for `ubyte`-element arrays, from a string literal. Prefix fill;
      remainder zero.
- [x] Global initialisers must be constant expressions (elements fold via
      `ConstFolder`); local fixed-address initialisers may use runtime
      expressions, stored at function entry.
- [x] `[]` is legal = zero-init. No trailing comma.
```

- [ ] **Step 4: Run the full test suite to verify the docs match the code**

Run: `./gradlew test`
Expected: all tests PASS (the docs are not executable, but a failing test means the implementation and the docs disagree — fix the implementation or the docs so they agree).

- [ ] **Step 5: Commit**

```javascript
const d = await commit.draft();
await d.commit("docs: array initialisers in REFERENCE.md and TODO.md", ["- Arrays section: initialiser syntax, semantics, accepted positions", "- Diagnostics table: six new messages", "- TODO.md landed entry"]);
```

---

## Self-Review

### Spec coverage

| Spec section | Task(s) |
|---|---|
| Approaches (A + C) | Tasks 1-7 |
| Goals (global, global fixed, local fixed, string, `[]`) | Tasks 3, 5, 7 |
| Non-goals (no local stack arrays, no dynamic sizing, no array values, no bounds checks, no repeat syntax) | Tasks 3 (rejections), 8 (docs) |
| Constraints (no implicit promotion, constant globals, count vs domain, string NUL) | Tasks 3, 4 |
| Syntax (grammar rule, multi-line, no trailing comma) | Task 1 |
| Semantics (prefix fill, element typing, constant rule, string initialiser, count check, storage) | Tasks 3, 4, 5 |
| Where array literals are accepted (table) | Task 3 (accepted + rejected positions) |
| Diagnostics (six new messages) | Tasks 3, 8 |
| Implementation shape (every file listed) | Tasks 1-8 |
| Adjacent gap (scalar FixedAddress) | Task 5 (left alone; documented in Global Constraints) |
| Designer's calls (1-6) | Tasks 1 (multi-line), 1 (no trailing comma), 3 (local runtime), 3 (string any index type with ubyte element), 3 (`[]` legal), 4 (function-address element rejected) |

### Step scan

Every task has the five required steps after the initial task header: failing test → run → implement → run → commit. No placeholders.

### Type consistency

- `Ast.ArrayLiteral(elements: List<Expr>, type: WolinType)` — same shape as `Ast.ArrayAccess(array, index, type)`.
- `ConstValue.ArrayValue(elements: List<ConstValue>, elementType: WolinType, indexType: WolinType)` — same shape as the other `ConstValue` data classes; `spelling` is a `String` like every other `ConstValue`.
- `arrayDomain(indexType: WolinType): Int` — mirrors `ModuleEmitter.arrayCount` (256 / 65536 by index kind).
- `checkArrayInit(init: Expr, type: WolinType): Expr` — returns the (possibly rewritten) initialiser, matching the `LocalDecl.init: Expr?` / `GlobalDecl.init: Expr?` field type.

### Review focus

1. Trailing comma rejection — Task 1 tests.
2. Count/domain — Task 3 tests (`256` accepted, `257` rejected; string `255` accepted, `256` rejected).
3. String initialiser element type — Task 3 tests (`ubyte[ubyte]` accepted, `ubyte[uword]` accepted, `uword[uword]` rejected).
4. Global array initialiser constness — Task 4 tests (`foldInit` returns `ArrayValue` for a constant list, `null` for a non-constant element).
5. Local fixed-address array initialiser elements are runtime — Task 5 tests (volatile stores in the function body, not static data).
6. Data-section constant array spelling — Task 6 golden test.
7. `[]` is zero-init — Task 3 (`typeChecker` accepts `[]`), Task 5 (`zeroinit` in the IR).
8. `var x = [1, 2]`, `arr = [1, 2]`, local non-fixed initialiser — Task 3 rejection tests.

### Proportion

The plan is a mid-size feature (one grammar line, one new AST node, one new `ConstValue`, two new checker helpers, three emitter edits, ~20 tests). It is broken into eight tasks, each of which is independently testable and committable. No task is longer than ~30 minutes of work.

## Execution

Per the `superpowers:writing-plans` skill handoff, the user chooses the execution approach:

- **Subagent-driven** (`superpowers:subagent-driven-development`) — dispatch a fresh subagent per task, with a two-stage review after each. Best when the tasks are independent and the plan is the source of truth.
- **Native** (`superpowers:executing-plans`) — execute the plan inline in the current session. Best when the tasks are tightly coupled on shared interfaces (this plan) and the session model is mid-tier.

Recommendation: **native**, because the tasks share interfaces (the `ArrayLiteral` node, the `ArrayValue` spelling, the `checkArrayInit` signature) and the plan carries the full design.