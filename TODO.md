# TODO — features not yet implemented

Wolin v2 (this compiler) implements the language surface shipped in
`REFERENCE.md`. Working target is v2. This list tracks v2 items plus known
gaps moved from the code audit (2026-11-15).

Sources: spec §3 (v1 non-goals), §12 (out of scope), the diagnostic
catalogue in `UnsupportedTest`, and a code audit of 2026-11-15.

## This cycle

- [x] Simple `enum` (not Kotlin `enum class`) representing an ubyte value,
      either autonumber or with values like: `enum { PENDING = 0, RUNNING =5, ERROR = 255}`
      — [design](docs/superpowers/specs/2026-11-25-simple-enum-design.md),
      [plan](docs/superpowers/plans/2026-11-25-simple-enum.md)
- [ ] Struct field initialisers, struct/array fields, local arrays of
      structs without a fixed address, and nested member access.
- [ ] Trait returns, trait struct fields, downcasting, generic trait bounds
      (`fun f<T: Shape>(x: T)`), associated types/constants, supertraits, and
      method overloading. Deliberately out of scope for now.

## Design-level v2 items (spec §3 and §12)

No plan yet. Each was deferred from v1 for the stated reason.

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

### Known gaps moved to v2 (code audit 2026-11-15)

Moved from the "Known gaps" section below; now v2 work items:

- [ ] **Property modifiers are silently accepted too.** `const val`,
      `lateinit var`, `open val` and friends parse and are ignored
      (`buildGlobal`/`buildLocal` read no modifiers).

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
