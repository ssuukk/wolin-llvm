# Array initialisers — design

Date: 2026-11-17. Status: awaiting approval.

## Intent

Wolin arrays are zero-initialised only; giving one values is a run of
`arr[i] = …` statements. Scene code is full of data — screen patterns,
sprite rows, LUTs, vectors — and it should be written as data, not as
code. This adds a way to initialise an array declaration with a list of
values, and (for byte arrays) with a string.

## Approaches

Three candidates were considered; the user picked A, with C included.

- **A (chosen) — `[v, v, …]` array literal.** The Kotlin grammar already
  has a `collectionLiteral` rule (`LSQUARE expression? (COMMA expression)*
  RSQUARE`, `KotlinParser.g4:487`); Wolin currently rejects it in the
  checker (`AstBuilder.kt:348`, "Unsupported construct: collection
  literal"). The rule needs no new grammar production — Wolin gives it
  exactly one meaning: array values (there are no collections).
- **B (rejected) — `arrayOf(…)` builtin.** `vararg` is on the reject
  list, so it cannot be real code; it would be a special-cased name in
  the checker and const folder. Verbose, Kotlin-flavoured, more
  machinery for the same result.
- **C (included, complement) — string literal as a `ubyte`-element array
  initialiser.** The old Wolin's `string` *was* `ubyte[ubyte]`.
  Restoring that identity also gives a fill idiom for free: a 40-character
  string fills 40 elements without any repeat syntax.

## Goals

- Initialise a global array (data section) from a constant list: static
  data, remainder zero.
- Initialise a global fixed-address array from a constant list: stores in
  the file's init function.
- Initialise a local fixed-address array from a list: stores at function
  entry (runtime, matching local fixed-address scalar initialisers).
- Accept a string literal as the initialiser of a `ubyte`-element array:
  the string's bytes plus the NUL terminator, remainder zero.
- `[]` (empty) is legal and means zero-init.

## Non-goals

- No local (stack) arrays: there is no storage model for them; an
  initialiser on a local non-fixed array is a compile error.
- No dynamic sizing: the array's size stays the index type's domain; the
  list is a prefix fill, not a length declaration.
- No array value semantics: no array-to-array assignment, no passing
  arrays as values, no array literal outside a declaration initialiser.
- No bounds checks (v1-wide rule).
- No repeat/fill syntax (e.g. `[0x20 × 40]`) — strings are the fill idiom.

## Constraints

- The grammar is the Kotlin grammar, with existing Wolin-specific
  extensions (binary literals `0b…`, `&`/`|` precedence); a minimal,
  isolated grammar tweak is acceptable, not the default.
- No implicit promotion: element literals are typed contextually to the
  element type; a non-literal element must have exactly the element type
  (existing kind check, "Attempt reassign X Y").
- Global initialisers must be constant expressions (`ConstFolder`); array
  elements follow the same rule.
- Element count may not exceed the index type's domain (256 / 65536) —
  the same domain the emitter uses (`ModuleEmitter.arrayCount`).
- Strings are NUL-terminated; a string initialiser occupies
  `bytes.length + 1` elements.
- A `ubyte[uword]` global is 64 KB today already; an initialiser changes
  no size.

## Syntax

```kotlin
var pattern: ubyte[ubyte] = [12, 12, 12, 13]          // global, data section
var screen: ubyte[ubyte]^0x0400 = [176, 176]          // global, fixed address
var w: uword[uword] = [0, 1024, 0x3000]               // 16-bit elements
var line: ubyte[ubyte] = "HI\r\n"                     // 4 bytes + NUL
var bg: ubyte[ubyte] = "                                "  // 40 spaces
var empty: ubyte[ubyte] = []                          // explicit zero-fill

fun main() {
    var local: ubyte[ubyte]^0x2000 = [a + 1, 7]       // local fixed address
    while (true) {}
}
```

**Grammar note (verified empirically).** `collectionLiteral` parses
single-line only: `= [` followed by a newline is a parse error today. The
rule is extended to tolerate newlines:

```
collectionLiteral
    : LSQUARE NL* expression? (COMMA NL* expression)* NL* RSQUARE
    ;
```

Verified against the generated parser:

| Form | Result |
|---|---|
| `[1, 2, 3]` (single line) | parses — as today |
| `[` NL `1, 2,` NL `3` NL `]` (one element per line) | parses |
| `[1, 2, 3,]` (trailing comma, single line) | parse error — as today |
| `[` NL `1,` NL `2,` NL `]` (trailing comma, multi-line) | parse error |
| `[]` and `[` NL `]` | parse |

The `NL*` groups match zero on newline-free input, so single-line parsing
is token-identical to today's rule: no existing program changes behaviour.
A trailing comma is rejected in every form, as today. Data tables are the
primary use case, and a 256-element list on one line is hostile. The rule
has exactly one use (in `atomicExpression`), the change only adds accepted
sequences, and it follows the precedent of the existing Wolin grammar
extensions (binary literals, `&`/`|` precedence).

## Semantics

- **Prefix fill.** Element *i* of the list goes to index *i*; the rest of
  the array stays zero. The list does not name the array's length.
- **Element typing.** Each element is checked with the element type as
  the expected type: integer literals type contextually (existing
  `literalType` rule; "Value out of range" on overflow); a non-literal
  must have exactly the element type (kind check → existing "Attempt
  reassign X Y").
- **Constant rule.** Global arrays (both storage kinds): every element
  must fold in `ConstFolder`; an element referencing an earlier global
  folds, as scalar global initialisers already do. An element that does
  not fold, or that folds to a function address, is the existing error
  "global initialiser must be a constant expression" (a function address
  is "never a constant in the IR" by the language's own definition).
  Local fixed-address arrays: elements are arbitrary expressions,
  evaluated and stored at function entry — exactly like local
  fixed-address scalar initialisers today.
- **String initialiser.** Only when the element type is `ubyte` (any
  index type). The string's bytes plus the NUL terminator, prefix fill,
  remainder zero. The bytes are the same bytes string literals use today
  (ASCII for now; the PETSCII-conversion known gap applies uniformly).
- **Count check.** List length (for a string: `bytes.length + 1`) must
  not exceed the index type's domain; a compile error otherwise.
- **Storage.**
  - Global, data section: the LLVM global becomes a constant array of the
    full domain — list first, remainder zero
    (`global [256 x i8] [i8 12, i8 12, i8 0, …]`). An empty list keeps
    today's `zeroinit` spelling. (A `uword`-indexed global spells all
    65536 elements in IR text; accepted — such arrays are 64 KB anyway.)
  - Global, fixed address: the file's init function
    (`__wolin_init_file_…`) stores each element to
    `address + i × elementSize` before `main` runs; element stores are
    volatile, like every other fixed-address store.
  - Local, fixed address: stores at function entry, one per element, same
    addressing; re-run on every entry, like local fixed-address scalar
    initialisers today.

## Where array literals are accepted

| Position | Result |
|---|---|
| Global array declaration initialiser | accepted (constant elements) |
| Global fixed-address array declaration initialiser | accepted (constant elements) |
| Local fixed-address array declaration initialiser | accepted (arbitrary expressions) |
| Local non-fixed array declaration initialiser | error — no local array storage in v1 |
| `var x = [1,2,3]` (no declared type) | error — the literal's type is not inferable |
| `arr = [1,2,3]` (assignment) | error — declaration initialisers only |
| Call argument, any other expression position | error — same message |
| String initialiser on a non-`ubyte`-element array | error |

## Diagnostics (new; added to the REFERENCE.md table)

| Message (shape) | Cause |
|---|---|
| `Array literal needs an array type` | no declared/expected array type (`var x = [1,2]`) |
| `Array literal can only initialise a declaration` | assignment or expression position |
| `Too many array elements` (with the count and the domain) | list longer than the index domain |
| `String initialiser needs a ubyte element type` | string initialiser on e.g. `uword[uword]` |
| `String initialiser too long` (with the length and the domain) | `bytes + 1 > domain` |
| `local arrays need a fixed address in v1` | array initialiser on a local non-fixed array |

Unchanged, reused: "Value out of range" (literal element out of range),
"Attempt reassign X Y" (element type mismatch), "global initialiser must
be a constant expression" (non-constant element, function-address
element). The "Unsupported construct: collection literal" rejection is
removed (it is not pinned by `UnsupportedTest`).

## Implementation shape

Left to the builder, in dependency order:

- `ast/Ast.kt` — new `Expr` node `ArrayLiteral(elements: List<Expr>,
  type)`.
- `sema/AstBuilder.kt:348` — build `ArrayLiteral` from
  `collectionLiteral` instead of `unsupported("collection literal")`.
- `sema/TypeChecker.kt` — a domain function (256 / 65536 by index kind,
  mirroring `ModuleEmitter.arrayCount`); a dedicated array-initialiser
  check in `checkGlobal` and in the `LocalDecl` branch of `checkStmt`,
  running before the generic `checkExpr`: types the elements, checks the
  count, converts a `StringLit` initialiser into byte elements; the
  generic `checkExpr` gets an `ArrayLiteral` case throwing "Array literal
  can only initialise a declaration"; `checkAssign` rejects an
  `ArrayLiteral` value; the `varRefs` walker learns `ArrayLiteral` so the
  "initialiser references a global that is not yet initialised" ordering
  error still fires inside lists.
- `sema/ConstValue.kt` + `sema/ConstFolder.kt` — new
  `ConstValue.ArrayValue(elements, elementType, indexType)`; its
  `spelling` is the full-domain LLVM constant-array body, each element
  prefixed with its IR type name spelled from the type's kind (sema must
  not depend on the `ir` package), padded to the domain with per-type
  zero spellings (`0`, `0.0`, `false`); `fold` gains an `ArrayLiteral`
  case returning `null` when any element is `null` or folds to
  `FunctionValue` / `NullValue`.
- `ir/ModuleEmitter.kt` — `emitGlobals` array branch: spell the constant
  array from `g.constValue as ArrayValue`; keep `zeroinit` for an empty
  list; remove the "must be zero-initialised in v1" throw
  (`ModuleEmitter.kt:104`).
- `ir/GlobalInitEmitter.kt` — stop skipping `FixedArray` globals that
  carry an `ArrayValue`: emit the volatile element stores at
  `address + i × elementSize`. Scalar `FixedAddress` globals keep today's
  behaviour (see the adjacent gap below).
- `ir/StmtEmitter.kt:187-190` — the `FixedArray` branch: emit per-element
  stores from the `ArrayLiteral` / `StringLit` initialiser; keep the
  crash-throw for a non-literal initialiser (the checker now prevents
  reaching it).
- `src/main/antlr/KotlinParser.g4:487` — the `collectionLiteral` newline
  extension.
- Docs — REFERENCE.md: the Arrays section (initialisers, the string
  form, the count rule, the accepted-position table) and the Diagnostics
  table; the line "Initialising a global array is an error in v1" is
  replaced. TODO.md: a landed entry.
- Tests —
  - sema/IR golden: data-section constant array; `uword` elements;
    init-function stores for a global fixed-address array; function-entry
    stores for a local fixed-address array; string initialiser; `[]`.
  - sema rejections: every row of the accepted-position table that is an
    error, with its message; count/domain; element type mismatch;
    non-constant global element.
  - parser: single-line and multi-line literals both parse.
  - integration (VICE, real 6502): a fixed-address array initialised from
    a list and from a string, read back through the existing `^0x400`
    out-pattern (the `ArrayTest.kt` shape).

## Adjacent gap (flagged, not fixed)

A **scalar** fixed-address **global** with an initialiser is silently
dropped today: `var irqVector: uword^0x0314 = 5` compiles, but the init
function comes out empty (`GlobalInitEmitter.kt:36` skips every
`FixedAddress` / `FixedArray` global; verified against emitted IR).
REFERENCE.md shows exactly this form (`var irqVector: uword^0x0314 = 0`)
as if it worked. This feature deliberately un-skips only `FixedArray`
globals carrying an array initialiser; scalar behaviour is left alone so
the change cannot alter the startup behaviour of existing programs.
Fixing the scalar case is a separate decision.

## Designer's calls (review while reading)

1. **Multi-line literal** — extend `collectionLiteral` to tolerate
   newlines (one-line, isolated, precedented grammar change). Without it,
   a 256-element table is one line.
2. **No trailing comma** — rejected in every form (verified); matches
   today's single-line behaviour; cheap to add later if wanted.
3. **Local fixed-address array elements are runtime expressions** (not
   restricted to constants) — consistent with local fixed-address scalar
   initialisers.
4. **String initialiser on any index type with a `ubyte` element**
   (`ubyte[ubyte]` and `ubyte[uword]`), not only `ubyte[ubyte]`.
5. **`[]` is legal** and identical to no initialiser.
6. **A function-address element in a global array initialiser** is
   rejected through the existing constant-expression error.
