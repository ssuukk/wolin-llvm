# xor and Signed Byte Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `xor` operator and complete the signed `byte` semantics — the full −128..127 literal range and explicit conversions between the integer types — to the Wolin dialect.

**Architecture:** No grammar changes: the full Kotlin grammar already parses `a xor b` (an infix call, `KotlinParser.g4:342-344`) and `b.toWord()` (a postfix member call, `KotlinParser.g4:644`). The analyser special-cases those two shapes and produces `BinaryOp("xor")` and a new `Convert` node. The emitter lowers them to existing IR instructions (`xor`, `sext`/`zext`/`trunc`); the const folder folds both so global initialisers stay compile-time constants.

**Tech Stack:** Kotlin 2.2.10 (JVM 21), Gradle 8.13, ANTLR 4.13.1, JUnit 5.11.4, LLVM-MOS SDK under `~/.local/share/rust-mos`, VICE 3.10 (`x64sc`) for the integration tests.

**Spec:** `docs/superpowers/specs/2026-11-07-wolin-llvm-design.md` — §6 "Bit operations" and "Expressions" (the xor decision), §6 types table (`byte` signedness), §6 "Numeric type rules" (no promotion), §12 (out-of-scope items this plan retires), §9.4 (test-program convention).

## Global Constraints

- JDK 21; `./gradlew` builds; the compiler's main class is `pl.qus.wolin.driver.MainKt`.
- **No integer promotion**: mixed operand types in arithmetic, comparison or bitwise operations are a compile error ("Attempt reassign X Y") — spec §6 "Numeric type rules". The new conversions are *explicit* and must not loosen this rule.
- **Arithmetic never widens**: the result type of a binary numeric operator is the left operand's type.
- Every rejected construct throws `SemaException` with a message that names it (`Unsupported construct: …`); the catalogue is pinned by `UnsupportedTest`.
- Integration-test convention (spec §9.4): the result block lives at `$0400`, the program ends in `while (true) {}`, the harness reads `$0400`–`$040F` (`runProgram`, `ItSupport.kt:37`).
- Do not modify the ANTLR grammar files for this work.
- Signed comparison, signed division/remainder and signed constant folding for `byte`/`word` **already exist** in v1 (`ExprEmitter.isUnsigned` at `ExprEmitter.kt:273`, `ConstFolder.isSigned` at `ConstFolder.kt:124`, pinned by `ComparisonTest.byte comparison is signed`). This plan adds regression tests for them; it does not re-implement them.
- `TypeCheckerTest.localTypeOf` returns the **first** local declaration of the first function. Tests that assert on a *result* declaration (one that follows an operand declaration) must use the `typeOfLast` helper pattern below, not `localTypeOf`.

## Review Focus

1. **Precedence mixing.** `&` is multiplicative-level, `|` additive-level, `xor` sits at the infix level — below `|`, above `==`. A reader writing `a | b xor c & d` expects `(a | b) xor (c & d)`; a wrong grouping silently yields a different value. → pinned in Task 1 (`xor precedence…` test; the operand values are chosen so the plausible wrong grouping produces a different byte).
2. **Off-by-one at the byte boundary.** `byte` must accept exactly −128..127; `128` and `−129` stay errors. → pinned in Task 2.
3. **Sign extension vs zero extension mix-up.** `b.toWord()` must sext (`0xFF → 0xFFFF`), `u.toUword()` must zext (`0xFF → 0x00FF`); a swap is a silent miscompile. → pinned in Task 3 (IR test plus the `sign extension fills the high byte` integration test).
4. **Constant folding diverging from runtime.** `var w: word = b.toWord()` as a global folds at compile time; the folded value must equal the runtime sext. → pinned in Task 3 (the two `folded global` tests, IR and 6502).
5. **The `xor` special case swallowing every infix call.** Only the identifier `xor` may be accepted; `a and b` must stay rejected with the construct named. → pinned in Task 1 (`UnsupportedTest`).

---

### Task 1: The `xor` operator

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:213-216` (`buildInfixCall`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:533` (the `"&", "|"` branch of `checkBinary`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt:230-231` (the bitwise arms of `emitBinary`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt:95-104` (`foldInt`)
- Test: `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/BitOpsTest.kt`

**Interfaces:**
- Consumes: the grammar rule `infixFunctionCall: rangeExpression (simpleIdentifier NL* rangeExpression)*`; `AstBuilder.chain` (`AstBuilder.kt:598`) — left-associative fold into nested `BinaryOp`s; `ExprEmitter.bitwise(name, l, r, operandType)`; `WolinType.isBitwise` (all four integer types, `Types.kt:19`).
- Produces: `BinaryOp` nodes with `op == "xor"`; the checker, emitter and const folder all accept the string `"xor"`.

- [ ] **Step 1: Write the failing tests**

Add to `TypeCheckerTest`:

```kotlin
@Test fun `xor takes the left operand type`() {
    fun typeOfLast(src: String): WolinType =
        typeChecker(src).functions[0].body.filterIsInstance<Ast.LocalDecl>().last().type
    assertEquals(Types.ubyte, typeOfLast("fun f() { var a: ubyte = 1; var b: ubyte = 2; var c = a xor b }"))
    assertEquals(Types.word, typeOfLast("fun f() { var a: word = 1; var b: word = 2; var c = a xor b }"))
}

@Test fun `xor rejects mixed operand types`() {
    assertThrows<SemaException> {
        typeChecker("fun f() { var a: ubyte = 1; var b: uword = 2; var c = a xor b }")
    }
}

@Test fun `xor rejects bool operands`() {
    assertThrows<SemaException> {
        typeChecker("fun f() { var a: bool = true; var b: bool = false; var c = a xor b }")
    }
}
```

Add to `UnsupportedTest`:

```kotlin
@Test fun `a non-xor infix call stays rejected`() {
    assertRejected("fun f() { var a: ubyte = 1; var b: ubyte = 2; val x = a and b }")
}
```

Add to `GoldenIrTest`:

```kotlin
@Test fun `xor emits a bitwise xor instruction`() {
    val src = """
        fun f(): ubyte {
            var a: ubyte = 0x0F
            var b: ubyte = 0xF0
            var c = a xor b
            return c
        }
    """.trimIndent()
    val ir = CompilerPipeline().compileToIr(src, "t.wtk")
    assertTrue(ir.contains("xor i8"), ir)
}
```

Add to `BitOpsTest`:

```kotlin
@Test fun `xor of two bytes runs on the real 6502`() {
    val bytes = runProgram("""
        fun main() {
            var a: ubyte = 0x0F
            var b: ubyte = 0xF0
            var out: ubyte^0x400 = 0
            out = a xor b
            while (true) {}
        }
    """)
    assertEquals(0xFF, bytes[0].toInt() and 0xFF)
}

@Test fun `a literal in xor takes the operand type`() {
    val bytes = runProgram("""
        fun main() {
            var a: uword = 0x0F0F
            var out: uword^0x400 = 0
            out = a xor 0xFFFF
            while (true) {}
        }
    """)
    assertEquals(0xF0, bytes[0].toInt() and 0xFF)
    assertEquals(0xF0, bytes[1].toInt() and 0xFF)
}

@Test fun `xor precedence sits between the additive and the equality level`() {
    // a | b xor c & d must group as (a | b) xor (c & d) = 0x00.
    // The plausible wrong grouping a | (b xor (c & d)) yields 0x11.
    val bytes = runProgram("""
        fun main() {
            var a: ubyte = 0x11
            var b: ubyte = 0x11
            var c: ubyte = 0x11
            var d: ubyte = 0x11
            var out: ubyte^0x400 = 0
            out = a | b xor c & d
            while (true) {}
        }
    """)
    assertEquals(0x00, bytes[0].toInt() and 0xFF)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.TypeCheckerTest' --tests 'pl.qus.wolin.sema.UnsupportedTest' --tests 'pl.qus.wolin.ir.GoldenIrTest' --tests 'pl.qus.wolin.it.BitOpsTest'`
Expected: FAIL — the new `xor` tests fail with `Unsupported construct: infix function call 'xor'`; `a non-xor infix call stays rejected` already passes.

- [ ] **Step 3: Implement the four changes**

`AstBuilder.kt` — replace `buildInfixCall` (the whole function):

```kotlin
private fun buildInfixCall(ctx: KotlinParser.InfixFunctionCallContext): Expr {
    val ops = ctx.simpleIdentifier().map { it.text }
    val other = ops.firstOrNull { it != "xor" }
    if (other != null) unsupported("infix function call '$other'")
    return if (ops.isEmpty()) {
        buildRange(ctx.rangeExpression()[0])
    } else {
        chain(ctx.rangeExpression(), ops) { buildRange(it) }
    }
}
```

`TypeChecker.kt` — in `checkBinary`, the bitwise branch header becomes:

```kotlin
"&", "|", "xor" -> {
```

The branch body is unchanged: the kind check rejects mixed types, `isBitwise` rejects bool.

`ExprEmitter.kt` — in `emitBinary`, add one arm beside the `&`/`|` arms:

```kotlin
"xor" -> bitwise("xor", l, r, e.left.type)
```

`ConstFolder.kt` — in `foldInt`, add one case to the `when (op)`:

```kotlin
"xor" -> ls xor rs
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt src/test/kotlin/pl/qus/wolin
git commit -m "feat: xor operator"
```

---

### Task 2: `byte` covers the full two's-complement range

**Background:** `TypeChecker.fits` accepts `byte` as −127..127 (`TypeChecker.kt:793`) and `smallestType` maps only −127..−1 to `byte` (`TypeChecker.kt:785`); `−128` — the lowest two's-complement byte — is rejected with "Value out of range". `word` already covers its full range (−32768..32767); only `byte` is one short. The runtime path already handles the 0x80 pattern correctly: `ConstValue.IntValue.spelling` sign-interprets, `ConstFolder.signedView` reads it signed, and the emitter uses `icmp slt`/`sdiv`/`srem` for signed types. Only the two literal-range checks are wrong.

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:785,793` (`smallestType`, `fits`)
- Test: `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt`
- Create: `src/test/kotlin/pl/qus/wolin/it/SignedByteTest.kt`

**Interfaces:**
- Consumes: `runProgram` (`ItSupport.kt:37`) — compiles, runs under VICE, returns `$0400`–`$040F`.
- Produces: `byte` literals in −128..127 accepted; `128`, `−129` rejected.

- [ ] **Step 1: Write the failing tests**

Add to `TypeCheckerTest`:

```kotlin
@Test fun `byte literals cover the full two's complement range`() {
    assertEquals(Types.byte, typeOf("var x: byte = -128"))
    assertEquals(Types.byte, typeOf("var x = -128"))
}

@Test fun `byte literals outside the range are rejected`() {
    assertThrows<SemaException> { typeOf("var x: byte = 128") }
    assertThrows<SemaException> { typeOf("var x: byte = -129") }
}
```

Create `SignedByteTest.kt` (package `pl.qus.wolin.it`):

```kotlin
package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class SignedByteTest {
    @Test fun `byte -128 is accepted and arithmetic wraps`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -128
                var b: byte = a + 1
                var r: bool = b == -127
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte division and remainder are signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -7
                var d: byte = a / 2
                var m: byte = a % 2
                var r: bool = d == -3 && m == -1
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte comparison at the boundary is signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -128
                var b: byte = 127
                var r: bool = a < b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.TypeCheckerTest' --tests 'pl.qus.wolin.it.SignedByteTest'`
Expected: FAIL — the `−128` tests fail with "Value out of range"; the division/remainder and comparison tests pass (they pin behaviour that already exists).

- [ ] **Step 3: Implement the two range fixes in `TypeChecker.kt`**

```kotlin
// smallestType:
value in -128..-1 -> Types.byte
// fits:
TypeKind.BYTE -> value in -128..127
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt src/test/kotlin/pl/qus/wolin
git commit -m "fix: byte literals cover -128..127"
```

---

### Task 3: Explicit integer conversions `toByte` / `toUbyte` / `toWord` / `toUword`

**Design decision (flagged for review):** v1 has no way to move a value between integer types — `var w: word = b` with `b: byte` is an error, and a `byte` cannot be passed to a ROM call expecting a `ubyte`. The v1 rule "mixed types are an error" is kept: conversion is an *explicit* postfix call, Kotlin-style. Same width (byte↔ubyte, word↔uword) is a reinterpretation and emits no IR; widening is `sext` for a signed source and `zext` for an unsigned one; narrowing is `trunc` (low bits, no range check). Float and bool are not convertible in this cycle.

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt` (new `Convert` node + typealias)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:263-273` (`buildPostfix`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:395-411` (`checkExpr` dispatch) + new `checkConvert`
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt:103-119` (`emit` dispatch) + new `emitConvert`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ConstFolder.kt:32-46` (`fold` dispatch) + new `foldConvert`
- Test: `src/test/kotlin/pl/qus/wolin/sema/TypeCheckerTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt`
- Test: `src/test/kotlin/pl/qus/wolin/it/SignedByteTest.kt`

**Interfaces:**
- Consumes: grammar `postfixUnaryOperation: … | NL* memberAccessOperator postfixUnaryExpression` (`KotlinParser.g4:644`) — `b.toWord()` is a DOT operation whose inner expression is the identifier `toWord` with a `callSuffix ()`; `ExprEmitter.isUnsigned` (`ExprEmitter.kt:273`); `ConstFolder.mask`/`width`/`isSigned`/`signedView` (`ConstFolder.kt:118-131`).
- Produces: `Ast.Convert(value: Expr, to: WolinType, type: WolinType)`; the checker sets `type = to` when `value.type.isInteger`, otherwise throws.

- [ ] **Step 1: Write the failing tests**

Add to `TypeCheckerTest`:

```kotlin
@Test fun `conversion takes the target type`() {
    fun typeOfLast(src: String): WolinType =
        typeChecker(src).functions[0].body.filterIsInstance<Ast.LocalDecl>().last().type
    assertEquals(Types.word, typeOfLast("fun f() { var b: byte = -1; var w = b.toWord() }"))
    assertEquals(Types.ubyte, typeOfLast("fun f() { var b: byte = -1; var u = b.toUbyte() }"))
    assertEquals(Types.uword, typeOfLast("fun f() { var u: ubyte = 255; var w = u.toUword() }"))
    assertEquals(Types.byte, typeOfLast("fun f() { var w: word = -1; var b = w.toByte() }"))
}

@Test fun `conversion rejects non-integer operands`() {
    assertThrows<SemaException> { typeChecker("fun f() { var f: bool = true; var x = f.toByte() }") }
    assertThrows<SemaException> { typeChecker("fun f() { var s: string = \"\"; var x = s.toByte() }") }
}
```

Add to `UnsupportedTest`:

```kotlin
@Test fun `an unknown member call stays rejected`() {
    assertRejected("fun f() { var b: byte = 1; var x = b.other() }")
}

@Test fun `a conversion call with arguments stays rejected`() {
    assertRejected("fun f() { var b: byte = 1; var x = b.toWord(2) }")
}
```

Add to `GoldenIrTest`:

```kotlin
@Test fun `conversions emit the right extension instruction`() {
    val src = """
        fun f(): uword {
            var b: byte = -1
            var u: ubyte = 255
            var w: word = 300
            var x = b.toWord()
            var y = u.toUword()
            var z = w.toByte()
            return x.toUword() + y - z.toUbyte().toUword()
        }
    """.trimIndent()
    val ir = CompilerPipeline().compileToIr(src, "t.wtk")
    assertTrue(ir.contains("sext i8"), "sign extension missing:\n$ir")
    assertTrue(ir.contains("zext i8"), "zero extension missing:\n$ir")
    assertTrue(ir.contains("trunc i16"), "truncation missing:\n$ir")
}

@Test fun `a converted global initialiser folds to a constant`() {
    val src = """
        var b: byte = -1
        var w: word = b.toWord()
        fun main() { while (true) {} }
    """.trimIndent()
    val ir = CompilerPipeline().compileToIr(src, "t.wtk")
    assertTrue(ir.contains("i16 -1"), "the folded global must be the sign extension:\n$ir")
}
```

Add to `SignedByteTest`:

```kotlin
@Test fun `a byte converts to a word with sign extension`() {
    val bytes = runProgram("""
        fun main() {
            var b: byte = -2
            var w: word = b.toWord()
            var r: word = w + 1
            var ok: bool = r == -1
            var out: ubyte^0x400 = 0
            out = if (ok) 1 else 0
            while (true) {}
        }
    """)
    assertEquals(1, bytes[0].toInt() and 0xFF)
}

@Test fun `sign extension fills the high byte`() {
    val bytes = runProgram("""
        fun main() {
            var b: byte = -1
            var w: word = b.toWord()
            var hi: ubyte = ((w & 0xFF00) / 256).toUbyte()
            var out: ubyte^0x400 = 0
            out = hi
            while (true) {}
        }
    """)
    assertEquals(0xFF, bytes[0].toInt() and 0xFF)
}

@Test fun `a converted global initialiser runs on the real 6502`() {
    val bytes = runProgram("""
        var b: byte = -1
        var w: word = b.toWord()
        fun main() {
            var r: word = w + 1
            var ok: bool = r == 0
            var out: ubyte^0x400 = 0
            out = if (ok) 1 else 0
            while (true) {}
        }
    """)
    assertEquals(1, bytes[0].toInt() and 0xFF)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.TypeCheckerTest' --tests 'pl.qus.wolin.sema.UnsupportedTest' --tests 'pl.qus.wolin.ir.GoldenIrTest' --tests 'pl.qus.wolin.it.SignedByteTest'`
Expected: FAIL — the new conversion tests fail with `Unsupported construct: member access` (parse-analyser level); the Task 2 tests still pass.

- [ ] **Step 3: Implement the five changes**

`Ast.kt` — new node in the `Expr` section (after `ArrayAccess`), plus `typealias Convert = Ast.Convert` in the alias block:

```kotlin
/**
 * An explicit integer conversion: `value.toByte()`, `.toUbyte()`, `.toWord()`
 * or `.toUword()`. [to] is the target type; the checker fills [type] with it.
 */
data class Convert(val value: Expr, val to: WolinType, override var type: WolinType) : Expr
```

`AstBuilder.kt` — in `buildPostfix`, replace the member-access arm:

```kotlin
operation.memberAccessOperator() != null ->
    buildConversion(result, operation) ?: unsupported("member access")
```

and add the helper:

```kotlin
/**
 * `b.toWord()` and friends: a dot call of one of the four conversion names
 * with no arguments and no type arguments. Anything else — other names,
 * arguments, type arguments, a non-identifier callee, an extra postfix
 * operation on the callee — is not a conversion and returns null, so the
 * caller rejects it as member access.
 */
private fun buildConversion(value: Expr, operation: KotlinParser.PostfixUnaryOperationContext): Convert? {
    val inner = operation.postfixUnaryExpression() ?: return null
    val name = inner.atomicExpression().simpleIdentifier()?.text ?: return null
    val target = when (name) {
        "toByte" -> Types.byte
        "toUbyte" -> Types.ubyte
        "toWord" -> Types.word
        "toUword" -> Types.uword
        else -> return null
    }
    val op = inner.postfixUnaryOperation().singleOrNull() ?: return null
    val call = op.callSuffix() ?: return null
    if (call.typeArguments() != null) return null
    if (call.valueArguments()?.valueArgument().orEmpty().isNotEmpty()) return null
    return Convert(value, target, Types.unit)
}
```

`TypeChecker.kt` — add `is Convert -> checkConvert(expr)` to the `checkExpr` `when`, and:

```kotlin
/**
 * An explicit conversion: the operand must be an integer, the result is the
 * named target type. This is the only way a value changes integer type —
 * there is no promotion anywhere else.
 */
private fun checkConvert(expr: Convert): WolinType {
    val valueType = checkExpr(expr.value, null)
    if (!valueType.isInteger) {
        throw SemaException("${expr.to} conversion requires an integer, not $valueType")
    }
    expr.type = expr.to
    return expr.type
}
```

`ExprEmitter.kt` — add `is Convert -> emitConvert(e)` to the `emit` `when`, and:

```kotlin
/**
 * The explicit conversion: same width is a reinterpretation (no code),
 * widening is `sext` for a signed source and `zext` for an unsigned one,
 * narrowing is `trunc` (the low bits, no range check).
 */
private fun emitConvert(e: Convert): IrValue {
    val v = emit(e.value)
    val vty = v.type
    val tty = TypeMapper.ir(e.to)
    return when {
        vty == tty -> v
        tty == IrType.I16 ->
            b.instr(tty, "${if (isUnsigned(e.value.type)) "zext" else "sext"} ${vty.llvmName()} ${v.name} to ${tty.llvmName()}")
        else -> b.instr(tty, "trunc ${vty.llvmName()} ${v.name} to ${tty.llvmName()}")
    }
}
```

`ConstFolder.kt` — add `is Convert -> foldConvert(expr, constSoFar, functionNames)` to the `fold` `when`, and:

```kotlin
/**
 * A conversion over a constant: the source is read in its own signedness,
 * then widened (sign- or zero-extended) or truncated to the target width,
 * exactly as the `sext`/`zext`/`trunc` instructions do. `(src shl 24) shr 24`
 * is the two's-complement sign extension of an 8-bit value to 16 bits.
 */
private fun foldConvert(
    e: Convert,
    constSoFar: Map<String, ConstValue>,
    functionNames: Map<String, String>,
): ConstValue? {
    val v = fold(e.value, constSoFar, functionNames) as? ConstValue.IntValue ?: return null
    val signed = isSigned(v.type)
    val src = if (signed) signedView(mask(v.bits, v.type)) else mask(v.bits, v.type)
    val bits = when {
        width(e.to) == 16 && width(v.type) == 8 -> if (signed) (src shl 24) shr 24 else src
        else -> src
    }
    return ConstValue.IntValue(bits, e.to)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: same command as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pl/qus/wolin/ast/Ast.kt src/main/kotlin/pl/qus/wolin/sema src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt src/test/kotlin/pl/qus/wolin
git commit -m "feat: explicit integer conversions toByte toUbyte toWord toUword"
```

---

### Task 4: The documentation

**Files:**
- Modify: `README.md` (types table, statements section, new Conversions subsection)
- Modify: `docs/superpowers/specs/2026-11-07-wolin-llvm-design.md` (§6 types table `byte` row, §6 "Expressions" xor paragraph, §12)

**Interfaces:**
- Consumes: the language as implemented by Tasks 1–3.

- [ ] **Step 1: Update `README.md`**

- Types table, `byte` row — replace "signed interpretation is a v2 concern; v1 arithmetic wraps modulo 256" with: "signed two's complement, −128..127; comparisons, `/` and `%` are signed; arithmetic wraps modulo 256".
- Statements section — the README currently documents only the *assignment* forms of the bit operators; add after the assignments paragraph: "Bitwise expressions: `&`, `|`, `xor` — any integer type, no promotion."
- New subsection "Conversions": the four functions `toByte()`, `toUbyte()`, `toWord()`, `toUword()`; explicit — the only way a value changes integer type; same width reinterprets, widening sign- or zero-extends, narrowing truncates.

- [ ] **Step 2: Update the spec**

In §12, mark the `xor` bullet implemented, and note that the `byte` signedness and the conversion gap are closed — both pointing at this plan (`docs/superpowers/plans/2026-11-15-xor-signed-byte.md`). Update the §6 types-table `byte` row and the §6 "Expressions" "There is no XOR" paragraph the same way: they describe v1, and v1 now has both.

- [ ] **Step 3: Run the full build**

Run: `./gradlew build`
Expected: PASS (all unit and integration tests, including the VICE ones).

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-11-07-wolin-llvm-design.md
git commit -m "docs: xor and signed byte semantics in the language docs"
```
