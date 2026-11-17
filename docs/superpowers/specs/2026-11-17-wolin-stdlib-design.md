# Wolin standard library — first two files (2026-11-17)

## Intent

Start the Wolin standard library: a kernal-declaration file that user code
imports, and a Wolin-level `print` built on top of it. No compiler changes.

## Approaches (A chosen)

- **A: Plain stdlib files in this repo.** The user passes them on the command
  line: `wolinc stdlib/c64-kernal.wtk stdlib/std.wtk myprog.wtk`. No driver
  work. **Chosen** — the smallest thing that delivers the library, and it
  needs zero compiler risk.
- **B: `wolinc` auto-includes a bundled stdlib** (discover it next to the
  jar, order it before user files, handle name collisions). Deferred: real
  driver work, not needed to use the library.

Everything the build needs already exists: fixed-address function
declarations (`fun name^0xffff(p: t^CPU.REG)`, emitted as a `jsr` wrapper —
see `RomCallTest`), `package` declarations, named and wildcard imports of
functions and globals, multi-file linking, NUL-terminated `string` with
indexing.

## Files

1. `stdlib/c64-kernal.wtk` — `package c64.kernal`; the first kernal
   declaration: `fun chrout^0xffd2(c: ubyte^CPU.A)` (bodyless; the emitter
   wraps it as `jsr $ffd2`).
2. `stdlib/std.wtk` — `package wolin`; `import c64.kernal.*`;
   `fun print(x: string)` — a while-loop over `x[i]` until the NUL
   terminator, `chrout(x[i])` per byte. No `for` loop in v1, so the index is
   manual.
3. `src/test/kotlin/pl/qus/wolin/it/StdlibTest.kt` — end-to-end: compiles
   the two stdlib files plus a temp `main` file that calls `print("hi")`,
   runs the result under VICE, asserts the screen bytes. After the C64 boot
   banner the cursor sits at row 2, column 6 — `$0456`; the test expects
   `$0456 = 'h'`, `$0457 = 'i'`.
4. `TODO.md` — known-gap entry: a fixed-address function **with a return
   type crashes the compiler** — `emitFixedFunction` terminates with
   `ret(null)` and `IrBuilder.ret` asserts void (ModuleEmitter.kt:264,
   IrBuilder.kt:183–186). Kernal routines that return a value in A
   (`readst^0xffc7(): ubyte`) therefore cannot be declared; this blocks the
   kernal file from growing past void functions.
5. `REFERENCE.md` — a short "Standard library" section: the two files, the
   command line, the package names.

## Usage

```
wolinc stdlib/c64-kernal.wtk stdlib/std.wtk myprog.wtk -o myprog.prg
```
```wtk
// myprog.wtk
package app
import wolin.print
fun main() { print("hi") }
```

## Non-goals

More kernal routines (`readst`, `clrchr`, …), `println`, kernal functions
with return values (blocked by the TODO.md gap), stdlib auto-inclusion
(approach B), import aliases.

## Calls (mine, flagged for the user)

- Package names `c64.kernal` / `wolin`: the old corpus already contains
  `//import c64.kernal`, so `c64.kernal` follows prior art; `wolin` matches
  the language's name.
- The VICE test depends on the boot banner layout (deterministic under
  `-default -autostart`). If the readback doesn't match, the test fails
  loudly and the offset is corrected from the actual screen bytes.
- Only `chrout` in the first kernal file — the smallest set that serves
  `print`; the file is meant to grow.
