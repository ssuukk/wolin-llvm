# TODO — features not yet implemented

Wolin v1 (this compiler) implements the language surface in `REFERENCE.md`.
This list is what remains. Sources: spec §3 (v1 non-goals), §12 (out of
scope), the diagnostic catalogue in `UnsupportedTest`, and a code audit of
2026-11-15.

## This cycle — landed

Implemented 2026-11-16; plan: `docs/superpowers/plans/2026-11-15-xor-signed-byte.md`.

- [x] `xor` operator (`a xor b`) — Task 1
- [x] Signed `byte` semantics: `byte` literals cover the full −128..127 range
      — Task 2
- [x] Explicit integer conversions `toByte()` / `toUbyte()` / `toWord()` /
      `toUword()` — Task 3

## Design-level v2 items (spec §3 and §12)

No plan yet. Each was deferred from v1 for the stated reason.

- [ ] **Exceptions** (`try` / `catch` / `throw`) — LLVM exception handling
      does not exist on the MOS backend (no unwinder); needs a hand-written
      runtime.
- [ ] **Classes and objects** — needs a struct layout, an object model, and a
      dispatch decision.
- [ ] **Capturing closures** — upvalue capture; on the 6502 the most
      expensive feature in the set. Non-capturing function literals are v1.
- [ ] **`double`** — `float` is single-precision IEEE-754; `double` would need
      a second soft-float ABI.
- [ ] **Wozniak-FFP ↔ IEEE-754 conversion helper** — old Wolin `float` data is
      not byte-compatible with v1; converting old data is a v2 convenience.
- [ ] **Array bounds checking and a dynamic allocator** — v1 arrays are
      fixed-address or global, with no bounds checks.
- [ ] **String interpolation** (`${...}`) — backslash escapes are supported;
      templates are rejected.
- [ ] **Link map** in the output — the CLI note "(no link map in v1)".
- [ ] **Debugger.**
- [ ] **Other MOS targets** — only `c64`.
- [ ] **Performance tuning** of the emitted code beyond what LLVM does by default.

## Known gaps found in the code audit (2026-11-15)

- [ ] **Unrecognised declaration modifiers are silently accepted.**
      `buildFunction` (`AstBuilder.kt:82-83`) reads only the `interrupt`
      modifier; `external fun`, `cc65 fun`, `suspend fun`, `infix fun`,
      `operator fun` and visibility modifiers compile as if absent. The
      diagnostic catalogue (spec plan Task 9) lists `cc65 fun` and
      `external fun` as rejects, but nothing rejects them today. Decide:
      reject all unrecognised modifiers, or support `cc65 fun` (inline 6502)
      and `external fun`.
- [ ] **Property modifiers are silently accepted too.** `const val`,
      `lateinit var`, `open val` and friends parse and are ignored
      (`buildGlobal`/`buildLocal` read no modifiers).
- [ ] **`when` expression without a match has no defined value.** A
      `when` *expression* with no matching branch and no `else` falls through
      with an undefined phi (`StmtEmitter.whenFlow`); the spec §6 wanted a
      compile error at the use site.
- [ ] **A negated literal does not take the other operand's type.** A
      positive literal does (`w + 1` types the `1` as `word`), but `-1` is a
      `UnaryOp` typed by the smallest type holding it (`byte`), so
      `word == -1` is a type error while `word == 1` compiles. Found while
      landing the conversions.
- [ ] **A bitmask literal has to fit the signed range of its operand.**
      `word & 0xFF00` is rejected ("Value out of range") because the literal
      is typed with `fits` (the signed range) rather than `fitsWidth` (the
      bit-pattern range, which the bit-assignment operators already use).
      Workaround: a negative literal (`w & -256`).
- [ ] **A local declared inside an `if` *expression* branch has no stack
      slot.** `var x = if (c) { var b: byte = -1; b } else { 0 }` aborts the
      compiler with `IllegalStateException: Local b has no slot`: the
      pre-scan (`StmtEmitter.collectLocalDecls`) reaches a `LocalDecl` only
      as a statement, so the declaration inside an expression-position
      `if` branch is never allocated. Pre-existing (reproduces on `main`);
      found while landing the conversions.

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
- **Statements and control flow:** `for` loops, `break@` / `continue@` /
      `return@` with a *label* (the address form `return@0xea31` is v1),
      labels, `suspend`.
- **Imports:** `import … as <alias>`.
