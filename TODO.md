# TODO — features not yet implemented

Wolin v2 (this compiler) implements the language surface shipped in
`REFERENCE.md`. Working target is v2. This list tracks v2 items plus known
gaps moved from the code audit (2026-11-15).

Sources: spec §3 (v1 non-goals), §12 (out of scope), the diagnostic
catalogue in `UnsupportedTest`, and a code audit of 2026-11-15.

## This cycle — landed

### 2026-11-22 — string interpolation

Design: `docs/superpowers/specs/2026-11-22-string-interpolation-design.md`.
Plan: `docs/superpowers/plans/2026-11-22-string-interpolation.md`.

- [x] `${expr}` and `$name` for integers, `bool`, `string` and `float`.
- [x] One static 256-byte buffer per template site; 255-byte result cap; no heap.
- [x] Float via a self-contained IR formatter over compiler-rt soft-float; no C library.
- [x] Borrowed-result contract documented.

### 2026-11-23 — explicit array counts and structs

- [x] Explicit array lengths: `T[N]` (a `ubyte` index) and
      `T[index, N]`. Without a length, the array keeps its index-domain
      sizing. A length is checked against the index domain, the 64 KiB
      address space, and a fixed base address.
- [x] `struct` declarations with scalar `var` fields. A struct is a packed
      byte blob; fields are read and written through `p.field` and
      `points[i].field`. No heap, constructors, copying, or struct values as
      arguments/returns. Methods live in `impl` blocks (see below).
- [ ] Struct field initialisers, struct/array fields, local arrays of
      structs without a fixed address, and nested member access.

### 2026-11-23 — traits and impls

- [x] `trait` declarations with method signatures and optional default
      bodies. No state and no generics.
- [x] `impl Type` for inherent methods and `impl Type : Trait` for trait
      implementations. Conformance is checked at compile time; a call on a
      concrete struct dispatches statically, with a hidden receiver pointer.
- [x] `this` in impl methods; fields through `this` are volatile, so a
      method works on a fixed-address hardware struct as well as on global,
      local and array-element storage.
- [x] Default bodies are copied per implementor and checked there; they may
      use that struct's fields and methods.
- [x] Dynamic traits: `Shape` is a 4-byte non-owning cell holding a data
      pointer and a vtable pointer. Structs coerce to a trait they implement;
      calls dispatch through a static 2-byte-per-method vtable. Trait arrays
      and parameters work; fixed-address structs work.
- [ ] Trait returns, trait struct fields, downcasting, generic trait bounds
      (`fun f<T: Shape>(x: T)`), associated types/constants, supertraits, and
      method overloading. Deliberately out of scope for now.

### 2026-11-21 — labels and labelled jumps

Design: `docs/superpowers/specs/2026-11-21-labels-design.md`.

- [x] A `name@` statement is a label: a named point of the function, visible
      forward and backward, function-local; it emits one basic block.
- [x] `break@name` / `continue@name` / `return@name` each compile to one
      `jmp` to the label's block — the name is intent, not mechanism;
      `return@<label>` keeps an interrupt handler's attribute (only the
      address form drops it).
- [x] The `if`/`when` expression phi names the block that produced each
      branch's value: a branch that leaves through a jump (a loop or a
      label) contributes no incoming, fixing the invalid
      `phi i8 [ 5, %if.then ]` IR.

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

### 2026-11-17 — kernal routines return a value

Design: `docs/superpowers/specs/2026-11-17-kernal-return-values-design.md`.

- [x] A fixed-address function can declare a return type; the wrapper
      reads the result from the named CPU register right after the ROM
      call. `ubyte` in `^CPU.A`/`^CPU.X`/`^CPU.Y`; `uword` in the pair
      `^CPU.AX` (A low, X high) or `^CPU.XY` (X low, Y high).
- [x] CPU register variables (`var x: T^CPU.REG`) are rejected by the
      checker in v1 — no register storage in v1; the register IR plumbing
      was reverted. Follow-up: re-land the accessors when register
      variables are wanted.
- [x] CPU flag variable (`var m: bool^CPU.I`, `var c: bool^CPU.C`) — a flag
      is a single bit of P, so it survives other code. A write lowers to
      the flag's set/clear instruction (`sei`/`cli` for I, `sec`/`clc` for
      C), a read to the bit test. Only C and I qualify — the 6502 has no
      `sen`/`sez` — so `CPU.N`/`CPU.Z`, like the full registers
      (`CPU.A`/`CPU.X`/`CPU.Y`), are rejected.

### 2026-11-16 — signed byte and conversions

Plan: `docs/superpowers/plans/2026-11-15-xor-signed-byte.md`.

- [x] `xor` operator (`a xor b`) — Task 1
- [x] Signed `byte` semantics: `byte` literals cover the full −128..127 range
      — Task 2
- [x] Explicit integer conversions `toByte()` / `toUbyte()` / `toWord()` /
      `toUword()` — Task 3
## Design-level v2 items (spec §3 and §12)

No plan yet. Each was deferred from v1 for the stated reason.

- [x] **Statements and control flow:** `break@` / `continue@` /
      `return@` with a *label* (the address form `return@0xea31` is v1),
      labels.
- [ ] **Exceptions** (`try` / `catch` / `throw`) — LLVM exception handling
      does not exist on the MOS backend (no unwinder); needs a hand-written
      runtime.
- [ ] **Classes and objects** — the packed struct layout, field
      addressing, methods, static trait dispatch, and dynamic trait
      dispatch now exist; the object model and constructors remain.
- [ ] **Capturing closures** — upvalue capture; on the 6502 the most
      expensive feature in the set. Non-capturing function literals are v1.
- [ ] **Array bounds checking and a dynamic allocator** — v1 arrays are
      fixed-address or global, with no bounds checks.
- [x] **Link map** in the output — the CLI note "(no link map in v1)". Fixed:
      every link writes `<output>.map` next to the `.prg`.

### Known gaps moved to v2 (code audit 2026-11-15)

Moved from the "Known gaps" section below; now v2 work items:

- [x] **Unrecognised declaration modifiers silently accepted.**
      `buildFunction` (`AstBuilder.kt`) now rejects every modifier except
      `interrupt` (`external fun`, `cc65 fun`, `suspend fun`, `infix fun`,
      `operator fun`, visibility modifiers); catalogue pinned in
      `UnsupportedTest`.
- [ ] **Property modifiers are silently accepted too.** `const val`,
      `lateinit var`, `open val` and friends parse and are ignored
      (`buildGlobal`/`buildLocal` read no modifiers).
- [x] **`when` expression without a match has no defined value.** A
      `when` *expression* with no matching branch and no `else` falls through
      with an undefined phi (`StmtEmitter.whenFlow`); the spec §6 wanted a
      compile error at the use site. Fixed: `checkWhenExpr` requires an
      `else` branch; statement-form `when` still falls through.
- [x] **A negated literal does not take the other operand's type.** A
      positive literal does (`w + 1` types the `1` as `word`), but `-1` is a
      `UnaryOp` typed by the smallest type holding it (`byte`), so
      `word == -1` is a type error while `word == 1` compiles. Found while
      landing the conversions. Fixed: a single-sign literal operand now takes
      the other operand's type (`isContextualIntLiteral`).
- [x] **A bitmask literal has to fit the signed range of its operand.**
      `word & 0xFF00` is rejected ("Value out of range") because the literal
      is typed with `fits` (the signed range) rather than `fitsWidth` (the
      bit-pattern range, which the bit-assignment operators already use).
      Workaround: a negative literal (`w & -256`).
      Fixed: `checkBinary` types a `&`/`|`/`xor` literal with `fitsWidth`
      against the other operand, so the full width is accepted.
- [x] **A local declared inside an `if` *expression* branch has no stack
      slot.** `var x = if (c) { var b: byte = -1; b } else { 0 }` aborts the
      compiler with `IllegalStateException: Local b has no slot`: the
      pre-scan (`StmtEmitter.collectLocalDecls`) reaches a `LocalDecl` only
      as a statement, so the declaration inside an expression-position
      `if` branch is never allocated. Pre-existing (reproduces on `main`);
      found while landing the conversions.
      Fixed: the local pre-scan now visits declaration initialisers, statement
      conditions, `when` subjects/conditions, and nested expression conditions.
- [x] **A bit-assignment from a wider integer emits invalid IR.** `x := g`
      with `x: ubyte` and `g: uword` emits `zext i16 %g to i8` (and
      `sext i16 %g to i8` for a signed `word`), which LLVM rejects — a
      `zext`/`sext` may not narrow. `ExprEmitter.widenIfNeeded` picks the
      direction from the source's signedness without checking the widths, so
      a narrowing bit-assignment takes the widening path. Pre-existing
      (reproduces on `main` with no conversion in the program); the
      conversions add one more route to it. The range check that would
      reject such a mask is `TypeChecker.maskLiteral`, which sees only a
      literal or a negated literal, so a non-literal mask skips it.
      Fixed: the emitter's `fitTargetWidth` truncates a wider mask to the
      target's low bits; narrower masks still extend.

## Known gaps found in the code audit (2026-11-15) — moved to v2

All 7 gaps moved to v2 above; source-strings conversion landed:

- [x] **Source strings are converted to PETSCII.** String literals are mapped
      through the 64tass Unicode → PETSCII table at compile time: uppercase
      letters go to the high range ($C1–$DA), lowercase to the low range
      ($41–$5A), punctuation and digits map directly. Unmapped bytes pass
      through unchanged, so escapes like `\n` and `\r` keep their meaning.

## Rejected today — diagnostic catalogue, no date

The grammar is the full Kotlin grammar, so almost everything parses; the
analyser rejects each construct with `Unsupported construct: <name>`. The
catalogue is pinned by `UnsupportedTest` so a silent acceptance fails CI.
Nothing below has a plan; several are unlikely to ever be implemented.

- **Types and declarations:** generics, inheritance, constructors, `vararg`,
      annotations, type aliases, nullable types (`T?`), `dynamic`, multiple
      variable declarations (`var x, y`), nested functions, anonymous
      functions, property accessors (`get` / `set`), delegated properties
      (`by`).
- **Expressions and operators:** `as` / `as?`, `is` / type tests, safe calls
      (`?`), elvis (`?:`), ranges and `in`, `!!`, callable references (`::`),
      collection literals, object literals, `this` / `super`, infix calls
      other than `xor` (`a and b`, `a shl b`, …).
- **Imports:** `import … as <alias>`.
- **Coroutines:** suspend.
- **Double:** double precision types
