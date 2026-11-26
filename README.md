# wolinc — the Wolin compiler, rebuilt on LLVM

`wolinc` compiles a subset of Kotlin — the "Wolin" dialect the Commodore 64
scene used — to 6502 machine code. It emits LLVM IR and hands it to the
LLVM-MOS toolchain, so the backend, the optimiser and the linker are the
upstream ones rather than a hand-written code generator.

The compiler is a Kotlin program: the grammar is the Kotlin grammar, the
parser is ANTLR, the type checker and the emitter are plain Kotlin.

## Requirements

| Tool | Why | Where |
|---|---|---|
| **JDK 21** | builds and runs the compiler | `java -version` |
| **Gradle** | the build | `./gradlew` |
| **LLVM-MOS SDK** | `mos-c64-clang`: validates the IR and links the `.prg` | `~/.local/share/rust-mos/sdk-*/` |
| **VICE** (`x64sc`) | only for the integration tests, which run real 6502 code | `x64sc` on `PATH` |
| **Xvfb** (`xvfb-run`) | runs VICE headless, so tests do not open windows | `xvfb-run` on `PATH` |

The SDK is found under `~/.local/share/rust-mos`; `-S <dir>` overrides the
search.

## Build

```bash
./gradlew build
```

The compiler's main class is `pl.qus.wolin.driver.MainKt`. To run it without
installing:

```bash
java -cp "$(./gradlew -q printClasspath)" pl.qus.wolin.driver.MainKt --help
```

## Compile a program

```kotlin
// hello.wtk
var border: ubyte^0xd020

fun main() {
    border = 6
    while (true) {}
}
```

```bash
java -cp "$(./gradlew -q printClasspath)" pl.qus.wolin.driver.MainKt \
    -o hello.prg hello.wtk
```

`hello.prg` is a loadable C64 program. `var border: ubyte^0xd020` is a
fixed-address variable: it is the VIC-II border register, and the compiler
emits a direct load or store — no address is loaded first.

## CLI

```
wolinc [options] <file.wtk>...
  -o, --output <file>     output .prg (default: first input's name)
  -d, --debug             keep intermediate .ll files
  -e, --emit-ir           write .ll and stop
  -S, --sdk <dir>         llvm-mos SDK path (overrides discovery)
      --target <name>     c64 (default)
      --keep-ir           keep the .ll next to the .prg
```

Every link writes `<output>.map` next to the `.prg`: symbol addresses, sizes,
and the section layout, as produced by the SDK linker.

Several input files compile into one program: each file becomes its own LLVM
module, and the driver module calls them. The files are checked together, so
a name from another package resolves through an import.

## The language, v1

### Types

| Type | LLVM | Notes |
|---|---|---|
| `ubyte` | `i8` | the primary 8-bit type |
| `byte` | `i8` | signed two's complement, −128..127; comparisons, `/` and `%` are signed; arithmetic wraps modulo 256 |
| `uword`, `word` | `i16` | 16-bit |
| `bool` | `i1` | |
| `float` | `float` | 4 bytes, IEEE-754 |
| `string` | `ptr i8` | NUL-terminated |
| `ubyte[ubyte]` | `ptr` | array; the index type is part of the type |
| `ubyte[ubyte, 16]` | `ptr` | array with an explicit length; `ubyte[16]` uses a `ubyte` index |

A declaration without a type takes the type of its initialiser. A declaration
with neither a type nor an initialiser is an error.

### Declarations

```kotlin
var x: ubyte                  // local, a stack slot
var x: ubyte^0xd020           // fixed-address variable: direct loads and stores
var x: bool^CPU.I             // CPU-register-backed variable
var arr: ubyte[ubyte] // array in the data section, 256 elements
var arr: ubyte[ubyte, 16] // 16 elements; `ubyte[16]` is the same
var arr: ubyte[ubyte]^0x3000 // array at a fixed address
struct Point { var x: ubyte; var y: uword } // packed layout
var p: Point // global struct instance
var points: Point[ubyte, 4] // array of structs, contiguous
trait Sum { fun sum(): uword } // method contract, no state
impl Point { fun bump() { this.x++ } } // inherent method
impl Point : Sum { fun sum(): uword { return this.x.toUword() + this.y } } // static impl
val x: ubyte = 5              // cannot be assigned to
enum { PENDING = 0, RUNNING = 5 }       // anonymous ubyte constants
enum Status { PENDING, RUNNING = 5 }    // Status.PENDING; Status is a ubyte alias
fun f(): ubyte { ... }        // function
interrupt fun handler() { }   // 6502 interrupt handler
fun chrout^0xffd2(c: ubyte^CPU.A)  // fixed-address function: a ROM call
```

### Statements and expressions

`if` / `else`, `when` (with or without a subject), `while`, `do..while`,
`break`, `continue`, `return`. Labels (`name@`) are named jump points, with
`break@name` / `continue@name` / `return@name` jumping to one, and
`return@0xea31` a tail jump to a fixed address, for the "continue with the
ROM routine" idiom.

Assignments: `=`, `+=`, `-=`, `*=`, `/=`, `%=`, and the bit-assignment
operators `:=` (or), `.=` (and), `&=`, `|=`.

Bitwise expressions: `&`, `|`, `xor` — any integer type, no promotion.

### Conversions

The four explicit conversions: `toByte()`, `toUbyte()`, `toWord()`,
`toUword()`. They are the only way a value changes integer type — there is
no implicit promotion. A conversion to the same width reinterprets the bits
(no code is emitted), a widening one sign- or zero-extends according to the
source's signedness, and a narrowing one truncates. Conversions chain, so
`b.toUbyte().toUword()` widens in two steps.

### Two things that differ from the old Wolin

- **`float` is IEEE-754**, not the old Wozniak FFP format. Old float data is
  not byte-compatible and has to be converted.
- **Literals are typed contextually.** `var x: uword = 0` is fine — the
  literal takes the destination's type. A literal that does not fit the
  destination type is an error, and there is no implicit promotion between
  the integer types.

## Examples

`examples/` holds programs to run on a C64 (or under VICE). Each is built
the same way, with the stdlib files it needs:

```bash
java -cp "$(./gradlew -q printClasspath)" pl.qus.wolin.driver.MainKt \
    stdlib/c64/kernal.wtk stdlib/c64/vic.wtk stdlib/wolin/screen.wtk \
    examples/petscii.wtk -o petscii.prg
```

The stdlib ships `c64/kernal.wtk` (the KERNAL entry points),
`c64/vic.wtk` (the VIC-II registers and the enums that name their
values), `c64/sid.wtk` (the SID registers and their enums) and
`wolin/screen.wtk` (screen output).

- `hello.wtk` — print text, then cycle the border and background through
  all 16 palette colours.
- `sprites.wtk` — move a 1-colour and a multicolour sprite.
- `petscii.wtk` — draw frames with the Unicode box-drawing characters.
  A source string is mapped to PETSCII at compile time, so `┌─┐` reaches
  the screen as the C64's own corner glyphs (see
  [REFERENCE.md](REFERENCE.md#petscii)).
- `raster_irq.wtk` — an open-border sprite and a raster-interrupt
  colour band.
- `labels.wtk` — a border-colour cycle driven by labels: `return@` skips a
  colour, `continue@` retries the loop, `break@` leaves it.
- `enums.wtk` — simple enums: anonymous `ubyte` constants and a named enum
  whose entries are `Status.ENTRY`, with `Status` as a `ubyte` alias.

## Tests

```bash
./gradlew test
```

The integration tests compile a program, run it under VICE and read the
memory back, so they test the generated code on a real 6502 — not just the
IR's shape.
