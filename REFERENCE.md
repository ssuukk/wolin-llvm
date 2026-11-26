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
| `elem[index, N]` | `ptr` | | array with explicit length `N`; `elem[N]` uses a `ubyte` index |
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
var arr: ubyte[ubyte, 16]     // 16 elements, ubyte index
var arr: ubyte[16]            // 16 elements, ubyte index
var arr: uword[uword, 16]     // 16 elements, uword index
var arr: ubyte[ubyte]^0x3000  // array at a fixed address
val x: ubyte = 5              // cannot be assigned to
enum { PENDING = 0, RUNNING = 5 }    // anonymous ubyte constants
enum Status { PENDING, RUNNING = 5 } // named; Status.PENDING, Status is a ubyte alias
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

### Enums

`enum { … }` declares anonymous file-scope `ubyte` constants:

```kotlin
enum { PENDING = 0, RUNNING = 5, ERROR = 255 }
var state: ubyte = RUNNING
```

`enum Name { … }` declares a named enum. Entries are accessed as
`Name.ENTRY`, and `Name` is a **ubyte alias** in type position — not a
distinct type:

```kotlin
enum Status { PENDING, RUNNING = 5, ERROR = 255 }
var state: Status = Status.RUNNING
var raw: ubyte = state        // same type; no conversion
```

- Autonumber: the first entry is `0`; an entry without a value is the
  previous value + 1; an explicit value resets the counter.
- Values are integer literals in `0..255` (decimal, hex, binary).
- Entries are `val`-like and occupy no storage: every use is an inline
  `i8` constant. A global initialised from an entry is static data.
- Anonymous entries are visible to same-package files and through
  `import pkg.NAME` / `import pkg.*` like globals.
- A named enum is imported by its name (`import lib.Status`) or through a
  package wildcard. Importing an entry directly is not supported.
- Named entries are not bare constants: write `Status.RUNNING`, not
  `RUNNING`.
- No local enums, no `enum class`, no enum-typed struct fields or
  trait/impl method signatures. There is no `when` exhaustiveness check.

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
  an expression. A `when` expression must have an `else` branch; without one
  it may not match and is rejected.

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
| Bitwise | `&` `|` `xor` | any integer type, same-width operands; a literal mask may use the full width of the other operand (`word & 0xFF00`) |
| Logical | `&&` `||` `!` | bool only; `&&`/`||` **short-circuit** (the old Wolin did not — that is a deliberate bug fix) |
| Increment | `++` `--` | prefix and postfix; postfix yields the old value, prefix the new one |
| Assignment | `=` `+=` `-=` `*=` `/=` `%=` | value must have the target's type (literals excepted) |
| Bit-assignment | `:=` `.=` `&=` `\|=` | `:=` sets bits (or), `.=` clears bits (and-not), `&=` and `\|=` mask; the mask may differ in width: narrower is extended, wider is truncated to the target's low bits |

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
  `\r` `\0` `\\` `\"` `\'` `\$` `\uXXXX`. Stored NUL-terminated in the data
  section. `${expr}` and `$name` interpolate (see below).

```kotlin
var ch: ubyte = 'A'
var s: string = "hi\r\n"
var b: ubyte = s[0]             // indexing a string yields a ubyte
```

### String interpolation

`"${expr}"` and `"$name"` render a value into the string. `\$` is a literal
dollar; a `$` not followed by an identifier or `{` is ordinary text.

| Part type | Rendering |
|---|---|
| `ubyte`, `byte`, `uword`, `word` | decimal |
| `bool` | `true` / `false` |
| `string` | the pointed-to bytes |
| `float` | 7 significant digits; fixed for exponents −3..6, `E` notation outside; `.0` on integral values |

There is no `char` type: `'A'` is a `ubyte`, so `${'A'}` renders `65`.
`unit`, arrays and function pointers are compile errors.

```kotlin
var n: ubyte = 42
var f: float = 1.5
print("n=${n} f=${f}")   // n=42 f=1.5
```

**Storage and lifetime.** Each syntactic template site has one static
256-byte buffer. The result is capped at 255 bytes plus NUL; a runtime
`string` part is truncated at the cap. The returned pointer is *borrowed*:
evaluating the **same site** again (a loop, a recursive call, another call
of the enclosing function) overwrites the bytes. Distinct sites have
distinct buffers, so nested templates and several interpolations in one
expression are safe. Copy the bytes if a result must survive a
re-evaluation.

Float interpolation is a self-contained formatter using the compiler-rt
soft-float routines float arithmetic already links; it adds no C library.
The format is 7 significant digits, not Kotlin's shortest round-trip:
`3.1415927f` prints `3.141593`.

## Arrays

```kotlin
var arr: ubyte[ubyte]^0x3000
arr[3] = 42
var v: ubyte = arr[3]
```

- The index may be any integer type; there are **no bounds checks**.
- `uword`-element arrays are scaled by the GEP; `ubyte` arrays are not.
- **Explicit length:** `T[N]` is an array of `N` elements with a `ubyte`
  index; `T[index, N]` names both the index type and the length. Without a
  length, the size is the index type's domain (`ubyte` → 256, `uword` →
  65536).
- An explicit length must fit the index type's domain and the 64 KiB address
  space; `uword[uword, 40000]` is rejected because two bytes per element do
  not fit.
- Global arrays without a fixed address are zero-initialised in the data
  section.

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
- **Count check.** The list (or string + NUL) may not exceed the explicit
  length or, without one, the index type's domain; longer lists are a compile
  error.
- **Accepted positions:** global array initialiser (constant elements),
  global fixed-address array initialiser (constant elements), local
  fixed-address array initialiser (runtime expressions). Anywhere else —
  `var x = [1, 2]` with no declared type, `arr = [1, 2]` in assignment
  position, a call argument — is a compile error.

## Structs

A `struct` is a named packed layout. It is storage, not a first-class value:
fields are readable and writable, but the struct itself cannot be copied,
passed, returned, compared, or allocated on a heap.

```kotlin
struct Point {
    var x: ubyte
    var y: uword
}

var p: Point                    // global, zero-initialised, 3 bytes
var points: Point[ubyte, 4]     // 4 elements, contiguous, 12 bytes
var screen: Point[ubyte]^0x3000 // fixed address, no storage emitted

fun main() {
    p.x = 10
    p.y = 300
    points[2].x = 7
    var out: ubyte^0x400 = 0
    out = points[2].x
    while (true) {}
}
```

- Fields are in declaration order with **no padding**. The 6502 has no
  alignment requirement; an unaligned `uword` field is a valid unaligned
  load/store.
- Field types are scalar: `ubyte`, `byte`, `uword`, `word`, `bool`, `float`
  and `string`. Arrays and other structs are not fields in v1.
- Fields must be `var`, must declare their type, and may not have an
  initialiser or a fixed address.
- A struct is stored as a byte blob. An array of structs is a contiguous
  `N × sizeof(struct)` blob; element *i* starts at `i × sizeof(struct)`.
- A global struct/array is zero-initialised. A local struct is stack storage
  and is uninitialised until written. A fixed-address struct's field access
  is volatile, like every other fixed-address access.
- A struct can be bound to a trait it implements: `var s: Shape = p` stores
  `p`'s address and the vtable for `(Point, Shape)` in a 4-byte trait cell.
  This is a non-owning reference, not a copy; see *Trait values* below.
- Local arrays of structs need a fixed address, like other local arrays.
- Operations: read `p.field`, write `p.field = v`, compound assignment
  `p.field += v`, increment `p.field++` / `++p.field`; array element fields
  `points[i].field`. There are no bounds checks.
- Not supported: constructors, literals, copying, assignment of one struct
  to another, struct parameters/returns, arrays or structs as fields, and
  structs inside fixed-address hardware descriptions with per-field
  addresses.

## Traits and impls

A `trait` is a named set of method signatures. A `struct` opts into it with
`impl Type : Trait`; methods are defined in `impl` blocks, not in the struct
body. A trait name is also a type: `var s: Shape` is a non-owning
reference to a struct that implements the trait. A call through it
dispatches at runtime through a static vtable.

```kotlin
trait Shape {
    fun area(): uword
    fun name(): string { return "shape" }   // default body
}

struct Rect {
    var w: ubyte
    var h: ubyte
}

impl Rect {                                  // inherent methods
    fun scale(k: ubyte) {
        this.w *= k
        this.h *= k
    }
}

impl Rect : Shape {                          // trait implementation
    fun area(): uword {
        return this.w.toUword() * this.h.toUword()
    }
}

var r: Rect

fun main() {
    r.w = 3
    r.h = 4
    r.scale(2)
    var out: ubyte^0x400 = 0
    out = r.area().toUbyte()
    while (true) {}
}
```

- An `impl Type` block adds inherent methods; an `impl Type : Trait` block
  adds the trait's methods. One impl per `(trait, struct)`.
- An impl must be in the **same file** as the struct. A trait from another
  package must be imported to write the impl.
- A trait declares methods only. No fields, state, constructors, associated
  types or constants, supertraits, or generics. Method parameters and
  return types are scalar; a struct value is still not first class.
- Every trait method must be present exactly once in the impl, unless the
  trait supplies a default body. An impl may not add methods the trait does
  not declare.
- A default body is copied into each implementor and checked there, so it
  may use that struct's fields (`this.w`) and its other methods. A method
  name may not collide with a field, and may not repeat across impl blocks.
  The copy trades code size for a shared runtime body; every `(struct, trait)`
  vtable points at its implementor's copy.
- `this` names the receiver storage. Inside an impl method, fields are
  reached through it: `this.w`, `this.w = 1`, `this.bump()`. `this` is not
  available in a free function or a function literal.
- A method is emitted as a function with a hidden receiver pointer:
  `define i8 @"app.Point.get"(ptr %this)`. The call passes the struct's
  address. A trait impl also emits a static vtable:
  `@"vt.app.Point.Shape" = constant [N x ptr] [...]`.
- A method call on a **concrete** struct is static: the receiver's struct
  type selects the method at compile time. It works on a global, local,
  fixed-address, or array-element struct: `p.get()`, `points[i].bump()`.
- A method call on a **trait-typed** value is dynamic: the receiver cell
  supplies the data pointer and vtable pointer, and the call goes through
  the vtable slot for that method. See *Trait values* below.
- Field access through `this` is volatile. A method is shared by every
  instance, so it cannot know whether its receiver is a fixed-address
  hardware struct; treating its fields as volatile keeps hardware access
  correct.
- Not supported: trait bounds or generics, trait returns, trait fields,
  downcasting, method overloading, methods on arrays or scalars, and methods
  that pass or return a struct.

### Trait values

A trait name is a type: `var s: Shape`. The value is a 4-byte non-owning
cell: a 2-byte data pointer followed by a 2-byte vtable pointer. Assigning
a struct stores the struct's address and the static vtable for
`(struct, trait)`; assigning another trait value copies both pointers. The
struct must implement the trait.

```kotlin
var shapes: Shape[ubyte, 2]
shapes[0] = rect
shapes[1] = circle
var a: uword = shapes[0].area()   // indirect call through the vtable
```

- The data pointer may name a global, local, fixed-address, or array-element
  struct. Storage must outlive the reference; there is no borrow checking.
- Trait arrays are supported; every element is a 4-byte cell.
- A trait value may be a function parameter: `fun f(s: Shape)`. The cell
  address is passed, so the callee reads the same data and vtable pointers.
- A trait value may not be returned, stored in a struct field, compared, or
  used in arithmetic.
- A vtable is one 2-byte pointer per trait method, one table per
  `(struct, trait)`, in trait declaration order. There is no drop, size or
  alignment header.
- Fixed-address structs work: the data pointer is the hardware address, and
  field access through `this` stays volatile.
- There is no heap or ownership: a trait value is a reference, not a `Box`.

## Fixed addresses and hardware

`var x: T^0xADDR` declares the variable *at* the address: reads and writes
are direct (volatile) loads and stores of the variable's width, and the
variable has no storage of its own.

A `string` variable is a pointer, so its fixed-address slot holds a 2-byte
pointer; reads load that pointer and index the bytes it points at, exactly
like a global or local string.

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

Five files ship in `stdlib/`; pass the ones you use on the command
line before your own files:

```
wolinc stdlib/c64/kernal.wtk stdlib/c64/vic.wtk stdlib/c64/sid.wtk stdlib/c64/cia.wtk stdlib/wolin/screen.wtk myprog.wtk -o myprog.prg
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
  The file also declares the enums that name the register values:
  `Color` (the 16 palette values), `SpriteMask` (bit 7-n selects sprite n),
  `InterruptSource` and `InterruptFlag` (the IRQEN and IRQST bits),
  `Control1` and `Control2` (the bits of $d011 and $d016).
- `c64/sid.wtk` (`package c64.sid`) — the SID register map ($d400-$d41f) as
  fixed-address `ubyte` windows: the three voices (frequency, pulse width,
  control, envelope), the filter (cutoff, resonance and routing, mode and
  volume), and the read-only oscillator and envelope outputs. The file also
  declares the enums that name the register values: `Waveform` (the
  waveform bits of a voice control register), `VoiceControl` (its TEST, RING,
  SYNC and GATE bits), `FilterRouting` (which voices the filter takes),
  `FilterMode` (the lowpass, bandpass, highpass and voice-3-off bits) and
  `Volume` (the sixteen master volume steps).
- `c64/cia.wtk` (`package c64.cia`) — both MOS 6526 interface adapters, CIA 1
  at $dc00 and CIA 2 at $dd00, in one file: the sixteen registers of each
  chip (ports and their directions, the two timers, the time-of-day clock,
  the serial data register, the interrupt control and status register and
  the two control registers). The file also declares the enums that name
  the register values: `CiaControl` (the control register bits), `TimerBClock`
  (the two-bit clock source field of control register B), `InterruptSource`
  (the interrupt sources, whose bit positions are the same for the flags
  and the enables), `InterruptMask` (the fill bit of the mask register),
  `JoystickButton` (the joystick bits, active low) and `VicBank` (the
  inverted bank select of CIA 2's port A). Two registers do not read back
  what was written: the control registers show the timers' running state in
  their start bit, and the interrupt control register is a flag register
  that clears its flags when read.

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
| `Unknown struct X` / `Unknown variable X` / `Unknown function X` | resolution failure |
| `X conversion requires an integer, not Y` | a conversion on a non-integer |
| `Cannot assign to val X` | assignment to a `val` |
| `The else branch of a when must be last` | `else` not in the final position |
| `when condition type X does not match subject type Y` | subject-form `when` with a mismatched condition |
| `this when expression may not match any branch` | a `when` expression with no `else` |
| `Array literal needs an array type` | `var x = [1, 2]` with no declared array type |
| `Array literal can only initialise a declaration` | an array literal in assignment or expression position |
| `Too many array elements` (with the length) | a list longer than the explicit length or index domain |
| `String initialiser needs a ubyte element type` | a string initialiser on a `uword`-element array |
| `String initialiser too long` (with the length) | a string whose bytes + NUL exceed the explicit length or index domain |
| `local arrays need a fixed address in v1` | an array initialiser on a local non-fixed array |
| `Array length must be at least 1` | an explicit array length is zero |
| `Array length N exceeds the X index range M` | an explicit length does not fit the index type |
| `Array X does not fit in the 64 KiB address space` | length × element size, or a fixed base, exceeds 64 KiB |
| `A struct value is not first class` | a struct is copied, passed, returned, or used as a value; binding it to a trait reference is allowed |
| `Cannot assign a struct value; assign a field instead` | `points[i] = ...` rather than `points[i].field = ...` |
| `Struct X has no method Y` | a method-style call whose name is not implemented for `X` |
| `Unknown trait X` | an `impl X : Y` naming no visible trait |
| `Trait X has no method Y` | a call on a trait value naming no trait method |
| `Struct X does not implement trait Y` | assigning a struct to a trait it does not implement |
| `A trait value cannot be returned` | a trait used as a function or method return type |
| `Impl of trait X for Y is missing method Z` | a required trait method has no implementation |
| `Method X does not match trait Y` | the implementation's parameter or return types differ |
| `Trait X does not declare method Y` | an extra method in a trait impl |
| `Duplicate impl of trait X for Y` | two impls of one trait for one struct |
| `Duplicate method X in struct Y` | the same method name in two impl blocks, or twice in one |
| `Method X collides with a field of Y` | a method and a field share a name |
| `this is only available in an impl method` | `this` in a free function or function literal |
| `impl for X must be in the same file as the struct` | a cross-file inherent/trait impl |
| `Struct field X is not a scalar type: Y` | an array or struct used as a struct field |
| `Unknown label X` | a labelled jump to a name that is not a label of the current function |
| `Label X already defined` | the same label name declared twice in one function |
| `Duplicate enum entry X` | two entries of one enum share a name |
| `Enum value must be an integer literal` | an enum value that is not an integer literal |
| `Enum value out of range` | an enum value outside 0..255, or autonumber past 255 |
| `Duplicate enum X` | two visible named enums share a name |
| `Enum name X is reserved` | the name is a built-in type or `CPU` |
| `Unknown enum entry X in Y` | `Y.X` names no entry of enum `Y` |
| `Enum Y is a type, not a value` | an enum name used where a value is expected |
| `Cannot assign to enum entry Y.X` | assignment to an enum entry |

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
