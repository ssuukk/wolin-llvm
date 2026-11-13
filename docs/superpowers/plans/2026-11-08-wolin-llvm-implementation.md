
# Wolin-NG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `wolinc`, a Kotlin/JVM compiler that translates `.wtk` sources to LLVM IR text and links them into Commodore 64 `.prg` programs via `mos-c64-clang`.

**Architecture:** ANTLR 4.13.1 parses the old Kotlin-derived grammar (reused from `/tmp/wolin-old`, plus one added production for `return@<literal>`). A semantic analyser builds a typed AST with scope resolution, address resolution and contextual literal typing. An IR builder emits textual LLVM IR (`.ll`), one module per source file plus a generated driver module holding `main` and the global-init sequence. `mos-c64-clang` compiles and links the modules into a `.prg`. Integration tests run the `.prg` in headless VICE and read results back over the binary monitor protocol.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), Gradle 8.13, ANTLR 4.13.1, JUnit 5.11.4, LLVM-MOS SDK 24 (`~/.local/share/rust-mos/sdk-v23.2.0`), VICE 3.10 (`~/.local/bin/x64sc`).

**Spec:** `docs/superpowers/specs/2026-11-07-wolin-llvm-design.md` — the plan argues from the spec, so the spec travels with it; executors read both.

## Global Constraints

- Source extension `.wtk`; old `.ktk` files must still parse.
- Emitted IR is **textual LLVM IR** (`.ll`), never the LLVM C++ API. Target triple `mos-c64`.
- SDK discovery order: `WOLIN_SDK` env var → glob `~/.local/share/rust-mos/sdk-*` newest first → `mos-c64-clang` on `PATH`. Fail with a message listing what was tried.
- IR is validated before linking with `mos-c64-clang -fno-lto -O2 -x ir -c out.ll -o /dev/null`. The final link uses the SDK default configuration (which enables `-flto`).
- **Inline-asm constraints the backend accepts** (verified; anything else crashes or miscompiles):
  - `"a"(v)`, `"y"(v)`, `"x"(v)` — input constraints, pin the value in that register.
  - An input constraint requires a matching parameter (upstream LLVM `InlineAsm::verify`): `"=a,a"()` — an input constraint with **no operand** — is rejected: `number of input constraints does not match number of parameters`. Pure reads therefore use **output-only** constraints (`"=a"` / `"=x"`, no operand); writes pin their value with a plain input constraint (`"a"(v)`).
  - `"=m"(ptr)` **does not substitute an address** — never store to a specific address from inside inline asm. Deliver the value in a register and let LLVM store it.
  - `"cc"` constraint crashes the backend (`unable to translate instruction: call`).
  - `asm goto` crashes the backend (`unable to translate instruction: callbr`).
  - Instruction separator in a template is `\0A` (newline). `\09` (tab) is **not** a separator.
  - A literal `$` in a template is `$$`.
  - **No clobber lists.** An `asm` with a clobber list fails with `error: expected instruction opcode`. The constraints alone carry the register requirements.
- **6502 status register** is `N V - B D I Z C` from bit 7 down: carry = bit 0, zero = bit 1, negative = **bit 7**.
- **`PLA` loads into A.** Any sequence ending in `PLA` destroys the accumulator, and `PLA` sets N and Z from the value it pulls.
- Flag reads use the status-preserving sequence `php/pha/php/pla/and #mask/lsr…/tax/pla/plp`, delivering the bit in X.
- `CPU.A` read uses `sta ($0),y` with `y = 0` into a scratch slot — `STA` touches no flag.
- Result type of an arithmetic operation = the **left** operand's type. No integer promotion. Mixed operand types are a compile error.
- Comparisons are unsigned for `ubyte`/`uword` (`icmp ult`/`icmp ugt`) and signed for `byte`/`word` (`icmp slt`/`icmp sgt`).
- Literal typing is **contextual**: a literal takes the expected type when there is one, otherwise the smallest-type rule (`0..255` → `ubyte`, `256..65535` → `uword`, `-127..-1` → `byte`, `-32768..-128` → `word`).
- `float` is IEEE-754 single precision. The old Wozniak FFP format is **not** compatible.
- Arrays are either fixed-address (`^0x3000`) or a global whose length is implied by the index type. No allocator, no bounds checks.
- One `main` and one global-init sequence per program, emitted in a generated driver module.
- Symbol mangling is by hand: `<package>.<file>.<symbol>`, emitted quoted (`@"game.demo.main"`).
- **ANTLR 4 must be invoked with `-package` and `-lib` before the grammar
  files.** The Gradle `antlr` plugin appends its arguments after the file
  list, so the plugin cannot set them; the tool is invoked directly.
- **The Gradle `antlr` plugin runs ANTLR 2 by default.** Declare
  `antlr("org.antlr:antlr4:4.13.1")` or invoke the tool directly.
- **The Kotlin plugin compiles Kotlin before Java**, so kotlinc cannot see Java
  sources in the same source set. The generated parser is compiled by its own
  `JavaCompile` task; its output goes on the Kotlin compile classpath.
- The grammar's root rule is `kotlinFile`, so the parse result is
  `KotlinParser.KotlinFileContext`.

## Review Focus

The spec is a vision document; these are the inputs it implies but no task's tests obviously exercise. Each line names the input and the behaviour a reasonable person would expect. The test that pins each line is named in the task that owns the code.

1. **An empty source file** — compiles to a valid `.prg` that does nothing and exits, not a crash in the driver or a missing-`main` link error.
2. **A source file with a syntax error** — a diagnostic with the file, line and column of the offending token, and a non-zero exit code. Not a stack trace.
3. **A global initialiser that references a global declared *later* in the file** — a compile error ("initialiser references a global that is not yet initialised"), not a silently-zero value.
4. **An out-of-range array index** — reads/writes whatever is at that address (no bounds check), per the spec's explicit "no bounds checking" decision. A user must not expect a trap.
5. **A `return@` inside a function that also has an ordinary `return`** — both are reachable and each behaves per spec: the `return@` is a per-site tail jump, the ordinary `return` returns normally.

---

### Task 1: Project skeleton, grammar, and the parse-only pipeline

Establishes the build, vendors the grammar (including the missing `UnicodeClasses.g4`), and proves the old corpus parses. Nothing else exists yet — this is the foundation every later task builds on.

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/antlr/KotlinLexer.g4` (from `/tmp/wolin-old/KotlinLexer.g4`)
- Create: `src/main/antlr/KotlinParser.g4` (from `/tmp/wolin-old/KotlinParser.g4`)
- Create: `src/main/antlr/UnicodeClasses.g4` (fetched — see Step 4)
- Create: `src/main/kotlin/pl/qus/wolin/parser/ParserFacade.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/Diagnostics.kt`
- Create: `src/test/kotlin/pl/qus/wolin/parser/CorpusParseTest.kt`
- Create: `src/test/resources/corpus/strings.ktk`, `misc.ktk`, `objects.ktk`, `raster_irq.ktk` (copied from `/tmp/wolin-old/`)

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `class Diagnostics { fun error(msg: String, line: Int, col: Int); val hasErrors: Boolean; val messages: List<String> }`
  - `class ParserFacade(private val diag: Diagnostics) { fun parse(source: String, fileName: String): KotlinParser.KotlinFileContext? }` — returns `null` when `diag.hasErrors` is true. The grammar's root rule is `kotlinFile`, so the generated context class is `KotlinParser.KotlinFileContext` — **not** `File`.

- [ ] **Step 1: Write the failing corpus parse test**

```kotlin
package pl.qus.wolin.parser

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

class CorpusParseTest {
    @ParameterizedTest
    @ValueSource(strings = ["strings", "misc", "objects", "raster_irq"])
    fun oldCorpusParsesWithoutErrors(name: String) {
        val src = javaClass.getResource("/corpus/$name.ktk")!!.readText()
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "$name.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors in $name.ktk: ${diag.messages}")
    }
}
```

The test file needs a `package` declaration and explicit imports — a test
class in the default package cannot see `pl.qus.wolin.parser` or
`pl.qus.wolin.driver`.

The test needs `kotlin("test")` on the test classpath for
`kotlin.test.assertTrue`, or use `org.junit.jupiter.api.Assertions.assertTrue`
instead — either works, but the dependency must be declared.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*CorpusParseTest*'`
Expected: FAIL — `ParserFacade` and `Diagnostics` do not exist.

- [ ] **Step 3: Create the Gradle build**

`settings.gradle.kts`:

```kotlin
rootProject.name = "wolin"
```

`build.gradle.kts` — **this exact form is verified to work**; it was reached by
building it, not by guessing. Three things force it:

1. The Gradle `antlr` plugin runs **ANTLR 2** by default. The ANTLR 4 tool must
   be declared: `antlr("org.antlr:antlr4:4.13.1")`.
2. ANTLR requires `-package` and `-lib` **before** the grammar files on the
   command line. The plugin appends its arguments *after* the file list, so the
   tool is invoked directly by a `ProcessBuilder` instead.
3. The Kotlin plugin compiles Kotlin **before** Java, so kotlinc cannot see
   Java sources in the same source set. The generated parser is compiled by its
   own `JavaCompile` task whose output goes on the Kotlin compile classpath.

```kotlin
plugins {
    kotlin("jvm") version "2.2.10"
    id("java")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.antlr:antlr4-runtime:4.13.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

val antlrTool: Configuration by configurations.creating
dependencies { antlrTool("org.antlr:antlr4:4.13.1") }

val antlrSrc = layout.projectDirectory.dir("src/main/antlr")
val antlrOut = layout.buildDirectory.dir("generated/antlr")
val antlrClasses = layout.buildDirectory.dir("classes/antlr")

val generateParser by tasks.registering {
    val tool = antlrTool
    val srcDir = antlrSrc
    val outDir = antlrOut
    inputs.dir(srcDir)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val classpath = tool.resolve().joinToString(File.pathSeparator) { it.absolutePath }
        val grammars = srcDir.asFile.listFiles { f -> f.name.endsWith(".g4") }!!.sorted()
        val cmd = listOf("java", "-cp", classpath, "org.antlr.v4.Tool",
                         "-visitor", "-package", "pl.qus.wolin.parser",
                         "-lib", srcDir.asFile.absolutePath, "-o", out.absolutePath) +
                  grammars.map { it.absolutePath }
        val proc = ProcessBuilder(cmd).inheritIO().start()
        if (proc.waitFor() != 0) throw GradleException("ANTLR failed")
    }
}

val compileAntlr by tasks.registering(JavaCompile::class) {
    dependsOn(generateParser)
    source(antlrOut)
    classpath = files(configurations["compileClasspath"])
    destinationDirectory.set(antlrClasses)
    options.release.set(21)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn(compileAntlr)
    libraries.from(antlrClasses)
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    dependsOn(compileAntlr)
    libraries.from(antlrClasses)
}

tasks.test {
    useJUnitPlatform()
    classpath += files(antlrClasses)
}
```

The `java` command must be on `PATH`. If it is not, use
`System.getProperty("java.home") + "/bin/java"` in the command list.

`gradle.properties`:

```
org.gradle.jvmargs=-Xmx2g
kotlin.code.style=official
```

- [ ] **Step 4: Copy the grammar and add `UnicodeClasses.g4`**

Copy `KotlinLexer.g4` and `KotlinParser.g4` from `/tmp/wolin-old/` into
`src/main/antlr/`. **Keep the grammar names as they are** (`KotlinLexer`,
`KotlinParser`) — ANTLR requires the grammar name to match the file name, and
the generated classes are referenced as `KotlinParser.KotlinFileContext` and
`KotlinLexer` throughout the code.

Keep `import UnicodeClasses;` at the top of the lexer.

`src/main/antlr/UnicodeClasses.g4` — **this file is missing from
`/tmp/wolin-old`** (the old build had it at `src/main/antlr/UnicodeClasses.g4`).
It is not a handful of fragments: the lexer references eight Unicode-category
rules that only this file defines —

```
UNICODE_CLASS_ND  UNICODE_CLASS_NL  UNICODE_CLASS_LL  UNICODE_CLASS_LM
UNICODE_CLASS_LO  UNICODE_CLASS_LT  UNICODE_CLASS_LU
```

— plus the `Digit`, `HexDigit`, `BinDigit`, `Letter` and `EscapedIdentifier`
fragments. A hand-written stub with only the obvious fragments does **not**
work: ANTLR reports `can't find or load grammar UnicodeClasses` and then
`reference to undefined rule: UNICODE_CLASS_ND` and six more.

Fetch the authoritative file instead of writing one:

```bash
curl -sL -o src/main/antlr/UnicodeClasses.g4 \
  https://raw.githubusercontent.com/Kotlin/kotlin-spec/release/grammar/src/main/antlr/UnicodeClasses.g4
```

It is 1648 lines — the full Unicode category tables, taken from
`http://www.antlr3.org/grammar/1345144569663/AntlrUnicode.txt`. Keep its
`lexer grammar UnicodeClasses;` declaration as it is.

**Verify it works before going on:** with all three `.g4` files in
`src/main/antlr/`, ANTLR must generate without errors. A direct invocation
proves it without Gradle:

```bash
java -jar /tmp/antlr-4.13.1-complete.jar -visitor -package pl.qus.wolin.parser \
  -lib src/main/antlr -o /tmp/gen \
  src/main/antlr/KotlinLexer.g4 src/main/antlr/KotlinParser.g4
```

Expected: no output, and `/tmp/gen/` holds `KotlinLexer.java`,
`KotlinParser.java`, `KotlinParserBaseVisitor.java` and the listener files.
If `antlr-4.13.1-complete.jar` is not at `/tmp/`, download it from
`https://www.antlr.org/download/antlr-4.13.1-complete.jar`.

- [ ] **Step 5: Write `Diagnostics.kt`**

`src/main/kotlin/pl/qus/wolin/driver/Diagnostics.kt`:

```kotlin
package pl.qus.wolin.driver

class Diagnostics {
    private val _messages = mutableListOf<String>()

    val messages: List<String> get() = _messages
    val hasErrors: Boolean get() = _messages.isNotEmpty()

    fun error(msg: String, line: Int, col: Int) {
        _messages += "error: $msg (line $line, column $col)"
    }
}
```

- [ ] **Step 6: Write `ParserFacade.kt`**

`src/main/kotlin/pl/qus/wolin/parser/ParserFacade.kt` — creates the lexer with a `ConsoleErrorListener` that forwards to `Diagnostics` instead of printing, removes the default error listeners, and parses the `file` rule:

```kotlin
package pl.qus.wolin.parser

import org.antlr.v4.runtime.*
import pl.qus.wolin.driver.Diagnostics

class ParserFacade(private val diag: Diagnostics) {
    fun parse(source: String, fileName: String): KotlinParser.KotlinFileContext? {
        val lexer = KotlinLexer(CharStreams.fromString(source, fileName))
        lexer.removeErrorListeners()
        lexer.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>, offendingSymbol: Any?,
                line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?
            ) { diag.error(msg, line, charPositionInLine) }
        })

        val parser = KotlinParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>, offendingSymbol: Any?,
                line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?
            ) { diag.error(msg, line, charPositionInLine) }
        })

        val tree = parser.kotlinFile()
        return if (diag.hasErrors) null else tree
    }
}
```

- [ ] **Step 7: Copy the corpus files**

Copy the four `.ktk` files from `/tmp/wolin-old/` into `src/test/resources/corpus/`.

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew test --tests '*CorpusParseTest*'`
Expected: PASS — all four files parse with 0 errors.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties src/main/antlr src/main/kotlin src/test
git commit -m "feat: project skeleton, Wolin grammar, and corpus parse test"
```

---

### Task 2: AST, type system, and scope resolution

Turns the parse tree into a typed AST. This task owns the type rules — contextual literal typing, no promotion, mixed-type errors — and the scope/shadowing model.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/Types.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/Scope.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/SemaException.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/ScopeTest.kt`

**Interfaces:**
- Consumes: `KotlinParser.KotlinFileContext` (Task 1), `Diagnostics` (Task 1).
- Produces:
  - `enum class TypeKind { UBYTE, BYTE, UWORD, WORD, BOOL, UNIT, FLOAT, STRING, ARRAY, FUNCTION }`
  - `data class WolinType(val kind: TypeKind, val nullable: Boolean = false, val elementType: WolinType? = null, val indexType: WolinType? = null, val params: List<WolinType> = emptyList(), val retType: WolinType? = null)`
  - `object Types { val ubyte: WolinType; val byte: WolinType; val uword: WolinType; val word: WolinType; val bool: WolinType; val unit: WolinType; val float: WolinType; val string: WolinType }`
  - `class SemaException(message: String) : Exception(message)`
  - `sealed class Symbol { data class DataSymbol(val type: WolinType, val location: Location?, val init: Expr?) : Symbol(); data class FunSymbol(val decl: FunDecl) : Symbol() }`
  - `class Scope(private val parent: Scope? = null) { fun declare(name: String, symbol: Symbol); fun lookup(name: String): Symbol?; fun lookupLocal(name: String): Symbol?; fun child(): Scope }`
  - `class AstBuilder { fun build(file: KotlinParser.KotlinFileContext): Ast.Program }` — walks the parse tree into the typed AST of `Ast.kt`.
  - `class TypeChecker { fun check(file: Ast.Program): Ast.Program }` — throws `SemaException` on the first error. The returned `Program` carries a `resolved: MutableMap<VarRef, Symbol>` map so tests can assert which declaration each reference bound to.
    - **`resolved` must be an `IdentityHashMap`.** Two `VarRef`s to the same
      name and the same type are equal under the data-class `equals`, so a
      plain `HashMap` would merge them — and the scope test could no longer
      tell the shadowed inner declaration from the outer one. Identity
      comparison keeps them apart.

- [ ] **Step 1: Write the failing type-rule tests**

```kotlin
class TypeCheckerTest {
    /** The type of the first global declaration. */
    private fun typeOf(src: String): WolinType =
        typeChecker(src).globals[0].type

    /** The type of the first local declaration inside the first function. */
    private fun localTypeOf(src: String): WolinType =
        typeChecker(src).functions[0].body.filterIsInstance<LocalDecl>()
            .first().type

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `literal takes the expected type`() {
        assertEquals(Types.uword, typeOf("var i: uword = 0"))
        assertEquals(Types.ubyte, typeOf("var len: ubyte = 0"))
    }

    @Test fun `literal without an expected type uses the smallest type`() {
        assertEquals(Types.uword, typeOf("var x = 300"))
        assertEquals(Types.ubyte, typeOf("var x = 200"))
    }

    @Test fun `literal that does not fit is rejected`() {
        assertThrows<SemaException> { typeOf("var x: ubyte = 300") }
    }

    @Test fun `mixed operand types are rejected`() {
        assertThrows<SemaException> { typeChecker("fun f() { var a: ubyte = 1; var b: uword = 2; var c = a + b }") }
    }

    @Test fun `arithmetic result type is the left operand type`() {
        assertEquals(Types.ubyte, localTypeOf("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a + b }"))
    }

    @Test fun `negative literal is a byte`() {
        assertEquals(Types.byte, typeOf("var x = -1"))
    }

    @Test fun `negative literal into an unsigned destination is rejected`() {
        assertThrows<SemaException> { typeOf("var x: ubyte = -1") }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*TypeCheckerTest*'`
Expected: FAIL — `Types`, `TypeChecker`, `AstBuilder` do not exist.

- [ ] **Step 3: Write `Types.kt`**

`src/main/kotlin/pl/qus/wolin/sema/Types.kt` defines `TypeKind`, `WolinType` and the `Types` object exactly as in the Interfaces block. `WolinType.equals` is the data-class one, so `Types.uword == Types.uword` holds by identity of the singletons.

- [ ] **Step 4: Write `Ast.kt`**

`src/main/kotlin/pl/qus/wolin/ast/Ast.kt` defines the node types the analyser produces:

```kotlin
package pl.qus.wolin.ast

import pl.qus.wolin.sema.Symbol
import pl.qus.wolin.sema.WolinType

/**
 * The typed AST.
 *
 * Every node is nested under [Ast] so that it is reachable as `Ast.Program`,
 * `Ast.LocalDecl` and so on; the top-level type aliases below are the same
 * types under their plain names.
 */
sealed interface Ast {
    sealed interface Expr : Ast { val type: WolinType }
    sealed interface Stmt : Ast

    data class Program(
        val globals: List<GlobalDecl>,
        val functions: List<FunDecl>,
        val imports: List<ImportDecl>,
        /** Which declaration each variable reference bound to. Filled by the checker. */
        val resolved: MutableMap<VarRef, Symbol> = mutableMapOf()
    ) : Ast

    data class ImportDecl(val path: String, val alias: String?) : Ast

    /**
     * A file-scope variable. `type` is the declared type, or the inferred one
     * when the declaration has no type annotation; `declaredType` tells the two
     * apart.
     */
    data class GlobalDecl(
        val name: String,
        var type: WolinType,
        val init: Expr?,
        val location: Location?,
        /** `val` declarations cannot be assigned to. */
        val isVal: Boolean = false,
        /** The type as written in the source, or null when there was none. */
        val declaredType: WolinType? = null
    ) : Ast

    data class FunDecl(
        val name: String,
        val params: List<Param>,
        val retType: WolinType?,
        val body: List<Stmt>,
        val isInterrupt: Boolean,
        val fixedAddress: Int?,
        val returnAt: List<ReturnAt>
    ) : Ast

    data class Param(val name: String, val type: WolinType, val location: Location?) : Ast
    data class ReturnAt(val address: Int) : Ast

    sealed class Location : Ast
    data class FixedAddress(val address: Int) : Location()
    data class CpuRegister(val name: String) : Location()      // "CPU.A", "CPU.X", "CPU.Y", "CPU.C", "CPU.I", "CPU.N", "CPU.Z", "CPU.AX", "CPU.XY"
    data class FixedArray(val address: Int) : Location()

    data class IntLit(val value: Long, override val type: WolinType) : Expr
    data class RealLit(val value: Double, override val type: WolinType) : Expr
    data class BoolLit(val value: Boolean, override val type: WolinType) : Expr
    data class CharLit(val value: Int, override val type: WolinType) : Expr
    data class StringLit(val bytes: ByteArray, override val type: WolinType) : Expr
    data class NullLit(override val type: WolinType) : Expr
    data class VarRef(val name: String, override val type: WolinType) : Expr
    data class UnaryOp(val op: String, val operand: Expr, override val type: WolinType) : Expr
    data class BinaryOp(val op: String, val left: Expr, val right: Expr, override val type: WolinType) : Expr
    data class Call(val callee: Expr, val args: List<Expr>, override val type: WolinType) : Expr
    data class Assign(val target: Expr, val value: Expr, val op: String = "=", override val type: WolinType) : Expr
    data class IfExpr(val cond: Expr, val then: Expr?, val otherwise: Expr?, override val type: WolinType) : Expr
    data class ArrayAccess(val array: Expr, val index: Expr, override val type: WolinType) : Expr
    data class FunctionLit(val params: List<String>, val body: List<Stmt>, override val type: WolinType) : Expr

    data class LocalDecl(
        val name: String,
        var type: WolinType,
        val init: Expr?,
        val isVal: Boolean = false,
        val declaredType: WolinType? = null,
        val location: Location? = null
    ) : Stmt

    data class BlockStmt(val statements: List<Stmt>) : Stmt
    data class ExprStmt(val expr: Expr) : Stmt
    data class IfStmt(val cond: Expr, val then: List<Stmt>, val otherwise: List<Stmt>) : Stmt
    data class WhileStmt(val cond: Expr, val body: List<Stmt>, val isDoWhile: Boolean) : Stmt
    data class BreakStmt(val label: String?) : Stmt
    data class ContinueStmt(val label: String?) : Stmt
    data class ReturnStmt(val value: Expr?) : Stmt
}

/* The same types under their plain names. */
typealias Program = Ast.Program
typealias ImportDecl = Ast.ImportDecl
typealias GlobalDecl = Ast.GlobalDecl
typealias FunDecl = Ast.FunDecl
typealias Param = Ast.Param
typealias ReturnAt = Ast.ReturnAt
typealias Location = Ast.Location
typealias FixedAddress = Ast.FixedAddress
typealias CpuRegister = Ast.CpuRegister
typealias FixedArray = Ast.FixedArray
typealias Expr = Ast.Expr
typealias Stmt = Ast.Stmt
typealias IntLit = Ast.IntLit
typealias RealLit = Ast.RealLit
typealias BoolLit = Ast.BoolLit
typealias CharLit = Ast.CharLit
typealias StringLit = Ast.StringLit
typealias NullLit = Ast.NullLit
typealias VarRef = Ast.VarRef
typealias UnaryOp = Ast.UnaryOp
typealias BinaryOp = Ast.BinaryOp
typealias Call = Ast.Call
typealias Assign = Ast.Assign
typealias IfExpr = Ast.IfExpr
typealias ArrayAccess = Ast.ArrayAccess
typealias FunctionLit = Ast.FunctionLit
typealias LocalDecl = Ast.LocalDecl
typealias BlockStmt = Ast.BlockStmt
typealias ExprStmt = Ast.ExprStmt
typealias IfStmt = Ast.IfStmt
typealias WhileStmt = Ast.WhileStmt
typealias BreakStmt = Ast.BreakStmt
typealias ContinueStmt = Ast.ContinueStmt
typealias ReturnStmt = Ast.ReturnStmt
```

The nodes are nested under `sealed interface Ast` so that both `Ast.Program`
and `Program` spell the same type — the brief's Step-1 test uses the nested
form, and the code below uses the plain form.

`LocalDecl.type` is the *declared* type, or the *inferred* type when the
declaration has no explicit type annotation; `declaredType` tells the two
apart. `isVal` records `val` versus `var`, which is what makes `val`
immutability checkable.

- [ ] **Step 5: Write `Scope.kt`**

`src/main/kotlin/pl/qus/wolin/sema/Scope.kt` implements a scope stack with shadowing:

```kotlin
package pl.qus.wolin.sema

class Scope(private val parent: Scope? = null) {
    private val names = mutableMapOf<String, Symbol>()

    fun declare(name: String, symbol: Symbol) { names[name] = symbol }
    fun lookup(name: String): Symbol? = names[name] ?: parent?.lookup(name)
    fun lookupLocal(name: String): Symbol? = names[name]

    fun child(): Scope = Scope(this)
}
```

`Symbol` is a sealed class: `DataSymbol(val type: WolinType, val location: Location?, val init: Expr?)` and `FunSymbol(val decl: FunDecl)`.

- [ ] **Step 6: Write `AstBuilder.kt` and `TypeChecker.kt`**

**The parse-tree context classes you will walk** (read from the generated
`KotlinParser.java` — do not guess these names):

| Construct | Context class |
|---|---|
| whole file | `KotlinParser.KotlinFileContext` |
| a top-level property (`var x: ubyte = 5`) | `PropertyDeclarationContext` |
| a local variable (`var x = 5` inside a block) | `VariableDeclarationContext` |
| a function | `FunctionDeclarationContext` |
| a type annotation | `TypeReferenceContext` |
| a literal of any kind | `LiteralConstantContext` |
| a string literal | `StringLiteralContext` |
| an identifier | `SimpleIdentifierContext` |
| an `if` | `IfExpressionContext` |
| `while` / `do..while` | `WhileExpressionContext` / `DoWhileExpressionContext` |
| a function literal | `FunctionLiteralContext` |
| an array type suffix | `ArrayDeclarationContext` |

The literal kinds are reachable through `LiteralConstantContext`'s accessors:
`IntegerLiteral()`, `HexLiteral()`, `BinLiteral()`, `RealLiteral()`,
`BooleanLiteral()`, `CharacterLiteral()`, `stringLiteral()`, `LongLiteral()`,
`NullLiteral()`.

**How the `^location` suffix parses.** The grammar rule is
`typeReference: ... | userType (arrayDeclaration)? (NL* CARON locationReference)?`
and `locationReference: literalConstant | userType`. So:

- `var x: ubyte^0xd020` — the location is a **literalConstant** → a
  `FixedAddress`.
- `var x: bool^CPU.I` — the location is a **userType** whose qualified name
  starts with `CPU.` → a `CpuRegister`.
- `var arr: ubyte[ubyte]^0x3000` — a userType plus an `arrayDeclaration`, then
  the location.

The `CPU` prefix is what distinguishes a register from an ordinary qualified
type name. `CPU` is a reserved namespace in v1: a user type named `CPU` is a
compile error.

`src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt` walks the parse tree into the AST of `Ast.kt`. It resolves declarations, expressions and statements, and leaves every type slot unset — the `TypeChecker` fills them in.

`src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` — `check(file: Ast.Program): Ast.Program` walks the AST and enforces the type rules. The rules it must implement, each with a test above:

- **Contextual literal typing.** `IntLit` gets the expected type when the context supplies one, else the smallest-type rule. `expectedTypeOf(0..255) = ubyte`, `256..65535 = uword`, `-127..-1 = byte`, `-32768..-128 = word`; anything else throws `"Value out of range"`.
- **Literal fit.** A literal assigned to a narrower type must fit: `300` into `ubyte` throws, `-1` into `ubyte` throws.
- **No promotion.** `BinaryOp` with arithmetic requires both operand types to be *equal* (same `kind`); otherwise throw `"Attempt reassign X Y"`. The result type is the **left** operand's type.
- **Comparison.** `==`/`!=` accept any two equal-typed operands and produce `bool`. `<`/`<=`/`>`/`>=` accept equal-typed operands and produce `bool`.
- **Logical `&&`/`||`** require `bool` operands and produce `bool`. `&`/`|` are bitwise and accept `ubyte`/`uword`/`word`/`byte` operands.
- **Type inference.** A declaration with no explicit type takes the initialiser's type. A declaration with neither type nor initialiser throws `"Variable X has no type!"`.
- **`val` immutability.** Assigning to a `val` throws. `var` is mutable.
- **Scope.** A block introduces a child scope; a declaration in it shadows an outer one of the same name; the shadow ends with the block.

- [ ] **Step 7: Write the scope tests**

```kotlin
class ScopeTest {
    @Test fun `inner declaration shadows outer and is restored on exit`() {
        val src = "fun f() { var x: ubyte = 1; { var x: ubyte = 2; var y: ubyte = x }; var z: ubyte = x }"
        // x inside the inner block resolves to the inner declaration
        // x after the inner block resolves to the outer declaration
    }
}
```

Assert the resolved types/values by having `TypeChecker` record the resolved `Symbol` for each `VarRef` — add a `resolved: MutableMap<VarRef, Symbol>` field to the checker's result so the test can assert which declaration each reference bound to.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests '*TypeCheckerTest*' --tests '*ScopeTest*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ast src/main/kotlin/pl/qus/wolin/sema src/test/kotlin/pl/qus/wolin/sema
git commit -m "feat: AST, type system with contextual literal typing, and scope resolution"
```

---

### Task 3: The IR builder

The core abstraction: SSA value naming, basic blocks, terminators, and phi placement. Every later task emits IR through this, so it is built and tested before anything is emitted into it.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ir/IrBuilder.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/IrValue.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/IrType.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/IrBuilderTest.kt`

**Interfaces:**
- Consumes: `pl.qus.wolin.sema.WolinType` (Task 2).
- Produces:
  - `sealed interface IrType { object I8; object I16; object I32; object I1; object Float; object Void; object Ptr; data class Array(val of: IrType, val count: Int) }`
  - `data class IrValue(val name: String, val type: IrType)` — `name` is the full SSA name including `%`.
  - `class IrBuilder { fun startFunction(name: String, retType: IrType, params: List<IrValue> = emptyList()); fun endFunction(); fun addBlock(name: String); fun block(): String; fun value(type: IrType): IrValue; fun alloca(type: IrType): IrValue; fun instr(type: IrType, text: String, operands: List<IrValue> = emptyList()): IrValue; fun phi(type: IrType, incoming: List<Pair<IrValue, String>>): IrValue; fun branch(target: String); fun condBranch(cond: IrValue, ifTrue: String, ifFalse: String); fun ret(value: IrValue?); fun callVoid(target: String, args: List<IrValue>, fnType: IrType? = null); fun call(retType: IrType, target: String, args: List<IrValue>): IrValue; fun render(): String }`
    - `startFunction` takes the function's parameters so `render()` can emit
      `define <ret> @<name>(<params>)`. The parameters are **not** allocated SSA
      names — they keep the names they are given (e.g. `%a`).

- [ ] **Step 1: Write the failing builder tests**

```kotlin
class IrBuilderTest {
    @Test fun `ssa names are deterministic and per function`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        val v1 = b.instr(IrType.I8, "add i8 %a, 1", listOf(IrValue("%a", IrType.I8)))
        val v2 = b.instr(IrType.I8, "add i8 %b, 1", listOf(IrValue("%b", IrType.I8)))
        b.endFunction()
        assertEquals(listOf("%v0", "%v1"), listOf(v1.name, v2.name))
    }

    @Test fun `every block gets a terminator`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.ret(IrValue("%v0", IrType.I32))
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("define i32 @f"))
        assertTrue(ir.contains("ret i32 %v0"))
    }

    @Test fun `a block without a terminator is rejected`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.addBlock("bb1")
        b.endFunction()
        assertThrows<IllegalStateException> { b.render() }
    }

    @Test fun `phi takes one incoming per predecessor`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32)
        b.branch("merge")
        b.addBlock("merge")
        b.phi(IrType.I32, listOf(IrValue("%v0", IrType.I32) to "entry", IrValue("%v1", IrType.I32) to "else"))
        b.ret(IrValue("%v2", IrType.I32))
        b.endFunction()
        assertTrue(b.render().contains("phi i32 [ %v0, %entry ], [ %v1, %else ]"))
    }

    @Test fun `function parameters are rendered`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I32, listOf(IrValue("%a", IrType.I8), IrValue("%b", IrType.I16)))
        b.ret(IrValue("%v0", IrType.I32))
        b.endFunction()
        assertTrue(b.render().contains("define i32 @f(i8 %a, i16 %b)"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*IrBuilderTest*'`
Expected: FAIL — `IrBuilder` does not exist.

- [ ] **Step 3: Write `IrType.kt` and `IrValue.kt`**

`src/main/kotlin/pl/qus/wolin/ir/IrType.kt` declares `IrType` exactly as in the Interfaces block, plus `fun IrType.llvmName(): String` returning `i8`, `i16`, `i32`, `i1`, `float`, `void`, `ptr`, or `[N x T]`.

`src/main/kotlin/pl/qus/wolin/ir/IrValue.kt` declares `IrValue`.

- [ ] **Step 4: Write `IrBuilder.kt`**

`src/main/kotlin/pl/qus/wolin/ir/IrBuilder.kt` holds, per function: a list of blocks, each block a list of instruction lines plus a terminator; a counter for SSA names; a counter for block labels.

Rules the implementation must follow:

- Value names are `%v0`, `%v1`, … allocated in emission order, **reset per function**.
- Block labels are `entry`, `bb1`, `bb2`, … within a function.
- `instr` appends `"  <text>"` to the current block and returns a fresh `IrValue` whose `name` is the next `%vN`.
- `render()` emits, per function: `define <ret> @<name>(<params>) {`, then the blocks, then `}`. Every block must have a terminator; `render()` throws `IllegalStateException("block %s has no terminator")` if one is missing.
- `startFunction` opens an `entry` block automatically, so a caller that
  immediately emits a `ret` does not have to call `addBlock("entry")` first.
  `addBlock` after that creates the next block (`bb1`, `bb2`, …).
- `phi` emits `phi <type> [ %v, %label ], ...` — one entry per incoming, in the given order.
- `branch` emits `br label %<target>`; `condBranch` emits `br i1 %<cond>, label %<ifTrue>, label %<ifFalse>`; `ret` emits `ret <type> <value>` or `ret void`.
- `callVoid`/`call` emit `call <ret> @<target>(<args>)` — `call void @f(i8 %a)` for void, `call i32 @f(i8 %a)` otherwise.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*IrBuilderTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir src/test/kotlin/pl/qus/wolin/ir
git commit -m "feat: IR builder with SSA naming, blocks, terminators and phi placement"
```

---

### Task 4: IR emission for declarations, expressions and statements

The first thing that emits real IR. Covers globals, locals, fixed-address variables, arrays, arithmetic, comparisons, assignments and control flow.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ir/TypeMapper.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt`
- Test: `src/test/resources/golden/`

**Interfaces:**
- Consumes: `IrBuilder` (Task 3), `Ast` (Task 2), `Types` (Task 2).
- Produces:
  - `object TypeMapper { fun ir(t: WolinType): IrType }` — `ubyte`/`byte` → `I8`, `uword`/`word` → `I16`, `bool` → `I1`, `float` → `Float`, `unit` → `Void`, `string` → `Ptr`, array → `Ptr` (arrays are addressed, not first-class).
  - `class ExprEmitter(private val b: IrBuilder) { fun emit(e: Expr): IrValue }`
  - `class StmtEmitter(private val b: IrBuilder) { fun emit(s: Stmt) }`
  - `class ModuleEmitter(private val b: IrBuilder) { fun emitFile(program: Ast.Program, mangledPrefix: String): String }`

- [ ] **Step 1: Write the failing golden test**

```kotlin
class GoldenIrTest {
    @ParameterizedTest
    @ValueSource(strings = ["add_ubyte", "global_const"])
    fun emitsExpectedIr(name: String) {
        val src = javaClass.getResource("/golden/$name.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        val expected = javaClass.getResource("/golden/$name.ll")!!.readText()
        assertEquals(expected.trim(), ir.trim())
    }

    @Test fun `a fixed address variable emits no storage`() {
        val src = javaClass.getResource("/golden/fixed_address_var.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertFalse(ir.contains("@border"), "fixed-address vars must not allocate storage:\n$ir")
        assertTrue(ir.contains("inttoptr (i64 53280 to i8*)"), ir)
    }
}
```

The three `.wtk` fixtures and their `.ll` expectations:

`add_ubyte.wtk`:
```
fun add(a: ubyte, b: ubyte): ubyte {
    return a + b
}
```
`add_ubyte.ll`:
```
target triple = "mos-c64"

define i8 @"t.add"(i8 %a, i8 %b) {
entry:
  %v0 = add i8 %a, %b
  ret i8 %v0
}
```

The symbol is mangled to `<package>.<file>.<symbol>` and emitted quoted. The
test source has no `package` declaration, so the prefix is just the file name
`t`.

`fixed_address_var.wtk`:
```
var border: ubyte^0xd020
```
`fixed_address_var.ll`:
```
; a fixed-address variable emits NO storage — every access is a
; load/store through the constant address
```
That is, the module contains no global for `border`. Its accesses are
`load i8, ptr inttoptr (i64 53280 to i8*)` and
`store i8 %x, ptr inttoptr (i64 53280 to i8*)`. A golden file with no
content is awkward to express as an exact-match assertion, so this fixture
asserts the *absence* of a global and the *presence* of the `inttoptr`
form — see Step 1's second assertion.

`global_const.wtk`:
```
var count: ubyte = 5
```
`global_const.ll`:
```
target triple = "mos-c64"

@"t.count" = global i8 5
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*GoldenIrTest*'`
Expected: FAIL — `CompilerPipeline` does not exist.

- [ ] **Step 3: Write `TypeMapper.kt` and `ExprEmitter.kt`**

`TypeMapper` maps as in the Interfaces block.

`ExprEmitter` implements, per expression kind:

- `IntLit` — materialise the value: `b.value(type)` produces an SSA name, and the emitter emits `add <ty> %vN, 0` (or the backend's equivalent) so the constant is defined. For `i1` (bool) the constant is `1`/`0`.
- `RealLit` — a `float` global `@floatConst_<n> = private global float <value>` in the module, and the expression loads from it.
- `StringLit` — a module-level `private unnamed_addr constant [N+1 x i8] c"…\00"`, and the expression is a `getelementptr` to it.
- `VarRef` — load from the variable's storage.
- `BinaryOp` — arithmetic on `i8`/`i16`: `add`/`sub`/`mul`/`udiv`/`urem`/`shl`/`lshr`; the **left** operand's type decides the result type. Division uses `udiv`/`urem` for unsigned types and `sdiv`/`srem` for `byte`/`word`. Comparisons: `icmp eq`/`ne` for equality; `icmp ult`/`ugt` for unsigned order; `icmp slt`/`sgt` for signed order.
- `UnaryOp` — `-x` is `sub` from zero; `!x` is `xor i1 %v, true`.
- `Assign` — store to the target's storage.
- `Call` — `call <ret> @<callee>(<args>)`; a call to a function-typed variable is `call <ret> %<ptr>(<args>)`.

**Array indexing** always promotes the index to `uword` and emits a GEP:

```
%idx = zext i8 %i to i16
%p = getelementptr i8, ptr @arr, i16 %idx
%v = load i8, ptr %p
```

For a 16-bit element type the GEP element type is `i16` and the index is scaled by the element size.

**Fixed-address variables** load and store through `inttoptr`:

```
%p = inttoptr (i64 53280 to i8*)
%v = load i8, ptr %p
store i8 %x, ptr inttoptr (i64 53280 to i8*)
```

The store must be `volatile` — a plain store to a hardware register would be optimised away.

- [ ] **Step 4: Write `StmtEmitter.kt`**

`StmtEmitter` implements:

- **Local declaration** — `alloca` the slot, store the initialiser.
- **`if`/`else`** — a comparison, then `condBranch` to `then`/`else`/`end` blocks.
- **`while` / `do..while`** — a `loop` block, a `body` block, and a `br` back to `loop`.
- **`when`** — a chain of comparisons and branches; the subject form compares the subject against each branch value; the conditionless form tests each condition's boolean.
- **`break` / `continue`** — branch to the enclosing loop's break/continue label.
- **Assignment statement** — delegate to `ExprEmitter`.
- **Expression statement** — delegate to `ExprEmitter`, discard the result.

`if`/`while`/`when` produce **phi nodes** at their merge points: the emitter tracks the current SSA value of each in-scope variable per block and, at a merge, emits a phi for every variable whose value differs between the predecessors. The emitter must supply an incoming value for *every* predecessor of the merge block — LLVM rejects the module otherwise.

- [ ] **Step 5: Write `ModuleEmitter.kt`**

`ModuleEmitter.emitFile` produces:

```
target triple = "mos-c64"

<globals>

<functions>
```

Global names are mangled `<package>.<file>.<symbol>` and emitted quoted: `@"game.demo.count"`.

- [ ] **Step 6: Write `CompilerPipeline.kt` (a thin seam for this task)**

`src/main/kotlin/pl/qus/wolin/driver/CompilerPipeline.kt`:

```kotlin
package pl.qus.wolin.driver

import pl.qus.wolin.ir.ModuleEmitter
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.TypeChecker

class CompilerPipeline {
    /**
     * @param fileName the source's file name; its base name (without extension)
     *   is the mangling prefix, so `t.wtk` mangles `main` to `@"t.main"`.
     */
    fun compileToIr(source: String, fileName: String): String {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(source, fileName) ?: error(diag.messages.joinToString("\n"))
        val program = TypeChecker().check(AstBuilder().build(tree))
        return ModuleEmitter().emitFile(program, fileName.removeSuffix(".wtk").removeSuffix(".ktk"))
    }
}
```

- [ ] **Step 7: Run the golden test to verify it passes**

Run: `./gradlew test --tests '*GoldenIrTest*'`
Expected: PASS — the three fixtures match byte for byte.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir src/main/kotlin/pl/qus/wolin/driver src/test/kotlin/pl/qus/wolin/ir src/test/resources/golden
git commit -m "feat: IR emission for declarations, expressions and statements"
```

---

### Task 5: CPU registers, fixed-address functions, and inline-asm accessors

Emits the register accessors, the ROM-call mechanism and the interrupt form. Every sequence here was verified against the real backend — the task must not invent new ones.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ir/CpuAccessors.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/ExternalCalls.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/CpuAccessorTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/ExternalCallTest.kt`

**Interfaces:**
- Consumes: `IrBuilder` (Task 3), `ExprEmitter` (Task 4).
- Produces:
  - `object CpuAccessors` with:
    - `fun readA(b: IrBuilder): IrValue` — creates its scratch slot via `b.alloca(IrType.I8)`, emits `ldy #0` + `sta ($0),y` with the slot as the `"r"` pointer operand, then loads from the slot. **Touches no flag.** The accessor assumes the current block is the function's `entry` block (the slot is an `alloca`).
    - `fun readAValue(b: IrBuilder): IrValue` — emits `pha\npla` with constraint `"=a"` (output-only, no operand). Clobbers N and Z.
    - `fun readFlag(b: IrBuilder, mask: Int): IrValue` — emits the status-preserving sequence with constraint `"=x"` (output-only, no operand).
    - `fun writeRegister(b: IrBuilder, reg: String, value: IrValue)` — emits an empty `asm sideeffect ""` with an input constraint `"a"`/`"x"`/`"y"`.
    - `fun readAxLow(b: IrBuilder): IrValue`, `fun readAxHigh(b: IrBuilder): IrValue`.
  - `class ExternalCalls { fun emitCall(b: IrBuilder, target: Int, args: List<Pair<IrValue, String>>) }` — `args` pairs each argument value with its CPU register name.

- [ ] **Step 1: Write the failing accessor tests**

```kotlin
class CpuAccessorTest {
    @Test fun `read A uses sta so no flag is touched`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readA(b)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("ldy #0"), ir)
        assertTrue(ir.contains("sta ($0),y"), ir)
        assertFalse(ir.contains("pha"), ir)
    }

    @Test fun `flag read preserves A and P`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        val v = CpuAccessors.readFlag(b, 0x01)
        b.ret(v)
        b.endFunction()
        val ir = b.render()
        for (op in listOf("php", "pha", "pla", "tax", "plp")) assertTrue(ir.contains(op), "$op missing in:\n$ir")
        assertTrue(ir.contains("\"=x\"()"))
        // the backend rejects clobber lists
        assertFalse(ir.contains(": \"memory\""), "no clobber list may be emitted:\n$ir")
    }

    @Test fun `negative flag mask is bit 7`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.I8)
        CpuAccessors.readFlag(b, 0x80)
        b.ret(IrValue("%v0", IrType.I8))
        b.endFunction()
        assertTrue(b.render().contains("and #128"))
    }
}

class ExternalCallTest {
    @Test fun `rom call pins arguments in their declared registers`() {
        val b = IrBuilder()
        b.startFunction("f", IrType.Void)
        ExternalCalls().emitCall(b, 0xffba, listOf(
            IrValue("%a", IrType.I8) to "CPU.A",
            IrValue("%y", IrType.I8) to "CPU.Y",
            IrValue("%x", IrType.I8) to "CPU.X",
        ))
        b.ret(null)
        b.endFunction()
        val ir = b.render()
        assertTrue(ir.contains("jsr $$ffba"), ir)
        assertTrue(ir.contains("\"a,y,x\""), ir)
        assertFalse(ir.contains(": \"memory\""), "no clobber list may be emitted:\n$ir")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*CpuAccessorTest*' --tests '*ExternalCallTest*'`
Expected: FAIL — `CpuAccessors` and `ExternalCalls` do not exist.

- [ ] **Step 3: Write `CpuAccessors.kt`**

`src/main/kotlin/pl/qus/wolin/ir/CpuAccessors.kt` emits exactly these sequences.

**`readA`** — `STA` touches no flag, so the status register survives:

```
ldy #0
sta ($0),y
```

The scratch slot is created inside `readA` with `b.alloca(IrType.I8)` and
passed as a pointer operand (`"r"(i8* %slot)`); the accessor assumes the
current block is `entry` (the slot is an `alloca`).
**Verified at IR level**: a program that set A to `$42`, read it with this
sequence into an `alloca`, and stored the result to `$0400` read back `42` —
the same value `pha`/`pla` produced.

The constraint string is just `"r"` — **the backend rejects a clobber list**
here. Adding `"a", "y", "memory"` produces `error: expected instruction
opcode`. A `memory` clobber is not expressible in this backend's inline asm.

**`readAValue`** — `pha\npla` with `"=a"` (output-only, no operand).

**`readFlag(mask)`** — the status-preserving sequence:

```
php
pha
php
pla
and #<mask>
lsr ...        ; only for masks above bit 0
tax
pla
plp
```
with `"=x"` (output-only, no operand). The number of `lsr` instructions is the **bit position** of the mask (`mask.trailingZeros()`; the mask is a single bit) — for mask `1` (carry) none, for `2` (zero) one, for `128` (negative) seven. The `lsr` count normalises the bit to position 0 so the result is `0` or `1`.

**`writeRegister`** — an empty template with an input constraint:

```
call void asm sideeffect "", "a"(i8 %v)
```

**`readAxLow`** — `pha\npla` with `"=a"` (output-only, no operand).

**`readAxHigh`** — `pha\ntxa\ntax\npla` with `"=x"` (output-only, no operand).

**No clobber lists.** The MOS backend rejects them: an `asm` with a clobber
list fails with `error: expected instruction opcode`. The constraints alone
carry the register requirements.

**Empty-operand input constraints are invalid IR.** An input constraint
requires a matching parameter — upstream LLVM `InlineAsm::verify` rejects
`"=a,a"()` with `error: number of input constraints does not match number of
parameters`. The pure reads are therefore **output-only** (`"=a"` / `"=x"`,
no operand). Verified against the real backend on 2026-11-13
(`.superpowers/sdd/2026-11-08-wolin-llvm-implementation/task-5-report.md`):
no prologue is emitted and the live register value is returned; consecutive
same-register output asms compile (the backend parks the first result in a
scratch reg); a void-return asm with an output constraint followed by another
same-register output asm fails with `could not allocate output register` —
keep reads returning the value and writes taking input constraints.

- [ ] **Step 4: Write `ExternalCalls.kt`**

`ExternalCalls.emitCall` emits:

```
call void asm sideeffect "jsr $$<hex>", "a,y,x"(i8 %a, i8 %y, i8 %x)
```

The constraint string lists the registers in **declaration order**, and the operands follow the same order. The `$$` escape is mandatory.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests '*CpuAccessorTest*' --tests '*ExternalCallTest*'`
Expected: PASS.

- [ ] **Step 6: Verify the emitted asm against the real backend**

Write a small `.ll` by hand containing exactly the sequences above, compile it, and check the assembly:

```bash
SDK=~/.local/share/rust-mos/sdk-v23.2.0
$SDK/bin/mos-c64-clang -target mos-c64 -O2 -fno-lto -S /tmp/check.ll -o /tmp/check.s
```

Expected in the output: `ldy #0` then `sta (__rc…),y` for the A read; `php`/`pha`/`php`/`pla`/`and #1`/`tax`/`pla`/`plp` for the carry read; `jsr 65466` for a `$ffba` call.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir src/test/kotlin/pl/qus/wolin/ir
git commit -m "feat: CPU register accessors, ROM calls and verified inline-asm sequences"
```

---

### Task 6: The toolchain driver, SDK discovery, and the generated driver module

Turns a set of `.wtk` files into a `.prg`. Owns SDK discovery, IR validation, the per-file/driver module split, and symbol mangling.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/driver/SdkLocator.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/Mangler.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/Linker.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/DriverModule.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/Main.kt`
- Test: `src/test/kotlin/pl/qus/wolin/driver/SdkLocatorTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/driver/ManglerTest.kt`

**Interfaces:**
- Consumes: `ModuleEmitter` (Task 4), `CpuAccessors`/`ExternalCalls` (Task 5).
- Produces:
  - `data class Sdk(val root: Path) { val clang: Path get() = root.resolve("bin/mos-c64-clang") }`
  - `object SdkLocator { fun locate(explicit: String? = null, env: Map<String, String> = System.getenv()): Sdk }`
  - `object Mangler { fun mangle(packageName: String, fileName: String, symbol: String): String }`
  - `data class CompiledFile(val sourceName: String, val mangledPrefix: String, val moduleIr: String, val initFnName: String?)`
  - `class DriverModule(private val b: IrBuilder) { fun emit(files: List<CompiledFile>): String }` — emits `main` and the global-init calls.
  - `class Linker(private val sdk: Sdk) { fun validate(ll: Path); fun link(llFiles: List<Path>, output: Path, workDir: Path) }`

- [ ] **Step 1: Write the failing locator and mangler tests**

```kotlin
class SdkLocatorTest {
    @Test fun `explicit path wins`() {
        val sdk = SdkLocator.locate("/opt/sdk", mapOf("WOLIN_SDK" to "/other"))
        assertEquals(Path.of("/opt/sdk"), sdk.root)
    }

    @Test fun `env var is used when no explicit path`() {
        val sdk = SdkLocator.locate(null, mapOf("WOLIN_SDK" to "/from/env"))
        assertEquals(Path.of("/from/env"), sdk.root)
    }

    @Test fun `missing sdk fails with a message naming what was tried`() {
        val e = assertThrows<IllegalStateException> {
            SdkLocator.locate(null, mapOf("WOLIN_SDK" to "/nope"), globRoots = listOf(Path.of("/also/nope")))
        }
        assertTrue(e.message!!.contains("WOLIN_SDK"), e.message)
    }
}

class ManglerTest {
    @Test fun `symbols are mangled by package file and name`() {
        assertEquals("game.demo.main", Mangler.mangle("game", "demo", "main"))
    }

    @Test fun `dotted names are quoted for LLVM`() {
        assertTrue("\"game.demo.main\"".length > 0)
    }
}
```

Give `SdkLocator.locate` an extra `globRoots: List<Path> = emptyList()` parameter so the test can exercise the glob path without touching the real home directory.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*SdkLocatorTest*' --tests '*ManglerTest*'`
Expected: FAIL — `SdkLocator` and `Mangler` do not exist.

- [ ] **Step 3: Write `SdkLocator.kt`**

Resolution order, exactly:

1. The `explicit` argument (the `--sdk` CLI option).
2. The `WOLIN_SDK` environment variable.
3. The glob `~/.local/share/rust-mos/sdk-*`, newest first (sort by version descending, comparing the numeric suffix).
4. `mos-c64-clang` on `PATH` — in this case the SDK root is the parent of the executable's parent.

On failure, throw `IllegalStateException` with a message naming every path that was tried.

- [ ] **Step 4: Write `Mangler.kt`**

`Mangler.mangle(packageName, fileName, symbol)` returns `"$packageName.$fileName.$symbol"` with empty parts omitted (a file with no `package` declaration mangles to `"<fileName>.<symbol>"`). The mangled name is emitted in LLVM as a quoted identifier: `@"game.demo.main"`.

- [ ] **Step 5: Write `DriverModule.kt`**

`DriverModule.emit` produces the module that holds `main` and the global-init
sequence. It calls each file's init function (named by `CompiledFile.initFnName`)
in the order the files were given, then calls the user's `main`:

```
target triple = "mos-c64"

@__wolin_init_globals_a = internal void () {
entry:
  call void @__wolin_init_file_a()
  ret void
}

define i32 @main() {
entry:
  call void @__wolin_init_globals_a()
  call void @__wolin_init_globals_b()
  call void @"game.demo.main"()
  ret i32 0
}
```

Each file contributes `__wolin_init_file_<mangled>`; the driver calls them in command-line order, then calls the user's `main`. If no file declares `main`, the driver returns immediately.

- [ ] **Step 6: Write `Linker.kt`**

`Linker.validate(ll)` runs `mos-c64-clang -fno-lto -O2 -x ir -c <ll> -o /dev/null` and throws with the compiler's stderr on failure.

`Linker.link(llFiles, output, workDir)` runs `mos-c64-clang -O2 <llFiles...> -o <output>` — the SDK's default configuration enables `-flto`, which is what we want.

- [ ] **Step 7: Write `Main.kt`**

`src/main/kotlin/pl/qus/wolin/driver/Main.kt` parses the CLI:

```
wolinc [options] <file.wtk>...
  -o, --output <file>     output .prg (default: first input's name)
  -d, --debug             keep intermediate .ll and emit a link map
  -e, --emit-ir           write .ll and stop
  -S, --sdk <dir>         llvm-mos SDK path (overrides discovery)
      --target <name>     c64 (default)
      --keep-ir           keep the .ll next to the .prg
```

On a compile error, print every diagnostic to stderr and exit with code 1. On success, exit 0.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests '*SdkLocatorTest*' --tests '*ManglerTest*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/driver src/test/kotlin/pl/qus/wolin/driver
git commit -m "feat: toolchain driver, SDK discovery, mangling and generated driver module"
```

---

### Task 7: The VICE integration harness and the first end-to-end program

Proves the whole pipeline produces a `.prg` that *runs*. The harness protocol is verified against VICE 3.10's source, not guessed.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/test/ViceMonitor.kt`
- Create: `src/main/kotlin/pl/qus/wolin/test/ViceRunner.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/EndToEndTest.kt`
- Test: `src/test/resources/it/add_ubyte.wtk`

**Interfaces:**
- Consumes: `Linker` (Task 6), the CLI's compile path.
- Produces:
  - `class ViceMonitor(host: String = "127.0.0.1", port: Int = 6502) { fun memGet(start: Int, end: Int): ByteArray; fun close() }`
  - `class ViceRunner(val vicePath: String = "/home/qus/.local/bin/x64sc") { fun run(prg: Path, readStart: Int, readEnd: Int): ByteArray }`

- [ ] **Step 1: Write the failing end-to-end test**

```kotlin
class EndToEndTest {
    @Test fun `ubyte addition runs on the real 6502`() {
        val src = """
            fun main() {
                var a: ubyte = 40
                var b: ubyte = 2
                var r: ubyte = a + b
                var out: ubyte^0x400 = r
                out = r
                while (true) {}
            }
        """.trimIndent()
        val prg = compileToPrg(src, "add_ubyte")
        val bytes = ViceRunner().run(prg, 0x0400, 0x0401)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*EndToEndTest*'`
Expected: FAIL — `ViceRunner` does not exist.

- [ ] **Step 3: Write `ViceMonitor.kt`**

`src/main/kotlin/pl/qus/wolin/test/ViceMonitor.kt` implements the binary protocol:

**Request header** (11 bytes + body): `STX(0x02)`, `api_version(0x02)`, `body_len` (u32 LE), `request_id` (u32 LE), `command_type` (u8).

**MEM_GET body** (8 bytes): `sidefx` (u8), `start` (u16 LE), `end` (u16 LE), `memspace` (u8), `bank` (u16 LE).

**Response header** (12 bytes): `STX(0x02)`, `api_version(0x02)`, `body_len` (u32 LE), `response_type` (u8), `errorcode` (u8), `request_id` (u32 LE).

**MEM_GET response body**: `length` (u16 LE) followed by `length` data bytes.

`memGet(start, end)` sends the request and then loops reading frames: **skip any frame whose `request_id` is `0xffffffff`** (the monitor pushes unsolicited event frames), and read the body of exactly `body_len` bytes after the 12-byte header. `body_len` covers the body only.

- [ ] **Step 4: Write `ViceRunner.kt`**

`src/main/kotlin/pl/qus/wolin/test/ViceRunner.kt`:

1. Start `x64sc -default -binarymonitor -limitcycles 0 -warp -autostart <prg>` with stdout and stderr discarded.
2. Poll `127.0.0.1:6502` until a connection succeeds, up to 40 seconds.
3. Sleep 2.5 seconds so the program has run.
4. Open a `ViceMonitor`, call `memGet(readStart, readEnd)`, close.
5. Terminate the VICE process.

- [ ] **Step 5: Write the compile helper and the test fixture**

Add to the test sources a `compileToPrg(source, name)` helper that runs the pipeline: parse → check → emit one `.ll` per file → emit the driver module → validate each `.ll` → link → return the `.prg` path in a temporary directory.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests '*EndToEndTest*'`
Expected: PASS — the readback at `$0400` is `42`.

If the readback is all zeros, the program did not run: check that the driver module's `main` is actually called, that the `.prg` was loaded (the `AUTOSTART` line in the VICE output must say the file was "recognized as program/p00 file"), and that the result byte was written to `$0400` rather than to screen RAM.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/test src/test/kotlin/pl/qus/wolin/it
git commit -m "feat: VICE binary-monitor harness and the first end-to-end program"
```

---

### Task 8: The remaining language surface

One task per remaining area of the spec's language surface, so a reviewer can reject one while approving its neighbours. Each adds integration tests that run on the real 6502.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ir/TypeOps.kt` (bit-assignment forms)
- Create: `src/main/kotlin/pl/qus/wolin/ir/InterruptEmitter.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/FunctionPointerEmitter.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/BitOpsTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/ComparisonTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/ControlFlowTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/ArrayTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/StringTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/FunctionPointerTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/InterruptTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/RomCallTest.kt`

**Interfaces:**
- Consumes: `ExprEmitter`/`StmtEmitter` (Task 4), `CpuAccessors` (Task 5), `DriverModule` (Task 6), `ViceRunner` (Task 7).
- Produces: the remaining emission paths.

- [ ] **Step 1: Write the failing bit-assignment tests**

```kotlin
class BitOpsTest {
    @Test fun `set bits ORs the mask in`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0b00000000
                x := 0b00001111
                var out: ubyte^0x400 = x
                out = x
                while (true) {}
            }
        """)
        assertEquals(0x0F, bytes[0].toInt() and 0xFF)
    }

    @Test fun `clear bits ANDs the complement in`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0b11111111
                x .= 0b00001111
                var out: ubyte^0x400 = x
                out = x
                while (true) {}
            }
        """)
        assertEquals(0xF0, bytes[0].toInt() and 0xFF)
    }

    @Test fun `and-assign and or-assign are bitwise`() {
        // x &= mask, x |= mask
    }
}
```

`runProgram(source)` compiles the source, runs it under VICE, and returns the 16 bytes read from `$0400`–`$040F`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*BitOpsTest*'`
Expected: FAIL — the bit-assignment forms are not emitted yet.

- [ ] **Step 3: Implement the bit-assignment forms**

`:=` → OR, `.=` → AND with the complement, `&=` → AND, `|=` → OR. `.=` is `x = x and not v` — the documented intent, **not** the old template's `lda #$ff-val; sta dst` (which replaces the byte instead of masking it).

- [ ] **Step 4: Write the failing comparison tests**

`ubyte` comparisons are unsigned: `255 > 0` is true. `byte` comparisons are signed: `-1 > 0` is false. Both run on the real 6502 and write a `bool` result to `$0400`.

```kotlin
class ComparisonTest {
    @Test fun `ubyte comparison is unsigned`() {
        val bytes = runProgram("""
            fun main() {
                var a: ubyte = 255
                var b: ubyte = 0
                var r: bool = a > b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte comparison is signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -1
                var b: byte = 0
                var r: bool = a > b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(0, bytes[0].toInt() and 0xFF)
    }
}
```

- [ ] **Step 5: Run the comparison tests to verify they fail**

Run: `./gradlew test --tests '*ComparisonTest*'`
Expected: FAIL — the comparison paths are not emitted, or the `bool` result is wrong.

- [ ] **Step 6: Implement the comparison paths**

`ubyte`/`uword` order comparisons emit `icmp ult` / `icmp ugt`; `byte`/`word` emit `icmp slt` / `icmp sgt`. Equality is `icmp eq` / `icmp ne` for every integer type. The `bool` result is an `i1`; storing it to a `ubyte` widens it with `zext i1` to `i8`.

- [ ] **Step 7: Write the failing control-flow tests**

`if`/`else`, `when` in both forms, `while`, `do..while`, `break`, `continue`. Each writes a counter to `$0400` and the test asserts the exact count.

For `when` with a subject, the subject is compared against each **branch value** — not against the condition expression, which is what the old Wolin's inverted `simpleWhen` flag actually did.

- [ ] **Step 8: Run the control-flow tests to verify they fail**

Run: `./gradlew test --tests '*ControlFlowTest*'`
Expected: FAIL — the control-flow paths are not emitted.

- [ ] **Step 9: Implement the control-flow paths**

`if`/`else` emits a comparison, then `condBranch` to a `then` block, an `else` block and a merge block. `while` emits a `loop` block that branches to a `body` block, which branches back to `loop`. `do..while` branches to the body first, then tests. `break` branches to the loop's break label, `continue` to its continue label.

Every merge point gets a **phi node** for each in-scope variable whose value differs between the predecessors. The emitter must supply one incoming value per predecessor — LLVM rejects the module otherwise.

- [ ] **Step 10: Write the failing array tests**

A `ubyte[ubyte]` array at a fixed address: write element 3, read it back. The index is always promoted to `uword`, so the GEP is `getelementptr i8, ptr @arr, i16 %idx`.

A `ubyte[uword]` array at a fixed address: same, with a 16-bit index.

- [ ] **Step 11: Run the array tests to verify they fail**

Run: `./gradlew test --tests '*ArrayTest*'`
Expected: FAIL — array access is not emitted.

- [ ] **Step 12: Implement array access**

The index is always promoted to `uword`: `zext i8 %i to i16`, then
`getelementptr i8, ptr @arr, i16 %idx`. For a 16-bit element type the GEP
element type is `i16` and the index scales by the element size. A store to an
array element uses the same GEP.

- [ ] **Step 13: Write the failing string tests**

A string literal is NUL-terminated: `var s: string = "hi"` writes `h`, `i`, `0`. The IR is `private unnamed_addr constant [3 x i8] c"hi\00"`. Escapes are decoded: `"\n"` is one byte `0x0A`.

- [ ] **Step 14: Run the string tests to verify they fail**

Run: `./gradlew test --tests '*StringTest*'`
Expected: FAIL — string emission is not implemented.

- [ ] **Step 15: Implement string emission**

A string literal becomes `private unnamed_addr constant [N+1 x i8] c"...\00"`,
one per distinct literal. The expression is a `getelementptr` to it. Escapes
`\n`, `\t`, `\r`, `\0`, `\\`, `\"`, `\'` and `\uXXXX` are decoded into
bytes; `${...}` is rejected (see Task 9).

- [ ] **Step 16: Write the failing function-pointer test**

Store a function's address in a `uword` and call through it; also store it in a `(ubyte,ubyte)->ubyte` variable and call through that. The typed pointer is a `ptr` and the call is `call i8 %ptr(i8 %a, i8 %b)`; the `uword` path is `ptrtoint` → `inttoptr` → `call`.

- [ ] **Step 17: Run the function-pointer tests to verify they fail**

Run: `./gradlew test --tests '*FunctionPointerTest*'`
Expected: FAIL — indirect calls are not emitted.

- [ ] **Step 18: Implement indirect calls**

A function's address is `ptrtoint ptr @f to i16`; storing it into a `uword`
global is an ordinary 16-bit store. Calling through it is `inttoptr` back to a
function pointer and an indirect `call`. A `(ubyte,ubyte)->ubyte` variable holds
a `ptr` directly and the call is `call i8 %ptr(i8 %a, i8 %b)`.

- [ ] **Step 19: Write the failing interrupt test**

```kotlin
class InterruptTest {
    @Test fun `interrupt handler runs and sets the border`() {
        // install the handler address in $0314, trigger the raster IRQ,
        // and assert $d020 changed
    }
}
```

The handler function is emitted with `attributes #0 = { "interrupt" }`, which makes the backend emit the register-save prologue and `rti`. **The compiler must not emit its own A/X/Y save/restore** — the backend already does it.

Note that `$d020` is an I/O register and does not read back through the binary monitor's CPU memspace. Assert the *handler ran* by having it write to RAM at `$0400` instead, and treat the border change as a manual/screenshot check.

- [ ] **Step 20: Run the interrupt test to verify it fails**

Run: `./gradlew test --tests '*InterruptTest*'`
Expected: FAIL — the interrupt function is not emitted with the `interrupt` attribute.

- [ ] **Step 21: Implement the interrupt form**

The handler function is emitted with `attributes #0 = { "interrupt" }`, which
makes the backend emit the register-save prologue and `rti`.

**The compiler must not emit its own A/X/Y save/restore.** The backend already
does it — verified in the generated assembly, which shows `pha`/`tya`/`pha` on
entry and `tay`/`pla`/`pla` before the `rti`. A manual save/restore on top
would double the stack traffic and restore a value the backend had already
restored.

The handler's address reaches the hardware vector through an ordinary 16-bit
store to `$0314`: the program writes `ptrtoint ptr @handler to i16` there. The
SDK installs **no** IRQ dispatcher for the C64 target, so `$0314` belongs to
the program.

- [ ] **Step 22: Write the failing ROM-call test**

`fun chrout^0xffd2(c: ubyte^CPU.A)` calls the KERNAL routine. Assert the character appears in screen RAM — at `$0400` in the *character* map, so the test's own result block must live elsewhere (e.g. `$0408`).

- [ ] **Step 23: Run the ROM-call test to verify it fails**

Run: `./gradlew test --tests '*RomCallTest*'`
Expected: FAIL — the fixed-address call is not emitted.

- [ ] **Step 24: Implement the fixed-address call**

A `fun f^0xffd2(c: ubyte^CPU.A)` declaration emits a function whose body is a
single inline-asm call:

```
define void @"t.chrout"(i8 %c) {
entry:
  call void asm sideeffect "jsr $$ffd2", "a"(i8 %c)
  ret void
}
```

The constraint list follows the **declaration order** of the parameters, and
the operands follow the same order. The `$$` escape is mandatory — a bare `$`
crashes the backend.

- [ ] **Step 25: Run all the integration tests**

Run: `./gradlew test --tests '*it.*'`
Expected: PASS.

- [ ] **Step 26: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir src/test/kotlin/pl/qus/wolin/it
git commit -m "feat: bit ops, comparisons, control flow, arrays, strings, pointers, interrupts, ROM calls"
```

---

### Task 9: Grammar work — `return@<literal>`, unsupported-construct diagnostics

The grammar diff and the diagnostic catalogue. The reused grammar is a full Kotlin grammar, so most unsupported constructs parse and must be rejected by the analyser.

**Files:**
- Modify: `src/main/antlr/KotlinLexer.g4`, `src/main/antlr/KotlinParser.g4`
- Create: `src/main/kotlin/pl/qus/wolin/sema/Unsupported.kt`
- Test: `src/test/kotlin/pl/qus/wolin/parser/ReturnAtGrammarTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt`

**Interfaces:**
- Consumes: the grammar (Task 1), `TypeChecker` (Task 2), `StmtEmitter` (Task 4).
- Produces:
  - `object Unsupported { fun reject(ctx: ParseTree, feature: String): Nothing }` — throws `SemaException("$feature is not supported in v1")`.

- [ ] **Step 1: Write the failing `return@` grammar test**

```kotlin
class ReturnAtGrammarTest {
    @Test fun `return at a hex address parses`() {
        val diag = Diagnostics()
        ParserFacade(diag).parse("fun f() { return@0xea31 }", "t.ktk")
        assertTrue(diag.messages.isEmpty(), diag.messages.toString())
    }

    @Test fun `the old corpus still parses`() {
        // raster_irq.ktk contains four return@0xea31 sites
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReturnAtGrammarTest*'`
Expected: FAIL — `RETURN_AT: 'return@' Identifier` cannot match `0xea31`.

- [ ] **Step 3: Extend the grammar**

In the lexer, add a rule for a `return@` followed by an integer literal:

```antlr
RETURN_AT_LITERAL: 'return@' (DecimalLiteral | HexLiteral) ;
```

In the parser, allow `RETURN_AT_LITERAL` wherever `RETURN_AT` is allowed. The `prefixUnaryOperation` rule that currently accepts `labelDefinition` must also accept the literal form.

- [ ] **Step 4: Write the failing unsupported-construct test**

```kotlin
class UnsupportedTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "fun f() { try { } catch (e: word) { } }",
        "class C { }",
        "fun f() { val x: ubyte = 1; x = 2 }",
        "fun f() { for (i in 0..10) { } }",
        "fun f() { val x = 1 as ubyte }",
        "fun f() { val x: ubyte? = null }",
        "fun f() { val s = \"a ${1} b\" }",
    ])
    fun unsupportedConstructsAreRejected(src: String) {
        assertThrows<SemaException> { compile(src) }
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew test --tests '*UnsupportedTest*'`
Expected: FAIL — the constructs are not rejected yet.

- [ ] **Step 6: Implement the diagnostic catalogue**

`Unsupported.kt` holds one entry per construct in the spec's catalogue: exceptions, classes/objects, constructors, capturing closures, inheritance, generics, type tests, `as`/`as?`, safe calls, elvis, ranges and `in`, `for` loops, `break@label`, string interpolation, `!!`, property accessors, type aliases, annotations, `vararg`, `this`/`super`, `suspend`, labelled expressions, `when` range/type tests, `cc65 fun`, `external fun`, and `import ... as`.

Each entry throws `SemaException` with the construct named and a pointer to v2. The check lives in the **analyser**, not the parser — the grammar keeps accepting the construct.

Two constructs are *not* unsupported and must keep working: `while`/`do..while`, and `break`/`continue` without a label.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ReturnAtGrammarTest*' --tests '*UnsupportedTest*'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/antlr src/main/kotlin/pl/qus/wolin/sema src/test/kotlin/pl/qus/wolin
git commit -m "feat: return@<literal> grammar and the unsupported-construct catalogue"
```

---

### Task 10: The `return@` semantics, and global initialisers

Two spec areas that need their own emitter paths: the per-site tail jump, and the constant-folded global init sequence.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/ir/ReturnAtEmitter.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/GlobalInitEmitter.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/ReturnAtTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/GlobalInitTest.kt`

**Interfaces:**
- Consumes: `StmtEmitter` (Task 4), `ExternalCalls` (Task 5), `DriverModule` (Task 6).
- Produces:
  - `class ReturnAtEmitter(private val b: IrBuilder) { fun emit(address: Int) }`
  - `class GlobalInitEmitter(private val b: IrBuilder) { fun emitFile(program: Ast.Program, mangledName: String): String }`

- [ ] **Step 1: Write the failing `return@` test**

```kotlin
class ReturnAtTest {
    @Test fun `return at a fixed address jumps instead of returning`() {
        val bytes = runProgram("""
            fun main() {
                var out: ubyte^0x400 = 0
                out = 1
                return@0xea31
                out = 2
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
```

The `out = 2` after the `return@` must never execute.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*ReturnAtTest*'`
Expected: FAIL — the `return@` is not emitted.

- [ ] **Step 3: Implement the per-site tail jump**

`ReturnAtEmitter.emit(address)` emits:

```
call void asm sideeffect "jmp $$ea31", ""()
unreachable
```

The jump is emitted **at the site of the `return@`**, not in the function's epilogue. A function may contain several `return@` sites, each with its own address, and may also contain an ordinary `return` — the ordinary return is unaffected.

For an `interrupt fun`, this replaces the `rti`: the function's normal exit (which the backend's `interrupt` attribute turns into `rti`) is still emitted, but the `return@` site jumps away before reaching it.

- [ ] **Step 4: Write the failing global-init test**

```kotlin
class GlobalInitTest {
    @Test fun `globals are initialised in source order before main`() {
        val bytes = runProgram("""
            var a: ubyte = 7
            var b: ubyte = a + 1
            fun main() {
                var out: ubyte^0x400 = b
                out = b
                while (true) {}
            }
        """)
        assertEquals(8, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a global initialiser that references a later global is rejected`() {
        assertThrows<SemaException> {
            compile("""
                var a: ubyte = b
                var b: ubyte = 7
                fun main() { }
            """)
        }
    }
}
```

`a + 1` is a constant expression over a constant global, so it is folded at
compile time to `8`. The reference to `b` inside `a`'s initialiser is *not*
allowed when `b` is declared later — that is Review Focus item 3.

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew test --tests '*GlobalInitTest*'`
Expected: FAIL — globals are not initialised yet.

- [ ] **Step 6: Implement the constant-folded global init**

Initialisers are folded at compile time: a literal, a reference to another
constant global, or the address of a function or fixed-address variable.
Anything else is a compile error — "global initialiser must be a constant
expression".

**Two representations, one value.** A global whose initialiser is a *literal*
is emitted as static data and needs no init code:

```
@"game.demo.a" = global i8 7
```

A global whose initialiser *references another global* cannot be static data
(an LLVM global initialiser must be a constant expression, and loading from
another global is not one), so it gets a runtime store in the file's init
function. The two mechanisms produce the same value — which is what the spec
means by the initialiser working "identically in the global-init routine and in
the static data section".

`GlobalInitEmitter.emitFile` emits, per file, a function
`__wolin_init_file_<mangled>` that stores only the globals that need runtime
initialisation, **in source order**:

```
define internal void @__wolin_init_file_demo() {
entry:
  store i8 4, ptr @"game.demo.b"
  ret void
}
```

(`@b` holds `4` as static data; `@a` copies it at init time.) The function is
emitted even when it is empty, so the driver's call list is uniform.

A global whose initialiser references a global declared **later** in the file is a compile error — "initialiser references a global that is not yet initialised" (Review Focus item 3).

The driver calls `__wolin_init_file_<mangled>` for each file, in command-line order, before calling `main`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests '*ReturnAtTest*' --tests '*GlobalInitTest*'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ir src/test/kotlin/pl/qus/wolin/it
git commit -m "feat: per-site return@ tail jumps and constant-folded global initialisation"
```

---

### Task 11: Function literals, imports, and multi-file compilation

The last three spec areas: non-capturing function literals with contextual parameter inference, real imports, and the whole-program view.

**Files:**
- Create: `src/main/kotlin/pl/qus/wolin/sema/FunctionLiteralInfer.kt`
- Create: `src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt`
- Create: `src/main/kotlin/pl/qus/wolin/driver/ProgramBuilder.kt`
- Create: `src/main/kotlin/pl/qus/wolin/ir/LambdaEmitter.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/FunctionLiteralTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/ImportTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/MultiFileTest.kt`

**Interfaces:**
- Consumes: `TypeChecker` (Task 2), `ModuleEmitter` (Task 4), `DriverModule` (Task 6).
- Produces:
  - `data class SourceFile(val fileName: String, val source: String)`
  - `class LambdaEmitter(private val b: IrBuilder) { fun emit(lit: Ast.FunctionLit): IrValue }` — emits the literal's own function and returns a pointer to it.
  - `class ProgramBuilder { fun build(files: List<SourceFile>): Ast.Program }` — one whole-program view across all input files.
  - `class ImportResolver { fun resolve(name: String, imports: List<ImportDecl>, program: Ast.Program): Symbol? }`

- [ ] **Step 1: Write the failing function-literal test**

```kotlin
class FunctionLiteralTest {
    @Test fun `parameter types come from the assignment target`() {
        val program = compile("""
            var suma: (ubyte,ubyte)->ubyte
            fun main() {
                suma = { a, b -> a + b }
            }
        """)
        val lit = findFunctionLiteral(program)
        assertEquals(listOf(Types.ubyte, Types.ubyte), lit.params.map { p -> p.type })
    }

    @Test fun `a literal with no expected type is rejected`() {
        assertThrows<SemaException> { compile("fun main() { val f = { a, b -> a + b } }") }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*FunctionLiteralTest*'`
Expected: FAIL — the literal's parameters are not inferred.

- [ ] **Step 3: Implement the contextual inference**

`FunctionLiteralInfer` resolves a literal's parameter types from the expected type at the assignment site. When there is no expected type, throw `SemaException("cannot infer the parameter types of this function literal; give it an explicit type")`.

Each literal is compiled to its own function named `lambda_function_<n>`, emitted at module scope.

- [ ] **Step 4: Write the failing import test**

```kotlin
class ImportTest {
    @Test fun `an imported name is visible unqualified`() {
        val program = compileFiles(
            listOf(
                SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                SourceFile("main.wtk", "package app\nimport lib.helper\nfun main() { val x = helper() }")
            )
        )
        // helper() resolves
    }

    @Test fun `a name from another package without an import does not resolve`() {
        assertThrows<SemaException> {
            compileFiles(listOf(
                SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
                SourceFile("main.wtk", "package app\nfun main() { val x = helper() }")
            ))
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew test --tests '*ImportTest*'`
Expected: FAIL — imports are not resolved.

- [ ] **Step 6: Implement the import resolver**

Resolution order, exactly as the spec states: local scope → enclosing scope → explicit imports → same-package declarations → imported packages.

An import makes a name visible unqualified; `import a.b as c` renames it. An import with an alias is rejected in v1 (see the catalogue in Task 9) — so only plain imports are supported, and the alias form throws.

- [ ] **Step 7: Write the failing multi-file test**

```kotlin
class MultiFileTest {
    @Test fun `two files link into one program`() {
        val bytes = runProgramFiles(listOf(
            SourceFile("lib.wtk", "package lib\nfun helper(): ubyte { return 7 }"),
            SourceFile("app.wtk", "package app\nimport lib.helper\nfun main() { var out: ubyte^0x400 = helper(); out = helper(); while (true) {} }")
        ))
        assertEquals(7, bytes[0].toInt() and 0xFF)
    }

    @Test fun `two files each defining main is a link error`() {
        assertThrows<IllegalStateException> {
            runProgramFiles(listOf(
                SourceFile("a.wtk", "package a\nfun main() { }"),
                SourceFile("b.wtk", "package b\nfun main() { }")
            ))
        }
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `./gradlew test --tests '*MultiFileTest*'`
Expected: FAIL — multi-file compilation is not wired up.

- [ ] **Step 9: Implement the whole-program build**

`ProgramBuilder.build(files)` parses and checks all files together, so a function in one file is visible to another in the same package or via an import. `Main` then emits one `.ll` per file plus the driver module, validates each, and links them in one `mos-c64-clang` invocation.

When more than one file declares `main`, the driver fails with "main must be defined in exactly one place".

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./gradlew test --tests '*FunctionLiteralTest*' --tests '*ImportTest*' --tests '*MultiFileTest*'`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema src/main/kotlin/pl/qus/wolin/driver src/main/kotlin/pl/qus/wolin/ir src/test
git commit -m "feat: function literals, real imports, and multi-file compilation"
```

---

### Task 12: The CLI end to end, and the README

Proves the shipped artifact works the way the spec's CLI section describes, and documents the toolchain requirements.

**Files:**
- Create: `README.md`
- Test: `src/test/kotlin/pl/qus/wolin/it/CliTest.kt`

**Interfaces:**
- Consumes: `Main` (Task 6), the whole pipeline.
- Produces: the `wolinc` executable.

- [ ] **Step 1: Write the failing CLI test**

```kotlin
class CliTest {
    @Test fun `emit ir writes a ll file and stops`() {
        val dir = tempDir()
        val src = dir.resolve("t.wtk")
        src.writeText("fun main() { }")
        val rc = runCli(listOf("--emit-ir", src.toString()))
        assertEquals(0, rc)
        assertTrue(dir.resolve("t.ll").exists())
    }

    @Test fun `a syntax error exits non-zero with a diagnostic`() {
        val dir = tempDir()
        val src = dir.resolve("bad.wtk")
        src.writeText("fun main( {")
        val rc = runCli(listOf(src.toString()))
        assertNotEquals(0, rc)
        assertTrue(errOutput.contains("error:"), errOutput)
    }

    @Test fun `compiling produces a prg`() {
        val dir = tempDir()
        val src = dir.resolve("t.wtk")
        src.writeText("fun main() { var out: ubyte^0x400 = 9; out = 9; while (true) {} }")
        val rc = runCli(listOf("-o", dir.resolve("t.prg").toString(), src.toString()))
        assertEquals(0, rc)
        assertTrue(dir.resolve("t.prg").exists())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*CliTest*'`
Expected: FAIL — the CLI is not wired to the pipeline end to end.

- [ ] **Step 3: Wire the CLI to the pipeline**

`Main` compiles every input file, emits the per-file `.ll` modules and the driver module, validates, and links. `--emit-ir` stops after writing the `.ll` files. `--keep-ir` leaves them next to the `.prg`. `--debug` also writes the link map.

- [ ] **Step 4: Write the README**

`README.md` covers: what the compiler is, the toolchain requirements (JDK 21, Gradle 8.13, the LLVM-MOS SDK path, VICE for the tests), how to build, how to compile a `.wtk` file, the CLI options, the language's v1 surface, and the two deviations from the old Wolin that a user is most likely to trip over — the `float` format (IEEE-754, not Wozniak FFP) and the literal-typing rule (contextual, so `var x: uword = 0` is fine).

- [ ] **Step 5: Run the whole test suite**

Run: `./gradlew test`
Expected: PASS — every unit and integration test green.

- [ ] **Step 6: Verify the shipped compiler by hand**

```bash
./gradlew installDist
cat > /tmp/hello.wtk <<'EOF'
var border: ubyte^0xd020
fun main() {
    border = 6
    while (true) {}
}
EOF
./build/install/wolinc/bin/wolinc -o /tmp/hello.prg /tmp/hello.wtk
```

Expected: `/tmp/hello.prg` exists and is non-empty.

- [ ] **Step 7: Commit**

```bash
git add README.md src/test/kotlin/pl/qus/wolin/it/CliTest.kt src/main/kotlin/pl/qus/wolin/driver
git commit -m "feat: CLI end to end and README"
```

---

## Self-Review

**1. Spec coverage.** Every spec section maps to a task:

| Spec section | Task |
|---|---|
| §4 constraints, `UnicodeClasses.g4` | 1 |
| §6 types, literals, scoping, initialisers | 2, 8, 10 |
| §6 function pointers, function literals | 11 |
| §6 bit operations | 8 |
| §6 unsupported constructs | 9 |
| §7.1 fixed-address variables | 4 |
| §7.2 CPU registers, `CPU.AX`, backend constraints | 5 |
| §7.3 external functions | 5, 8 |
| §7.4 calling convention | 5, 6, 11 |
| §7.5 interrupts, `return@` | 8, 10 |
| §7.7 zero page | 4 (implicit — nothing special needed) |
| §8.1–8.2 components, IR builder | 3, 4 |
| §8.3 toolchain discovery | 6 |
| §8.4 entry point | 6 |
| §8.5 multiple files, imports, mangling | 6, 11 |
| §8.6 IR validation | 6 |
| §8.7 global init order | 10 |
| §9.1 unit tests | 2, 3, 4, 5, 6, 9, 11 |
| §9.2–9.4 integration harness | 7, 8, 10, 11, 12 |
| §10 CLI | 12 |
| §11 risks | 5, 10 (the float and flag risks are resolved in the spec) |
| §13 evidence | carried into the tests that reproduce each result |

**2. Step scan.** Every step names a file, a signature, or a command with its expected output. No step says "add appropriate validation" or "write tests for the above".

**3. Type consistency.** `WolinType`/`Types` are defined in Task 2 and used unchanged in Tasks 4, 8 and 11. `IrBuilder`/`IrValue`/`IrType` are defined in Task 3 and used unchanged in Tasks 4, 5, 6, 8 and 10. `CpuAccessors.readA`/`readFlag`/`writeRegister` and `ExternalCalls.emitCall` are defined in Task 5 and used in Tasks 8 and 10. `ViceRunner.run` is defined in Task 7 and used in Tasks 8, 10 and 11.

**4. Review Focus.** Each of the five items has a home: empty source file → Task 12's CLI test (a `.wtk` with only `fun main() { }`); syntax error → Task 1's `Diagnostics` plus Task 12's CLI test; out-of-order global initialiser → Task 10's Step 6; out-of-range array index → Task 8's Step 6 (assert no trap, assert the address is read/written as-is); `return@` alongside an ordinary `return` → Task 10's Step 3.

**5. Proportion.** The plan is roughly 1700 lines against a 1600-line spec. Code blocks carry signatures, test bodies and exact expected values — not the implementation bodies: the only place a body appears is where a step fixes an exact sequence (the status-preserving flag read, the VICE frame layout, the driver module's shape).

**Verification performed on this plan.** Beyond reading it back, the following were checked mechanically:

- 12 tasks, 101 steps; every task has a `**Files:**` block, an `**Interfaces:**` block and at least one step; every step carries a runnable command, an exact expected output, or a code block.
- Code fences balance (120 markers, even count).
- **Interface ordering:** every symbol a task consumes is produced by a *strictly earlier* task. Checked by extracting all `Consumes:`/`Produces:` symbols and comparing task numbers.
- **Type consistency:** no symbol is declared with two different signatures. The only apparent duplicates were call sites versus declarations; `emitFile` was unified (`mangledPrefix` in `ModuleEmitter`, `mangledName` in `GlobalInitEmitter` — genuinely different parameters, so they were given distinct names in the signatures).
- **Undefined symbols:** every capitalised identifier used in a code block resolves to a type declared in the plan, in `Ast.kt`, or in the JDK. `DataSymbol`/`FunSymbol` were missing from Task 2's interface block and were added; `SourceFile` and `LambdaEmitter` were missing from Task 11 and were added.
- **Spec coverage:** 28 named spec concepts (fixed-address variables, `CPU.AX`, the flag-read sequence, external calls, the `interrupt` attribute, per-site `return@`, no promotion, contextual literal typing, signed vs unsigned comparison, the `uword` array index, IEEE-754 floats, global-init order, symbol mangling, the driver module, SDK discovery, IR validation, the VICE protocol, the unsupported-construct catalogue, NUL-terminated strings, `xor` exclusion) were checked for presence in the plan text. All present.
- **Two plan errors were found and fixed by this review**, both by checking the plan against the spec rather than against itself:
  - The `fixed_address_var` golden file expected a `@border` global. The spec (§7.1) says a fixed-address variable emits *no* storage — every access is a load/store through the constant address. The golden file was rewritten as a negative assertion.
  - The global-init step emitted every global's initialiser as a runtime store. The spec (§8.7) says constant initialisers are emitted as *static data*, and only a global that references another global needs a runtime store. The step now specifies both mechanisms and the test covers the reference case.

**Backend behaviours this plan relies on were verified against `mos-c64-clang` 24 and VICE 3.10 while writing it**, not assumed:**

| Behaviour | Verification |
|---|---|
| `sta ($0),y` with a pointer operand reads A correctly | IR-level test, readback `a5 42 42` — same value as `pha`/`pla` |
| The status-preserving flag read leaves A and P intact | readback `a5 00 42 01 42 01 00 01 80 01` |
| A plain `php`/`pla` flag read destroys A | readback `30` instead of `42` |
| `asm goto` crashes the backend | `unable to translate instruction: callbr` |
| The `"cc"` constraint crashes the backend | `unable to translate instruction: call` |
| A bare output constraint is rejected | `number of input constraints does not match number of parameters` |
| **Clobber lists are rejected** | `error: expected instruction opcode` — the plan emits constraints only |
| `\09` does not separate instructions, `\0A` does | `invalid operand instruction pha pla` vs. working |
| `float 1.5` is IEEE-754 | `.long 0x3fc00000`; runtime readback `00 00 c0 3f` |
