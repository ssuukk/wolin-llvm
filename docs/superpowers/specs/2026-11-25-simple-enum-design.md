# Simple enums — design

Date: 2026-11-25. Status: awaiting approval (named-enum revision).

## Intent

Wolin code needs named 8-bit states for C64 hardware and program logic:
`PENDING`, `RUNNING`, `ERROR`. Kotlin's `enum class` brings objects,
constructors and method dispatch; none of that belongs on a 6502.

This feature adds C-style enums backed by `ubyte`, in two forms:

```kotlin
enum { PENDING = 0, RUNNING = 5, ERROR = 255 }   // anonymous: bare constants
enum Status { PENDING, RUNNING = 5, ERROR = 255 } // named: Status.PENDING
```

Every entry is a compile-time `ubyte` value and occupies no storage. A
named enum also supplies a type name usable as a `ubyte` alias:

```kotlin
var s: Status = Status.RUNNING
var u: ubyte = Status.RUNNING   // same type; no conversion
```

This is the TODO item "Simple `enum` (not Kotlin `enum class`) representing
an ubyte value, either autonumber or with values", widened to named enums.

## Approaches

- **A — anonymous constants only.** `enum { … }` entries are bare `ubyte`
  constants. Matches the TODO syntax, no type machinery.
- **B (chosen) — A plus named ubyte aliases.** `enum Status { … }` adds a
  qualified form `Status.PENDING` and a type name `Status` that resolves to
  `ubyte`. Named entries are **not** bare constants, so two enums can reuse
  `ERROR`. Reuses the existing integer type rules; no new `TypeKind`.
- **C (rejected) — nominal enum type.** `Status` is a distinct type;
  `Status.PENDING` has type `Status`; assignment to/from `ubyte` needs
  conversions. Stronger typing, but adds `TypeKind.ENUM`, `sizeOf`,
  `sameType`, IR mapping, conversion rules, array/field/parameter rules,
  and makes hardware interop noisy. Not requested; remained a non-goal.

## Goals

- Parse both forms at file scope, with newlines and an optional trailing
  comma: `enum { … }` and `enum Status { … }`.
- Autonumber: first entry is `0`; each entry without a value is the previous
  value + 1; an explicit value resets the counter.
- Explicit values are integer literals in `0..255` (decimal, hex, binary).
- Anonymous entries are bare `ubyte` constants: `RUNNING`.
- Named entries are qualified `ubyte` constants: `Status.RUNNING`.
- A named enum's name is a type alias for `ubyte`: `var s: Status`,
  `Status` in arrays (`Status[ubyte]`), parameters and returns.
- No storage and no runtime load: uses are inline `i8` constants.
- Visibility matches other declarations: same package automatically,
  `import pkg.Status` for a named enum, `import pkg.*` for a package.
- Real-6502 verification for a named `when`, and for an imported named
  enum across two modules.

## Non-goals

- No `enum class`.
- No strict nominal type: `Status` **is** `ubyte` after resolution, so
  `var s: Status = 5` and `var u: ubyte = Status.PENDING` are accepted.
  A value from one named enum can be assigned to another named enum; both
  are `ubyte`. If nominal typing is wanted later, it is approach C.
- No bare entries for named enums: `RUNNING` outside the enum is an unknown
  variable; write `Status.RUNNING`.
- No local (function-scope) enums.
- No non-literal values (`A = 1 + 1`, `A = OTHER`), no negative values.
- No enum-typed struct fields or trait/impl method signatures: those types
  are resolved before a file's enum names are known. Ordinary globals,
  locals, function parameters, returns, and arrays are supported.
- No `when` exhaustiveness checking; the existing `when` rules apply.
- No new arithmetic or bitwise rules; enum values follow `ubyte` rules.

## Syntax

```kotlin
enum { PENDING = 0, RUNNING = 5, ERROR = 255 }   // anonymous
enum { A, B, C }                                 // 0, 1, 2
enum Status { PENDING, RUNNING = 5, ERROR = 255 } // named
enum {
    IDLE,
    BUSY = 0x10,
    DONE,
}
```

Grammar additions (top level only):

```
topLevelObject
    : classDeclaration
    | enumDeclaration
    | ...
    ;

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

`enum class` keeps parsing as `classDeclaration` (`ENUM` is a
`classModifier`) and stays rejected by the AST builder. `enum` followed by
an identifier and no `{` is a parse error.

## Semantics

### Common value rules

- The AST builder computes values, because explicit values are restricted
  to integer literals. It keeps `next`: `0` initially; an explicit literal
  sets the entry's value; `next` becomes value + 1.
- A duplicate entry name in one enum, a non-literal value, and a value or
  autonumber above 255 are compile errors.
- Every entry is a compile-time `ubyte`; values `0..255`.

### Anonymous enums

- Each entry becomes a `GlobalDecl` with declared type `ubyte`, a literal
  initialiser, `isVal = true`, and `enumValue = <value>`.
- Visibility follows globals: the declaring file, same-package files, and
  `import pkg.NAME` / `import pkg.*`.
- The declaration occupies no storage: it is skipped by the module and init
  emitters, and inlined at every use.

### Named enums

- The declaration is an `Ast.EnumDecl(name, entries)` in `Program.enums`
  and `FileScope.enums`.
- `Status` is visible as a type name and as the receiver of
  `Status.<entry>`; it is not a value on its own.
- `Status.PENDING` is checked as an enum-entry access: the entry's value,
  type `ubyte`. Unknown entry names are errors.
- `Status` in a type position resolves to `ubyte`. The checker normalises
  the type (`kind = UBYTE`, `structName = null`), so `Status` and `ubyte`
  are identical for assignment, comparison, `when`, arrays and calls. A
  global's declared type is resolved before its initialiser is checked, so
  `var s: Status = Status.PENDING` compares `ubyte` with `ubyte`.
- Named entries are not bare constants; `PENDING` alone does not resolve.
- Visibility matches structs/traits: same package automatically; an
  explicit import (`import lib.Status`) or a wildcard package import brings
  the enum name into scope. Importing an entry directly
  (`import lib.Status.PENDING`) is not supported.
- An enum name colliding with a built-in type, `CPU`, a struct or a trait
  is an error.

### Emission

- `ExprEmitter.emitVarRef` inlines a resolved anonymous entry.
- `ExprEmitter.emitMemberAccess` inlines a resolved named entry.
- `ModuleEmitter.emitGlobals`, `ModuleEmitter.emitForeignGlobals` and
  `GlobalInitEmitter.emitFile` skip anonymous enum declarations, so no slot
  is allocated.
- `ConstFolder` folds `Status.ENTRY` as an integer constant; a global
  initialised from an entry is static data.
- `referencesAnotherEntity` takes `program.resolved` and treats an
  anonymous enum-entry `VarRef` as a literal; a named entry is a
  `MemberAccess`, already literal.

## Diagnostics (new; added to REFERENCE.md)

| Message | Cause |
|---|---|
| `Duplicate enum entry X` | two entries of one enum share a name |
| `Enum value must be an integer literal` | `= 1 + 1`, `= OTHER`, a character, etc. |
| `Enum value out of range` | an explicit value or autonumber outside `0..255` |
| `Duplicate enum X` | two visible named enums share a name |
| `Enum name X is reserved` | the name is a built-in type or `CPU` |
| `Unknown enum entry X in Y` | `Y.X` names no entry of enum `Y` |
| `Enum Y is a type, not a value` | `Y` used where a value is expected |
| `Cannot assign to enum entry Y.X` | assignment to an entry |

## Implementation shape

- **Grammar:** `KotlinParser.g4` — one top-level alternative and three rules
  (optional name).
- **AST:** `EnumDecl(name: String?, entries: List<EnumEntry>, line)`,
  `EnumEntry(name, value, line)`; `Program.enums`, `FileScope.enums`;
  `GlobalDecl.enumValue: Int?`; `MemberAccess.enumValue: Int?`.
- **Builder:** `AstBuilder.build` accepts `enumDeclaration`; anonymous
  entries become `GlobalDecl`s, named ones an `EnumDecl`.
- **Plumbing:** `ProgramBuilder`, `Main.emitFiles`,
  `TypeChecker.singleFileScope` carry `enums`.
- **Checker:** `Symbol.EnumSymbol`; `enumTypes` per file; `visibleEnums`
  (same package + imports); `resolveType` alias normalisation; entry access
  in `checkMemberAccess`; errors in `checkVarRef`/`checkAssign`;
  `ImportResolver.resolveEnum`; `ConstFolder` handles `MemberAccess`.
- **Emitter:** inline paths in `ExprEmitter`; storage skips in
  `ModuleEmitter`/`GlobalInitEmitter`; `referencesAnotherEntity` consults
  resolved symbols.
- **Docs/tests:** REFERENCE declarations and diagnostics, README, TODO,
  parser/sema/IR tests, VICE tests, one two-module VICE run.

## Constraints

- Grammar remains the Kotlin grammar plus the existing Wolin extensions
  (`struct`, `trait`, `impl`, labels). One isolated addition.
- No new `TypeKind`; named enums resolve to the existing `ubyte` type.
- The 6502 target still sees `i8` values; nothing is added to the runtime.
- ANTLR sources regenerate through `./gradlew generateParser` (implicit on
  `compileKotlin`).
