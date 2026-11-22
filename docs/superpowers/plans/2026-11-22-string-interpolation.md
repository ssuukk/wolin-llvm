# String interpolation — implementation plan (first cut)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `${expr}` and `$name` in string literals for `ubyte`, `byte`, `uword`, `word`, `bool`, `string` and `float`, with no heap: one static 256-byte buffer per template site, 255-byte result cap, type-directed formatting, and a self-contained float formatter.

**Architecture:** The parser already produces `lineStringLiteral` / `multiLineStringLiteral` nodes with `LineStrRef` (`$name`) and `LineStrExprStart` (`${`). The AST builder turns them into `StringTemplate(parts)`; the checker types every part and rejects unsupported part types; the emitter writes into a module-level `private global [256 x i8]` per syntactic site and calls small runtime append helpers. All append helpers are hand-emitted IR in the driver module. Float formatting is a self-contained IR helper over the same soft-float operations float arithmetic already uses (`fcmp`, `fptoui`, `uitofp`, `fsub`, `fmul`, `fdiv`); it is emitted only when a file actually uses a float part. No C library is called.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), ANTLR 4.13 (Kotlin grammar), hand-rolled LLVM-IR text, LLVM-MOS SDK v23.2.0 (`mos-c64-clang`), JUnit 5, VICE (`x64sc`) for integration tests.

**Spec:** `docs/superpowers/specs/2026-11-22-string-interpolation-design.md`

## Global Constraints

- **No heap.** No `malloc`, no `free`, no dynamic-size `alloca`.
- **Storage:** one `private global [256 x i8] zeroinitializer` per template site. Every site has its own buffer; nested templates and several interpolations in one expression do not clobber each other.
- **Result cap:** 255 bytes plus the final NUL. A runtime `string` part is copied until its NUL or until the cursor reaches 255; truncation is silent. A fixed part whose compile-time maximum alone exceeds 255 is a compile error: `String interpolation is limited to 255 bytes`.
- **Cursor:** `i16` (`uword`), never `i8` as a GEP index.
- **Part types:** `ubyte`, `byte`, `uword`, `word`, `bool`, `string`, `float`. Everything else is a compile error naming the construct. `char` does not exist: `'A'` is a `ubyte` and renders `65`.
- **All-literal templates** (no `${}` / `$name`) stay `StringLit`: no buffer, no interpolation code.
- **Templates with value parts in global initialisers** are rejected by the existing constant-expression rule. Constant folding of `${}` is a non-goal for this cut.
- **Float format:** custom IR formatter, 7 significant digits, rounded on the 8th. Fixed notation for decimal exponents `-3..6`; `E` notation outside that range, no `+`, no leading exponent zeros. `.0` on integral fixed values. `NaN`, `Infinity`, `-Infinity` are written directly. This is not Kotlin's shortest round-trip.
- **Float dependencies:** only compiler-rt soft-float (`fcmp`, `fptoui`, `uitofp`, `fsub`, `fmul`, `fdiv`), the same class of runtime float arithmetic already links. No C library, no `printf`, no `snprintf`.
- **Float code size:** the float helper is emitted only when a float part exists. Programs without float interpolation must contain neither `append_float` nor the float digit scratch in the driver module.
- Commits: conventional style (`feat(parser):`, `feat(ir):`, `feat(driver):`, `docs:`), one commit per task.
- Test command: `./gradlew test` (integration tests need `x64sc`; it is installed).
- ANTLR regeneration: `./gradlew generateParser` (also runs implicitly on `compileKotlin`). The grammar is **not** changed by this plan.

## Review Focus

Input classes the spec implies but no task's tests would obviously cover; each line's test is added to the owning task.

1. **A `$` not followed by an identifier or `{` is literal.** `"price $5"` is a `StringLit`, not an interpolation or an error. → Task 1 parser tests.
2. **`\$` escapes interpolation.** `"\${n}"` is the literal six bytes `${n}`, not a template. → Task 1 parser tests.
3. **NUL in a template literal is rejected.** `"a\0${n}"` cannot be printed past the first NUL, so it is a sema error; a plain `"a\0b"` keeps its old behaviour. → Task 1 sema tests.
4. **A runtime string before a fixed part does not overflow.** With `"${s}${n}"`, `s` can consume all 255 bytes; `append_ubyte` must check the cursor before every store. → Task 2 VICE test with a long string.
5. **A float part at the end of a nearly full buffer truncates, not overflows.** The float formatter writes through the capped append-char helper. → Task 3 VICE test.
6. **No float parts means no float helper.** A program with only integer/bool/string templates must not contain `append_float` or the float digit scratch in the driver module. → Task 3 driver-module IR test.
7. **Rounding and notation boundaries.** `9.999999f` must not print as `10.0` in the wrong place; `e10 = -3` is fixed (`0.001`), `e10 = -4` is scientific (`1.0E-4`), `e10 = 6` is fixed, `e10 = 7` is scientific. → Task 3 VICE tests.

---

### Task 1: AST, builder, checker — interpolation parses and types

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt` (next to `StringLit`, line 118)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt` (`buildLiteral` :503, `stringBytes` :647)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (`checkExpr` :666, array-initialiser paths :170/:383)
- Modify: `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt` (the two interpolation tests, :127-136)
- Create: `src/test/kotlin/pl/qus/wolin/parser/StringTemplateGrammarTest.kt`
- Create: `src/test/kotlin/pl/qus/wolin/sema/StringTemplateTest.kt`

**Interfaces:**
- Consumes: the grammar already provides `lineStringContent` / `lineStringExpression` and `multiLineStringContent` / `multiLineStringExpression` (`KotlinParser.g4:441-466`), plus `LineStrRef` and `LineStrExprStart` (`KotlinLexer.g4:487,500`).
- Produces:

```kotlin
data class StringTemplate(val parts: List<Part>, override var type: WolinType) : Expr {
    sealed interface Part : Ast
    data class Literal(val bytes: ByteArray) : Part
    data class Value(val expr: Expr) : Part
}
```

`Ast.StringTemplate`, `Ast.StringTemplate.Literal` and `Ast.StringTemplate.Value`; typealiases `StringTemplate`, `StringPart` as the file already does. Task 2 emits this shape.

At the end of this task every existing test is green, new parse/sema tests pass, and no IR is emitted for a template yet.

- [ ] **Step 1: Write the parser/builder tests**

Create `StringTemplateGrammarTest.kt` in the style of `LabelGrammarTest`:

```kotlin
private fun build(src: String): Ast.Program {
    val diag = Diagnostics()
    val tree = ParserFacade(diag).parse(src, "t.ktk")
    assertTrue(diag.messages.isEmpty(), "syntax errors: ${diag.messages}")
    return AstBuilder().build(tree!!)
}
```

Cover:

- `"a ${n} b"` → `StringTemplate` with `Literal("a ")`, `Value(VarRef("n"))`, `Literal(" b")`.
- `"$n"` → one `Value(VarRef("n"))`.
- `"${n + 1}"` → one `Value(BinaryOp("+", …))`.
- `"${a[0]}"` → one `Value(ArrayAccess)`.
- `"${"x"}"` → one `Value(StringLit)` (nested literal).
- `"a\$b"` → `StringLit` bytes `a$b`.
- `"price $5"` → `StringLit` bytes `price $5`.
- `"a\nb"` still uses the escape decoder.
- A triple-quoted `"""a${n}b"""` builds the same parts.
- A plain literal `"abc"` is still `StringLit`.

- [ ] **Step 2: Run the parser tests; they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.StringTemplateGrammarTest'`
Expected: FAIL because interpolation still becomes a `StringLit` and `${` is rejected.

- [ ] **Step 3: Add the AST nodes**

Add `StringTemplate` and its nested `Part` / `Literal` / `Value` next to `StringLit` (`Ast.kt:118`). Add the typealiases at the bottom with the existing ones:

```kotlin
typealias StringTemplate = Ast.StringTemplate
typealias StringPart = Ast.StringTemplate.Part
```

- [ ] **Step 4: Build the parts in `AstBuilder`**

Replace `ctx.stringLiteral()?.let { return StringLit(stringBytes(it.text), Types.string) }` with `buildStringLiteral(it)`.

Implement `buildStringLiteral` by walking the parse-tree children, not `.text`:

- `LineStrText` / `MultiLineStrText` → decode raw content to literal bytes.
- `LineStrEscapedChar` / `MultiLineStrEscapedChar` → decode the escape; add `\$` → `$`.
- `LineStrRef` / `MultiLineStrRef` → `Value(VarRef(name.removePrefix("$").removeSurrounding("`"), Types.unit))`. The token's text includes the leading `$` (`FieldIdentifier : '$' Identifier`, `KotlinLexer.g4:309`).
- `lineStringExpression` / `multiLineStringExpression` → `Value(buildExpression(ctx.expression()))`.
- `MultiLineStringQuote` → literal quote bytes.
- A nested `lineStringLiteral` inside a multi-line literal → flatten its parts into the same list.

Merge adjacent literal parts and drop empty literals. If no `Value` remains, return `StringLit(mergedBytes, Types.string)` — this preserves the existing literal fast path and constant folding. Do not reject `${` anymore. Refactor `stringBytes` into a segment decoder that both the old plain-literal path and the template path use.

- [ ] **Step 5: Write the sema tests**

Create `StringTemplateTest.kt`:

```kotlin
private fun check(src: String): Ast.Program =
    TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))
```

Cover:

- Accepts `ubyte`, `byte`, `uword`, `word`, `bool`, `string`, `float`.
- Rejects `unit`: `fun g() {} fun f() { val s = "${g()}" }`.
- Rejects an array value and a function value.
- Rejects `null` / nullable-unit.
- Rejects NUL in a literal part: `fun f(n: ubyte) { val s = "a\0${n}" }`.
- Rejects a fixed part over 255:
  `val s = "${n}" + "a".repeat(256)` is not available in Wolin; write a 256-byte literal in the source followed by `${n}` and expect the message exactly `String interpolation is limited to 255 bytes`.
- Accepts a 300-byte plain literal (no value parts); it is still a `StringLit`.

- [ ] **Step 6: Implement the checker**

Add `is StringTemplate -> checkStringTemplate(expr)` to `checkExpr`. For each `Value`, call `checkExpr(part.expr, null)`; require the resulting kind to be one of `UBYTE`, `BYTE`, `UWORD`, `WORD`, `BOOL`, `STRING`, `FLOAT`. Otherwise throw:

```kotlin
SemaException("Unsupported construct: ${part.expr.type} in string interpolation")
```

Reject a `StringTemplate.Literal` containing a zero byte with `SemaException("NUL is not allowed in a string template")`. Compute the fixed maximum: literal bytes + per-type maxima (`ubyte` 3, `byte` 4, `uword` 5, `word` 6, `bool` 5, `float` 16; a `string` part contributes 0 because its length is runtime and capped). If it exceeds 255, throw `String interpolation is limited to 255 bytes`. Set `expr.type = Types.string`.

Remove the two interpolation-rejection tests from `UnsupportedTest`.

- [ ] **Step 6b: Teach every AST walker about templates**

A template contains arbitrary expressions, so every existing walk must descend into `StringTemplate.Value` parts:

- `TypeChecker.varRefs` (global-initialiser ordering) and its expression walker.
- `TypeChecker`'s label pre-scan (`visitLabelsInExpr`), so a label in an `if`/`when` expression branch inside `${}` is still collected.
- `ModuleEmitter.collectVarRefs` / `collectStmtVarRefs`, so a call to an imported function inside `${}` still produces the cross-module `declare`.
- `StmtEmitter.collectLocalDeclsInExpr`, so a local declared in an `if`-expression branch inside `${}` gets its `alloca`.
- `ConstFolder`: a template with value parts folds to `null`; an all-literal template is already a `StringLit`.

Add a test for the imported-call case in Task 2's IR tests (`import` a function, call it in `${...}`; the module must carry a `declare` for it).

- [ ] **Step 7: Run the parser/sema tests and the full suite**

Run: `./gradlew test --tests 'pl.qus.wolin.parser.StringTemplateGrammarTest' --tests 'pl.qus.wolin.sema.StringTemplateTest' --tests 'pl.qus.wolin.sema.UnsupportedTest'`
Expected: PASS. Then `./gradlew test`.
Expected: PASS; no golden IR has changed because no interpolation is emitted yet.

---

### Task 2: Emit buffers and integer/bool/string append helpers

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt` (`emit` :109, `emitStringLit` :161)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt` (`emitFile` :51, global/constant section)
- Modify: `src/main/kotlin/pl/qus/wolin/driver/DriverModule.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/driver/Main.kt` (`CompiledFile` use in `emitFiles` :206)
- Create: `src/main/kotlin/pl/qus/wolin/ir/InterpRuntime.kt`
- Create: `src/test/kotlin/pl/qus/wolin/ir/StringInterpIrTest.kt`
- Create: `src/test/kotlin/pl/qus/wolin/it/StringInterpTest.kt`
- Modify: `src/test/kotlin/pl/qus/wolin/it/ItSupport.kt` (add a range-returning runner)

**Interfaces:**
- Consumes: `Ast.StringTemplate` from Task 1; existing `IrBuilder.call`, `alloca`/GEP helpers, and the module constants list.
- Produces: `CompiledFile.usedInterpHelpers: Set<String> = emptySet()`; module globals `@__wolin_interp_<n> = private global [256 x i8] zeroinitializer`; calls to:

```llvm
declare i16 @__wolin_interp_append_string(ptr, i16, ptr)
declare i16 @__wolin_interp_append_ubyte(ptr, i16, i8)
declare i16 @__wolin_interp_append_byte(ptr, i16, i8)
declare i16 @__wolin_interp_append_uword(ptr, i16, i16)
declare i16 @__wolin_interp_append_word(ptr, i16, i16)
declare i16 @__wolin_interp_append_bool(ptr, i16, i1)
```

The first argument is the site buffer, the second the cursor, the return is the new cursor. All helpers stop at cursor 255.

- [ ] **Step 1: Write the IR shape tests**

Create `StringInterpIrTest.kt` using `emitIr` from `ItSupport`. Assert:

- `fun f(n: ubyte) { val s = "n=${n}" }` contains `@__wolin_interp_0 = private global [256 x i8] zeroinitializer`.
- It contains a call to `@__wolin_interp_append_string` for the literal and `@__wolin_interp_append_ubyte` for `n`.
- The final cursor gets a `store i8 0`.
- Two template sites produce `@__wolin_interp_0` and `@__wolin_interp_1`.
- A literal-only string has no `__wolin_interp` global.
- No `malloc` appears.
- A `bool` part calls `append_bool`; a `string` part calls `append_string`; `byte`/`uword`/`word` call the matching helper.

- [ ] **Step 2: Run the IR tests; they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.StringInterpIrTest'`
Expected: FAIL; templates currently reach no emitter (`ExprEmitter.emit` is not exhaustive for `StringTemplate`).

- [ ] **Step 3: Emit the site buffer and append sequence**

In `ExprEmitter`:

```kotlin
private val interpBuffers = IdentityHashMap<StringTemplate, String>()
private var nextInterp = 0
internal val helperDeclarations = LinkedHashSet<String>()
internal val usedInterpHelpers = LinkedHashSet<String>()
```

Every helper registration adds to both sets: `helperDeclarations` holds the `declare` line, `usedInterpHelpers` holds the bare helper name (`"__wolin_interp_append_ubyte"`, …). The driver module emits only the helpers in the union of `usedInterpHelpers`, plus their dependencies, so a program with no interpolation carries no runtime helper code.

Identity matters: two textually equal templates at different sites must not share a buffer.

For each site, register one global in the module constants:

```llvm
@__wolin_interp_0 = private global [256 x i8] zeroinitializer
```

and return the first-element GEP. Then:

```kotlin
var pos = IrValue("0", IrType.I16)
for (part in e.parts) when (part) {
    is StringTemplate.Literal -> {
        if (part.bytes.isNotEmpty()) {
            val ptr = stringPtr(part.bytes)   // refactored from emitStringLit
            pos = b.call(IrType.I16, "__wolin_interp_append_string", listOf(buf, pos, ptr))
            helperDeclarations += "declare i16 @__wolin_interp_append_string(ptr, i16, ptr)"
        }
    }
    is StringTemplate.Value -> {
        val v = emit(part.expr)
        val helper = when (part.expr.type.kind) {
            TypeKind.UBYTE -> "__wolin_interp_append_ubyte"
            TypeKind.BYTE -> "__wolin_interp_append_byte"
            TypeKind.UWORD -> "__wolin_interp_append_uword"
            TypeKind.WORD -> "__wolin_interp_append_word"
            TypeKind.BOOL -> "__wolin_interp_append_bool"
            TypeKind.STRING -> "__wolin_interp_append_string"
            TypeKind.FLOAT -> TODO in Task 3
            else -> error("checker allowed ${part.expr.type}")
        }
        pos = b.call(IrType.I16, helper, listOf(buf, pos, v))
        helperDeclarations += declarationFor(helper)
    }
}
val end = b.instr(IrType.Ptr, "getelementptr i8, ptr ${buf.name}, i16 ${pos.name}")
b.instr(IrType.Void, "store i8 0, ptr ${end.name}")
return buf
```

`emitStringLit` is refactored so `stringPtr(bytes)` registers/returns the `@str_<n>` constant and its GEP; both plain literals and template literal parts use it.

- [ ] **Step 4: Emit the helper declarations**

`ModuleEmitter.emitFile` appends `stmt.expr.helperDeclarations` after the global section and before the functions. Keep the existing constant/global ordering; declarations are separate lines. The file module must not declare helpers it never calls.

- [ ] **Step 5: Implement the integer/bool/string runtime helpers**

Create `InterpRuntime.kt` in `pl.qus.wolin.ir` (or `driver`) with one entry point:

```kotlin
fun emitInterpolationRuntime(
    b: IrBuilder,
    used: Set<String>,
    includeFloat: Boolean,
): String
```

It emits only the requested helpers and their dependencies (`append_byte` → `append_uword`, `append_bool` → `append_string`, `append_float` → `append_string` + canonicaliser). `append_string`:

```llvm
entry:
  %stop = icmp uge i16 %pos, 255
  br i1 %stop, label %done, label %check
check:
  %ch = load i8, ptr %src
  %nul = icmp eq i8 %ch, 0
  br i1 %nul, label %done, label %body
body:
  %dst = getelementptr i8, ptr %buf, i16 %pos
  store i8 %ch, ptr %dst
  %pos2 = add i16 %pos, 1
  %src2 = getelementptr i8, ptr %src, i16 1
  br label %check        ; phis carry pos/src
done:
  ret i16 %pos
```

Integer formatting uses **no division routine**: walk powers of ten `10000, 1000, 100, 10, 1`, repeatedly subtract the current power to obtain each digit, and skip leading zeros. `append_uword` handles 0 as the single digit `0`. `append_byte`/`append_word` render `-` first and then call the unsigned helper with the magnitude; for `-32768` compute `0 - v` in `i16` and read it unsigned. Every digit/sign store checks `pos < 255` first.

`append_bool` calls `append_string` with PETSCII `true` / `false` constants (`t` $54, `r` $52, `u` $55, `e` $45; `f` $46, `a` $41, `l` $4C, `s` $53, `e` $45). Use `PetsciiConverter` at compiler time or spell the escapes explicitly.

- [ ] **Step 6: Wire the driver and linker**

`ModuleEmitter` exposes the `usedInterpHelpers` of the file it just emitted; `Main.emitFiles` puts it into `CompiledFile.usedInterpHelpers`.

`DriverModule.emit` unions the files' helper sets and calls `emitInterpolationRuntime(b, used, includeFloat = false)`, adding its definitions to the driver module. The driver currently defines only `main`; helper definitions are additional functions in the same builder, before or after `main` (order does not matter). No file uses a helper: emit nothing. `Main.emitFiles` needs no float flag yet: Task 3 adds `usesFloatInterp`.

Add an IR test that a program with no interpolation has no `__wolin_interp_append_*` definition in the driver module, and a program with one template has exactly the helpers it uses plus dependencies.

- [ ] **Step 7: Write the VICE tests**

Add a `runProgramRange(source: String, start: Int, end: Int): ByteArray` helper to `ItSupport` (compile, run under `ViceRunner`, return the requested range). In `StringInterpTest`:

- Copy the template result into a fixed-address byte array at `$3000` and read it back:

```kotlin
var out: ubyte[ubyte]^0x3000
fun copyOut(s: string) {
    var i: ubyte = 0
    while (s[i] != 0) { out[i] = s[i]; i++ }
}
```

- Pin:
  - `"n=${n}"` for n = 0, 9, 10, 255 → `n=0`, `n=9`, `n=10`, `n=255`.
  - `byte` -128, 127; `word` -32768, 32767; `uword` 65535.
  - `bool` true/false.
  - A string part.
  - Nested `"a${"b${n}"}c"`.
  - Several parts in one template.
  - A 255-byte runtime string followed by `${n}`: output stops at the cap and does not wrap or overflow.
  - Same-site overwrite: a helper `fun f(n: ubyte): string { return "n=${n}" }`, `f(1)` then `f(2)`, copy the first result and observe `n=2` (documents the site-buffer contract).

- [ ] **Step 8: Run the task tests and the full suite**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.StringInterpIrTest' --tests 'pl.qus.wolin.it.StringInterpTest'`
Expected: PASS. Then `./gradlew test`.
Expected: PASS.

---

### Task 3: Float parts with a self-contained IR formatter

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/driver/DriverModule.kt` (`CompiledFile`, `emit`)
- Modify: `src/main/kotlin/pl/qus/wolin/driver/Main.kt` (`emitFiles`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt` (expose `usesFloatInterpolation`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt` (float helper)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/InterpRuntime.kt`
- Modify: `src/test/kotlin/pl/qus/wolin/ir/StringInterpIrTest.kt`
- Modify: `src/test/kotlin/pl/qus/wolin/it/StringInterpTest.kt`
- Modify: `src/test/kotlin/pl/qus/wolin/it/CliTest.kt` (float compile/run smoke test)

**Interfaces:**
- Consumes: Task 2's site buffers and helper layout; `TypeKind.FLOAT`; `ExprEmitter` returns an `IrType.Float` value from `emit`.
- Produces: `CompiledFile.usesFloatInterp: Boolean = false`; `ModuleEmitter.usesFloatInterpolation`; a driver-module helper:

```llvm
declare i16 @__wolin_interp_append_float(ptr, i16, float)
```

only when a file uses a float part. No linker flag and no external library.

- [ ] **Step 1: Write the failing IR and driver tests**

In `StringInterpIrTest`:

- A template with `${f}` (`f: float`) contains `@__wolin_interp_append_float` and a `declare ... @__wolin_interp_append_float(ptr, i16, float)`.
- A template with only integers/bools/strings contains no `append_float` and no `__wolin_float_digits` global.
- `DriverModule(IrBuilder()).emit(files)` with `usesFloatInterp = false` contains neither `append_float` nor `__wolin_float_digits`; with `true` it contains both. (`DriverModule` is directly constructible; `CompiledFile` may use a small fake module IR string.)
- No emitted module contains `snprintf`, `printf`, `malloc` or `-lprintf_flt`.

- [ ] **Step 2: Run the tests; they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.StringInterpIrTest'`
Expected: FAIL on the missing float helper and flag.

- [ ] **Step 3: Mark float use**

- `ExprEmitter` gets `var usesFloatInterpolation = false; private set` and sets it when it emits a float part.
- `ModuleEmitter` exposes it after `emitFile` (property or return value).
- `CompiledFile` gains `usesFloatInterp: Boolean = false`.
- `Main.emitFiles` passes `moduleEmitter.usesFloatInterpolation` into `CompiledFile`.

- [ ] **Step 4: Emit the float helper call**

In the `TypeKind.FLOAT` arm:

```kotlin
usesFloatInterpolation = true
pos = b.call(IrType.I16, "__wolin_interp_append_float", listOf(buf, pos, v))
helperDeclarations += "declare i16 @__wolin_interp_append_float(ptr, i16, float)"
usedInterpHelpers += "__wolin_interp_append_float"
```

The float helper depends on `append_string` (for `NaN`/`Infinity`), `append_char` and `append_uword` (exponent); include them in the dependency closure.

- [ ] **Step 5: Add the capped char writer and float scratch**

`i16 @__wolin_interp_append_char(ptr %buf, i16 %pos, i8 %ch)` stores `%ch` at `buf + pos` when `pos < 255`, returns `pos + 1`, otherwise returns `pos`. All float output goes through it.

Driver-module data, emitted only when the float helper is emitted:

```llvm
@__wolin_float_digits = private global [7 x i8] zeroinitializer
@__wolin_nan = private unnamed_addr constant [4 x i8] c"\CE\41\CE\00"
@__wolin_pos_inf = private unnamed_addr constant [9 x i8] c"\C9\4E\46\49\4E\49\54\59\00"
@__wolin_neg_inf = private unnamed_addr constant [10 x i8] c"\2D\C9\4E\46\49\4E\49\54\59\00"
```

Also declare `float @llvm.fabs.f32(float)` for the helper. No `printf`, `snprintf`, `malloc`, `memcpy` or `-lprintf_flt`.

- [ ] **Step 6: Implement `append_float`**

Signature `i16 @__wolin_interp_append_float(ptr %buf, i16 %pos, float %v)`.

1. **NaN**: `%isnan = fcmp uno float %v, %v`. If true, call `append_string(@__wolin_nan)` and return.
2. **Infinity**: `%abs = call float @llvm.fabs.f32(float %v)`; `%isinf = fcmp oeq float %abs, 0x7FF0000000000000` (a `float` constant). If true, write `-` when the sign bit of `%v` is set, then `append_string` with `@__wolin_neg_inf`/`@__wolin_pos_inf` and return.
3. **Zero**: `%iszero = fcmp oeq float %abs, 0.0`. If true, write `-` when the sign bit is set, then copy the literal `0.0` (`append_string`), and return. This gives `-0.0` for negative zero.
4. **Sign and magnitude**: remember the sign bit; `%m = forward` (fabs). Write `-` only after the format is known to fit (see step 10), so a truncated result never starts with a lone sign.
5. **Normalise**: `%e10: i16 = 0`. While `%m >= 10.0`, `%m = fdiv %m, 10.0` and `%e10++`. While `%m < 1.0`, `%m = fmul %m, 10.0` and `%e10--`. Do one final correction if rounding left `%m` outside `[1, 10)`.
6. **Extract 7 digits + rounding digit** into `@__wolin_float_digits`:
   - For `i = 0..6`: `%d = fptoui float %m to i8` (0..9); store at `digits[i]`; `%m = fmul float (fsub float %m, uitofp %d to float), 10.0`.
   - `%next = fptoui float %m to i8`.
   - If `%next >= 5`, add 1 from index 6 backwards with carry. If carry leaves index 0, set all digits to `0`, then `digits[0] = 1` and `%e10++`.
   - If a digit came out as 10 (normalisation edge), set it to 9 before continuing; the next digit extraction absorbs the remainder.
7. **Find the last nonzero digit** `%last` (0..6). If all digits are zero (only possible for a malformed zero path), set `digits[0] = 0`, `%last = 0`.
8. **Choose notation**: fixed when `-3 <= e10 <= 6`, scientific otherwise.
9. **Emit** using `append_char`, `append_uword` (exponent magnitude, no leading zeros) and `append_string` for the sign:
   - Fixed, `p = e10 + 1`:
     - `p <= 0`: `0.`, then `-p` zeros, then digits `0..last`.
     - `0 < p <= last`: digits `0..p-1`, `.`, digits `p..last`.
     - `p > last`: digits `0..last`, then zeros until `p` digits, then `.0`.
   - Scientific: `digits[0]`, `.`, digits `1..last` (or `0` if `last == 0`), `E` (PETSCII `$C5`), then `-` and `append_uword(-e10)` for a negative exponent or `append_uword(e10)` for a positive one.
   - Fixed `-3..6` and scientific outside that range match the documented rule: `0.001` is fixed, `1e-4f` is `1.0E-4`, `1e6f` is `1000000.0`, `1e7f` is `1.0E7`.
10. **Sign**: if the sign bit is set and `pos` can still grow, write `-` **before** the numeric text. The simplest implementation is to compute the numeric bytes into the site buffer first, then if `pos` advanced and sign is set, insert `-` at the original position and shift the numeric bytes right by one; the 255-byte cap makes the shift bounded. Alternatively write the sign first and accept that a full buffer may lose the sign; do not add a second scratch buffer for this. Pick one and pin it in a test.

All float output goes through the capped `append_char`, so a nearly full buffer truncates instead of overflowing.

- [ ] **Step 7: Write the VICE float tests**

In `StringInterpTest`, using the same `$3000` copy-out harness:

- `1.5f` → `1.5`
- `1.0f` → `1.0`
- `-2.25f` → `-2.25`
- `3.1415927f` → `3.141593`
- `1e6f` → `1000000.0`
- `1e7f` → `1.0E7`
- `1e-3f` → `0.001`
- `1e-4f` → `1.0E-4`
- `1e-5f` → `1.0E-5`
- `1e-20f` → `1.0E-20`
- `9.999999f` → `10.0` (carry across the digit boundary, still 7 digits)
- `0.1f` → `0.1`
- NaN from `0.0f / 0.0f` → `NaN`
- `1.0f / 0.0f` → `Infinity`, negative → `-Infinity`, `-0.0f` → `-0.0`
- A float part at the end of a full buffer truncates without corrupting memory.

Add a `CliTest` that compiles and runs a one-float program; it must link with no external library.

- [ ] **Step 8: Run the task tests and the full suite**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.StringInterpIrTest' --tests 'pl.qus.wolin.it.StringInterpTest' --tests 'pl.qus.wolin.it.CliTest'`
Expected: PASS. Then `./gradlew test`.
Expected: PASS, including the existing float arithmetic tests.

---

### Task 4: Reference, TODO, and regression pass

**Files:**
- Modify: `REFERENCE.md` (string/literal section, around lines 179-186)
- Modify: `TODO.md` (design-level item and landed section)
- Modify: `docs/superpowers/specs/2026-11-22-string-interpolation-design.md` (status)
- Modify: `docs/superpowers/plans/2026-11-22-string-interpolation.md` (check boxes as tasks land)

**Interfaces:**
- Consumes: the implemented behaviour from Tasks 1-3.
- Produces: user-facing documentation and the tracked TODO state.

- [ ] **Step 1: Document interpolation in `REFERENCE.md`**

Add a subsection next to **Strings**:

- Syntax: `${expr}` and `$name`; `\$` is a literal dollar; a `$` not followed by an identifier or `{` is literal.
- Allowed part types and output:
  - `ubyte`/`byte`/`uword`/`word` — decimal.
  - `bool` — `true`/`false`.
  - `string` — copied.
  - `float` — 7 significant digits, fixed notation for decimal exponents `-3..6`, `E` notation outside, `.0` on integral values, `NaN`/`Infinity`.
  - `char` does not exist; `'A'` is a `ubyte` and prints `65`.
- No heap: each template site has a 256-byte static buffer; the result is capped at 255 bytes; a runtime string is truncated at the cap.
- **Borrowed result:** the pointer is valid for the program, but the bytes are overwritten the next time the *same site* is evaluated (loop, recursive call, another call of the enclosing function). Distinct sites are independent. Copy if the result must be retained across a re-evaluation.
- Float interpolation is a self-contained formatter; it uses the compiler-rt soft-float routines float arithmetic already links and adds no C library.
- Example:

```kotlin
fun main() {
    var n: ubyte = 42
    var f: float = 1.5
    print("n=${n} f=${f}")   // n=42 f=1.5
}
```

- [ ] **Step 2: Update `TODO.md`**

Move the interpolation item to a landed section:

```markdown
### 2026-11-22 — string interpolation

Design: `docs/superpowers/specs/2026-11-22-string-interpolation-design.md`.
Plan: `docs/superpowers/plans/2026-11-22-string-interpolation.md`.

- [x] `${expr}` and `$name` for integers, `bool`, `string` and `float`.
- [x] One static 256-byte buffer per template site; 255-byte result cap; no heap.
- [x] Float via a self-contained IR formatter over compiler-rt soft-float; no C library.
- [x] Borrowed-result contract documented.
```

Remove the old unchecked item from the design-level list.

- [ ] **Step 3: Update the design status**

Change the spec header to `Status: implemented (first cut)` and link the plan. Record any deviation from this plan (expected: the exact float samples pinned in the tests).

- [ ] **Step 4: Run the full suite twice**

Run: `./gradlew test`
Expected: PASS.
Run again: `./gradlew test`
Expected: PASS (VICE ports and temp dirs are isolated; no flakiness).

- [ ] **Step 5: Check examples build**

Run: `make clean && make`
Expected: every `examples/*.prg` builds. No example is changed by this plan, and the link command is unchanged.

---

## Explicit non-goals

- Kotlin value semantics; the result is a borrowed site buffer.
- Kotlin's shortest-round-trip float string; this cut is 7 significant digits with fixed notation for decimal exponents `-3..6` and `E` notation outside.
- Constant folding of `${}` in global initialisers; a template with value parts there stays an error.
- A public formatting API or user-defined `toString`.
- Dynamic allocation, heap-backed strings, or a caller-supplied return-buffer ABI.
- A general `StringBuilder`.
