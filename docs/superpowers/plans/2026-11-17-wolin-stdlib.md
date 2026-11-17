# Wolin Standard Library (First Two Files) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first two Wolin stdlib files — a kernal declaration file
(`c64.kernal.chrout`) and `wolin.print(x: string)` built on it — with an
end-to-end VICE test and the docs that go with them.

**Architecture:** Approach A from the spec: plain `.wtk` files in
`stdlib/`, no compiler changes. User code imports the packages and passes
the files on the `wolinc` command line. The test compiles the two stdlib
files plus a temp `main` into one `.prg`, runs it under VICE, and reads the
screen back through the binary monitor.

**Tech Stack:** Wolin language (`.wtk`), Kotlin JUnit 5, VICE
(`/home/qus/.local/bin/x64sc` via `ViceRunner`), Gradle
(`./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-11-17-wolin-stdlib-design.md`

## Global Constraints

- No compiler changes: the only files created or modified are
  `stdlib/c64-kernal.wtk`, `stdlib/std.wtk`,
  `src/test/kotlin/pl/qus/wolin/it/StdlibTest.kt`, `TODO.md`,
  `REFERENCE.md`.
- v1 language surface (`REFERENCE.md`): no `for` loop — `print` uses a
  manual index in a while loop; no import aliases; `string` is
  NUL-terminated and indexed with integer types.
- Kernal entry points are bodyless fixed-address declarations:
  `fun name^0xffff(p: t^CPU.REG)` — every parameter needs a `^CPU.REG`
  annotation; the emitter wraps the function as `jsr $ffff`.
- Stdlib package names are fixed: `c64.kernal` (kernal declarations) and
  `wolin` (Wolin-level functions).
- The Gradle test task runs with the project directory as working
  directory, so the test may reference `stdlib/…` relatively.

## Review Focus

1. **`print("")` (empty string)** — the loop body must never run and the
   cursor must not move; a following `print("hi")` lands at the original
   position. Pinned by Task 1, test 2.
2. **`\r` (0x0D) in a printed string** — CHROUT treats it as carriage
   return: the cursor jumps to column 0 of the next screen row. A user who
   expects a newline will write `print("a\rb")`; it must produce exactly
   that, not a literal byte on the same row. Pinned by Task 1, test 3.
3. **String longer than 255 bytes** — `print` indexes with a `ubyte`; at
   256 the index wraps and the function loops forever re-printing from
   byte 0. This is a v1 property of *all* `string` indexing, not a
   `print` defect; no test can usefully pin the hang, so Task 2 documents
   the limit in `REFERENCE.md` instead.
4. **User file defines its own `print`** — the file's own declaration
   shadows the import (documented same-file shadowing); the stdlib files
   must not depend on their names being unique program-wide. No test: this
   is compiler behavior already pinned by `MultiFileTest`.
5. **`stdlib/std.wtk` passed without `stdlib/c64-kernal.wtk`** — the
   wildcard import finds no matching package and `chrout` fails to resolve
   as `Unknown function chrout` at compile time, never at link time. No
   test: the diagnostic is compiler behavior; the REFERENCE section lists
   both files as one command line.

---

### Task 1: stdlib files and the end-to-end test

**Files:**
- Create: `stdlib/c64-kernal.wtk`
- Create: `stdlib/std.wtk`
- Test: `src/test/kotlin/pl/qus/wolin/it/StdlibTest.kt`

**Interfaces:**
- Consumes: `pl.qus.wolin.driver.compileToPrgFile(inputs: List<Path>, output: Path, sdk: Sdk, workDir: Path): List<Path>?`,
  `pl.qus.wolin.driver.SdkLocator.locate(env: Map<String, String>, globRoots: List<Path>): Sdk`,
  `pl.qus.wolin.test.ViceRunner.run(prg: Path, readStart: Int, readEnd: Int): ByteArray` — all existing.
- Produces (used by Task 2's docs): `stdlib/c64-kernal.wtk` in package
  `c64.kernal` declaring `chrout^0xffd2(c: ubyte^CPU.A)`; `stdlib/std.wtk`
  in package `wolin` with `import c64.kernal.*` and
  `fun print(x: string)` (no return type).

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/pl/qus/wolin/it/StdlibTest.kt`:

```kotlin
package pl.qus.wolin.it

import pl.qus.wolin.driver.SdkLocator
import pl.qus.wolin.driver.compileToPrgFile
import pl.qus.wolin.test.ViceRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The standard library on the real 6502: `wolin.print` walks the string
 * and sends each byte to CHROUT ($FFD2). The bytes land on the screen
 * after the boot banner, which leaves the cursor at row 2, column 6 —
 * $0456.
 */
class StdlibTest {

    @Test fun `print writes the string to the screen`() {
        val bytes = runStdlibProgram("print(\"hi\")")
        assertEquals('h'.code, bytes[0].toInt() and 0xFF)
        assertEquals('i'.code, bytes[1].toInt() and 0xFF)
    }

    @Test fun `printing an empty string emits nothing`() {
        val bytes = runStdlibProgram("print(\"\")\n    print(\"hi\")")
        assertEquals('h'.code, bytes[0].toInt() and 0xFF)
        assertEquals('i'.code, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a carriage return in the string starts the next row`() {
        val bytes = runStdlibProgram("print(\"a\\rb\")")
        // 'a' at $0456, CHROUT $0D moves the cursor to column 0 of the
        // next row ($0456 + 40), 'b' lands there.
        assertEquals('a'.code, bytes[0].toInt() and 0xFF)
        assertEquals('b'.code, bytes[40].toInt() and 0xFF)
    }

    /**
     * Compiles the two stdlib files plus a `main` file whose body is
     * [body], runs it under VICE, and returns screen memory
     * $0456..$0497 (one row past the cursor's start).
     */
    private fun runStdlibProgram(body: String): ByteArray {
        val dir = Files.createTempDirectory("wolinc-stdlib")
        val main = dir.resolve("main.wtk")
        main.writeText(
            "package app\n" +
                "import wolin.print\n" +
                "fun main() {\n" +
                "    $body\n" +
                "    while (true) {}\n" +
                "}"
        )
        val stdlib = Path.of("stdlib")
        check(stdlib.resolve("c64-kernal.wtk").exists()) { "stdlib/c64-kernal.wtk is missing" }
        check(stdlib.resolve("std.wtk").exists()) { "stdlib/std.wtk is missing" }
        val output = dir.resolve("out.prg").toAbsolutePath()
        val sdk = SdkLocator.locate(
            env = System.getenv(),
            globRoots = listOf(Path.of(System.getProperty("user.home"), ".local/share/rust-mos")),
        )
        val workDir = Files.createTempDirectory("wolinc-stdlib-ir")
        try {
            val linked = compileToPrgFile(
                listOf(stdlib.resolve("c64-kernal.wtk"), stdlib.resolve("std.wtk"), main),
                output, sdk, workDir,
            )
            check(linked != null) { "the program did not compile" }
            return ViceRunner().run(output, 0x0456, 0x0497)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.it.StdlibTest'`
Expected: all three tests FAIL with `stdlib/c64-kernal.wtk is missing`
(the `check` in `runStdlibProgram`).

- [ ] **Step 3: Create `stdlib/c64-kernal.wtk`**

```wtk
package c64.kernal

// CHROUT ($FFD2): output the byte in A as a character.
fun chrout^0xffd2(c: ubyte^CPU.A)
```

- [ ] **Step 4: Create `stdlib/std.wtk`**

```wtk
package wolin

import c64.kernal.*

// Write [x] to the screen: every byte up to the NUL terminator goes
// through CHROUT. The index is a ubyte, so strings are capped at 255
// bytes (the v1 limit on string indexing in general).
fun print(x: string) {
    var i: ubyte = 0
    while (x[i] != 0) {
        chrout(x[i])
        i++
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'pl.qus.wolin.it.StdlibTest'`
Expected: all three tests PASS.
If a test fails, read the actual byte values out of the assertion message:
a wrong offset means the boot-banner cursor assumption moved — correct
`0x0456` in `runStdlibProgram` and the row math in test 3 to match the
actual screen, and say so in the commit message. A failure that is not an
offset (e.g. `Unknown function chrout`, a type error in `std.wtk`) is a
real defect in the stdlib files — fix the files, not the test.

- [ ] **Step 6: Commit**

```bash
git add stdlib/c64-kernal.wtk stdlib/std.wtk src/test/kotlin/pl/qus/wolin/it/StdlibTest.kt
git commit -m "feat(stdlib): kernal chrout declaration and wolin.print"
```

---

### Task 2: docs — TODO.md gap and REFERENCE.md section

**Files:**
- Modify: `TODO.md` (the "Known gaps found in the code audit" list)
- Modify: `REFERENCE.md` (new section after "Packages, imports, multiple
  files", before "Diagnostics")

**Interfaces:**
- Consumes: Task 1's file names, package names, and the `print` signature,
  exactly as shipped.

- [ ] **Step 1: Add the known gap to `TODO.md`**

Append to the bullet list under `## Known gaps found in the code audit
(2026-11-15)`:

```markdown
- [ ] **A fixed-address function with a return type crashes the compiler.**
      `emitFixedFunction` (ModuleEmitter.kt:264) ends in `ret(null)`, and
      `IrBuilder.ret` asserts the function returns void
      (IrBuilder.kt:183–186), so a kernal routine that returns a value in
      A — e.g. `readst^0xffc7(): ubyte` — cannot be declared. Blocks the
      stdlib kernal file from growing past void functions.
```

- [ ] **Step 2: Add the "Standard library" section to `REFERENCE.md`**

Insert between the "Packages, imports, multiple files" section and the
"Diagnostics" section:

````markdown
## Standard library

Two files ship in `stdlib/`; pass them on the command line before your own
files:

```
wolinc stdlib/c64-kernal.wtk stdlib/std.wtk myprog.wtk -o myprog.prg
```

- `c64-kernal.wtk` (`package c64.kernal`) — kernal entry points as
  fixed-address declarations: `chrout^0xffd2(c: ubyte^CPU.A)` outputs the
  byte in A.
- `std.wtk` (`package wolin`) — Wolin built on the kernal:
  `print(x: string)` writes every byte up to the NUL terminator through
  `chrout`. Use `\r` (`0x0D`) in the string for a screen newline.

```wtk
package app
import wolin.print
fun main() { print("hi") }
```

`print` indexes with a `ubyte`, so a string longer than 255 bytes wraps
the index and prints forever; that is the v1 limit on all string
indexing. Kernal routines that return a value in A cannot be declared
yet (see `TODO.md`).
````

- [ ] **Step 3: Verify the docs match the shipped code**

Run: `./gradlew test --tests 'pl.qus.wolin.it.StdlibTest'`
Expected: PASS (the test is the executable spec; the docs must agree with
it — command line, package names, signatures, the `\r` behavior, the 255
limit).

- [ ] **Step 4: Commit**

```bash
git add TODO.md REFERENCE.md
git commit -m "docs(stdlib): usage section and the fixed-address return gap"
```
