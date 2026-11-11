# Wolin-NG

A compiler for the Wolin language — a Kotlin-flavoured language for the
Commodore 64 — targeting the [LLVM-MOS](https://github.com/llvm-mos/llvm-mos)
backend.

This is a **work in progress**. The front end is built; the back end is not.
See [What is missing](#what-is-missing) below.

---

## What this is

The original Wolin compiler (`github.com/ssuukk/wolin`) translated a
Kotlin-like language to 6502 assembly by hand, through a custom pseudo-assembly
VM, a hand-rolled zero-page register allocator, and a catalogue of assembly
templates. It was a complete compiler — and maintaining a register allocator,
an optimiser and a template catalogue is a permanent tax.

Wolin-NG keeps the **language** and replaces the **implementation**. The
grammar is the old Kotlin-derived one, reused almost verbatim. The output is
textual LLVM IR, which `mos-c64-clang` compiles and links into a C64 `.prg`.

The design is in [`docs/superpowers/specs/2026-11-07-wolin-llvm-design.md`](docs/superpowers/specs/2026-11-07-wolin-llvm-design.md);
the implementation plan is in
[`docs/superpowers/plans/2026-11-08-wolin-llvm-implementation.md`](docs/superpowers/plans/2026-11-08-wolin-llvm-implementation.md).

---

## Status

| Stage | State |
|---|---|
| Grammar (ANTLR 4.13.1, reused from the old compiler) | **done** |
| Parser — parse tree → diagnostics | **done** |
| AST — typed node tree | **done** |
| Type checker — contextual literal typing, scopes | **done** |
| IR builder — SSA names, blocks, phis | not started |
| IR emission — declarations, expressions, statements | not started |
| CPU registers, ROM calls, inline assembly | not started |
| Toolchain driver, SDK discovery, linking | not started |
| VICE test harness | not started |
| The rest of the language surface | not started |

**You cannot compile a program yet.** There is no `wolinc` executable and no
code generator — see [What is missing](#what-is-missing).

---

## Requirements

| Tool | Version | Why |
|---|---|---|
| JDK | 21 | the build targets Java 21 |
| Gradle | 8.13 | the build (a wrapper is committed — use `./gradlew`) |
| Kotlin | 2.2.10 | pulled in by the Gradle plugin |
| ANTLR | 4.13.1 | the grammar tool and runtime |
| LLVM-MOS SDK | 23.2.0 (LLVM 24) | **not needed yet** — only once the code generator exists |
| VICE | 3.10 | **not needed yet** — only for the integration tests |

The SDK and VICE are expected at:

```
~/.local/share/rust-mos/sdk-v23.2.0/    # mos-c64-clang, libcrt.a, mos-platform/
~/.local/bin/x64sc                      # the VICE binary
```

If `java` is not on `PATH`:

```bash
export PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH"
```

---

## Building

```bash
./gradlew build
```

This compiles the Kotlin sources, generates the ANTLR parser, and runs the
unit tests.

## Running the tests

```bash
./gradlew test
```

39 tests at present:

| Suite | Tests | What it covers |
|---|---|---|
| `CorpusParseTest` | 4 | the four original `.ktk` example programs parse without syntax errors |
| `TypeCheckerTest` | 32 | the type rules — literal typing, no promotion, compound assignment, `val` immutability |
| `ScopeTest` | 3 | block scoping and shadowing |

The corpus files are the original ones from the old compiler, copied
byte-for-byte into `src/test/resources/corpus/`.

---

## The language, so far

The type checker implements the language's rules. What is *accepted* today:

```kotlin
package demo

var border: ubyte^0xd020      // a fixed-address variable
var count: ubyte = 5          // a global with a constant initialiser

fun add(a: ubyte, b: ubyte): ubyte {
    return a + b
}

fun main() {
    var x: ubyte = 40
    x += 2                     // read-modify-write
    x := 0x0F                  // set bits
    x .= 0x0F                  // clear bits
    border = 6
}
```

### Types

| Wolin | Notes |
|---|---|
| `ubyte` | the primary 8-bit type |
| `byte` | signed interpretation; comparisons are signed |
| `uword`, `word` | 16-bit |
| `bool` | |
| `float` | IEEE-754 single precision |
| `string` | NUL-terminated; distinct from `ubyte[ubyte]` |
| `unit` | |

### The rules that are implemented

- **No integer promotion.** `ubyte + ubyte` is a `ubyte` and wraps at 256.
- **Mixed operand types are an error.** `ubyte + uword` does not compile.
- **The result type is the left operand's type.**
- **Contextual literal typing.** A literal takes the expected type when there
  is one, and the smallest type that holds it when there is not. So
  `var i: uword = 0` is fine, and `var x = 300` is a `uword`, but
  `var x: ubyte = 300` is an error.
- **Comparisons are unsigned for `ubyte`/`uword`, signed for `byte`/`word`.**
- **`val` is immutable**, `var` is mutable. (The old compiler ignored this
  distinction; v1 makes it real.)
- **Blocks shadow.** An inner declaration shadows an outer one and the shadow
  ends with the block.
- **Compound assignment** is checked by its operator: `+= -= *= /= %=` are
  read-modify-write; `:= .= &= |=` take a bit mask, which need not have the
  target's type but must fit its width.
- **Untyped `xor` does not exist** — the old Wolin had no `xor` either.

---

## What is missing

Everything from the IR builder onwards. Concretely, the following does not
exist yet:

### The code generator

- **No IR builder.** The SSA naming, basic-block and phi machinery that every
  later piece emits through.
- **No IR emission.** Nothing turns the AST into LLVM IR — not declarations,
  not expressions, not statements, not control flow.
- **No CPU-register access.** `CPU.A`, `CPU.X`, `CPU.Y`, `CPU.C`, `CPU.N`,
  `CPU.Z`, `CPU.I` and the 16-bit `CPU.AX` are not yet readable or writable.
- **No ROM calls.** `fun chrout^0xffd2(c: ubyte^CPU.A)` is parsed but cannot be
  emitted.
- **No interrupt handlers.** `interrupt fun` is parsed but not emitted.
- **No `return@<address>`.** The old corpus uses `return@0xea31`; the reused
  grammar cannot even parse it, because its rule is
  `RETURN_AT: 'return@' Identifier` — a number is not an identifier. Adding a
  production for it is planned.

### The driver

- **No `wolinc` executable.** There is no command line, no file handling and
  no pipeline. The CLI is specified in the design doc (§10) but not built.
- **No SDK discovery**, no IR validation, no linking to a `.prg`.
- **No multi-file support.** The type checker sees one file at a time.

### The test harness

- **No VICE integration tests.** The harness that runs a `.prg` in headless
  VICE and reads results back over the binary monitor protocol is specified
  (design doc §9.2–§9.4) but not built.

### Known gaps in what *is* built

- **Unsupported constructs produce one generic message.** The type checker
  throws `SemaException("Unsupported construct: …")` for anything outside v1 —
  classes, `throw`, `for` loops, and so on. A per-construct diagnostic
  catalogue is planned.
- **`import` is parsed but not resolved.** The old compiler also ignored it;
  v1 will resolve it properly.
- **Function literals are not yet type-checked.** The corpus has
  `var suma: (ubyte,ubyte)->ubyte` with `suma = { a, b -> a + b }`; the
  parameter types must be inferred from the assignment target, and that is not
  implemented.
- **Two of the four corpus programs do not fully type-check**, which is
  correct rather than broken:
  - `strings.ktk` assigns to a `val` (`val char: ubyte = what[i]`), which v1
    rejects — the old compiler did not enforce `val` immutability.
  - `misc.ktk` uses `throw` and `objects.ktk` uses `class`; both are v2
    features.
  `raster_irq.ktk` — the one that exercises `:=` and `.=` — passes fully.

---

## Layout

```
src/main/antlr/           the grammar, reused from the old compiler
  KotlinLexer.g4          (plus UnicodeClasses.g4, which the old tree lacked)
  KotlinParser.g4
src/main/kotlin/pl/qus/wolin/
  ast/Ast.kt              the typed AST
  driver/Diagnostics.kt   error collection
  parser/ParserFacade.kt  parse tree → diagnostics
  sema/Types.kt           the type model
  sema/Scope.kt           scopes and shadowing
  sema/AstBuilder.kt      parse tree → AST
  sema/TypeChecker.kt     the type rules
src/test/kotlin/          the unit tests
src/test/resources/       the old compiler's example programs
docs/superpowers/         the design doc and the implementation plan
```

---

## Why the grammar is invoked directly

The Gradle `antlr` plugin runs **ANTLR 2** by default and appends its
arguments *after* the grammar files, but ANTLR 4 requires `-package` and
`-lib` to come *before* them. So `build.gradle.kts` invokes
`org.antlr.v4.Tool` through a `ProcessBuilder` instead. The Kotlin plugin
also compiles Kotlin before Java, so the generated parser is compiled by its
own `JavaCompile` task and its output goes on the Kotlin compile classpath.

`src/main/antlr/UnicodeClasses.g4` is fetched from the Kotlin specification
repository — the old compiler's tree did not contain it, and the lexer needs
eight Unicode-category rules it defines.
