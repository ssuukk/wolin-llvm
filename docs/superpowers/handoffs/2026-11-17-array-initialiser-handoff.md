# Handoff — Array Initialisers for Wolin C64 Compiler

## Goal

Implement **array initialisers** for the Wolin C64 compiler (`/home/qus/dev/_c64/wolin-new`): allow global/local fixed-address array declarations to be initialised from a `[v, v, …]` literal and from a string literal (for `ubyte`-element arrays). Design doc approved; implementation plan written; next action is execution.

## Constraints & Preferences

- Repo is Kotlin + ANTLR4 grammar (`KotlinParser.g4`), LLVM-IR text emitter, 6502 backend (VICE for integration tests).
- No new grammar production needed: `collectionLiteral` already exists (`KotlinParser.g4:487`); only an `NL*` tolerance extension is required (verified rule below).
- Prefix-fill semantics: element *i* of list → index *i*, remainder zero. No dynamic sizing, no repeat syntax, no array value semantics.
- Global array initialisers must be constant expressions (`ConstFolder`); reject `FunctionValue`/`NullValue` elements via existing "global initialiser must be a constant expression" error.
- Local fixed-address arrays: arbitrary runtime expressions, stored at function entry (matches existing local fixed-address scalar initialiser behaviour, `StmtEmitter.kt:180-184`).
- String initialiser: `ubyte`-element arrays only (any index type); string bytes + NUL, prefix fill.
- Array literal accepted **only** in declaration-initialiser position; rejected in assignment/call/expression positions ("Array literal can only initialise a declaration"); `var x = [1,2]` with no declared type → "Array literal needs an array type".
- Local non-fixed array initialiser → "local arrays need a fixed address in v1" (no stack-array storage model).
- No trailing comma (rejected in all forms, verified). `[]` legal = zero-init.
- Plan goes to `docs/superpowers/plans/2026-11-17-array-initialiser.md` (per repo convention).
- Commits via `eval` `commit` namespace (`d = await commit.draft(); await d.commit("subject", ["- body"])`), NOT `git add`/`git commit`.
- Execution approach: user must confirm subagent-driven vs native per `superpowers:writing-plans` skill handoff. **Recommendation: native** — tasks share interfaces (the `ArrayLiteral` node, `ArrayValue.spelling`, `checkArrayInit` signature) and the plan carries the full design.
- Follow `AGENTS.md` (in context): Think Before Coding / Simplicity / Surgical Changes / Goal-Driven Execution.

## Progress

### Done

- [x] Researched current state: array initialisation doubly blocked — `AstBuilder.kt:348` rejects grammar's `collectionLiteral` rule ("Unsupported construct: collection literal"), `ModuleEmitter.kt:104` throws "Global array X must be zero-initialised in v1". Verified `git status` is dirty with user's in-progress kernal-return-values work (untracked + modified files NOT related to this task; do not touch).
- [x] Laid out approaches (A: `[v,v,…]` literal; B: `arrayOf(…)` builtin; C: string initialiser complement). User: "A perfect" (A + C included; B rejected).
- [x] Wrote full design doc: `docs/superpowers/specs/2026-11-17-array-initialiser-design.md` — semantics (prefix fill, remainder zero, count ≤ index domain 256/65536, element typing to element type, constant elements for globals via `ConstFolder`/new `ConstValue.ArrayValue`, runtime elements for local fixed-address arrays), accepted/rejected position table, new diagnostics, per-file implementation shape, test plan.
- [x] Builder-check subagent (BuilderCheck-2) read doc + code: found one blocker (written `collectionLiteral` production failed one-element-per-line and was inconsistent on trailing commas); verified every other doc claim against the code.
- [x] Fixed the grammar rule and verified empirically against the actually generated parser (temp g4 edit + `./gradlew build -x test` + 6 probes; g4 reverted afterwards, rebuild clean). Final verified rule:

  ```
  collectionLiteral
      : LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE
      ;
  ```

  Probe matrix (all confirmed): `[1, 2, 3]` single-line → "Unsupported construct: collection literal" (parses, checker rejects — today's behaviour preserved); one-element-per-line `[\n 1, 2,\n 3\n]` → parses; trailing comma single-line `[1, 2, 3,]` → parse error (as today); trailing comma multi-line `[\n 1,\n 2,\n]` → parse error; `[]` and `[\n]` → parse. On newline-free input the rule is token-identical to today's, so no existing program changes behaviour.
- [x] Updated design doc §Syntax with the verified rule + probe matrix; fixed an earlier edit mistake that had cut into the kotlin syntax example block (rewrote the whole Syntax section cleanly, tag now #988B). Designer's call #2 updated to "rejected in every form (verified)".
- [x] User approved the design doc ("approved"), then "compact and continue" — continuation instruction to proceed to the writing-plans step.
- [x] Invoked `superpowers:writing-plans` skill; read full design doc; read all relevant source files to gather exact signatures.
- [x] **Wrote the implementation plan** `docs/superpowers/plans/2026-11-17-array-initialiser.md` — eight tasks, each with the five-step TDD loop (failing test → run → implement → run → commit). Self-review done inline at the bottom (spec coverage, step scan, type consistency, review focus, proportion).
- [x] Presented the plan to the user and asked for the execution approach (native vs subagent-driven); user requested a handoff to start a new session.

### In Progress

- [ ] User confirms execution approach (subagent-driven vs native) — writing-plans skill handoff. **Recommendation: native.**
- [ ] Execute the plan task by task, committing via `eval` `commit` namespace.

### Pending

- [ ] Execute Task 1: grammar — re-apply the verified `collectionLiteral` NL* extension to `src/main/antlr/KotlinParser.g4:487-489`; add `src/test/kotlin/pl/qus/wolin/parser/ArrayLiteralGrammarTest.kt`; rebuild (`./gradlew build -x test`); run the new test; commit.
- [ ] Execute Task 2: `Ast.kt` new `ArrayLiteral` node + `typealias`; `AstBuilder.kt:348` `buildCollection` replacing `unsupported("collection literal")`; test + commit.
- [ ] Execute Task 3: `TypeChecker.kt` — `arrayDomain` helper, `checkArrayInit` dedicated check in `checkGlobal` + the `LocalDecl` branch of `checkStmt`, `ArrayLiteral` case in `checkExpr`/`checkAssign`/`varRefs`; `ArrayInitTest.kt` with all position/count/string rejections; commit.
- [ ] Execute Task 4: `ConstValue.kt` new `ArrayValue` data class with `spelling`; `ConstFolder.kt` `fold` gains an `ArrayLiteral` case; `ConstFolderArrayTest.kt`; commit.
- [ ] Execute Task 5: `ModuleEmitter.kt:103-108` array branch spells constant array from `ArrayValue` (remove the throw at `:104`, keep `zeroinit` for empty); `GlobalInitEmitter.kt:34-39` un-skips `FixedArray` globals carrying an `ArrayValue` (emits volatile element stores via new `storeArray` helper); `StmtEmitter.kt:187-190` `FixedArray` branch emits per-element volatile stores via new `emitArrayInit` helper; `ArrayInitIrTest.kt`; commit.
- [ ] Execute Task 6: golden IR pair `src/test/resources/golden/array_init.wtk` + `.ll`; add `"array_init"` to `GoldenIrTest.kt:13` `@ValueSource`; commit.
- [ ] Execute Task 7: integration test `src/test/kotlin/pl/qus/wolin/it/ArrayInitTest.kt` (VICE, fixed-address array from list and from string, read back through the `^0x400` out-pattern); commit.
- [ ] Execute Task 8: docs — `REFERENCE.md` Arrays section (replace lines 183-196) + Diagnostics table (replace line 437, add the six new messages); `TODO.md` landed entry under `## This cycle — landed`; commit.
- [ ] Delete throwaway probe files `/tmp/p.wtk` / `/tmp/p.ll` (in /tmp, not tracked) — clean up at the end.

## Key Decisions

- **Grammar rule (verified)**: `LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE`. Trailing commas rejected in every form; multi-line allowed. Verified against the generated parser, probe matrix in design doc §Syntax. g4 is REVERTED to original; Task 1 re-applies this edit.
- **No new production**: extend the existing `collectionLiteral` rule only.
- **String init is a complement, not a second feature**: `StringLit` as an array declaration initialiser is bytes + NUL, `ubyte`-element arrays only.
- **`ConstValue.ArrayValue(elements, elementType, indexType)`** new data class in `ConstValue.kt`; `spelling` = full-domain LLVM constant-array body (`[i8 12, i8 12, …]`), each element prefixed with its IR type name spelled from the type kind (sema must not import `ir`), padded with per-type zero spellings (`0`, `0.0`, `false`).
- **`ConstFolder.fold`** gains an `ArrayLiteral` case returning `null` when any element is `null` or folds to `FunctionValue`/`NullValue`.
- **New AST node** `ArrayLiteral(elements: List<Expr>, type)` in `Ast.kt` under the `Expr` sealed interface, with a top-level `typealias ArrayLiteral = Ast.ArrayLiteral` next to the other aliases (`Ast.kt:220-249`).
- **`AstBuilder.kt:348`** — replace `ctx.collectionLiteral()?.let { unsupported("collection literal") }` with `ctx.collectionLiteral()?.let { return buildCollection(it) }`. `buildCollection` walks `ctx.expression()` (the NL tokens are skipped; `ctx.expression()` returns only the `expression` children).
- **`checkExpr` in `TypeChecker.kt:480`** gets an `ArrayLiteral` case that throws "Array literal can only initialise a declaration" (the generic position; declaration initialisers are checked BEFORE `checkExpr` is invoked on them, by dedicated code in `checkGlobal` and the `LocalDecl` branch of `checkStmt`).
- **New dedicated check `checkArrayInit`** in `TypeChecker.kt`: type the elements (each against the element type), check the count against the index domain (256 for `ubyte`/`byte` index, 65536 for `uword`/`word` index), convert a `StringLit` initialiser into byte elements. Wired into `checkGlobal` (before the generic `checkExpr`) and into the `LocalDecl` branch of `checkStmt` (line ~329).
- **Domain helper** `arrayDomain(indexType: WolinType): Int` in `TypeChecker.kt` (256 / 65536, mirroring `ModuleEmitter.arrayCount`).
- **`varRefs` walker** (`TypeChecker.kt:181`) learns `ArrayLiteral` so the "initialiser references a global that is not yet initialised" ordering error still fires inside lists.
- **`checkAssign`** (`TypeChecker.kt:634`) rejects an `ArrayLiteral` value ("Array literal can only initialise a declaration").
- **`StmtEmitter.kt:187-190`** — the `FixedArray` branch currently throws `error("fixed array locals take no initialiser")`; replace with emitting per-element volatile stores from the `ArrayLiteral`/`StringLit` initialiser at function entry (one store to `address + i × elementSize` per element, using `expr.emitStoreFixed(address, v, ity)` from `ExprEmitter.kt:424`).
- **`GlobalInitEmitter.kt:34-39`** — stop skipping `FixedArray` globals that carry an `ArrayValue`; emit the volatile element stores via a new `storeArray` helper. Scalar `FixedAddress` globals keep today's skip (the "adjacent gap" in the design doc — do NOT fix scalar case in this plan; it's flagged only).
- **`ModuleEmitter.kt:103-108`** — `emitGlobals` array branch: spell the constant array from `g.constValue as ArrayValue` (full-domain LLVM constant-array body); keep `zeroinit` for an empty list; remove the "must be zero-initialised in v1" throw at `ModuleEmitter.kt:104`.
- **Diagnostics** (new, added to REFERENCE.md table, replacing line 437):
  - `Array literal needs an array type` (no declared/expected array type, e.g. `var x = [1,2]`)
  - `Array literal can only initialise a declaration` (assignment / expression position)
  - `Too many array elements` (count + domain)
  - `String initialiser needs a ubyte element type`
  - `String initialiser too long` (length + domain)
  - `local arrays need a fixed address in v1`
  - Unchanged/reused: "Value out of range", "Attempt reassign X Y", "global initialiser must be a constant expression". The "Unsupported construct: collection literal" rejection is removed (NOT pinned by `UnsupportedTest` — verified).

## Critical Context

- **Repo state (do not `git add`):**
  - `REFERENCE.md`, `TODO.md`, `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`, `src/main/kotlin/pl/qus/wolin/ir/CpuAccessors.kt`, `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt`, `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt`, `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt`, `src/test/kotlin/pl/qus/wolin/ir/CpuAccessorTest.kt`, `stdlib/c64-kernal.wtk`, `stdlib/std.wtk` — all `M` (user's in-progress kernal-return-values work, UNRELATED; do not touch).
  - Untracked: `.codebase-memory/`, `docs/superpowers/specs/2026-11-17-array-initialiser-design.md` (THIS task's doc), `docs/superpowers/specs/2026-11-17-kernal-return-values-design.md` (user's other work), `examples/`, `src/test/kotlin/pl/qus/wolin/it/CpuRegisterTest.kt`, `src/test/kotlin/pl/qus/wolin/sema/FixedAddressFunctionTest.kt`, `stdlib/vic.wtk`, `docs/superpowers/plans/2026-11-17-array-initialiser.md` (THIS task's plan — new untracked file).
  - `src/main/antlr/KotlinParser.g4` is CLEAN (my probe edit was reverted via `git checkout -- src/main/antlr/KotlinParser.g4`).
  - `./gradlew build -x test` is green (last run: `BUILD SUCCESSFUL in 293ms`).

- **Exact file locations (all verified by reading this session):**
  - `src/main/antlr/KotlinParser.g4:487-489` — current `collectionLiteral` production:
    ```
    collectionLiteral
        : LSQUARE expression? (COMMA expression)* RSQUARE
        ;
    ```
    Replace with the verified rule above (NL* extension).
  - `src/main/kotlin/pl/qus/wolin/ast/Ast.kt:198` — `data class ArrayAccess(val array: Expr, val index: Expr, override var type: WolinType) : Expr`. Add `ArrayLiteral(elements: List<Expr>, type)` after this line. `typealias ArrayLiteral = Ast.ArrayLiteral` goes after `typealias ArrayAccess = Ast.ArrayAccess` at line 244.
  - `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:348` — `ctx.collectionLiteral()?.let { unsupported("collection literal") }` — replace with `ctx.collectionLiteral()?.let { return buildCollection(it) }`. Add a `buildCollection(ctx: KotlinParser.CollectionLiteralContext): Expr` helper near `buildArrayAccess` (~line 328): `ArrayLiteral(ctx.expression().map { buildExpression(it) }, Types.unit)`.
  - `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:329` (the `LocalDecl` branch of `checkStmt`), `:146` (`checkGlobal`), `:480` (`checkExpr` — add `ArrayLiteral` case throwing "Array literal can only initialise a declaration"), `:634` (`checkAssign` — add `ArrayLiteral` value rejection), `:181` (`varRefs` — add `ArrayLiteral` case walking elements), `:871` (`literalType` — already handles integer contextual typing; use it per-element with expected = element type), `:891` (`fits` — integer literal range check; reused by `literalType`).
  - `src/main/kotlin/pl/qus/wolin/sema/ConstValue.kt` — add `data class ArrayValue(elements: List<ConstValue>, val elementType: WolinType, val indexType: WolinType) : ConstValue` with `spelling` returning the full-domain LLVM constant-array body. Add private helpers `llvmName(t: WolinType)` and `zeroFor(t: WolinType)` in the same file (sema must not import `ir`).
  - `src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt:33-48` — `fold` `when` expression: add `is ArrayLiteral -> foldArray(expr, constSoFar, functionNames)` case; add `foldArray` private helper returning `ConstValue.ArrayValue` when every element folds and none is `FunctionValue`/`NullValue`, else `null`.
  - `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt:103-108` — array branch of `emitGlobals`; remove the throw at `:104`, spell the constant array from `g.constValue as ArrayValue` (keep `zeroinit` for empty).
  - `src/main/kotlin/pl/qus/wolin/ir/GlobalInitEmitter.kt:34-39` — the loop that skips `FixedArray`/`FixedAddress` globals; un-skip `FixedArray` globals carrying an `ArrayValue`, emit volatile element stores via a new `storeArray` helper. Keep the scalar `FixedAddress` skip unchanged (the "adjacent gap").
  - `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt:187-190` — the `FixedArray` branch of `emitLocalDecl`; replace `error("fixed array locals take no initialiser")` with per-element volatile stores via a new `emitArrayInit` helper. `expr.emitStoreFixed(address, v, ity)` at `ExprEmitter.kt:424` is the primitive (it does `store volatile <ty> <v>, ptr inttoptr(<addr>)`).
  - `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt:153-166` — `emitStringLit` for reference (registers `@str_<n>` in the global section; not directly reused but a model for how byte constants are spelled).
  - `src/main/kotlin/pl/qus/wolin/ir/TypeMapper.kt` — `TypeMapper.ir(type)` returns `IrType`; `IrType.llvmName()` returns the LLVM spelling (`i8`, `i16`, `i1`, `float`, `ptr`, …). Used at `ModuleEmitter.kt:107,118` etc.

- **Test files to model new tests on:**
  - `src/test/kotlin/pl/qus/wolin/parser/ArrayLiteralGrammarTest.kt` — NEW (Task 1): `ParserFacade(Diagnostics()).parse(src, "t.ktk")` + `diag.messages.isEmpty()` to check parse success/failure; six tests (single-line, multi-line, `[]`, multi-line `[]`, trailing-comma single-line rejected, trailing-comma multi-line rejected).
  - `src/test/kotlin/pl/qus/wolin/sema/ArrayLiteralBuildTest.kt` — NEW (Task 2): `AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!)` → `program.globals[0].init` is an `ArrayLiteral`; three tests (three elements, empty, multi-line).
  - `src/test/kotlin/pl/qus/wolin/sema/ArrayInitTest.kt` — NEW (Task 3): `TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))` shape; accepted/rejected positions, count/domain, element type mismatch, non-constant global element; ~15 tests.
  - `src/test/kotlin/pl/qus/wolin/sema/ConstFolderArrayTest.kt` — NEW (Task 4): `ConstFolder.fold(g.init!!, mapOf(g.name to ConstValue.IntValue(0, Types.ubyte)), emptyMap())` on a global's init; five tests (integer list, string, empty, non-constant element, spelling shape).
  - `src/test/kotlin/pl/qus/wolin/ir/ArrayInitIrTest.kt` — NEW (Task 5): `CompilerPipeline().compileToIr(src, "t.wtk")` + `out.contains(…)` assertions; six tests (data-section i8 array, i16 array, `zeroinit` for `[]`, fixed-address global volatile stores, local fixed-address volatile stores, string initialiser stores).
  - `src/test/resources/golden/array_init.wtk` + `array_init.ll` — NEW (Task 6): golden pair; add `"array_init"` to `GoldenIrTest.kt:13` `@ValueSource`.
  - `src/test/kotlin/pl/qus/wolin/it/ArrayInitTest.kt` — NEW (Task 7): `runProgram(source)` from `ItSupport.kt` (returns `$0400-$040F` bytes); two tests (list init, string init, read back via `^0x400` out-pattern).
  - `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt:18-28` — the "collection literal" entry is NOT here (confirmed by reading the whole class in a prior session), so removing `unsupported("collection literal")` does not break this test. **Verify with `grep "collection literal" src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt` before committing Task 3.**
  - `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt:10-245` — `typeChecker(src)`, `typeOf(src)`, `localTypeOf(src)` helpers; the new `ArrayInitTest.kt` follows this shape (copy the `assertRejected` pattern from `UnsupportedTest.kt`).

- **Diagnostics table to update** (`REFERENCE.md:427-437`): line 437 is `Global array X must be zero-initialised in v1` — remove it, add the six new messages from the design doc §Diagnostics.

- **TODO.md landed entry**: add a `### 2026-11-17 — array initialisers` section mirroring the `### 2026-11-17 — kernal routines return a value` entry at `TODO.md:10-21` (the kernal-return-values entry is uncommitted user work; mine is separate and dated the same).

- **The "adjacent gap" (flagged, NOT fixed in this plan):** scalar `FixedAddress` globals with initialisers are silently dropped today (`GlobalInitEmitter.kt:36` skips every `FixedAddress`/`FixedArray` global; `var irqVector: uword^0x0314 = 5` compiles but the init function comes out empty — verified against emitted IR; `REFERENCE.md:290` shows that form as if it worked). This plan un-skips ONLY `FixedArray` globals carrying an `ArrayValue`; scalar `FixedAddress` behaviour is left unchanged so existing programs' startup behaviour can't change.

## Next Steps

1. **Confirm execution approach** with the user: native (`superpowers:executing-plans`) or subagent-driven (`superpowers:subagent-driven-development`). My recommendation is **native** — the tasks share interfaces (the `ArrayLiteral` node, `ArrayValue.spelling`, `checkArrayInit` signature) and the plan carries the full design; the writing-plans skill says "cheapest and fastest" for a mid-tier session model.
2. **Execute the plan** task by task, committing via `eval` `commit` namespace (`d = await commit.draft(); await d.commit("subject", ["- body"])`), NEVER `git add`/`git commit`.
3. **Task order** (from the plan, dependency order):
   1. Grammar: re-apply the verified `collectionLiteral` NL* extension to `src/main/antlr/KotlinParser.g4:487-489`; add `src/test/kotlin/pl/qus/wolin/parser/ArrayLiteralGrammarTest.kt`; rebuild (`./gradlew build -x test`); run the new test; commit.
   2. `Ast.kt`: new `ArrayLiteral(elements: List<Expr>, type)` node + `typealias`; `AstBuilder.kt:348`: `buildCollection` replacing `unsupported("collection literal")`; add `src/test/kotlin/pl/qus/wolin/sema/ArrayLiteralBuildTest.kt`; commit.
   3. `TypeChecker.kt`: `arrayDomain` helper (256/65536), `checkArrayInit` dedicated check wired into `checkGlobal` + the `LocalDecl` branch of `checkStmt` (element typing, count check, `StringLit` → byte conversion), `checkExpr` `ArrayLiteral` case throwing "Array literal can only initialise a declaration", `checkAssign` `ArrayLiteral` value rejection, `varRefs` `ArrayLiteral` case; add `src/test/kotlin/pl/qus/wolin/sema/ArrayInitTest.kt`; commit.
   4. `ConstValue.kt` + `ConstFolder.kt`: new `ArrayValue` + `fold` `ArrayLiteral` case; add `src/test/kotlin/pl/qus/wolin/sema/ConstFolderArrayTest.kt`; commit.
   5. `ModuleEmitter.kt:103-108`: `emitGlobals` array branch spells the constant array from `ArrayValue` (remove the throw at `:104`, keep `zeroinit` for empty). `GlobalInitEmitter.kt:34-39`: un-skip `FixedArray` globals carrying an `ArrayValue`, emit volatile element stores via new `storeArray` helper. `StmtEmitter.kt:187-190`: `FixedArray` branch emits per-element volatile stores via new `emitArrayInit` helper. Add `src/test/kotlin/pl/qus/wolin/ir/ArrayInitIrTest.kt`; commit.
   6. Golden IR: create `src/test/resources/golden/array_init.wtk` + `.ll`; add `"array_init"` to `GoldenIrTest.kt:13` `@ValueSource`; commit.
   7. Integration: create `src/test/kotlin/pl/qus/wolin/it/ArrayInitTest.kt` (VICE, fixed-address array from list and from string, read back through the `^0x400` out-pattern); commit.
   8. Docs: `REFERENCE.md` Arrays section (replace lines 183-196) + Diagnostics table (replace line 437, add the six new messages); `TODO.md` landed entry; commit.
4. **After all tasks:** delete throwaway probe files `/tmp/p.wtk` / `/tmp/p.ll` (in /tmp, not tracked — clean up).

## Key Decisions (repeated for the successor, since these drive the whole plan)

- **Prefix fill, no repeat syntax** — the list is a prefix fill, not a length declaration; the array's size is still the index type's domain.
- **String init is `bytes + NUL`**, prefix fill, remainder zero, `ubyte`-element arrays only (any index type).
- **`[]` is legal** and identical to no initialiser.
- **Function-address elements in a global array initialiser** are rejected through the existing "global initialiser must be a constant expression" error.
- **No trailing comma** — rejected in every form (verified), matches today's single-line behaviour.
- **Multi-line literal** — `NL*` extension to the existing production, one-line isolated grammar change, precedented (binary literals `0b…`, `&`/`|` precedence are already Wolin-specific deviations from the Kotlin grammar; see `REFERENCE.md` "Precedence, highest to lowest" §118-149).
- **Local fixed-address array elements are runtime expressions** (NOT restricted to constants) — consistent with the existing local fixed-address scalar initialiser behaviour (`StmtEmitter.kt:180-184`).
- **The "adjacent gap"** (scalar `FixedAddress` global initialisers silently dropped) is FLAGGED, NOT FIXED, in this plan — fixing it is a separate decision and would change existing programs' startup behaviour.
