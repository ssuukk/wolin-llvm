# Wolin Language Reference

The Wolin dialect compiled by `wolinc` to C64 (6502) machine code. The grammar
is the Kotlin grammar (ANTLR); the type checker, const folder and LLVM-IR
emitter are the Wolin-specific parts. Every feature below is implemented.
What is *not* implemented: see `TODO.md`.

Conventions in the examples: `var out: ubyte^0x400` is a fixed-address
variable (see [Fixed addresses](#fixed-addresses-and-hardware)); programs end
in `while (true) {}` because the C64 program loop never returns.

---

## Types

| Type | LLVM | Domain | Notes |
|---|---|---|---|
| `ubyte` | `i8` | 0..255 | the primary 8-bit type |
| `byte` | `i8` | −128..127 | two's complement; comparisons, `/`, `%` are **signed**; arithmetic wraps modulo 256. Literals cover the full −128..127 range. |
| `uword` | `i16` | 0..65535 | |
| `word` | `i16` | −32768..32767 | signed like `byte` |
| `bool` | `i1` | `true`, `false` | |
| `unit` | — | | the type of statements and functions with no return type |
| `float` | `float` | IEEE-754 single | **not** the old Wozniak FFP format; first use links ~3.5 KB of soft-float code |
| `string` | `ptr i8` | NUL-terminated byte sequence | |
| `elem[index]` | `ptr` | | array; the index type is part of the type: `ubyte[ubyte]`, `uword[uword]` |
| `(a, b)->r` | `ptr` | | function pointer type, e.g. `(ubyte,ubyte)->ubyte` |

A declaration without a type takes the type of its initialiser. A declaration
with neither a type nor an initialiser is an error (global arrays excepted —
they are zero-init and sized by the index type).

```kotlin
var x = 5            // ubyte (smallest type holding 5)
var x = 300          // uword
var x = -1           // byte
var x: uword = 0     // literal takes the declared type
```

**There is no implicit promotion.** Mixed operand types in arithmetic,
comparison, equality or bitwise operations are a compile error
("Attempt reassign X Y"); an integer literal is the only value that takes its
type from context. Arithmetic never widens: `ubyte + ubyte` is `ubyte`,
`uword + uword` is `uword`, and the result type of any binary numeric
operator is the **left** operand's type.

**Conversions:** `toByte()`, `toUbyte()`, `toWord()`, `toUword()` —
explicit, the only way a value changes integer type; same width reinterprets,
widening sign- or zero-extends, narrowing truncates. They chain, so
`b.toUbyte().toUword()` widens in two steps. A `byte` still cannot be
assigned to a `word` variable or passed to a function expecting a `ubyte`
without a conversion.

## Declarations
var x: ubyte                  // local: a stack slot (or global: data section)
var x: ubyte^0xd020           // fixed-address variable: direct load/store, no storage
var x: bool^CPU.I             // CPU status flag: interrupt bit
var carry: bool^CPU.C         // CPU status flag: carry bit
var arr: ubyte[ubyte]         // global array, data section, zero-initialised, 256 elements
var arr: ubyte[uword]         // 65536 elements (size = index type's domain)
var arr: ubyte[ubyte]^0x3000  // array at a fixed address
val x: ubyte = 5              // cannot be assigned to
fun f(a: ubyte, b: ubyte): ubyte { return a + b }
fun f()                       // no return type = unit
interrupt fun handler() { }   // 6502 interrupt handler: exits with rti, not rts
fun chrout^0xffd2(c: ubyte^CPU.A)   // fixed-address function: a ROM call (no body)
```

- **`val`** cannot be assigned to; "Cannot assign to val x".
- **`CPU`** is a reserved namespace: the only valid `CPU.*` names are the
  register locations. `CPU` as a type is an error.
- A global's initialiser may reference **earlier globals** (their constant
  values) and **functions** (their address, computed at startup). Global
  initialisers that are constant expressions are folded at compile time;
  division or remainder by zero in a folded initialiser is a compile error.

```kotlin
var a: ubyte = 7
var b: ubyte = a + 1          // folded to 8 at compile time
var addr: uword = f           // f's address; computed in the init function
```

## Statements and control flow

```kotlin
if (c) { ... } else { ... }
when (x) {                    // subject form: x compared against each condition
    1, 2 -> { ... }           // several conditions in one entry are OR-ed
    else -> { ... }           // must be last
}
when {                        // condition form: each condition is a bool
    a > b -> { ... }
    else -> { ... }
}
while (cond) { ... }
do { ... } while (cond)
break
continue
return value
return@0xea31                 // tail jump to a fixed address: "continue in the ROM"
name@                         // a label: a named jump point
break@name                    // jump to the label
continue@name
return@name                   // the label form of the tail-jump idiom
```

- `if` and `when` also work as **expressions**: the value is the last
  expression of the taken branch, or `unit` when the branch does not end in
  an expression.

```kotlin
var sign: byte = if (n < 0) -1 else 1
var code: ubyte = when (key) { 0x4C -> 1; 0x4F -> 2; else -> 0 }
```

- Unlabelled `break`/`continue` target the innermost enclosing loop;
  `break@name`, `continue@name`, `return@name` jump to the label `name`.
- A label is a named point inside a function: references may be forward or
  backward, and labels are local to the function.

## Operators

| Class | Operators | Notes |
|---|---|---|
| Arithmetic | `+` `-` `*` `/` `%` | wraps in the result type's width; for `byte`/`word`, `/` and `%` are **signed** (truncate toward zero); for `float`, IEEE-754 |
| Equality | `==` `!=` | bitwise equality |
| Comparison | `<` `<=` `>` `>=` | **signed** for `byte`/`word`, unsigned for `ubyte`/`uword`; ordered `fcmp` for `float` |
| Bitwise | `&` `|` `xor` | any integer type, same-width operands |
| Logical | `&&` `||` `!` | bool only; `&&`/`||` **short-circuit** (the old Wolin did not — that is a deliberate bug fix) |
| Increment | `++` `--` | prefix and postfix; postfix yields the old value, prefix the new one |
| Assignment | `=` `+=` `-=` `*=` `/=` `%=` | value must have the target's type (literals excepted) |
| Bit-assignment | `:=` `.=` `&=` `\|=` | `:=` sets bits (or), `.=` clears bits (and-not), `&=` and `\|=` mask; the mask may be *narrower* than the target (it is extended) |

### Precedence, highest to lowest

1. multiplicative: `*` `/` `%` **`&`**
2. additive: `+` `-` **`|`**
3. infix calls: **`xor`**
4. comparison: `<` `<=` `>` `>=`
5. equality: `==` `!=`
6. conjunction: `&&`
7. disjunction: `||`

Note the difference from Kotlin: Wolin's grammar binds `&` at the
multiplicative level and `|` at the additive level, so `a | b xor c & d`
groups as `(a | b) xor (c & d)`.

```kotlin
var c: ubyte = a & b | c          // (a & b) | c
var d: ubyte = a + b * c          // a + (b * c)
var e: bool = a < b && c == d     // (a < b) && (c == d)
```

### `++` / `--`

```kotlin
var i: ubyte = 0
i++            // i is 1; the expression's value was 0 (the old value)
var old: ubyte = i++
var fresh: ubyte = ++i
```

## Literals

- **Integers:** decimal `42`, hex `0xD020`, binary `0b00000011`,
  underscores allowed. Typed contextually: the expected type when there is
  one, else the smallest type holding the value (`ubyte`, then `uword`, then
  `byte`/`word` for negatives). A value that does not fit its declared type
  is "Value out of range".
- **Negative integers:** `-1` is a byte, `-32768` a word. `var x: ubyte = -1`
  is an error. A sign-prefixed literal takes the other operand's type in a
  binary operation, so `w == -1` on a `word` compares `word`s; without a
  context `-1` is a `byte`.
- **Reals:** `1.5` → `float`.
- **Booleans:** `true`, `false`.
- **Characters:** `'A'`, `'\n'` → `ubyte`. Escapes: `\n` `\t` `\r` `\b` `\\`
  `\'` `\"` `\uXXXX`.
- **Strings:** `"text"` or triple-quoted `""""text"""`. Escapes: `\n` `\t`
  `\r` `\0` `\\` `\"` `\'` `\uXXXX`. Stored NUL-terminated in the data
  section. String interpolation `${...}` is a compile error (see `TODO.md`).

```kotlin
var ch: ubyte = 'A'
var s: string = "hi\r\n"
var b: ubyte = s[0]             // indexing a string yields a ubyte
```

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

## Fixed addresses and hardware

`var x: T^0xADDR` declares the variable *at* the address: reads and writes
are direct (volatile) loads and stores of the variable's width, and the
variable has no storage of its own.

```kotlin
var border: ubyte^0xd020         // the VIC-II border colour register
var screen: ubyte[ubyte]^0x0400  // screen RAM

fun main() {
    border = 6
    screen[10] = 13
}
```

### CPU registers

In v1 a variable cannot live in a CPU register: `var x: ubyte^CPU.X` is
rejected by the checker. Registers only appear in ROM call signatures,
where a parameter or return value names the register it passes through
(see [ROM calls](#rom-calls)).

The exception is a flag variable: `var maskInterrupts: bool^CPU.I` (the
interrupt flag) and `var carry: bool^CPU.C` (the carry flag). A flag is a
single bit of P that the compiler can read and write without clobbering its
own accumulator, so it survives interleaved code. A write lowers to the
flag's set or clear instruction - `sei`/`cli` for `CPU.I`, `sec`/`clc` for
`CPU.C`; a read returns the bit as a `bool`. Only these two flags qualify:
the 6502 has a set and a clear instruction for `C` and `I`, but none for
`N` or `Z`, so `bool^CPU.N` and `bool^CPU.Z` are rejected.

| Register | Type |
|---|---|
| `CPU.A` `CPU.X` `CPU.Y` | `ubyte` |
| `CPU.C` `CPU.I` | `bool` (flags) |
| `CPU.AX` `CPU.XY` | `uword` (16-bit pairs) |

### ROM calls

A function declared with a fixed address and **no body** is a ROM (or any
fixed-address) routine. Every parameter must name the single register it
arrives in. A return type names the register — or register pair — the
result comes back in.

```kotlin
fun chrout^0xffd2(c: ubyte^CPU.A)
fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)
fun readst^0xffb7(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X): ubyte^CPU.A

fun main() {
    chrout('A')
    setLfs(0, 0, 8)          // LFS to drive 8
    val status = readst(0, 0, 8)
}
```

A return type is a `ubyte` in a single register, or a `uword` in a
register pair. A pair lists its registers low byte first: `CPU.AX` reads
A as the low byte and X as the high byte; `CPU.XY` reads X as the low
byte and Y as the high byte, the layout the C64 kernal leaves 16-bit
results in.

```kotlin
fun membot^0xff9c(): uword^CPU.XY   // bottom of memory: X low, Y high
fun rdtim^0xffde(): uword^CPU.AX    // RDTIM: A low, X high
```

### `return@<address>`

Tail-jumps to a fixed address instead of returning — the "continue with the
ROM routine" idiom. Each function may carry several sites; the address lives
at the site.

```kotlin
fun patch() {
    // ... do the patch ...
    return@0xea31        // jump into the ROM, not back to the caller
}
```

### Labels

A label is a named point inside a function:

```kotlin
fun main() {
    var i: ubyte = 0
    scan@
    while (i < 4) {
        i = i + 1
        if (i == 2) continue@scan
    }
    done@
    // ...
}
```

`name@` marks the point; `break@name`, `continue@name` and `return@name` jump
to it. All three compile to the same `jmp` to the label's block; the names are
intent: `break` leaves a region, `continue` resumes a loop, `return` is the
label form of the `return@<address>` tail-jump idiom. A label may be
referenced from before or after its definition, and is local to the function.
A label name may be any identifier.

Placement idiom: put `scan@` before a loop to retry it with `continue@scan`,
and `done@` after the loop to leave it with `break@done`. One label cannot
serve both: the continue target and the break target are different points.

A `return@<label>` is an in-function return: unlike the address form, it does
not strip the `interrupt` attribute from an interrupt handler. In an `if`
or `when` expression, a branch that ends in a label is a `unit` branch: the
other branch's value is used, and the jumping branch contributes none.

### Interrupts

`interrupt fun` marks a function as a 6502 interrupt handler. The
modifier's only effect is the function's exit: a normal exit emits `rti`
(return from interrupt) instead of the usual `rts`. A 6502 interrupt
handler was entered by the interrupt mechanism, not by a `jsr`, so it must
return with `rti`.

The backend emits a prologue and epilogue around the body that save and
restore A, X, Y and the software stack pointer, so a handler may freely use
the CPU registers and declare local variables.

An `interrupt fun` may not take arguments and may not return a value:
"Arguments not allowed for interrupt function!", "Return not allowed for
interrupt function!".

**Installing the handler.** The compiler does not install the handler; the
program writes its address to an IRQ vector:

```kotlin
var irqVector: uword^0x0314 = 0
irqVector = raster
```

Two vectors, two exit styles:

- **$FFFE** (the hardware IRQ vector): the handler runs with only the CPU's
  pushed status and return address on the stack, so its `rti` exit is
  correct; the prologue's A/X/Y save/restore keeps the interrupted program
  safe.
- **$0314** (the KERNAL's IRQ vector): the KERNAL's IRQ entry ($FF48) has
  already saved A, X, Y on the stack before `jmp ($0314)`, so a bare `rti`
  would pop those saved registers instead of the return address. A handler
  installed here must end with `return@0xea31`: the KERNAL's default IRQ
  tail ($EA31 → $EA81) re-runs the KERNAL's own IRQ work (cursor blink, key
  scan, jiffy clock), acknowledges the CIA1 interrupt, restores the saved
  A, X, Y, and performs the final `rti`.

**`return@<address>`** turns the exit into a `jmp <address>` instead of the
`rti`. Inside a handler the standard form is `return@0xea31`; outside
handlers it tail-jumps into fixed ROM code. A plain `return` still exits an
`interrupt fun` with `rti`; statements after a `return` are dead code.

```kotlin
interrupt fun raster() {
    // ... do the handler's work ...
    return@0xea31   // jmp $EA31: the KERNAL finishes the IRQ
}
```

## Functions and function pointers

```kotlin
fun add(a: ubyte, b: ubyte): ubyte { return a + b }
```

Function values are 16-bit code addresses. Two distinct forms:

```kotlin
// A uword holding an address: indirect call through it.
var addr: uword = add
// (the call through a uword is unchecked: arguments are well-formedness-checked only)

// A function-pointer variable with a known signature.
var f: (ubyte, ubyte) -> ubyte = add
var r: ubyte = f(2, 3)
```

**Function literals** are non-capturing: `{ a, b -> a + b }` is just a
function. The parameter types are inferred from the variable's declared type
at the assignment site; a literal assigned where no expected type is
available is an error ("cannot infer the parameter types of this function
literal; give it an explicit type"). Capturing closures are not implemented
(`TODO.md`).

```kotlin
var suma: (ubyte, ubyte) -> ubyte
fun main() {
    suma = { a, b -> a + b }
    var r: ubyte = suma(2, 3)
}
```

## Packages, imports, multiple files

Each input file is its own LLVM module; the driver module links them and
calls into them. Files declare a package; names from the same package are
visible without an import, names from other packages need an import.

```kotlin
// lib.wtk
package lib
fun helper(): ubyte { return 7 }
var shared: ubyte = 9

// main.wtk
package app
import lib.helper
import lib.shared

fun main() {
    var r: ubyte = helper()
    var s: ubyte = shared
}
```

- `import a.b.*` (wildcard) is supported; `import a.b as c` (alias) is a
  compile error.
- A file's own globals shadow same-package globals of other files; a global
  initialiser may reference another file's function.
- Global initialisation order is command-line order, same-package files
  first.

## Standard library

Three files ship in `stdlib/`; pass the ones you use on the command
line before your own files:

```
wolinc stdlib/c64/kernal.wtk stdlib/c64/vic.wtk stdlib/wolin/screen.wtk myprog.wtk -o myprog.prg
```

- `c64/kernal.wtk` (`package c64.kernal`) — kernal entry points as
  fixed-address declarations: `chrout^0xffd2(c: ubyte^CPU.A)` outputs the
  byte in A.
- `wolin/screen.wtk` (`package wolin`) — Wolin built on the kernal:
  `print(x: string)` writes every byte up to the NUL terminator through
  `chrout`. Use `\r` (`0x0D`) in the string for a screen newline.
  `printAt(row, col, s, color)` writes a string straight into screen RAM
  at row and column, in the given colour; `screenCode(p)` is the PETSCII
  → screen code conversion it uses (screen RAM holds screen codes, not
  PETSCII — see [PETSCII](#petscii)). Fixed-address windows `screen` ($0400)
  and `colorRam` ($D800) sit on top; `clearScreen^0xe544` clears the
  screen through the kernal.
- `c64/vic.wtk` (`package c64.vic`) — the VIC-II register map ($d000-$d02f) as
  fixed-address `ubyte` windows: `sprite0X`…`sprite7Y` (sprite
  coordinates), `rasterCounter`, `screenAddressHigh`/`screenAddressLow`,
  `interruptStatus`/`interruptEnable`, `borderColor`,
  `backgroundColor0`–`backgroundColor3`, `spriteColor0`–`spriteColor7`
  and friends. A read fetches the register, a write stores it; the two
  read-only collision registers are declared `val`.

```wtk
package app
import wolin.print
fun main() { print("hi") }
```
`print` indexes with a `ubyte`, so a string longer than 255 bytes wraps
the index and prints forever; that is the v1 limit on all string
indexing. A kernal routine that returns a value declares the register
the result comes back in: `readst^0xffb7(): ubyte^CPU.A` (see
[ROM calls](#rom-calls)). String bytes go out as written: screen codes are
the index and prints forever; that is the v1 limit on all string
indexing. Kernal routines that return a value in A cannot be declared
yet (see `TODO.md`). String bytes go out as written: source strings are
converted to PETSCII at compile time (uppercase letters to $C1–$DA,
lowercase to $41–$5A), and CHROUT maps them onto the screen-code
character set.

## PETSCII

A source string is mapped to PETSCII at compile time, through the 64tass
Unicode → PETSCII table. The case is inverted on the way: an uppercase
letter becomes the PETSCII high range ($C1–$DA), a lowercase letter the
low range ($41–$5A). The box-drawing, block, suit and symbol characters
have their PETSCII slots too, so a source frame like

```wtk
print("┌───────┐")
```

reaches the screen as PETSCII $B0 $C0 $C0 $C0 $C0 $C0 $C0 $C0 $AE.

The case inversion is undone by the KERNAL: CHROUT maps PETSCII onto the
screen codes, and PETSCII $41 (a lowercase 'a') becomes screen code
$41 (an uppercase 'A'). The two cancel, so the text reads on screen with
the case it was written in.

Screen RAM holds **screen codes**, not PETSCII: the VIC-II indexes the
character ROM by the screen code, and the two differ. `chrout` does the
conversion; a program that writes screen RAM directly has to do it too,
which is what `wolin.printAt` does through `wolin.screenCode`:

| PETSCII | screen code |
|---------|-------------|
| $20–$3F | $20–$3F |
| $40–$5F | $00–$1F |
| $60–$7F | $40–$5F |
| $80–$9F | $C0–$DF |
| $A0–$BF | $60–$7F |
| $C0–$FE | $40–$5F |
| $FF | $5E |

The table was measured on a C64: every PETSCII byte was sent through
CHROUT and the resulting screen RAM read back.

## Diagnostics

Errors are `SemaException`s with a message that names the problem:

| Message | Cause |
|---|---|
| `Unsupported construct: <name>` | a v1-rejected Kotlin construct; the name identifies it (`class C`, `elvis operator`, `for loop`, …). The full catalogue is pinned by `UnsupportedTest`. |
| `Attempt reassign X Y` | mixed operand types in arithmetic/comparison/bitwise, or an assignment whose value has a different type |
| `Value out of range` | a literal that does not fit its declared type |
| `Unknown type X` / `Unknown variable X` / `Unknown function X` | resolution failure |
| `X conversion requires an integer, not Y` | a conversion on a non-integer |
| `Cannot assign to val X` | assignment to a `val` |
| `The else branch of a when must be last` | `else` not in the final position |
| `when condition type X does not match subject type Y` | subject-form `when` with a mismatched condition |
| `Array literal needs an array type` | `var x = [1, 2]` with no declared array type |
| `Array literal can only initialise a declaration` | an array literal in assignment or expression position |
| `Too many array elements` (with the count and the domain) | a list longer than the index type's domain |
| `String initialiser needs a ubyte element type` | a string initialiser on a `uword`-element array |
| `String initialiser too long` (with the length and the domain) | a string whose bytes + NUL exceed the index domain |
| `local arrays need a fixed address in v1` | an array initialiser on a local non-fixed array |
| `Unknown label X` | a labelled jump to a name that is not a label of the current function |
| `Label X already defined` | the same label name declared twice in one function |

## Semantics notes — where v1 differs from the old Wolin

- **`float` is IEEE-754**, not the Wozniak FFP format. Old float data is not
  byte-compatible.
- **Literals are typed contextually** with no implicit promotion; the old
  language had the same no-promotion rule.
- **`&&` / `||` short-circuit.** The old Wolin compiled them to bitwise
  `and`/`or` with both operands always evaluated.
- **`x .= v` clears bits** (`x = x and not v`). The old template *replaced*
  the byte with the complement of the mask; the documented intent is
  implemented, the discrepancy is recorded in the spec.
- **`when` subject form compares the subject against each branch value.**
  The old compiler compared the subject against the *condition expression*
  (an inverted `simpleWhen` flag); v1 implements the documented intent.
- **Prefix `++`/`--` work.** The old Wolin was postfix-only; both forms are
  accepted, and postfix yields the old value in both compilers.
- **Division and modulo exist on all integer widths** (the old Wolin had
  8-bit division and no modulo); they are runtime library calls, so
  noticeably slower than `+`/`*`.
- **`xor` exists.** The old Wolin had no `xor` at all; it is written as the
  infix call `a xor b`.
- **`byte` is signed** and conversions are explicit: `toByte()`, `toUbyte()`,
  `toWord()`, `toUword()` are the only way a value changes integer type.
