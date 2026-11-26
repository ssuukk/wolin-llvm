# Simple enums Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add anonymous `enum { A, B = 5 }` constants and named `enum Status { … }` constants with `Status.A`, backed by `ubyte`, with no storage.

**Architecture:** The AST builder computes entry values and emits anonymous entries as `GlobalDecl`s carrying `enumValue`, and named ones as `EnumDecl` in `Program.enums`. The checker makes a named enum's name a `ubyte` type alias and resolves `Status.ENTRY` through `Symbol.EnumSymbol`. The expression emitter inlines both forms as `i8`; module and init emitters skip anonymous declarations.

**Tech Stack:** Kotlin 21, ANTLR 4.13 (Kotlin grammar), hand-rolled LLVM-IR text, LLVM-MOS, JUnit 5, VICE (`x64sc`) for integration tests.

**Spec:** `docs/superpowers/specs/2026-11-25-simple-enum-design.md`

## Global Constraints

- Syntax: `enum { … }` and `enum Name { … }`, file scope only. `enum class` stays rejected.
- Entries are `ubyte` (LLVM `i8`) and occupy **no storage**; every use is an inline constant.
- Autonumber starts at `0`; each unvalued entry is previous + 1; an explicit literal resets the counter.
- Explicit values are integer literals only, in `0..255` (`0x..`, `0b..` included).
- Named enums: `Name.ENTRY` is an entry access; `Name` alone is a type, not a value.
- `Name` is a **ubyte alias**, not a distinct type: `var s: Name`, `var u: ubyte = s`, and `var s: Name = 5` all type-check.
- Named entries are **not** bare constants; anonymous entries are.
- No enum-typed struct fields or trait/impl method signatures (resolution order; see non-goals).
- Exact diagnostics: `Duplicate enum entry X`, `Enum value must be an integer literal`, `Enum value out of range`, `Duplicate enum X`, `Enum name X is reserved`, `Unknown enum entry X in Y`, `Enum Y is a type, not a value`, `Cannot assign to enum entry Y.X`.
- Full test command: `./gradlew test` (integration tests need `x64sc` and `xvfb-run` on `PATH`).
- Commits: conventional style with a scope, one per task.

## Review Focus

1. **An imported named enum across two modules** must resolve and inline, not leave an undefined global. → Task 4, two-module VICE test.
2. **`Status` in value position** (`var x: ubyte = Status`) must be a clear error, not an address or a zero. → Task 2, sema test.
3. **A global initialised from `Status.ENTRY`** must be static data. → Task 3, IR test.
4. **A local variable shadowing an anonymous entry** must win over the constant. → Task 4, VICE test.
5. **Autonumber past 255** (explicit `255` plus an unvalued entry) must error, not wrap. → Task 1, sema test.
6. **A named entry of the wrong enum** (`Other.ENTRY`) must fail with the owning enum named. → Task 2, sema test.

---

### Task 1: Grammar, AST, builder, and anonymous constants

**Files:**
- Modify: `src/main/antlr/KotlinParser.g4` (`topLevelObject` rule)
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt` (`build`, declaration builders)
- Modify: `src/main/kotlin/pl/qus/wolin/driver/ProgramBuilder.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/driver/Main.kt` (`emitFiles`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (`singleFileScope`)
- Create: `src/test/kotlin/pl/qus/wolin/parser/EnumGrammarTest.kt`
- Create: `src/test/kotlin/pl/qus/wolin/sema/EnumTest.kt`

**Interfaces:**
- Consumes: nothing from later tasks.
- Produces: `Ast.EnumDecl(name: String?, entries: List<Ast.EnumEntry>, line: Int? = null)`, `Ast.EnumEntry(name: String, value: Int, line: Int? = null)`; `Program.enums` and `FileScope.enums` (`List<EnumDecl>`, default empty); anonymous entries as `GlobalDecl(..., enumValue = value)`.

- [ ] **Step 1: Write the parser test**

Create `EnumGrammarTest.kt`:

```kotlin
package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

class EnumGrammarTest {
    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `an anonymous enum with explicit values parses`() {
        assertTrue(parse("enum { PENDING = 0, RUNNING = 5, ERROR = 255 }"))
    }

    @Test fun `an anonymous enum with autonumbered entries parses`() {
        assertTrue(parse("enum { A, B, C }"))
    }

    @Test fun `a named enum parses`() {
        assertTrue(parse("enum Status { PENDING, RUNNING = 5 }"))
    }

    @Test fun `newlines and a trailing comma parse`() {
        assertTrue(parse("enum Status {\n    IDLE,\n    BUSY = 0x10,\n    DONE,\n}"))
    }
}
```

- [ ] **Step 2: Run the parser test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.EnumGrammarTest'`
Expected: FAIL — syntax errors on `enum {`.

- [ ] **Step 3: Write the sema test**

Create `sema/EnumTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { check(src) }
        assertTrue(e.message!!.contains(fragment), "expected '$fragment', got: ${e.message}")
    }

    @Test fun `anonymous autonumber starts at zero and increments`() {
        val p = check("enum { A, B, C }\nfun main() {}")
        assertEquals(listOf(0, 1, 2), p.globals.map { it.enumValue })
    }

    @Test fun `an explicit value resets the counter`() {
        val p = check("enum { A, B = 5, C }\nfun main() {}")
        assertEquals(listOf(0, 5, 6), p.globals.map { it.enumValue })
    }

    @Test fun `a named enum builds its entries`() {
        val e = check("enum Status { A, B = 5 }\nfun main() {}").enums.single()
        assertEquals("Status", e.name)
        assertEquals(listOf("A" to 0, "B" to 5), e.entries.map { it.name to it.value })
    }

    @Test fun `a duplicate entry is rejected`() {
        assertRejected("enum { A, A }\nfun main() {}", "Duplicate enum entry A")
    }

    @Test fun `a value above 255 is rejected`() {
        assertRejected("enum { A = 256 }\nfun main() {}", "Enum value out of range")
    }

    @Test fun `autonumber past 255 is rejected`() {
        assertRejected("enum { A = 255, B }\nfun main() {}", "Enum value out of range")
    }

    @Test fun `a non-literal value is rejected`() {
        assertRejected("enum { A = 1 + 1 }\nfun main() {}", "Enum value must be an integer literal")
    }

    @Test fun `an anonymous entry cannot be assigned`() {
        assertRejected("enum { A }\nfun main() { A = 1 }", "Cannot assign to val A")
    }
}
```

- [ ] **Step 4: Run the sema test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.EnumTest'`
Expected: FAIL — the enum declaration does not parse.

- [ ] **Step 5: Extend the grammar**

Add `| enumDeclaration` to `topLevelObject` after `classDeclaration`, and add:

```
enumDeclaration
    : ENUM NL* simpleIdentifier? NL* enumValueBody
    ;

enumValueBody
    : LCURL NL* (enumValueEntry (NL* COMMA NL* enumValueEntry)* NL* COMMA?)? NL* RCURL
    ;

enumValueEntry
    : simpleIdentifier (NL* ASSIGNMENT NL* expression)?
    ;
```

Run: `./gradlew generateParser`
Expected: ANTLR completes with no errors.

- [ ] **Step 6: Add the AST nodes and plumbing**

In `Ast.kt`:

```kotlin
data class EnumDecl(
    val name: String?,
    val entries: List<EnumEntry>,
    val line: Int? = null,
) : Ast

data class EnumEntry(
    val name: String,
    val value: Int,
    val line: Int? = null,
) : Ast
```

Add `val enums: List<EnumDecl> = emptyList(),` to `Program` and `FileScope`, with a doc line: named enums only form is anonymous (entries are globals); named ones live here. Add typealiases `EnumDecl`, `EnumEntry`.

Add `enumValue: Int? = null` to `GlobalDecl` after `line`.

Plumbing:
- `AstBuilder.build` creates `val enums = mutableListOf<EnumDecl>()` and passes `enums = enums` to `Program`.
- `ProgramBuilder` copies `built.enums` into each `FileScope` and `scopes.flatMap { it.enums }` into the merged `Program`.
- `Main.emitFiles` passes `enums = scope.enums` to the per-file `Ast.Program`.
- `TypeChecker.singleFileScope` passes `enums = file.enums`.

- [ ] **Step 7: Build enum declarations**

In `AstBuilder.build`, add before the `classDeclaration` arm:

```kotlin
top.enumDeclaration()?.let { declaration ->
    val built = buildEnum(declaration)
    if (built.name == null) {
        globals += built.entries.map { entry ->
            GlobalDecl(
                name = entry.name,
                type = Types.ubyte,
                init = IntLit(entry.value.toLong(), Types.ubyte),
                location = null,
                isVal = true,
                declaredType = Types.ubyte,
                line = entry.line,
                enumValue = entry.value,
            )
        }
    } else {
        enums += EnumDecl(built.name, built.entries, built.line)
    }
    return@forEach
}
```

Add:

```kotlin
private data class BuiltEnum(
    val name: String?,
    val entries: List<EnumEntry>,
    val line: Int,
)

private fun buildEnum(ctx: KotlinParser.EnumDeclarationContext): BuiltEnum {
    val entries = mutableListOf<EnumEntry>()
    val seen = mutableSetOf<String>()
    var next = 0
    for (entry in ctx.enumValueBody().enumValueEntry()) {
        val name = entry.simpleIdentifier().text
        if (!seen.add(name)) throw SemaException("Duplicate enum entry $name", entry.start.line)
        val explicit = entry.expression()?.let { expression ->
            (buildExpression(expression) as? IntLit)
                ?: throw SemaException("Enum value must be an integer literal", entry.start.line)
        }
        val value = explicit?.value ?: next.toLong()
        if (value !in 0..255) throw SemaException("Enum value out of range", entry.start.line)
        next = value.toInt() + 1
        entries += EnumEntry(name, value.toInt(), entry.start.line)
    }
    return BuiltEnum(ctx.simpleIdentifier()?.text, entries, ctx.start.line)
}
```

- [ ] **Step 8: Run the parser and sema tests**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.EnumGrammarTest' --tests 'pl.qus.wolin.sema.EnumTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/antlr/KotlinParser.g4 src/main/kotlin/pl/qus/wolin/ast/Ast.kt \
    src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt \
    src/main/kotlin/pl/qus/wolin/driver/ProgramBuilder.kt \
    src/main/kotlin/pl/qus/wolin/driver/Main.kt \
    src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt \
    src/test/kotlin/pl/qus/wolin/parser/EnumGrammarTest.kt \
    src/test/kotlin/pl/qus/wolin/sema/EnumTest.kt
git commit -m "feat(grammar): parse anonymous and named simple enums"
```

---

### Task 2: Named enum resolution as a ubyte alias

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/Types.kt` (`structName` becomes `var`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/Scope.kt` (`Symbol.EnumSymbol`)
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt` (`MemberAccess.enumValue`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt`
- Create: `src/test/kotlin/pl/qus/wolin/sema/NamedEnumTest.kt`

**Interfaces:**
- Consumes: `EnumDecl`/`EnumEntry`, `Program.enums`, `FileScope.enums` from Task 1.
- Produces: `Symbol.EnumSymbol(decl: EnumDecl)`; `MemberAccess.enumValue: Int?`; named enum names resolve to `TypeKind.UBYTE`; `Name.ENTRY` yields `ubyte` with `enumValue`.

- [ ] **Step 1: Write the named-enum sema tests**

Create `sema/NamedEnumTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedEnumTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun assertRejected(src: String, fragment: String) {
        val e = assertThrows<SemaException> { check(src) }
        assertTrue(e.message!!.contains(fragment), "expected '$fragment', got: ${e.message}")
    }

    @Test fun `a named entry is a ubyte constant`() {
        check("enum Status { PENDING, RUNNING = 5 }\nfun main() { var s: ubyte = Status.RUNNING }")
    }

    @Test fun `the enum name is a ubyte type alias`() {
        check(
            "enum Status { PENDING }\n" +
                "fun main() { var s: Status = Status.PENDING; var u: ubyte = s; s = 7 }"
        )
    }

    @Test fun `entries work in a when`() {
        check(
            "enum Status { PENDING, RUNNING }\n" +
                "fun main() { var s: Status = Status.PENDING; " +
                "when (s) { Status.PENDING -> { }; Status.RUNNING -> { } } }"
        )
    }

    @Test fun `an entry initialises a global constant`() {
        val p = check("enum Status { RUNNING = 5 }\nvar s: ubyte = Status.RUNNING\nfun main() {}")
        val bits = (p.globals.single { it.name == "s" }.constValue as ConstValue.IntValue).bits
        assertEquals(5L, bits)
    }

    @Test fun `an unknown entry is rejected`() {
        assertRejected(
            "enum Status { A }\nfun main() { var s: ubyte = Status.B }",
            "Unknown enum entry B in Status",
        )
    }

    @Test fun `the enum name is not a value`() {
        assertRejected("enum Status { A }\nfun main() { var s: ubyte = Status }", "Enum Status is a type, not a value")
    }

    @Test fun `an entry cannot be assigned`() {
        assertRejected(
            "enum Status { A }\nfun main() { Status.A = 1 }",
            "Cannot assign to enum entry Status.A",
        )
    }

    @Test fun `a duplicate visible enum name is rejected`() {
        assertRejected("enum Status { A }\nenum Status { B }\nfun main() {}", "Duplicate enum Status")
    }

    @Test fun `a reserved enum name is rejected`() {
        assertRejected("enum ubyte { A }\nfun main() {}", "Enum name ubyte is reserved")
    }

    @Test fun `an entry of the wrong enum is rejected`() {
        assertRejected(
            "enum A { X }\nenum B { Y }\nfun main() { var s: ubyte = B.X }",
            "Unknown enum entry X in B",
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.NamedEnumTest'`
Expected: FAIL — `Status` is an unknown variable/struct, and named entries do not resolve.

- [ ] **Step 3: Change the type object and symbol model**

In `Types.kt`, change `val structName: String? = null` to `var structName: String? = null` (the resolver clears it when an enum name becomes `ubyte`).

In `Scope.kt`, add `import pl.qus.wolin.ast.EnumDecl` and:

```kotlin
/** A named enum declaration. Its name is a type, and its entries are `Name.ENTRY`. */
data class EnumSymbol(val decl: EnumDecl) : Symbol()
```

In `Ast.kt`, add to `MemberAccess`:

```kotlin
/** The value of a named enum entry access; filled by the checker. */
var enumValue: Int? = null,
```

- [ ] **Step 4: Resolve named enums in the checker**

Add state:

```kotlin
/** The named enums visible in the file being checked. */
private var enumTypes: MutableMap<String, EnumDecl> = mutableMapOf()
```

In `checkFile`, after `traitTypes = visibleTraits(...)`, add:

```kotlin
enumTypes = visibleEnums(fileScope, program)
```

After the structs are declared, validate and declare the enums:

```kotlin
enumTypes.values.forEach { decl ->
    val name = decl.name!!
    if (name in BUILTIN_TYPE_NAMES || name == "CPU") {
        throw SemaException("Enum name $name is reserved", decl.line)
    }
    if (structTypes.containsKey(name) || traitTypes.containsKey(name)) {
        throw SemaException("Duplicate type $name", decl.line)
    }
    globals.declare(name, Symbol.EnumSymbol(decl))
}
```

Add `visibleEnums`, modelled on `visibleStructs`: own enums, same-package enums, and enums named by an explicit or wildcard import; duplicate names throw `Duplicate enum ${decl.name}`. Ignore anonymous declarations (`name == null`).

In `resolveType`, before the trait/struct checks:

```kotlin
TypeKind.STRUCT -> {
    val name = type.structName
    if (name != null && enumTypes.containsKey(name)) {
        type.kind = TypeKind.UBYTE
        type.structName = null
        return
    }
    ...
```

Resolve a global's declared type **before** its initialiser is checked. At the top of `checkGlobal`, add `global.declaredType?.let { resolveType(it, global.line) }`. Without this, `var s: Status = Status.PENDING` still compares a `STRUCT`-shaped declaration with the entry's `ubyte` and fails with `Attempt reassign`. Locals already resolve at the top of `checkStmt`.

- [ ] **Step 5: Resolve entries and reject misuse**

In `checkMemberAccess`, before checking the receiver as an expression:

```kotlin
val receiver = expr.receiver
if (receiver is VarRef) {
    val symbol = lookup(receiver.name)
    if (symbol is Symbol.EnumSymbol) {
        val entry = symbol.decl.entries.firstOrNull { it.name == expr.name }
            ?: throw SemaException("Unknown enum entry ${expr.name} in ${symbol.decl.name}")
        expr.enumValue = entry.value
        expr.type = Types.ubyte
        return expr.type
    }
}
```

In `checkVarRef`, add:

```kotlin
is Symbol.EnumSymbol ->
    throw SemaException("Enum ${symbol.decl.name} is a type, not a value")
```

In `checkCall`, add the same rejection for a callee that is an enum type, naming it `Enum ${symbol.decl.name} is a type, not a function`.

In `checkAssign`, at the start of the `is MemberAccess ->` arm:

```kotlin
val receiver = target.receiver
if (receiver is VarRef && lookup(receiver.name) is Symbol.EnumSymbol) {
    val enumName = (lookup(receiver.name) as Symbol.EnumSymbol).decl.name
    throw SemaException("Cannot assign to enum entry $enumName.${target.name}")
}
```

- [ ] **Step 6: Resolve imported enum names**

In `ImportResolver.resolve`, add `?: resolveEnum(name, imports, program)` after `resolveStruct`.

Add `resolveEnum`, mirroring `resolveStruct`: for each import, handle `package.*` and `package.Name`, scan `program.files` in that package, match a named `EnumDecl`, and return `Symbol.EnumSymbol(decl)`.

- [ ] **Step 7: Fold named entries in global initialisers**

In `ConstFolder.fold`, add:

```kotlin
is MemberAccess -> expr.enumValue?.let { ConstValue.IntValue(it.toLong(), expr.type) }
```

- [ ] **Step 8: Run the named and anonymous sema tests**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.NamedEnumTest' --tests 'pl.qus.wolin.sema.EnumTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/Types.kt \
    src/main/kotlin/pl/qus/wolin/sema/Scope.kt \
    src/main/kotlin/pl/qus/wolin/ast/Ast.kt \
    src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt \
    src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt \
    src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt \
    src/test/kotlin/pl/qus/wolin/sema/NamedEnumTest.kt
git commit -m "feat(sema): resolve named enums as ubyte aliases"
```

---

### Task 3: Inline entries and suppress storage

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/Scope.kt` (`Symbol.DataSymbol.enumValue`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (three global `DataSymbol` sites)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt` (two `DataSymbol` sites)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt` (`emitVarRef`, `emitMemberAccess`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt` (`emitGlobals`, `emitForeignGlobals`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/GlobalInitEmitter.kt` (`emitFile`, `referencesAnotherEntity`)
- Create: `src/test/kotlin/pl/qus/wolin/ir/EnumIrTest.kt`

**Interfaces:**
- Consumes: `GlobalDecl.enumValue`, `MemberAccess.enumValue`, `Symbol.EnumSymbol`.
- Produces: `Symbol.DataSymbol.enumValue: Int?`; inline `IrValue("<value>", I8)` for both forms; no global slot or init store for an enum declaration; a global initialised from an entry is static data.

- [ ] **Step 1: Write the failing IR tests**

Create `ir/EnumIrTest.kt`:

```kotlin
package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.it.emitIr
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnumIrTest {
    @Test fun `an anonymous entry is inlined and has no storage`() {
        val ir = emitIr("""
            enum { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: ubyte = RUNNING
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("store i8 5"), ir)
        assertFalse(ir.contains("RUNNING"), ir)
        assertFalse(ir.contains("ERROR"), ir)
    }

    @Test fun `a named entry is inlined and has no storage`() {
        val ir = emitIr("""
            enum Status { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: Status = Status.RUNNING
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("store i8 5"), ir)
        assertFalse(ir.contains("RUNNING"), ir)
        assertFalse(ir.contains("Status"), ir)
    }

    @Test fun `an entry initialises a global at compile time`() {
        val ir = emitIr("""
            enum Status { RUNNING = 5 }
            var state: ubyte = Status.RUNNING
            fun main() {
                var out: ubyte^0x400 = 0
                out = state
                while (true) {}
            }
        """)
        assertTrue(ir.contains("global i8 5"), ir)
    }
}
```

- [ ] **Step 2: Run the IR tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.EnumIrTest'`
Expected: FAIL — enum declarations get global slots and named entries are not emitted as constants.

- [ ] **Step 3: Carry the value on data symbols**

In `Symbol.DataSymbol`, after `prefix`, add:

```kotlin
/** The value of an anonymous enum entry, emitted inline instead of loaded. */
val enumValue: Int? = null,
```

In `TypeChecker.checkFile`, pass `global.enumValue` in both `DataSymbol` constructions (other-file globals and own globals); in the construction at the end of `checkGlobal`, pass `enumValue = global.enumValue`.

In `ImportResolver.resolveGlobal`, pass `global.enumValue` in both `DataSymbol` constructions.

- [ ] **Step 4: Inline both forms**

In `ExprEmitter.emitVarRef`, after the `Symbol.FunSymbol` arm and before `val ty`, add:

```kotlin
(resolved[e] as? Symbol.DataSymbol)?.enumValue?.let { value ->
    return IrValue(value.toString(), IrType.I8)
}
```

In `ExprEmitter.emitMemberAccess`, first line:

```kotlin
e.enumValue?.let { return IrValue(it.toString(), IrType.I8) }
```

- [ ] **Step 5: Skip storage and treat entries as literals**

In `ModuleEmitter.emitGlobals`, first line of the loop:

```kotlin
if (g.enumValue != null) continue
```

In `ModuleEmitter.emitForeignGlobals`, first line of the loop:

```kotlin
if (foreign.decl.enumValue != null) continue
```

In `GlobalInitEmitter.emitFile`, first line of the loop:

```kotlin
if (g.enumValue != null) continue
```

Change `referencesAnotherEntity`:

```kotlin
internal fun referencesAnotherEntity(
    e: Expr,
    resolved: Map<VarRef, Symbol>,
): Boolean = when (e) {
    is VarRef -> (resolved[e] as? Symbol.DataSymbol)?.enumValue == null
    is UnaryOp -> referencesAnotherEntity(e.operand, resolved)
    is BinaryOp -> referencesAnotherEntity(e.left, resolved) ||
        referencesAnotherEntity(e.right, resolved)
    is Convert -> referencesAnotherEntity(e.value, resolved)
    else -> false
}
```

Pass `program.resolved` at both call sites (`ModuleEmitter.emitGlobals`, `GlobalInitEmitter.emitFile`). A `MemberAccess` enum entry falls through to `else -> false`.

- [ ] **Step 6: Run the IR and sema tests**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.EnumIrTest' --tests 'pl.qus.wolin.sema.EnumTest' --tests 'pl.qus.wolin.sema.NamedEnumTest'`
Expected: PASS. The IR has inline `i8` constants, no enum globals, and `global i8 5` for the initialised global.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/Scope.kt \
    src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt \
    src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt \
    src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt \
    src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt \
    src/main/kotlin/pl/qus/wolin/ir/GlobalInitEmitter.kt \
    src/test/kotlin/pl/qus/wolin/ir/EnumIrTest.kt
git commit -m "feat(ir): inline simple enum entries as i8 constants"
```

---

### Task 4: Real-6502 coverage, cross-module import, and docs

**Files:**
- Create: `src/test/kotlin/pl/qus/wolin/it/EnumTest.kt`
- Modify: `src/test/kotlin/pl/qus/wolin/it/MultiFileTest.kt`
- Modify: `REFERENCE.md`
- Modify: `README.md`
- Modify: `TODO.md`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: runtime proof on VICE, cross-module proof, user-facing documentation.

- [ ] **Step 1: Write the VICE tests**

Create `src/test/kotlin/pl/qus/wolin/it/EnumTest.kt`:

```kotlin
package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EnumTest {
    @Test fun `a named enum drives a when`() {
        val bytes = runProgram("""
            enum Status { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: Status = Status.RUNNING
                var out: ubyte^0x400 = 0
                when (state) {
                    Status.PENDING -> out = 1
                    Status.RUNNING -> out = 5
                    Status.ERROR -> out = 255
                }
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `anonymous autonumbered entries run`() {
        val bytes = runProgram("""
            enum { A, B, C }
            fun main() {
                var out: ubyte^0x400 = 0
                out = C
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a local variable shadows an anonymous entry`() {
        val bytes = runProgram("""
            enum { RUNNING = 5 }
            fun main() {
                var RUNNING: ubyte = 9
                var out: ubyte^0x400 = 0
                out = RUNNING
                while (true) {}
            }
        """)
        assertEquals(9, bytes[0].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run the VICE tests**

Run: `./gradlew test --tests 'pl.qus.wolin.it.EnumTest'`
Expected: PASS (requires `x64sc` and `xvfb-run`).

- [ ] **Step 3: Add the cross-module test**

In `MultiFileTest`, add:

```kotlin
@Test fun `an imported named enum is inlined across modules`() {
    val bytes = runProgramFiles(
        listOf(
            SourceFile("lib.wtk", "package lib\nenum Status { RUNNING = 5 }"),
            SourceFile(
                "app.wtk",
                "package app\nimport lib.Status\nfun main() {\n" +
                    "    var out: ubyte^0x400 = 0\n" +
                    "    out = Status.RUNNING\n" +
                    "    while (true) {}\n" +
                    "}",
            ),
        )
    )
    assertEquals(5, bytes[0].toInt() and 0xFF)
}
```

- [ ] **Step 4: Run the cross-module and IR tests**

Run: `./gradlew test --tests 'pl.qus.wolin.it.MultiFileTest' --tests 'pl.qus.wolin.ir.EnumIrTest'`
Expected: PASS; the import links with no undefined symbol.

- [ ] **Step 5: Document `REFERENCE.md`**

In the Declarations code block add:

```kotlin
enum { PENDING = 0, RUNNING = 5 }          // anonymous ubyte constants
enum Status { PENDING, RUNNING = 5 }       // named; Status.PENDING, Status is a ubyte alias
```

After the Declarations section add:

```markdown
### Enums

`enum { … }` declares anonymous file-scope `ubyte` constants. `enum Name { … }`
declares a named enum: entries are `Name.ENTRY`, and `Name` is a **ubyte
alias** in type position (`var s: Name`), not a distinct type. An entry
without a value is the previous value + 1, starting at 0; an explicit value
resets the counter. Values are integer literals in 0..255. Entries are
`val`-like, occupy no storage, and are emitted as inline constants.
Anonymous entries are visible to same-package files and through `import`
like globals; a named enum is imported by its name (`import pkg.Name`) and
can be used after `import pkg.*`. Named entries are not bare constants. No
local enums, no `enum class`, and no enum-typed struct fields or trait/impl
method signatures.
```

Add to the Diagnostics table:

```markdown
| `Duplicate enum entry X` | two entries of one enum share a name |
| `Enum value must be an integer literal` | an enum value that is not an integer literal |
| `Enum value out of range` | an enum value outside 0..255, or autonumber past 255 |
| `Duplicate enum X` | two visible named enums share a name |
| `Enum name X is reserved` | the name is a built-in type or `CPU` |
| `Unknown enum entry X in Y` | `Y.X` names no entry of enum `Y` |
| `Enum Y is a type, not a value` | an enum name used where a value is expected |
| `Cannot assign to enum entry Y.X` | assignment to an enum entry |
```

- [ ] **Step 6: Update `README.md` and `TODO.md`**

In the README declaration list, add:

```kotlin
enum { PENDING = 0, RUNNING = 5 }       // anonymous ubyte constants
enum Status { PENDING, RUNNING = 5 }    // Status.PENDING; Status is a ubyte alias
```

In `TODO.md`, tick the item and link both documents:

```markdown
- [x] Simple `enum` (not Kotlin `enum class`) representing an ubyte value,
      either autonumber or with values like: `enum { PENDING = 0, RUNNING = 5, ERROR = 255}`
      — [design](docs/superpowers/specs/2026-11-25-simple-enum-design.md),
      [plan](docs/superpowers/plans/2026-11-25-simple-enum.md)
```

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/test/kotlin/pl/qus/wolin/it/EnumTest.kt \
    src/test/kotlin/pl/qus/wolin/it/MultiFileTest.kt \
    REFERENCE.md README.md TODO.md
git commit -m "test(it): cover simple enums on the 6502 and across modules"
```

---

## Self-Review

- **Spec coverage:** both syntaxes (Task 1), named resolution and alias (Task 2), inlining/no storage and static initialisers (Task 3), imports/VICE/docs (Task 4).
- **Step scan:** each step names exact files, symbols, messages and assertions; the only open implementation choices are body details that the signatures and tests fix.
- **Type consistency:** `enumValue` is `Int?` on `GlobalDecl`, `MemberAccess` and `DataSymbol`; named types normalise to `TypeKind.UBYTE`; no new `TypeKind` exists.
- **Review Focus:** each of the six lines has a test in Tasks 1–4.
