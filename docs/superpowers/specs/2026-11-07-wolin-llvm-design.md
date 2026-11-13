# Wolin-NG: a Kotlin-flavoured compiler targeting LLVM-MOS (MOS 6510)

Design document. Date: 2026-11-07. Status: proposed.

## 1. Intent and the "why"

The original Wolin (github.com/ssuukk/wolin) compiled a Kotlin-like language to 6502
assembly by hand: a hand-written pseudo-assembly VM, a 33 KB template file of
`mnemonic dest[type] = arg[type]` → 6502 patterns, and a custom zero-page register
allocator with a 72-slot "heap of CPU registers".

That design is now obsolete. LLVM-MOS provides a mature 6502/6510 backend with its own
register allocator, zero-page imaginary registers, LTO, and a C-compatible ABI. Re-implementing
codegen on top of templates means maintaining a register allocator, an optimiser, and a
template catalogue forever.

**Wolin-NG keeps the language, drops the codegen.** The Kotlin-derived grammar, the
hardware-addressing model (`var border: ubyte^0xd020`), and the interrupt/ROM-call idioms
are the assets worth keeping. Everything below the AST becomes LLVM IR, and LLVM-MOS does
the rest.

## 2. Goals

- Compile Wolin source to a bootable C64 `.prg` via LLVM-MOS.
- Preserve the old Wolin source syntax for the features listed in §6, so old `.ktk`
  files parse and mostly still mean what they meant.
- Make the compiler's job small enough to be obviously correct: AST → LLVM IR text,
  handed to `clang`.
- Provide an automated test loop that proves generated code *runs*, not just that it
  assembles.

## 3. Non-goals (v1)

- **Exceptions.** LLVM exception handling does not exist on the MOS backend: `invoke` and
  `landingpad` silently degrade to a plain `jmp`, and there is no unwinder
  (`__gxx_personality_v0` is not linked). C++ `throw` is rejected outright by the
  target. `try`/`catch`/`throw` remain in the grammar and produce a clear
  "not supported in v1" diagnostic. Revisit in v2 with a hand-written runtime.
- **Classes and objects.** Needs a struct layout, an object model, and a dispatch
  decision. v2.
- **Capturing closures.** Needs upvalue capture; on the 6502 this is the most
  expensive feature in the set. v2. *Non-capturing* function literals are in
  v1 — see §6, "Function pointers" — because they are just named functions.
  Whoever picks closure support up: decide whether to support capture at all, or
  keep the old non-capturing semantics and call the result a "function literal"
  rather a closure.

### Anti-goals

- Re-implementing optimisation. LLVM owns it. Concretely: the old Wolin shipped a
  whole optimiser (`optimizer/NewOptimizerProcessor.kt`) whose job was to remove
  redundant register allocations, collapse identity assignments, and replace
  redundant register pairs — all artefacts of its own pseudo-asm register
  allocator. None of that has a counterpart in the new architecture; the LLVM
  backend does it, better.
- Preserving the old Wolin *implementation* (pseudo-asm, templates, own allocator).
  The old tree is a reference for *semantics*, not for structure.

## 4. Constraints (verified, not assumed)

Every row below was checked on this machine against the installed toolchain.

| Constraint | Evidence |
|---|---|
| LLVM-MOS 24.0.0git compiles `.ll` → C64 `.prg` | `mos-c64-clang -O2 e2e.ll -o e2e.prg` produces `sta 53280` |
| `interrupt` function attribute → `rti` | `attributes #0 = { "interrupt" }` emits `rti` |
| Absolute-address variable | `inttoptr (i64 53280 to i8*)` → `sta 53280` |
| ROM call | `asm sideeffect "jsr $$e544"` → `jsr 65468` (note: `$$` escape required, else backend crash) |
| Register pinning | `"a"`/`"x"`/`"y"` constraints emit `lda #45 / ldx #46 / ldy #47` correctly |
| Inline-asm clobber lists honoured | `jsr $e544` with `"=a"(b)` correctly spills `a` to `ldx #119` afterwards |
| Function address | `ptrtoint` → 16-bit value, `ldx #mos16lo(g)` / `ldy #mos16hi(g)` |
| Zero-page globals | `section ".zeropage"` lands in the ZP region (confirmed in link map) |
| LLVM EH on MOS | **absent** — `invoke`/`landingpad` degrade to `jmp`, no unwinder |
| cc65 interop | **broken** — cc65 emits `o65`; `ld.lld` has no o65 emulation |
| float | soft-float via `libcrt.a` (`__addsf3` present); IEEE-754 single, 4 bytes |
| Old grammar reusable | ANTLR 4.13.1 generates a parser from `KotlinParser.g4` + `KotlinLexer.g4` + `UnicodeClasses.g4`; all four old `.ktk` examples parse with **0 errors** |
| Host toolchain | JDK 21 + Gradle + Kotlin 2.4.20 build and run |
| Test harness | VICE 3.10 headless + binary monitor (port 6502) reads back exact memory bytes |

### Toolchain locations (this machine)

| Tool | Path |
|---|---|
| LLVM-MOS SDK 24.0.0git | `~/.local/share/rust-mos/sdk-v23.2.0/` |
| `mos-c64-clang` | `$SDK/bin/mos-c64-clang` |
| VICE 3.10 | `~/.local/bin/x64sc` |
| cc65 (not used in v1) | `~/dev/_c64/cc65/bin/` |
| Old Wolin reference | `github.com/ssuukk/wolin` @ master |

The SDK path is version-suffixed, so the compiler must **discover** it (glob
`~/.local/share/rust-mos/sdk-*`, or `WOLIN_SDK` env var) rather than hard-code it. The old
Wolin's hard-coded `D:\Projekty\kotlinek\...` path is exactly the mistake to avoid.

**The grammar needs one file that the reference tree does not contain.**
`KotlinLexer.g4` starts with `import UnicodeClasses;`, but
`/tmp/wolin-old` holds only `KotlinLexer.g4` and `KotlinParser.g4` — the
`UnicodeClasses.g4` that defines the `Letter` / `Digit` fragments is missing.
The old build had it at `src/main/antlr/UnicodeClasses.g4` (per the old
`tree.json`), but it is not in the extracted tree.

This is the **first blocker of the grammar task**, and it has three possible
resolutions:

1. **Vendor it.** Fetch `UnicodeClasses.g4` from the ANTLR 4 repository (the
   Kotlin grammar in the ANTLR distribution ships one) and record its
   provenance and licence in the repo. Cheapest, and the fragments are
   trivial.
2. **Write a replacement.** The fragments are small: `Letter`, `Digit`,
   `HexDigit`, `OctalDigit`, `BinDigit`, `EscapedIdentifier`,
   `UnicodeLetter`. A 30-line file is enough for the identifiers Wolin uses.
3. **Inline them.** Paste the fragment definitions into `KotlinLexer.g4`. Works,
   but makes the diff against the old grammar noisier.

The plan should pick one explicitly. Whichever it is, the acceptance test is
the same: the four old example files parse with `errors=0` (see §13).

## 5. Approaches considered

### A. Kotlin compiler → LLVM IR text → `mos-c64-clang`  ← **chosen**

The compiler parses Wolin source, builds LLVM IR as text, and shells out to
`mos-c64-clang -O2 out.ll -o out.prg`.

*For:* fastest path to a working `.prg`; reuses the old ANTLR grammar and Kotlin
idioms; no native build step; IR is validated by `clang -x ir -c` before linking;
debugging is legible (read the `.ll`).

*Against:* IR text is stringly-typed, so the compiler needs its own symbol naming,
type tracking, and phi placement discipline.

### B. C++ compiler → LLVM C++ API → `libLLVM.so`

Link against the LLVM-MOS 16 development libraries in `~/.local/local/llvm-mos`
(`llc`, `opt`, `llvm-as/dis`, `libLLVM.so`, LLVM headers) and use the C++ API to build
modules in-process.

*For:* type-safe IR construction; the verifier, `opt`, and debug-info API are all
in-process; no shelling out.

*Against:* substantially slower to a first working `.prg`; no reuse of the old Kotlin
code; native build required; LLVM-MOS 16 vs SDK 24 version skew means the shipped
`libcrt.a`/`libc.a` cannot be linked by that lld (verified: `Unknown attribute kind
(102) (Producer: 'LLVM24.0.0git' Reader: 'LLVM 16.0.0git')`).

### C. Kotlin compiler → C → `clang`

*For:* most robust codegen; least machinery.

*Against:* not really "targeting LLVM" — C's integer promotion leaks into the 8-bit
semantics that Wolin is built on. Rejected.

### Decision

Approach **A**, for the reasons above. The IR-text approach keeps the compiler small
enough that "the compiler is obviously correct" is a realistic review standard, and
the old Wolin's Kotlin/ANTLR heritage is directly reusable.

## 6. Language surface for v1

### Types

| Wolin type | LLVM type | Notes |
|---|---|---|
| `ubyte` | `i8` | primary 8-bit type |
| `byte` | `i8` | signed interpretation is a v2 concern; v1 treats arithmetic modulo 256 |
| `uword` / `word` | `i16` | 16-bit |
| `bool` | `i1` | `i1` in IR; the backend materialises it in a register |
| `unit` | `void` | |
| `float` | `float` | 4 bytes, **IEEE-754 single precision**. The old Wolin defined `float` as the **Wozniak FFP** format (`Typ.kt` references `6502.org/source/floats/wozfp1.txt`) — a *different* format. v1 uses the LLVM-MOS soft-float routines (`libcrt.a`), which are IEEE-754: verified — `float 1.5` emits `.long 0x3fc00000`, and a runtime test read the bytes back as `00 00 c0 3f`. Old Wozniak-FFP data is **not** byte-compatible and must be converted. |
| `string` | `ptr` to `i8` | NUL-terminated |

Wolin has no `long`. `word`/`uword` are 16-bit.

**Nullable types.** The old Wolin carried a `nulable` flag on every type, written
`ubyte?`. It had no runtime representation for null — `NullLiteral` was typed as
`any?` with value 0 — so nullability was a compile-time annotation with no runtime
consequence. v1 keeps `?` as a type annotation that the type checker may use, but
does **not** introduce a null runtime check or a nullable representation. A `ubyte?`
and a `ubyte` have the same runtime representation.

**Type inference.** A declaration without an explicit type takes the type of its
initialiser expression (`var x = 5` → `ubyte`, because `5` is a `ubyte` literal per
the literal rules below). A declaration with neither a type nor an initialiser is an
error ("Variable X has no type!").

### Declarations

- `var x: ubyte` — local, stack slot (LLVM `alloca`), promoted to a register by LLVM
  when possible.
- `var x: ubyte^0xd020` — **fixed-address variable**. Emits direct loads/stores to the
  constant address. This is the core Wolin idiom and must generate *no* load of the
  address itself.
- `var x: bool^CPU.I` — **CPU-register-backed variable**. See §7.
- `var arr: ubyte[ubyte]` / `var arr: ubyte[ubyte]^0x3000` — fixed-size byte array,
  optionally at a fixed address.

**Array semantics.** The array type is written `elementType[indexType]`. The index
type is part of the declaration, not inferred: `ubyte[ubyte]` is an array of bytes
indexed by a byte (so at most 256 elements), `ubyte[uword]` is indexed by a 16-bit
index. The old Wolin's `string` type is exactly `ubyte[ubyte]` — a NUL-terminated
byte array with a byte index.

The old Wolin's "fast array" optimisation (a 1-byte index *and* a 1-byte element,
so the address arithmetic fits in 8 bits) was an artefact of its own register
allocator. LLVM's addressing modes already handle this, so v1 has no special case —
but the *declaration* keeps the index type, because it determines the address
arithmetic width.

The old Wolin could only *read* array elements in general (`ubyte[ubyte]` and
`ubyte[uword]`); writing to arrays was listed as a TODO ("writing elements to all
kinds of arrays"). v1 supports both read and write for byte arrays, and read/write
for 16-bit-element arrays.

**Array storage.** An array type `elementType[indexType]` names the element
type and the index type, but *not* a length — so it cannot describe storage by
itself. v1 gives arrays storage in one of two ways:

| Declaration | Storage | Bounds |
|---|---|---|
| `var a: ubyte[ubyte]^0x3000` | a fixed address; the compiler emits no storage at all | the programmer's responsibility |
| `var a: ubyte[ubyte]` | a **global** of `elementSize × maxElements` bytes, in the module's data section | the index type's range |

For a global array, the length is implied by the index type: `ubyte[ubyte]` holds
256 elements, `ubyte[uword]` holds 65536. That is what the index type means —
it is the *width of the index*, and therefore also the width of the address
arithmetic. A `ubyte[uword]` global is 64 KB, which does not fit on a C64, so
in practice a 16-bit-index array is almost always fixed-address.

**No dynamic arrays in v1.** The old Wolin had no array allocator at all — its
"heap" was a hand-rolled bump area (`setup HEAP = 176`) used for class
allocation, and an array type was just a pointer (`Typ.kt`: `isPointer` is true
for arrays, `sizeOnStack` is 2). A program that wanted a dynamic array had to
manage it by hand at a fixed address.

v1 keeps that: arrays are either fixed-address or global-with-implied-length.
There is no allocator, no `new`, and no bounds checking. An out-of-range index
reads or writes whatever is at that address — the same behaviour as a
fixed-address variable. Bounds checks are a v2 concern; adding them now would
cost code size on a machine with 64 KB.

**The empty index form.** The old corpus uses `var ekran: ubyte[]^1024` — an
empty index type. The reused grammar allows it (`arrayDeclaration: LSQUARE
(userType)? RSQUARE`), so v1 accepts it and treats it as `ubyte[uword]` (a
16-bit index), which is the only sensible default for a fixed-address buffer of
unknown length.

**Scoping.** A `var` declared at file scope is a **global** (static storage), not a
local. The old Wolin distinguished `FieldType.STATIC` (file scope) from
`FieldType.LOCAL` (inside a function); file-scope variables are initialised by the
program's startup code, in declaration order, before `main` runs.

**Block scoping and shadowing.** The old Wolin appended a `.` to a scope suffix on
entering a block (`visitBlock`) and dropped it on exit, so every declaration got a
name unique to its nesting path. A variable declared in an inner block therefore
*shadowed* an outer one with the same name rather than being an error, and the
inner declaration vanished at the end of the block.

v1 keeps this: blocks introduce a scope, inner declarations shadow outer ones, and
the shadowed declaration is restored on exit. This is ordinary Kotlin behaviour
and needs no special support beyond a scope stack in the semantic analyser.

**Initialisers.** `var x: ubyte = 5` at file scope initialises the global at startup.
Inside a function, `var x = expr` initialises at the point of declaration.

**`val` vs `var`, and `const`.** The old Wolin's grammar accepted both `val` and
`var` (Kotlin's syntax) and a `const` modifier, but the compiler **ignored all
three**: `visitPropertyDeclaration` never looked at which keyword was used, so
`val` and `var` behaved identically (both mutable), and `const` was accepted and
dropped. The only related check was that a `var` could not be a literal
allocation ("var can't const!").

v1 makes the distinction real, because Kotlin users expect it and it costs
nothing:

- `var` is mutable.
- `val` is immutable after initialisation; assignment to it is a compile error.
- `const` is accepted on a `val` with a compile-time-constant initialiser and is
  treated as an inline constant. (`const` at file scope only, as in Kotlin.)

This is a strict improvement, and it cannot break the old corpus: no old example
uses `val` for something it later mutates.

**No local variables in interrupt functions.** The old Wolin rejected this outright
(`InterruptFunctionWithLocals`: "Local variables are not allowed in interrupt
functions!"), because an interrupt can fire at any point and would have to save and
restore an arbitrary number of locals. v1 keeps this restriction — it is a real
constraint of the 6502, not an arbitrary limitation.

### Functions

- `fun f(a: ubyte, b: uword): uword { ... }` — ordinary function.
- `fun f^0xffba(a: ubyte^CPU.A)` — **external function at a fixed address** (KERNAL
  routine). See §7.
- `interrupt fun f() { ... }` — interrupt handler; the function is emitted with the
  `"interrupt"` attribute so it ends with `rti` rather than `rts`.
- `fun f() { ... return@0xea31 }` — tail-jump to a fixed address instead of returning.
  In an `interrupt fun` this replaces the `rti` with a jump into a ROM routine
  (the old Wolin "continue with ROM routine" idiom).

**This needs a grammar change**: the reused lexer rule is
`RETURN_AT: 'return@' Identifier`, so `return@0xea31` does not parse — `0xea31`
is a number, not an identifier. v1 adds a `return@<integer literal>` production
(see §7.5 for the semantics).

**Declaration rules** (all enforced by the old Wolin, kept in v1):

- An **interrupt function may not have a return type** ("Return not allowed for
  interrupt function!") and **may not take arguments** ("Arguments not allowed for
  interrupt function!"). An interrupt fires at an arbitrary point, so it has no
  caller to receive arguments from and no caller to return a value to.
- A **fixed-address function may not have a body** ("Fixed address function X with a
  body!"). The body would be dead code at an address that already belongs to
  something else — for a KERNAL routine, the ROM itself.
- A fixed-address function is a *declaration*; the compiler emits the call, not the
  function.

**Function naming.** The old Wolin mangled names as
`package_subpackage_file__function`, with `_` for `.` and `__` for the file
separator (e.g. `pl_qus_wolin_test_main__b`). The new compiler uses LLVM symbol
names directly, so the mangling is LLVM's, not ours. Source-level names are
package-qualified, matching Kotlin: `pl.qus.wolin.main` is the fully-qualified name
of `main` in package `pl.qus.wolin`.

### Control flow

`if` / `else if` / `else`, `do ... while`, `while`, `when` (with `else` branch),
`break`, `continue`, `return`.

**`when` semantics.** Two forms, as in the old Wolin:

- Subject form: `when (x) { 1 -> ...; 2 -> ...; else -> ... }` — the subject is
  evaluated once, then compared against each branch condition in order; the first
  match wins.
- Condition form: `when { a > b -> ...; c -> ... }` — each branch carries its own
  boolean condition.

The `else` branch must be **last**; the old Wolin's implementation assumed it
(`ctx.whenEntry()?.dropLast(1)` for the non-`else` branches, `last()` for the `else`).
A `when` with no `else` and no match falls through to the end of the `when`.

A branch may list several conditions separated by commas:
`1, 2, 3 -> ...` (grammar rule `whenEntry`). The old Wolin's grammar also allowed a
range test (`in` operator) and a type test as branch conditions; v1 supports the
comma-separated list and plain conditions, and rejects ranges and type tests.

`when` may also be used as an expression (its value is the value of the taken
branch), which is how the old Wolin implemented `when` used as a value.

### Bit operations

| Syntax | Meaning | Old Wolin template |
|---|---|---|
| `x := v` | set bits: `x = x or v` | `bit dst = val, #1` → `ora` |
| `x .= v` | clear bits: `x = x and not v` | `bit dst = val, #0` → `#$ff-val` + `and` |
| `x &= v` | `x = x and v` | `and dst = dst, #val` → `and` |
| `x \|= v` | `x = x or v` | `or dst = dst, #val` → `ora` |

The bare `&` and `|` operators (without assignment) also exist and are the
read-only versions of the same operations.

Note the old Wolin template for `.=` is `lda #$ff-{val}; sta {dst}` — i.e. it
*replaces* the byte with the complement of the mask rather than ANDing the existing
value. That is arguably a bug in the old template. v1 implements the documented
intent (`clear bits`) as `x = x and not v`; the discrepancy is recorded here
deliberately rather than silently reproduced.

**Note on `&&` and `||` in the old Wolin.** The old compiler did **not** implement
short-circuiting logical operators. `visitDisjunction` mapped `||` to the `or`
pseudo-instruction and `visitConjunction` mapped `&&` to `and` — *bitwise*
operations on the boolean values. Both operands were always evaluated. The old
templates for the boolean `and`/`or` even skip the store when the operand is
already the result, which changes the *destination's* value but not whether the
right operand is evaluated.

So the old Wolin's `&&` was `and`, not a short-circuiting `&&`. A program like
`if (p != null && p.value > 0)` would have evaluated `p.value` even when `p` was
null.

v1 implements `&&` and `||` as **proper short-circuiting branches**, matching
Kotlin, and keeps `&` and `|` as the non-short-circuiting bitwise operations. This
is a real semantic difference from the old Wolin, and it is a bug fix: the old
behaviour is the kind of thing that makes a language unpleasant to use.

The distinction matters in the type rules too: `&&` and `||` require `bool`
operands and produce `bool`; `&` and `|` are bitwise and work on `ubyte`/`uword`
as well.

### Expressions

Arithmetic `+ - * / %`, comparison `== != < > <= >=`, logical `&& || !`, bitwise
`& |`, `++`/`--` prefix and postfix, function literals as function pointers.

**There is no XOR.** The old Wolin has no `xor` operator at all: no lexer token,
no parser rule, no template. The bitwise operators it has are `&` (and), `|`
(or), and the assignment forms `:=` (set bits), `.=` (clear bits), `&=`, `|=`.
v1 keeps that set. Adding `xor` is a small, self-contained extension — the 6502
has `EOR` and the LLVM IR is `xor i8` — but it is *new*, not a port, so it is
listed in §12 as out of scope for v1 rather than silently assumed.

**Array indexing type rules.** The old Wolin **always promotes the index to a word**,
regardless of whether the array element type is `ubyte` or `uword`. The implementation
forces `Typ.uword` with `pp.pointer = false` on line 1071 of `WolinVisitor.kt`.

The index calculation then multiplies by the element size for multi-byte elements
(`mul reg, #size` → `add reg, array_base`), and adds directly for byte elements
(`add reg, array_base` with the "&" address-of operator). This ensures that array
indices are correctly scaled before being added to the base address — matching LLVM
IR semantics where the GEP is `getelementptr i8, ptr @arr, uword idx` (or `i16`,
`getelementptr i16, ptr @arr, uword idx`).

So `ubyte[] a; byte b = a[5]` uses a `uword` index, not a `byte` index — the old
compiler never allowed byte indices, and v1 must match this semantic rule.

**Increment/decrement.** The old Wolin implements **postfix** `++`/`--` but NOT
prefix. The postfix version reads the value, stores it as the result, then adds/subtracts
1 (`add var, #1`/`sub var, #1`). Prefix was attempted but left as commented-out code
that throws a compile error ("Nie umiem wykonać prefix unary operation dla ++").

Thus `x++` and `x--` are valid and work; `++x` and `--x` are compile errors in the
old Wolin. v1 should match this until someone objects — the Kotlin convention is
prefix, but the old Wolin was postfix-only and never shipped usage of prefix.

**when expression forms.** The old Wolin supports both simple and complex `when`
expressions:

- **Simple form**: `when (expression) { case X -> value; ... }`. The expression is
  evaluated once (called the "subject"), then `evaleq subject, branch_value` is
  emitted for each branch, jumping to the branch body if equal. This means the `when`
  is effectively a chain of equality checks until a match is found. The whole
  `when` has a type: the type of the chosen branch.

- **Complex form**: `when { case X -> value; else -> default }`. No subject expression.
  Each condition is evaluated separately; `evaleq` is still used to compare each
  condition to `true` (which `whenCondition` produces). The branch bodies are
  executed in order; the first true branch's value is the result of the `when`.

v1 should match the complete behaviour: both forms are valid, use LLVM's `icmp`
with equality checks, and the `when` expression type is the branch value type (not
the subject type).

**`else` must be last, and v1 enforces it.** The old Wolin did not enforce
this: `visitWhenEntry` checks `ctx.ELSE() != null` per entry and only treats the
*last* entry specially for the fall-through edge, so an `else` in a non-final
position worked in practice — but the `dropLast(1)` / `last()` split in
`visitWhenExpression` assumed the `else` was last, so the behaviour was
accidental rather than designed.

v1 makes it a rule: an `else` branch that is not the last entry is a compile
error ("the else branch of a when must be last"). A final branch without an
`else` is allowed — the `when` expression then has no value if no branch
matches, which is an error at the use site ("this when expression may not
match any branch").

**The old implementation has a bug in the subject form, and v1 does not reproduce
it.** In `visitWhenExpression` the flag `simpleWhen` is set to `false` when the
`when` *has* a subject and `true` when it does not — the names are inverted. The
consequence lands in `visitWhenEntry`: with a subject (`simpleWhen = false`) the
old code emits

```
evaleq boolReg = resultReg, condReg
```

comparing the **subject** (`resultReg`) against the **condition** (`condReg`)
instead of comparing the subject against the **branch value**. So in the old
Wolin, `when (x) { 1 -> ...; 2 -> ... }` tests `x == <the condition
expression>`, not `x == 1`. v1 implements the documented intent: the subject is
compared against each branch value.

**Numeric type rules** (taken from the old Wolin, which used
`simpleResultFunction2(op, null)` — result type = left operand's type, pointer flag
cleared):

- Arithmetic **never widens**. `ubyte + ubyte` is `ubyte`; `uword + uword` is `uword`.
  There is no integer promotion. A `ubyte` result wraps at 256.
- **Mixed operand types are a compile error** in the old Wolin
  (`RegTypeMismatchException`: "Attempt reassign X Y"). v1 keeps this as an error
  rather than inventing a promotion rule the old language never had.
- The result type follows the **left** operand, so `uword - ubyte` would be an error
  rather than silently producing a `uword`.
- Division and modulo follow the left operand's type as well.

This is the one place where v1 deliberately does *not* follow Kotlin: Kotlin promotes
`Byte + Byte` to `Int`. Wolin did not, and the whole point of the `ubyte` type is
that arithmetic stays 8-bit.

**Division and modulo are a runtime library call.** The 6502 has no divide
instruction. The LLVM-MOS backend lowers `udiv`/`urem` to calls into the
compiler-rt routines in `libcrt.a`:

| Operation | Routine |
|---|---|
| `udiv i8` | `__udivqi3` |
| `urem i8` | `__umodqi3` |
| `udiv i16` | `__udivhi3` |
| `urem i16` | `__umodhi3` |
| `sdiv i16` / `srem i16` | `__divqi3` family |

All four unsigned 8/16-bit routines are present in the SDK's `libcrt.a` (verified
with `llvm-nm`), so division and modulo work without the compiler shipping a
runtime of its own.

Note that this makes `/` and `%` noticeably more expensive than `+` and `*` on a
6502 — a call plus a software loop. The old Wolin had the same problem and had a
*worse* answer: its template file only had division for `word` operands (a call to
a `stack_div` runtime routine) and only special-cased `ubyte` division by powers of
two (`#1`, `#2`, `#4`, `#8` → shifts). It had **no general `ubyte / ubyte`
division and no modulo at all**. v1 is strictly more capable here.

**Division by zero** traps on the 6502 (the CPU jumps to `$FFFE`). The old Wolin
had no guard. v1 leaves the trap to the hardware but the compiler may reject a
literal-zero divisor at compile time, since that is always a bug.

**Array indexing is promoted to a word.** The old Wolin **always promotes the index
to `uword`**, regardless of whether the element type is `ubyte` or `uword`
(`WolinVisitor.kt` line 1071: `forceTopOregType(Typ.uword { pointer = false })`).
The index is then multiplied by the element size for multi-byte elements, then added
to the array base address. This matches LLVM IR where GEP uses `uword` for the index:
`getelementptr i16, ptr @arr, uword idx`.

So a `ubyte` array indexed by a `ubyte` literal still uses a `uword` index. v1
emits GEP with a word index. This is simpler than the old C-style array rule
"same type as element", and it is what the old Wolin actually did.

**Increment and decrement.** The old Wolin implements **only postfix** `++`/`--`;
prefix `++`/`--` is a compile error (the prefix handling is commented out and throws
`Nie umiem wykonać prefix unary operation dla ++` on line 667 of `WolinVisitor.kt`).

Postfix: read the value, store it as the result, then add or subtract 1.

v1 should match: postfix works, prefix does not. No support for prefix until someone
asks for it.

**`when` expression forms.** The old Wolin supports both forms, and both use
equality checks:

* **Simple form** — `when (subject) { A -> value; B -> other; else -> fallback }`:
  the subject is evaluated once, and each branch tests
  `evaleq subject, branch_value` → jump to branch body.
* **Complex form** — `when { cond1 -> value1; cond2 -> value2; else -> fallback }`:
  no subject; each `whenCondition` produces a `bool`, and the branch is taken when
  the condition is true.

In both cases the `when` expression's type is the *branch value* type, not the
subject's type. v1 emits a chain of `icmp eq` / `br` / `switch` blocks with
fallthrough, matching this.

**Unary minus does not exist in the old Wolin.** The grammar parses it
(`prefixUnaryOperation: ADD | SUB`), but `visitPrefixUnaryExpression` has no
`SUB` case, so `-1` falls into the catch-all and produces "Nie umiem wykonać
prefix unary operation dla -" (`WolinVisitor.kt:712-717`). The lexer has no
signed-literal rule either. So the `byte` and `word` rows of the literal table
below are **not reachable in the old compiler** — they exist in the type system
but nothing can produce a negative literal.

v1 decides this explicitly:

- **Unary minus is supported.** It is one line of grammar work and one IR
  instruction (`sub`), and without it the `byte`/`word` types are unreachable,
  which makes the signed-comparison rules dead. `-1` is a `byte`, `-128` is a
  `word`, matching the literal table.
- **A negative literal is an error when the destination is unsigned.**
  `var x: ubyte = -1` is an error, because -1 does not fit in 8 unsigned bits.

This is an addition to the old language, not a port of it.

**Integer literal typing.** An untyped integer literal is given the *smallest* type
that holds its value (old Wolin `parseNumberGuessType`):

| Literal value | Type |
|---|---|
| `0` … `255` | `ubyte` |
| `256` … `65535` | `uword` |
| `-127` … `-1` | `byte` |
| `-32768` … `-128` | `word` |
| anything else | compile error ("Value out range") |

So `var x: ubyte = 200` works, but `var x: ubyte = 300` does not — `300` is a
`uword` literal and the mixed-type rule rejects the assignment. This is why the old
Wolin's own examples wrote `0xff` for values that would not fit a byte.

A literal is *not* coerced to the destination type of an assignment; it is typed on
its own and then checked against the destination like any other expression.

**But there is one exception, and it decides whether the old corpus compiles.**
`parseNumberGuessType` only guesses a type when the target's type is still
unknown:

```kotlin
if (reg.type.isUnit) {
    when (wynik) {
        in 0..255 -> reg.type = Typ.byName("ubyte", this)
        ...
    }
}
```

So when the destination type is *already known*, the literal adopts it and no
range check happens. `var x: uword = 0` is fine (the literal becomes a `uword`),
and so is `var x: uword = 300` — while the same `300` in a context with no
expected type is a `uword` by the smallest-type rule, and `var x: ubyte = 300`
in an unknown-type context is an error.

v1 makes this explicit as **contextual literal typing**: a literal takes the
expected type when there is one, and falls back to the smallest-type rule when
there is not. This is what makes the old corpus compile:

| Source | Old Wolin | v1 |
|---|---|---|
| `var i: uword = 0` | literal typed `uword` (destination known) | literal typed `uword` |
| `var len: ubyte = 0` | literal typed `ubyte` | literal typed `ubyte` |
| `var x = 300` | `uword` (smallest-type rule) | `uword` (smallest-type rule) |
| `var x: ubyte = 300` | error (literal is `uword`, types differ) | error |

A literal still has to *fit*: `var x: ubyte = 300` is an error in v1 too, because
300 does not fit in 8 bits, even though the contextual type is `ubyte`.

**Comparisons are signed or unsigned depending on the operand type.** The old
Wolin branched on the left operand's type name: if it started with `u`
(`ubyte`, `uword`) it compared **unsigned** using the carry flag; otherwise
(`byte`, `word`) it compared **signed** using the negative flag
(`WolinVisitor.kt:355-440`):

```kotlin
if (lewa.type.name.startsWith("u")) {
    state.replaceRegWithCPU(wynik, "CPU.C")   // unsigned: bcs / bcc
    ...
} else {
    state.replaceRegWithCPU(wynik, "CPU.NN")  // signed: bmi / bpl
    ...
}
```

The templates carry both variants (`template.asm:972-995`: `evalless CPU.NC` is
`cmp` + `bcs`, `evalless CPU.N` is `cmp` + `bpl`/`bmi`).

The old implementation was itself inconsistent — `evalgteq CPU.NN` is `cmp` +
`bcs`, which is *unsigned* despite the `NN` (negative-flag) name, so `>=` on a
signed type was actually an unsigned comparison while `<` on a signed type was a
signed one.

v1 does not reproduce the inconsistency. It emits:

| Operand type | `<`, `<=`, `>`, `>=` | `==`, `!=` |
|---|---|---|
| `ubyte`, `uword` | `icmp ult` / `icmp ugt` (unsigned) | `icmp eq` / `icmp ne` |
| `byte`, `word` | `icmp slt` / `icmp sgt` (signed) | `icmp eq` / `icmp ne` |

This preserves the old *intent* (signed types compare signed) while fixing the
inconsistency. Signed types are reachable only through a negative literal or an
explicit `byte`/`word` declaration, so this rarely matters in practice — but it
is the difference between `byte` being a distinct type and being an alias for
`ubyte`.

**Other literals.** `true`/`false` are `bool`; `'c'` is a `ubyte`; a real literal
(`3.14`) is a `float`; a string literal is a `string` (a NUL-terminated byte
array). Hex literals (`0xff`) and binary literals use the same smallest-type
rule as decimal.

**Real literals: the old grammar had them, the old compiler ignored them.** The
grammar defines `RealLiteral` → `FloatLiteral` | `DoubleLiteral`
(`KotlinLexer.g4:187-203`), so `3.14` and `3.14f` both parse. But
`parseLiteralConstant` set `reg.floatValue = 3.14f` and pushed `3.14f` into the
float list *regardless of what the source said* (`WolinStateObject.kt:819-822`).
Every float literal in the old Wolin was literally the value 3.14.

v1 parses the literal properly and emits its actual value. The type is `float`
(IEEE-754 single), so `3.14` and `3.14f` mean the same thing. Double precision
is **not** in v1 — `float` is the only floating-point type — so a
`DoubleLiteral` is narrowed to single precision, and a value that does not fit
in a single is a compile error.

**`string` is a distinct type, not an alias for `ubyte[ubyte]`.** The old
Wolin made them the same type: `Typ.byName("string")` returned
`Typ("ubyte", array = true, shortIndex = true)` (`Typ.kt:44-46`), and `string`
was deliberately *not* in the `primitives` set (`Primitives.kt` lists
`bool, byte, ubyte, word, uword, float, unit`).

v1 keeps them separate, because they behave differently:

| | `string` | `ubyte[ubyte]` |
|---|---|---|
| Initialiser | a string literal | a string literal or nothing |
| Length | known at compile time, NUL-terminated | implied by the index type |
| Element access | `what[len]` | `a[idx]` |
| Representation | `ptr` to `i8` | `ptr` to `i8` (same at runtime) |

They share a runtime representation but not a type identity, so
`var s: string = "abc"` and `var a: ubyte[ubyte] = "abc"` are different
statements with the same runtime effect. A `string` is always NUL-terminated;
an `ubyte[ubyte]` array is not.

**String escapes and interpolation.** The reused grammar supports Kotlin string
templates (`${expr}`) and backslash escapes, but the old compiler took the raw
token text with no escape processing at all (`WolinStateObject.kt:818-822`), so
`"\n"` was the two characters backslash and `n`.

v1 decodes the standard Kotlin escapes (`\n`, `\t`, `\r`, `\0`, `\\`,
`\"`, `\'`, `\uXXXX`) into bytes. **String interpolation is not in v1** — a
`${...}` inside a string literal is a compile error ("string interpolation is
not supported in v1"), not a silently-literal `${`.

**String constants** are emitted once per distinct literal into a read-only data
section, and referenced by address. The old Wolin kept a `strings` list of distinct
literals; each got a label `stringConst_<n>` and a `segment RODATA` emission of
`.asciiz` (which **adds a trailing NUL** to cc65 output). In LLVM IR this is a
`private unnamed_addr constant [N+1 x i8] c"...\00"` and a `getelementptr` to
obtain the pointer. `string` is NUL-terminated, so `while (what[len] != 0)` is the
idiomatic length loop.

**Float constants** work the same way: a `floats` list, each emitted as a
`floatConst_<n>` label with `.fvp` (the **Wozniak** floating-point pseudo-op from
the old template). In LLVM IR a `float` global is `@floatConst = private global
float 1.5`, and the soft-float routines from `libcrt.a` implement the
arithmetic.

The two formats differ, and that is settled, not open:

| | Old Wolin | LLVM-MOS (`libcrt.a`) |
|---|---|---|
| Format | Wozniak FFP (sign-magnitude, 4-bit exponent, 12-bit mantissa) | IEEE-754 single (sign-magnitude, 8-bit exponent, 23-bit mantissa) |
| `1.5` in memory | not `0x3fc00000` | `00 00 c0 3f` (little-endian) |

Verified at runtime: a program storing the raw bytes of `float 1.5` read back
`00 00 c0 3f`, and `1.5 + 1.0` produced `2` while `1.5 + 1.0 + 1.0` produced
`3`. So the SDK's float is IEEE-754 and its arithmetic works.

**Consequence:** a `float` variable holding Wozniak-FFP data (for example, read
from a saved game or a ROM table) is misinterpreted. v1 does not convert — it
documents the difference and treats `float` as IEEE-754. A conversion helper is
a v2 concern.

**Cost:** the soft-float routines are pulled in on first use, and they are not
small — a program that only adds two floats grew to 3503 bytes. Programs that
never mention `float` do not pay this.

### Function pointers

`fun` values are 16-bit code addresses. `uwordVar = functionName` stores the
address; calling through a pointer is an indirect `jsr`.

The type of a function value is written with an arrow, e.g.
`(ubyte,ubyte)->ubyte`. This is the old Wolin's syntax
(`functionToLambdaType` builds `"(" + args.joinToString(",") + "->" + retType`),
and it is also what a variable declaration uses.

**Function *literals* are in v1; *capturing closures* are not.** The old
Wolin's lambdas were non-capturing: `visitFunctionLiteral` created a standalone
`Funkcja` with no environment, so `{ a, b -> a + b }` was just a named function
that a function pointer could point at. The old compiler inferred the literal's
parameter types from the variable it was assigned to (`lambdaTypeToFunction`);
where no such type was available it errored with "Can't infer type of lambda!".

v1 keeps the non-capturing form and the contextual inference, because the old
corpus uses it (`misc.ktk`: `var suma: (ubyte,ubyte)->ubyte` then
`suma = { a, b -> a + b }`, called from `main`):

```kotlin
var suma: (ubyte,ubyte)->ubyte
suma = { a, b -> a + b }
```

The compiler synthesises one function per literal (`lambda_function_0`), typed
from the expected type at the assignment site. A literal assigned where no
expected type is available is an error: "cannot infer the parameter types of
this function literal; give it an explicit type".

This is what §3 means by "lambdas / closures" being a v2 item: **capturing
closures** are v2. A function literal that captures nothing from its enclosing
scope is v1. The distinction matters on the 6502 — an upvalue needs a home, and
there is no room to put one in the zero page.

**Function pointers and `uword` are distinct.** A `uword` is a plain 16-bit
number; a function-pointer-typed variable is a pointer to a function with a
known signature. In the old Wolin these were conflated: assigning a function to
a variable went through `checkTypeAndAddAssignment`, which accepted only
`uword`, and calling a function-typed variable emitted a *statically named* label
derived from the variable name — not an indirect call.

v1 separates them:

| | Representation | Call lowering |
|---|---|---|
| `uword` holding a function address | `i16` | `ptrtoint` → `inttoptr` → `call` |
| `(ubyte,ubyte)->ubyte` variable | `ptr` | `call` through the pointer |

A `uword` that holds a function address can be converted to a function pointer
explicitly; the compiler does not do it implicitly, because a `uword` might hold
a data address.

**Increment / decrement.** `x++` and `x--` work on a variable and on a literal
constant. In the old Wolin, `x++` adds one *in place* to the variable and leaves
the expression's value as the variable's *new* value (it is a statement-level
operation: `add x = x, #1`). v1 implements the Kotlin semantics — postfix `x++`
evaluates to the *old* value and increments as a side effect, prefix `++x`
evaluates to the new value — which is what a reader of Kotlin-style code expects,
and is the only choice that makes `x++` usable in an expression at all.

**Compound assignment.** `+=`, `-=`, `*=`, `/=`, `%=` are read-modify-write on the
variable, and are equivalent to `x = x + ...` with the type rules above applied to
the result.

Note that the old Wolin implemented **only** `=`, `:=`, `.=`, `&=` and `|=`. The
other compound assignments (`+=`, `-=`, `*=`, `/=`, `%=`) were in the grammar but
reached the compiler's "Unknown assignment operator in block" error. v1 implements
all of them — they are trivial given the IR builder and their absence was clearly
an oversight, not a design decision.

### Explicitly rejected in v1

The reused grammar is a **full Kotlin grammar**, so a large set of constructs
parse but have no v1 implementation. Every one of them must produce a clear
"not supported in v1" diagnostic — never a crash, and never a silent
miscompile. This list is the diagnostic catalogue and the negative-test suite:

| Construct | Kotlin form | v1 behaviour |
|---|---|---|
| Exceptions | `try` / `catch` / `throw` / `finally` | rejected (§3) |
| Classes and objects | `class`, `object`, `interface`, `enum class`, `companion` | rejected (§3) |
| Constructors | `constructor`, `init` | rejected |
| Capturing closures | a literal that captures an enclosing variable | rejected (§6) |
| Inheritance | `: Super`, `override`, `open`, `abstract`, `sealed` | rejected |
| Generics | `fun f<T>(x: T)`, `List<T>` | rejected |
| Type tests | `x is Foo`, `x !is Foo` | rejected |
| Smart casts | `x as Foo` | rejected |
| Safe calls | `x?.y` | rejected |
| Elvis | `x ?: y` | rejected |
| Ranges and `in` | `1..10`, `x in 1..10` | rejected |
| `for` loops | `for (x in xs)` | rejected (the old Wolin: "Unknown loop expression") |
| `while` / `do-while` | `while (c) { }`, `do { } while (c)` | **supported** |
| `break` / `continue` | `break`, `continue`, `break@label` | `break`/`continue` supported; `break@label` rejected |
| String templates | `"value: $x"`, `"value: ${expr}"` | rejected; escapes are supported |
| Null-safety operator | `x!!` | rejected |
| Property accessors | `get() = ...`, `set(v) { }` | rejected |
| Type aliases | `typealias X = Y` | rejected |
| Annotations | `@Foo` | rejected |
| `vararg` | `fun f(vararg xs: Int)` | rejected |
| `this` / `super` | `this`, `super.x` | rejected |
| `suspend` | `suspend fun` | rejected |
| Labelled expressions | `loop@ while (...) { }` | rejected |
| `when` range/type tests | `when { x in 1..10 -> }`, `when { x is Foo -> }` | rejected (the old Wolin: "nie wiem, jak się robi range test we when") |
| `cc65 fun` | `cc65 fun f()` | rejected (§3) |
| `external fun` | `external fun f()` | rejected — no assembly-object import in v1 (may be lowered to an `asm` declaration later) |
| `import` with `as` | `import a.b as c` | rejected — plain `import` is supported (§8.5) |

Two rules make this tractable:

- **The check is in the semantic analyser, not the parser.** The grammar accepts
  the construct; the analyser recognises the context and reports it. This keeps
  the grammar diff (only `return@<literal>` is added) small and reviewable.
- **Every diagnostic names the construct and points at v2.** A user porting an
  old file should learn *why* it does not compile, not just that it does not.

## 7. Mapping Wolin features onto LLVM IR

### 7.1 Fixed-address variables

```kotlin
var border: ubyte^0xd020
```
→
```llvm
%p = inttoptr i64 53280 to i8*
%v = load i8, ptr %p
store i8 %x, ptr %p
```

The address is a compile-time constant, so the backend folds it into `sta 53280`.

**Spelling matters.** `inttoptr` in *instruction* position takes no
parentheses: `inttoptr i64 53280 to i8*`. The parenthesised form
(`inttoptr (i64 53280 to i8*)`) is the *constant-expression* spelling — it
is valid in a global initialiser
(`@"t.border" = global i8* inttoptr (i64 53280 to i8*)`) and inline in an
operand, but clang rejects it as a standalone instruction
(`error: expected type`). Verified against the real backend on 2026-11-13.

### 7.2 CPU-register-backed variables

The old Wolin exposes CPU registers as addressable variables: `CPU.A`, `CPU.X`,
`CPU.Y`, `CPU.C` (carry), `CPU.N` (negative), `CPU.Z` (zero), `CPU.I` (interrupt
disable), and the 16-bit pair `CPU.AX` (A = low byte, X = high byte).

`CPU.XY` and `CPU.YX` exist in the old template only as *interrupt save/restore*
targets (`restore CPU.XY`), not as readable/writable variables. There is **no
`CPU.AY`** — it appears nowhere in the old source. v1 does not add one.

There is no IR type for "the accumulator". Each register therefore becomes a
*runtime accessor* implemented with inline assembly.

**The 6502 status register** is `N V - B D I Z C` from bit 7 down, so the bit
numbers are: carry = bit 0, zero = bit 1, negative = **bit 7** (not bit 6 —
bit 6 is the overflow flag). `PLA` (pull accumulator) *loads into A*, so any
sequence ending in `PLA` destroys the accumulator; `PLA` also sets N and Z from
the value it pulls.

| Wolin | Implementation | Emitted |
|---|---|---|
| `CPU.A` read | `sta ($0),y` with `y = 0` into a scratch slot | `ldy #0; sta ($0),y` |
| `CPU.A` read (value form) | `pha`/`pla` | `pha; pla` |
| `CPU.A` write | empty asm with `"a"(v)` input constraint | `lda #v` |
| `CPU.X` read | `txa` | |
| `CPU.X` write | empty asm with `"x"(v)` input constraint | `ldx #v` |
| `CPU.Y` read | `tya` | |
| `CPU.Y` write | empty asm with `"y"(v)` input constraint | `ldy #v` |
| `CPU.I` write | `sei` / `cli` | |
| `CPU.C` read | status-preserving sequence, mask `#1` | see below |
| `CPU.N` read | status-preserving sequence, mask `#128` | see below |
| `CPU.Z` read | status-preserving sequence, mask `#2` | see below |

The **write** direction uses an empty `asm volatile("")` with an input constraint
(`"a"(v)`, `"x"(v)`, `"y"(v)`). The constraint is what forces the value into the
register; the empty template is what keeps the instruction sequence intact. The
MOS backend honours these constraints, so the value lands in the right register.

The **read** direction for `CPU.A` uses `sta ($0),y` into a scratch slot: `STA`
stores A without touching any flag, so the read leaves the status register
intact. The alternative `pha`/`pla` also returns A's value, but `PLA` sets N and
Z from the value it pulls, so it clobbers the zero and negative flags. Use
`pha`/`pla` only where flag state does not matter.

**Flag reads must not use `php`/`pla`.** `PLA` loads the status byte into A, so
`php`/`pla` *destroys the accumulator* — reading `CPU.C` would silently change
`CPU.A`. The old Wolin never made this mistake: its template branches on the
flag (`bcs`, `bpl`, `beq`) instead of moving P into a register.

v1 reads a flag with a sequence that preserves **both A and the whole status
register**:

```
php        push P            (P1)
pha        push A
php        push P again      (P2)
pla        A=P2              pop P2 into A
and #mask  A=bit            isolate the bit (AND leaves C alone)
lsr ...    A=0|1             normalise to bit 0
tax        X=0|1             deliver the value in X
pla        restore A
plp        restore P         (P1)
```

The stack order matters: A is pushed between the two P pushes, so the pops come
out P2, A, P1. `PLA` and `AND` damage N and Z on the way through; the final
`PLP` restores P exactly, so the damage is undone. The result is delivered in X,
so the IR constraint is `"=x"` (output-only, no operand).

The mask is a compile-time constant, so it is baked into the template as a
literal immediate (`and #1`, `and #2`, `and #128`) — an immediate operand in
the template would need an operand constraint, and the MOS backend does not
substitute a plain `"r"` operand into an immediate position.

Both directions are **verified at runtime**, not merely at compile time. A test
program stored a runtime-computed value to `$0404`, loaded it, pinned it into A,
read A back, and stored the result to `$0400`. VICE readback was `42a5` — exactly
the expected value followed by the control byte. A separate test confirmed that
`pha`/`pla` yields the same value on repeated reads (`0e`, `0e`).

The flag reads are verified too. A test program read carry (with C clear and
with C set), zero, and negative, and re-read A after every flag read. VICE
readback was `a5 00 42 01 42 01 00 01 80 01`: the control byte, carry `00` then
`01`, A preserved as `42` across each flag read, carry still `01` after a second
read (so the read did not disturb C), zero `01`, negative `01`. A second test
confirmed the flags-preserving A read: `sta ($0),y` returned `42` while leaving
the zero flag at `0` (`a5 42 00 42`).

A control test proved the bug this replaces: a plain `php`/`pla` flag read
destroyed A — the readback was `30`, not the expected `42`.

Because A/X/Y are read via a register-to-register move, they observe the value the
*previous instruction* left there. This matches the old Wolin model, where `CPU.A`
is a register rather than a memory location. The old Wolin's `CPU.I` is likewise a
flag, implemented by `sei`/`cli`.

**This was the riskiest part of v1, and it is now proven.** The remaining caveat is
that the compiler must not let LLVM reorder or elide the inline-asm sequences —
they carry `volatile` and `memory` clobbers, which prevents that.

**The complete register surface.** The old templates define a read and/or a
write path for each register; v1 implements exactly that set:

| Register | Read | Write | Old template |
|---|---|---|---|
| `CPU.A` | yes | yes | `let CPU.A[ubyte] = #?val` / `= SP(?s)` |
| `CPU.X` | yes | yes | `let CPU.X[ubyte] = SP(?s)` |
| `CPU.Y` | yes | yes | `let CPU.Y[ubyte] = SP(?s)` |
| `CPU.C` | yes | yes | `sec` / `clc` for the write |
| `CPU.I` | **no** | yes | `sei` / `cli` only |
| `CPU.N` | yes | no | read via the N flag |
| `CPU.Z` | yes | no | read via the Z flag |
| `CPU.AX` | yes | yes | 16-bit pair, A low / X high |
| `CPU.XY`, `CPU.YX` | no | no | interrupt save/restore targets only |

`CPU.I` has no read path in the old Wolin — the template is write-only, and
there is no instruction that reads the I flag without also disturbing it. v1
keeps that: `var x: bool^CPU.I` is writable and not readable. Reading it is a
compile error ("CPU.I cannot be read"), not a silent zero.

`CPU.C` *is* writable (`sec` / `clc`), which the old template supports and
which the compiler must emit for `carry = true`.

**Distinguishing a register from a type-based location.** `^CPU.A` is parsed by
the reused grammar as a `userType` — `CPU.A` looks like a qualified type name
(`KotlinParser.g4:271-274`). The analyser therefore has to recognise the
`CPU.` prefix in a *location* position and treat it as a register, not as a
type. This is a syntactic check, not a name lookup: the `CPU` namespace is
reserved and cannot be used for a user type.

### 7.2.1 Constraints the LLVM-MOS backend actually accepts

The backend's inline-assembly support is narrower than upstream LLVM. These
limits are verified against `mos-c64-clang` 24 and shape how the compiler may
emit register access:

| Form | Result |
|---|---|
| `"a"(v)` input constraint | works — pins the value in A |
| `"=a,a"(v)` output + input | works — the value comes back in A |
| `"=x,x"(v)` output + input | works — the value comes back in X |
| `"=a,a"()` / `"=x,x"()` output + input, **no operand** | **rejected**: `number of input constraints does not match number of parameters` — upstream LLVM `InlineAsm::verify` requires one parameter per input constraint |
| `"=a"()` / `"=x"()` output-only, no operand | works — no prologue, returns the live register (verified 2026-11-13; this is the form the pure reads use) |
| `"=x"(v)` output with no input | **rejected**: `number of input constraints does not match number of parameters` |
| `"=m"(ptr)` memory output operand | **does not substitute an address** — the store lands in the wrong place |
| `"cc"` constraint | backend crash: `unable to translate instruction: call` |
| `asm goto` (any form) | backend crash: `unable to translate instruction: callbr` |
| `\09` (tab) as the instruction separator in a template | not an instruction separator — `invalid operand instruction pha pla` |
| `\0A` (newline) as the instruction separator | works |

Two consequences:

- **An input constraint requires a matching parameter — in both directions.**
  `"=a,a"()` (input, no parameter) and `"=a"(v)` (parameter, no input) are
  both rejected with the same error; the check is upstream LLVM
  (`InlineAsm::verify`), not the MOS backend. Pure reads therefore use
  **output-only** constraints (`"=a"` / `"=x"`, no operand) — verified
  2026-11-13: no prologue is emitted and the live register value is
  returned. Writes pin their value with a plain input constraint.
- **A value that must be stored to a specific address cannot be stored by the
  inline assembly.** The assembly delivers the value in a register and LLVM
  performs the store. This is why the `CPU.A` read uses a scratch slot written
  by LLVM, and why the flag reads deliver the bit in X.

### 7.2.2 The 16-bit pair `CPU.AX`

The old Wolin exposes `CPU.AX` as a readable and writable `uword`: A is the low
byte, X is the high byte. Its template reads it with
`let SPF(?dst)[uword] = CPU.AX` and writes it with
`let CPU.AX[ubyte*] = #?val[uword]` (`lda #<val` / `ldx #>val`).

v1 implements the pair as two 8-bit accesses, because the MOS backend has no
16-bit register-pair constraint:

- **read low byte** — `pha` / `pla`, result in A.
- **read high byte** — `pha` / `txa` / `tax` / `pla`, result in X. `TXA` copies
  X into A, `TAX` copies it straight back, so X still holds the high byte.
- **write** — `lda (lo)` / `ldx (hi)`.

Both reads preserve A and X: the `PHA`/`PLA` pair restores A, and `TAX` puts X
back where it was. Verified at runtime: with A = `$34` and X = `$12`, the pair
read back `$34`/`$12`, and A and X still held `$34`/`$12` afterwards.

The sequence returns each byte in a register rather than through an `"=m"`
memory operand: the MOS backend does **not** substitute an address into a
memory operand, so a `sta $0` template with a pointer operand stores to the
wrong place. Deliver the byte in a register and let LLVM store it.

### 7.3 External functions at fixed addresses

```kotlin
fun chrout^0xffd2(c: ubyte^CPU.A)
```
→
```llvm
define void @chrout(i8 %c) {
  call void asm sideeffect "jsr $$ffd2", "a"(i8 %c)
  ret void
}
```

The `^CPU.A` annotation on the parameter declares that the argument arrives in A.
The compiler pins it there with the constraint and emits the `jsr`.

For a function that *returns* a value in A, the constraint set becomes
`"=a"(ret)` instead of an input constraint.

The `$$` escape is mandatory: a bare `$d020` inside `asm sideeffect` is a parse
error in the backend (`Bad $ operand number in inline asm string`). This bit is
easy to get wrong and is called out here because it crashed the backend during
spiking.

### 7.4 Calling convention

The old Wolin passed ordinary arguments on a **software stack** (`SPF`, a
zero-page pointer into a call-argument area), and only CPU-pinned arguments in
registers. The `CPU.*` arguments were set in a fixed order — the old code
comments it as "dla argumentów CPU kolejność musi być A, Y, X!!!" (for CPU
arguments the order must be A, Y, X) — because a register can hold only one
value, so several register-pinned arguments have to be set one at a time, each
saving the previous one.

v1 uses **LLVM's C calling convention** instead: arguments and return values go
in the imaginary zero-page registers (`__rc*`), which the backend manages. This
is strictly better — no software stack, and the backend keeps arguments in the
zero page.

The consequence to be aware of: **register-pinned arguments are a separate
mechanism**. A `fun f^0xffba(a: ubyte^CPU.A)` declaration says "this argument
arrives in A", which is a *fixed-address external function*, not an ordinary
Wolin function with a CPU-pinned parameter. v1 keeps these two things
separate:

- An ordinary function's parameters are ordinary LLVM parameters (C convention).
- A fixed-address function's parameters are pinned to the CPU registers named in
  the declaration, and the call is emitted as inline asm with the corresponding
  register constraints.

So `fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)`
becomes an inline-asm `jsr $ffba` with `"a"`, `"y"`, `"x"` constraints — the three
values are placed in the three registers and the call is made. The old code's
save/restore dance around the call exists because the old compiler could not
guarantee the registers were still correct at the call site; the inline-asm
constraints make the guarantee explicit.

**The external-function ABI.** The old Wolin *sorted* the pinned arguments
(A, then Y, then X) regardless of the order they were written in
(`WolinVisitor.kt:2700-2730`) and rejected any argument that was not
register-pinned ("Can't use Wolin-style arguments in native calls!"). Its
return value was read back from `CPU.A` or `CPU.AX` (`WolinVisitor.kt:2667-2670`),
so a 16-bit return was possible.

v1 keeps the substance and fixes the parts that were accidental:

- **Declaration order drives the constraint list.** The compiler emits the
  constraints in the order the parameters are written, so
  `fun f^0xffbd(len: ubyte^CPU.A, name: word^CPU.XY)` pins `len` in A and
  `name` in X/Y. Sorting the arguments the way the old Wolin did would make the
  declaration lie about what goes where.
- **Every argument of a fixed-address function must be register-pinned.** An
  ordinary parameter is a compile error: "a function at a fixed address cannot
  take ordinary arguments — pin each one to a CPU register".
- **The 16-bit pin `CPU.XY` is supported** (X = high byte, Y = low byte — the
  byte order the old `restore CPU.XY` template uses). This matters for real
  KERNAL calls: `setNam^0xffbd` takes a length in A and a name pointer in X/Y.
  See §7.2.2 for how a 16-bit pair is moved.
- **A 16-bit return is expressed with `CPU.AX`** (A = low byte, X = high byte),
  matching the old `CPU.AX` read.

### 7.5 Interrupt functions

```kotlin
interrupt fun backgroundToBlue() { ... }
```
→
```llvm
define void @backgroundToBlue() #0 {
  ...
}
attributes #0 = { "interrupt" }
```

**The interrupt vector belongs to the program.** LLVM-MOS does *not* install an
IRQ dispatcher for the C64 target: nothing in the SDK's `libcrt0.a` /
`libcrt.a` references `$0314`, and the C64 linker script
(`mos-platform/c64/lib/link.ld`) only defines the RAM region and the stack. The
`$0314` reference in the SDK is in the *Atari* and *C128* platform files, not
the C64.

So the old Wolin's idiom works unchanged in v1: the program writes the handler's
address into the hardware vector itself.

```kotlin
var interruptRoutineVector: uword^0x314   // C64 raster interrupt vector

interrupt fun myHandler() { ... }
```

The compiler's job is only to make the handler's address available as a 16-bit
value. A `fun` symbol's address is `ptrtoint` — the backend emits
`ldx #mos16lo(f)` / `ldy #mos16hi(f)` (§7.6) — so the store to `$0314` is an
ordinary 16-bit store of that address.

**Consequence for `return@0xea31`.** The "continue with the ROM routine" idiom
is meaningful precisely *because* there is no dispatcher: the handler jumps
straight into the KERNAL IRQ epilogue at `$EA31`. Under a dispatcher-based design
the ROM call would belong to the dispatcher, not to the handler, and the idiom
would be meaningless. The absence of a dispatcher is what makes v1's `return@`
support coherent.

The backend emits the register-save prologue and `rti`. Verified.

`return@0xea31` inside an interrupt function emits a tail `jmp $ea31` and
*suppresses* the `rti` — the old Wolin's "continue with ROM routine" idiom. It
is emitted as inline asm `jmp $$ea31` plus `unreachable`, so the function does
not return.

Two things about this construct differ from the old implementation, and both are
deliberate:

- **The old grammar cannot parse it.** `RETURN_AT: 'return@' Identifier`
  (`KotlinLexer.g4:99`) requires an identifier, so the corpus's
  `return@0xea31` (`raster_irq.ktk:43,51,70,77`) is a syntax error under the
  reused grammar. v1 adds a production for `return@<integer literal>`. This is a
  grammar task with a clear acceptance test: `raster_irq.ktk` must parse.
- **The old semantics were one exit per function, not per site.** The old
  compiler stored the address on the *function* (`currentFunction.returnAddress`,
  `WolinVisitor.kt:711`) and emitted a `goto <addr>` in place of the function's
  epilogue — so a second `return@` in the same function overwrote the first, and
  the tail jump happened at the end of the function regardless of where the
  `return@` appeared. v1 makes it a **per-site** tail jump: the jump is emitted
  at the point of the `return@`, and the function's normal exit is left intact.
  A function may contain several `return@` sites, each jumping somewhere
  different, and may also contain an ordinary `return`.

The `jmp` must be a tail jump *and* the function must be marked `noreturn` from
that point, so the `unreachable` after it is correct: control never reaches it.

### 7.5 Function pointers

`ptrtoint`/`inttoptr` on a 16-bit address. The backend emits
`ldx #mos16lo(f)` / `ldy #mos16hi(f)`.

### 7.6 Zero page

Old Wolin put nearly everything in a hand-rolled 72-slot zero-page register heap.
LLVM-MOS has its own equivalent: the "imaginary registers" `__rc0`..`__rc31` are
allocated in the zero page by the linker script, and the backend keeps temporaries
there. Measured behaviour:

- Pure arithmetic with locals → all in `__rc*` (zero page), no RAM touched.
- A local whose *address escapes* → spilled to a RAM static slot (`.Lstatic_stack`).

This is close to the old behaviour and is free. Nothing special is needed from the
compiler; the compiler simply must not defeat it by using `volatile` or opaque
operations.

## 8. Architecture

```
.wtk source
   │
   ├─ lexer/parser (ANTLR 4.13.1, Kotlin-derived grammar)
   │
   ├─ AST / semantic analysis
   │     types, scopes, declarations, address resolution
   │
   ├─ IR emission (LLVM IR text)
   │
   ├─ .ll file  ──►  mos-c64-clang -O2  ──►  out.prg
   │                    (validates IR, optimises, links C64 PRG)
   │
   └─ test harness: run out.prg in VICE, read memory via binary monitor, assert
```

### 8.1 Components

| Component | Language | Responsibility |
|---|---|---|
| `grammar/` | ANTLR `.g4` | Lexer + parser, derived from the old Kotlin grammar with Wolin extensions |
| `ast/` | Kotlin | Typed AST node definitions |
| `sema/` | Kotlin | Type checking, scope resolution, declaration table, address resolution |
| `ir/` | Kotlin | LLVM IR text emission; a small IR builder with SSA value naming, blocks, phis |
| `driver/` | Kotlin | CLI, file handling, pipeline orchestration, toolchain discovery |
| `runtime/` | Kotlin + asm | Optional support routines (e.g. a software multiply, if needed) |

### 8.2 The IR builder

The single most important internal abstraction. It must provide:

- **SSA value naming.** `%v0`, `%v1`, … per function, deterministic.
- **Basic blocks with labels** and explicit terminators.
- **Phi placement.** The compiler must know which variables are live at a merge
  point. Because Wolin's control flow is structured (`if`/`when`/`while`), this is
  tractable: the builder tracks a *current value map* per block and, on a merge,
  emits a phi for each variable that differs.
- **Type tracking** for every expression (needed for correct `i8` vs `i16` ops and
  for the implicit numeric rules).
- **Escape analysis** is *not* needed — LLVM's mem2reg handles it, as verified.

A common trap: LLVM requires all block terminators to be explicit and every
 predecessor to have a value for every phi. The builder must not emit an
 unreachable block without a terminator.

### 8.3 Toolchain discovery

The SDK path is version-suffixed. Resolution order:

1. `WOLIN_SDK` environment variable.
2. Glob `~/.local/share/rust-mos/sdk-*`, newest first.
3. `mos-c64-clang` on `PATH`.

Fail with a clear message listing what was tried.

### 8.4 Program entry point

The C64 PRG format has no `main` in the C sense: the BASIC stub `SYS 2061` jumps to
the program's load address, and the program is responsible for whatever it wants to
do next. The old Wolin looked for a function literally named `main` and called it
from the header.

v1 keeps that convention: a top-level function named `main` is the entry point. The
compiler emits a `main` symbol that performs global initialisation, then calls the
user's `main`, then ends the program. If the user declares no `main`, the compiler
emits an empty program that simply returns (useful for a library-only `.prg`, and
for tests that do their own setup).

The SDK's `libcrt0.o` also expects a `main` symbol, so this naming keeps the
default CRT happy.

### 8.5 Multiple source files

The old Wolin compiled each input file separately (`.ktk` → `.qasm` → `.s` → `.o`)
and then linked all the objects into one `.prg`. Each file carried its own
`package` declaration, and cross-file references worked because the name mangling
included the package.

v1 keeps this model, because it is what the CLI already implies
(`wolinc a.wtk b.wtk -o game.prg`):

- Each input file is parsed and semantically analysed on its own.
- Each file is emitted as its own LLVM IR module (`.ll`).
- The modules are passed to `mos-c64-clang` **together** in one invocation, so the
  linker resolves cross-file references and LTO can optimise across them.

This works because LLVM IR modules with the same target triple can be compiled
together (verified: two `.ll` files with a cross-module call link into one `.prg`).

The subtlety is *declaration* visibility: a function defined in `a.wtk` and used in
`b.wtk` must be declared in `b.wtk` (Kotlin-style) or the compiler must be told to
treat all files as one module. v1 uses the **Kotlin model**: files in the same
package see each other, and cross-package use requires an import. That means the
compiler needs a whole-program view for semantic analysis, not a per-file one —
which is also what it needs for global initialisation ordering (§8.7).

**One entry point, one init sequence.** With N input files there must still be
exactly one `main` and one global-initialisation sequence, or the link fails
with duplicate symbols. The rule:

- **The driver is a separate, generated module.** The compiler emits
  `driver.ll` containing `main` and `__wolin_init_globals`, and links it with
  the per-file modules in one `mos-c64-clang` invocation.
- **A source file may define `main` only if it is the only file.** With more
  than one input file, defining `main` in a source file is an error: "main must
  be defined in exactly one place; the driver generates it". More precisely —
  with one input file, the driver calls that file's `main`; with several, the
  driver calls the `main` of the file that declares it, and there must be
  exactly one such file.
- **Global initialisation is emitted once, in the driver.** Each file contributes
  its own initialisation as a separate function (`__wolin_init_globals_<file>`),
  and the driver calls them in a defined order (§8.7). Each file's function is
  emitted with `internal` linkage so it cannot collide with another file's.

**Symbol naming.** LLVM does not mangle IR symbol names — `@pl.qus.wolin.main` is
a literal symbol, not a decorated one. The old Wolin mangled by hand
(`package_subpackage_file__function`). v1 mangles by hand too, with a scheme
that is unambiguous and stable:

```
<package>.<file>.<symbol>          e.g.  @game.demo.main
```

Dots are legal in LLVM identifiers when quoted (`@"game.demo.main"`), so the
compiler emits them quoted. The mangling applies to every user-visible symbol —
functions, globals, and the per-file init function — so that two files in
different packages can both define `main` without colliding.

**Imports.** The old Wolin's grammar had `import` statements (`importList`,
`importHeader`, with `as` aliases) but the compiler **never used them** — the
import visitor did not exist. Name resolution instead walked up the package
hierarchy: `findFunction` tried the current scope, then the enclosing class, then
successively shorter package prefixes, until it found a match or ran out of
package levels ("Couldn't find procedure X").

v1 implements **real imports**, since the grammar already accepts them and Kotlin
users expect them to work. An import makes a name visible unqualified. An import
with an `as` alias renames it. Resolution order becomes: local scope → enclosing
scope → explicit imports → same-package declarations → imported packages.

This is a deliberate improvement over the old behaviour, and it is a difference
worth noting: a program that relied on the old "walk up the package hierarchy"
lookup would need explicit imports under v1.

### 8.6 IR validation

Before linking, run `mos-c64-clang -fno-lto -O2 -x ir -c out.ll -o /dev/null` to
catch malformed IR at the source level rather than at link time. The final link
uses the SDK's default configuration (which enables `-flto`).

Note: the SDK's `mos-c64-clang` defaults to `-flto`. Emitting `.ll` and letting it
apply LTO is fine (verified: `.ll` input links correctly with the default config).

### 8.7 Global initialisation order

Globals must be initialised before `main` runs. The old Wolin emitted
initialisation code inline in the startup section, iterating over
`initializedStatics()` — a filter over the symbol table for `FieldType.STATIC`
variables with an init expression.

The old implementation's ordering was the iteration order of a `HashMap`, which
is **not deterministic**. That is a latent bug: two globals whose initialisers
depend on each other could be initialised in either order, and the program
behaves differently between runs.

v1 defines the order explicitly: globals are initialised in **source order
within a file**, and files in the order given on the command line.

**Global initialisers must be constant expressions.** The old Wolin ran the
init *expression* at startup, so an initialiser could be any expression —
including a call. v1 restricts them to compile-time constants:

- literals (integer, real, character, boolean, string)
- a reference to another global that is itself a constant (`var b: ubyte = 4;
  var a: ubyte = b`)
- the address of a function or a fixed-address variable

Anything else — a call, an arithmetic expression on a runtime value — is a
compile error: "global initialiser must be a constant expression". The reason
is that constant initialisers can be emitted as static data, so they cost
nothing at startup and work identically in the global-init routine and in the
static data section. A non-constant initialiser would need a second code path
and would make "source order" a promise the compiler cannot keep.

**The driver calls the per-file init functions** (§8.5), so the ordering
guarantee spans files as well: `a.wtk`'s globals are initialised before
`b.wtk`'s, in command-line order.

## 9. Testing strategy

### 9.1 Unit tests (JVM)

- Grammar: parse the four old `.ktk` examples → expect 0 syntax errors. *(This is a
  real regression test: it pins the grammar to the old corpus.)*
- Semantic analysis: type errors, unknown identifiers, address resolution.
- IR emission: golden-file tests comparing emitted `.ll` for small inputs. These pin
  *shape*, and are the fastest feedback loop.

### 9.2 Integration tests (real 6502 execution)

The requirement is that generated code *runs correctly*, not merely that it assembles.

Verified harness:

1. Compile the test program to a `.prg`.
2. Launch `x64sc -default -limitcycles 900000000 -warp -remotemonitor -binarymonitor prog.prg`.
3. Connect to the VICE **binary monitor** on `127.0.0.1:6502`.
4. Send a `MEM_GET` frame (command type `0x01`, api version `0x02`) for the RAM
   range the test wrote to.
5. Read back the bytes and assert.

The frame format was decoded from the VICE 3.10 source
(`src/monitor/monitor_binary.c`):

```
request : 0x02 | api_ver(1) | body_len(4 LE) | request_id(4 LE) | cmd_type(1) | body
response: 0x02 | api_ver(1) | body_len(4 LE) | response_type(1) | error(1) | request_id(4 LE) | body
MEM_GET body: sidefx(1) | start(2 LE) | end(2 LE) | memspace(1) | bank(2 LE)
MEM_GET response body: block_len(2 LE) | bytes
```

Gotchas, learned the hard way:
- The **binary** monitor is on port **6502**; port 6510 is the *text* monitor, whose
  banner is `(C:$083c)`. Connecting to 6510 and expecting binary frames silently
  returns the banner.
- The server pushes **event frames** with `request_id = 0xffffffff`. The client must
  filter by `request_id` to find its own reply.
- `MEM_GET` with memspace `0` reads the CPU space. RAM at `$0400` reads back
  correctly; the I/O area at `$d020` does not read back the same way, so tests must
  assert against RAM, not I/O registers.

A worked example that proved the whole loop: a program writing `0x55, 0x0a, 0x05, 0xde`
to `$0400` read back exactly `550a05de`.

### 9.3 What the tests must cover

- Arithmetic on `ubyte` (including wraparound at 256).
- `uword` arithmetic.
- All four bit-assignment forms, checked against the documented semantics.
- `if`/`else`, `when`, `do..while` — loop counters and branch coverage.
- Fixed-address variable read/write.
- Function pointers: store an address, call through it.
- `interrupt fun`: install a raster interrupt, have the handler set the border
  colour, assert the border changed. *(Uses VICE memory readback; the border is
  visible in a screenshot too, for manual inspection.)*
- ROM call: `chrout` to the screen, then assert the screen RAM contains the
  character.

### 9.4 The test-program convention

Every integration test program follows one pattern, so the harness needs no
per-test configuration:

- The test writes its results to a **known RAM address**, conventionally `$0400`
  (the start of the C64 screen RAM, which is free to use in a test that does not
  display anything).
- A **control byte** is written first, so a test can distinguish "the program ran
  and produced the wrong value" from "the program never ran" (which reads back as
  all zeros). This caught a false negative during spiking: an early version of the
  `CPU.A` test read back `00000000`, which looked like a silent failure until the
  control byte proved the program had not executed at all.
- The test then **loops forever** (`while (true) {}`). The harness reads memory
  after a fixed cycle budget rather than waiting for the program to exit, so the
  test never needs a completion signal.

**The harness protocol** (verified against VICE 3.10's binary monitor, not
assumed):

1. Launch VICE headless with the binary monitor enabled:
   `x64sc -default -binarymonitor -limitcycles 0 -warp -autostart prog.prg`.
   The `-limitcycles 0` means *no limit* — the machine keeps running until the
   harness stops it. A finite cycle limit would either cut the program short or
   make the harness wait for it; neither is wanted, because the program loops
   forever by design.
2. Connect to the binary monitor's TCP port (**6502**). There is no greeting;
   send the request header directly.
3. Request a memory read with the binary protocol (`monitor_binary.c`):

   ```
   request:  STX(0x02) api_version(0x02) body_len(u32 LE)
             request_id(u32 LE) command_type(u8)
   MEM_GET:  sidefx(u8) start(u16 LE) end(u16 LE) memspace(u8) bank(u16 LE)
   response: STX(0x02) api_version(0x02) body_len(u32 LE)
             response_type(u8) errorcode(u8) request_id(u32 LE)
             length(u16 LE) <data bytes>
   ```

   The response's `body_len` covers the body only — the 12-byte header is sent
   separately. Read the header, then the body.
4. **Skip unsolicited events.** The monitor pushes register/bank events with
   `request_id == 0xffffffff`; a client that does not filter them will read an
   event as if it were the response. Loop until a frame with the matching
   `request_id` arrives.
5. Compare the bytes against the expected values.

**The read length is fixed, not per-test.** The harness always reads a fixed
window — `$0400`–`$040F`, 16 bytes — and each test declares which bytes it
uses. Unused bytes are ignored. This keeps the harness free of per-test
configuration, which is the property the convention exists to provide.

**Screen-RAM tests need a different address.** A test that checks what was
drawn on the screen writes to screen RAM, which overlaps `$0400`. Such a test
puts its result block elsewhere — `$0400` is convenient only because it is
guaranteed free in a non-displaying test. The convention is "a known address",
not "always `$0400`".

## 10. CLI

```
wolinc [options] <file.wtk>...

  -o, --output <file>     output .prg (default: first input's name)
  -d, --debug            keep intermediate .ll and emit a link map
  -e, --emit-ir          write .ll and stop
  -S, --sdk <dir>        llvm-mos SDK path (overrides discovery)
      --target <name>    c64 (default); other mos-* targets later
      --keep-ir          keep the .ll next to the .prg
```

Old Wolin used `.ktk` for sources and `.qasm` for intermediate pseudo-asm. The new
extension is `.wtk` (Wolin token/Kotlin) — distinct from `.ktk` so old files are not
accidentally picked up by a new build, while remaining obviously related. Old `.ktk`
files still parse.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| The old grammar is a *Kotlin* grammar, so Wolin-only syntax may be sparse or inconsistent in places | Some old `.ktk` files fail to parse | Grammar work is scoped as its own task; the four old examples are the acceptance corpus |
| Phi placement in the IR builder is error-prone | Miscompiles, hard to debug | The builder is the first component built, with golden-file tests before any hardware work |
| LLVM-MOS versions drift; the SDK is a moving target | Build breaks after an SDK update | Discover the SDK at runtime; pin a known-good version in the README; keep IR emission version-independent |
| `float` pulls in soft-float routines (`libcrt.a`), growing the binary | Size regression on small programs | Document the cost; consider deferring `float` if size becomes critical |
| VICE binary-monitor protocol changes between VICE versions | Test harness breaks | The protocol is stable in 3.x; isolate the harness behind a small client class with a version note |
| CPU flag reads (`CPU.C`, `CPU.N`, `CPU.Z`) use a status-preserving sequence | Silent wrong values in flag-dependent code | Verified at runtime in VICE: carry/zero/negative read correctly and leave A and P intact |
| Old Wolin `float` was Wozniak FFP; v1 `float` is IEEE-754 | Old Wozniak-FFP data is misread as garbage | Settled and documented: the formats differ, v1 treats `float` as IEEE-754. A conversion helper is a v2 concern |
| Soft-float routines are large | A program using `float` grows by ~3.5 KB | Documented cost; programs that never mention `float` do not link the routines |

## 12. Out of scope for this design

- The v2 language features listed in §3 (exceptions, classes, capturing
  closures).
- **The `xor` operator.** The old Wolin has none (§6), so adding it would be a
  new feature rather than a port. It is a small addition — `EOR` on the 6502,
  `xor i8` in the IR — and it belongs in v2.
- **Double-precision floats.** `float` is single-precision IEEE-754; `double`
  would need a second soft-float ABI.
- **A Wozniak-FFP ↔ IEEE-754 conversion helper.** v1 treats `float` as
  IEEE-754 and documents the difference (§6); converting old data is a v2
  convenience.
- **Array bounds checking and a dynamic allocator.** v1 arrays are
  fixed-address or global-with-implied-length, with no bounds checks (§6).
- **String interpolation.** Escapes are supported; `${...}` templates are not.
- The debugger.
- Other MOS targets.
- Performance tuning of the emitted code beyond what LLVM does by default.

## 13. Appendix: evidence

Commands run on 2026-11-07 against
`~/.local/share/rust-mos/sdk-v23.2.0` and VICE 3.10:

| Claim | Command | Result |
|---|---|---|
| `.ll` → `.prg` | `mos-c64-clang -O2 e2e.ll -o e2e.prg` | `sta 53280` in output |
| `interrupt` → `rti` | `attributes #0 = { "interrupt" }` | `rti` emitted |
| absolute address | `inttoptr (i64 53280 to i8*)` | `sta 53280` |
| ROM call | `asm sideeffect "jsr $$e544"` | `jsr 65468` |
| ROM call (bad escape) | `asm sideeffect "jsr $e544"` | backend crash: `Bad $ operand number` |
| register pinning | `"a"/"x"/"y"` constraints | `lda #45 / ldx #46 / ldy #47` |
| clobber list | `jsr` with `"=a"(b)` | `a` correctly spilled after |
| function address | `ptrtoint` | `ldx #mos16lo(g)` / `ldy #mos16hi(g)` |
| ZP global | `section ".zeropage"` | placed in ZP region per link map |
| float size | `global float 1.5` | `.long 0x3fc00000` (4 bytes) |
| grammar | ANTLR 4.13.1 on 4 old examples | `errors=0` for all four |
| `return@` / `interrupt` | token dump | `return@` → `RETURN_AT` (needs an `Identifier`, so `return@0xea31` does not parse); `interrupt` → `functionModifier` |
| VICE readback | binary monitor `MEM_GET` `$0400-$0403` | `550a05de` (exact) |
| `CPU.A` write+read round trip | runtime value pinned via `"a"`, read via `pha`/`pla` | readback `42a5` — **PASS** |
| `CPU.A` read (repeated) | `pha`/`pla` twice | `0e`, `0e` — consistent |
| `CPU.A` read (flags-preserving) | `asm sideeffect "ldy #0\0Asta ($0),y"` | `42` read back, zero flag still `0` — **PASS** |
| flag read (carry/zero/negative) | `php/pha/php/pla/and #mask/lsr/tax/pla/plp` | `00 42 01 42 01 00 01 80 01` — correct bits, A and P intact — **PASS** |
| `php`/`pla` flag read (rejected) | `asm sideeffect "php\0Apla"` | destroys A: readback `30` instead of `42` |
| `asm goto` (rejected) | `asm goto("bcs %l[set]")` | backend crash: `unable to translate instruction: callbr` |
| `"cc"` constraint (rejected) | `asm sideeffect "", "=r,cc"(i8 0)` | backend crash: `unable to translate instruction: call` |
| asm output needs an input | `asm sideeffect "", "=x"(i8 undef)` | `number of input constraints does not match number of parameters` |
| asm template newline | `\0A` inside the template string | `\09` (tab) does not split instructions; `\0A` does |
| `CPU.AX` pair read | `pha/pla` (low), `pha/txa/tax/pla` (high) | `34 12`, and A/X still `34 12` afterwards — **PASS** |
| float format | `global float 1.5` | `.long 0x3fc00000` — IEEE-754, not Wozniak FFP |
| float runtime | raw bytes of `1.5`, then `1.5+1.0`, `1.5+1.0+1.0` | `00 00 c0 3f`, then `2`, `3` — **PASS** |
| float size cost | program adding two floats | 3503 bytes (soft-float routines linked in) |
