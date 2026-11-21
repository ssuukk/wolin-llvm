# Classes and Objects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Wolin program declare `class` and `object` types with fields and methods, hold instances in global, local, and memory-attached (fixed-address) storage, and read and write their fields.

**Architecture:** A class is a **byte blob** with a compiler-computed field layout — not a heap object, not an LLVM struct. A declaration creates the instance: `var g: Foo` is a module global of the class size, `var l: Foo` is an `alloca`, `var m: Foo^0x3000` is a memory-attached instance with no storage of its own (each field carries its own address, exactly like a fixed-address variable). A field is either *plain* (storage inside the blob, at a computed offset) or *attached* (`var border: ubyte^0xd020` — no storage inside the blob, accessed directly at its absolute address). A method is a module-level function whose first parameter is the receiver pointer; the class is a new level in the symbol mangling. There is no constructor call, no allocator, and no run-time identity.

**Tech Stack:** Kotlin, ANTLR4 (`KotlinParser.g4`, no grammar change needed), LLVM-IR text emitter (`ModuleEmitter`, `StmtEmitter`, `ExprEmitter`), JUnit 5, VICE (`ViceRunner`), Gradle (`./gradlew test`).

**Spec:** `docs/superpowers/specs/2026-11-20-class-object-design.md`

## Global Constraints

- **No heap, no allocator, no constructor call.** A declaration creates the instance. `Foo()` is not a thing.
- **No grammar change.** `classDeclaration`, `objectDeclaration`, `classBody`, `memberAccessOperator`, `thisExpression` and the `^` location suffix all already parse. Only the builder and the checker change.
- **A class value has no expression form.** There is no way to write an expression of class type. `var g: Foo` declares an instance; `g = anything` is "Cannot assign to a class value".
- **A class value is not a pointer.** The instance *is* its storage; the compiler tracks where it lives statically. `TypeMapper.ir` maps `CLASS` to `IrType.Ptr` only so the exhaustive `when`s compile — the pointer is never produced.
- **No inheritance, no `interface`, no `enum class`, no `companion object`, no `constructor`, no `init { }`, no `super`, no generics, no `is`, no nullable class types.** Each is rejected with `Unsupported construct: <name>`.
- **Field alignment is 1 for every type.** The 6502 has no alignment requirement, and a packed layout keeps the offset arithmetic trivial. A `uword` field at an odd offset is legal and is pinned by a VICE test.
- **The instance size is rounded up to an even number of bytes.** The *offsets* are not rounded; only the total size is.
- **An attached field contributes zero bytes** to the instance size.
- **A memory-attached instance may not have a plain field** — it has no inside to hold one. Error: "Field <name> of a memory-attached class instance must be memory-attached".
- **The `^` suffix on a class type is a location on the *instance*, not on the fields.** Each field's own `^` is what places it at an address.
- **Do NOT touch the user's in-progress work.** `REFERENCE.md`, `TODO.md`, `README.md`, `stdlib/`, `examples/`, and the `CpuAccessors.kt` / `FixedAddressFunctionTest.kt` / `CpuRegisterTest.kt` files are user-modified. Read-only for this plan except where a task explicitly says to update `REFERENCE.md` / `TODO.md` / `README.md`.
- **Commits go through the `eval` `commit` namespace** (`d = await commit.draft(); await d.commit("subject", ["- body"])`), never `git add`/`git commit`.

## Review Focus

1. **A `uword` field after a `ubyte` field is at an odd offset** and must still read back its value — the 6502 has no alignment requirement, but the backend must emit an unaligned load. Pinned by Task 5's VICE test.
2. **A memory-attached instance's field access must be a direct access** — `vic.border = 6` must emit the same `load`/`store volatile` at `0xd020` that `var border: ubyte^0xd020` emits, with no indirection through the anchor. Pinned by Task 5's golden IR.
3. **A class's size is rounded up to even, but the field offsets are not.** A class with a `ubyte` and a `uword` has offsets 0 and 1 and size 4. Pinned by Task 3's layout test.
4. **An attached field inside a *plain* instance is legal** and must not consume blob space. Pinned by Task 3's layout test.
5. **A method's receiver is passed by pointer**, so the method can write the receiver's fields. Pinned by Task 5's VICE test (a method that mutates a field).
6. **An `object`'s field state survives across calls** — the singleton has one instance. Pinned by Task 6's VICE test.
7. **A local instance's fields are initialised at the declaration**, not at function entry, so a field initialiser may read a local declared before it. Pinned by Task 6's VICE test.
8. **An unknown member is "Unknown member <name>", not "Unsupported construct".** The `UnsupportedTest` entry "an unknown member call stays rejected" must be re-pinned, not deleted. Pinned by Task 4.
9. **`this` outside a method is an error, but `super` stays rejected** — the two must not be lumped into one catalogue entry. Pinned by Task 4.
10. **A class type name resolves through an import.** `import pkg.Foo` makes `Foo` a usable type name. Pinned by Task 2's test.

---

### Task 1: The AST and the builder — `class` and `object` parse into the AST

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:34-43` (the top-level dispatch), `:50-80` (`buildGlobal`/`buildLocal`), `:279-291` (`buildPostfix`), `:541-553` (`buildLocation`), `:670-671` (`unsupported`)
- Test: `src/test/kotlin/pl/qus/wolin/sema/ClassObjectAstTest.kt` (new)

**Interfaces:**
- Consumes: nothing (the first task in the chain).
- Produces:
  - `Ast.ClassDecl(name: String, fields: List<GlobalDecl>, methods: List<FunDecl>, isObject: Boolean, size: Int = 0, fieldOffsets: MutableMap<String, Int> = mutableMapOf())` — a new AST node, a top-level declaration category parallel to `GlobalDecl`/`FunDecl`.
  - `Ast.MemberAccess(receiver: Expr, name: String, type: WolinType)` — a new `Expr` node.
  - `Ast.Program.classes: List<ClassDecl>` and `Ast.FileScope.classes: List<ClassDecl>` — the new declaration list.
  - `AstBuilder.buildClass(ctx: KotlinParser.ClassDeclarationContext): ClassDecl` and `AstBuilder.buildObject(ctx: KotlinParser.ObjectDeclarationContext): ClassDecl` — private.
  - `AstBuilder.buildMemberAccess(receiver: Expr, name: String): MemberAccess` — private.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/sema/ClassObjectAstTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassObjectAstTest {
    private fun build(src: String): Ast.Program =
        AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!)

    @Test fun `a class declaration becomes a ClassDecl with its fields and methods`() {
        val program = build(
            """
            class Foo {
                var x: ubyte = 1
                var y: uword
                fun sum(): ubyte { return x }
            }
            """.trimIndent()
        )
        assertEquals(1, program.classes.size)
        val cls = program.classes[0]
        assertEquals("Foo", cls.name)
        assertEquals(false, cls.isObject)
        assertEquals(listOf("x", "y"), cls.fields.map { it.name })
        assertEquals(listOf("sum"), cls.methods.map { it.name })
    }

    @Test fun `an object declaration is a ClassDecl flagged as an object`() {
        val program = build(
            """
            object Counter {
                var count: ubyte = 0
                fun inc(): ubyte { return count }
            }
            """.trimIndent()
        )
        assertEquals(1, program.classes.size)
        assertEquals("Counter", program.classes[0].name)
        assertEquals(true, program.classes[0].isObject)
    }

    @Test fun `a field with a fixed address keeps its location`() {
        val program = build(
            """
            class Vic {
                var border: ubyte^0xd020
            }
            """.trimIndent()
        )
        val field = program.classes[0].fields[0]
        assertEquals(Ast.FixedAddress(0xd020), field.location)
    }

    @Test fun `a member access becomes a MemberAccess node`() {
        val program = build("fun f() { val x = obj.border }")
        val body = program.functions[0].body
        val stmt = body[0] as Ast.LocalDecl
        val access = stmt.init as Ast.MemberAccess
        assertEquals("border", access.name)
        assertEquals("obj", (access.receiver as Ast.VarRef).name)
    }

    @Test fun `a this expression becomes a ThisExpr node`() {
        val program = build("class Foo { fun f() { val x = this } }")
        val body = program.classes[0].methods[0].body
        val stmt = body[0] as Ast.LocalDecl
        assertTrue(stmt.init is Ast.ThisExpr)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectAstTest'`
Expected: FAIL — compilation error, `ClassDecl` / `MemberAccess` / `ThisExpr` do not exist.

- [ ] **Step 3: Add the AST nodes**

In `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`, add to the `Ast` sealed interface:

```kotlin
    /**
     * A class or object declaration: a field layout plus its methods.
     *
     * [size] and [fieldOffsets] are filled by the checker, which computes
     * the layout once per class.
     */
    data class ClassDecl(
        val name: String,
        val fields: List<GlobalDecl>,
        val methods: List<FunDecl>,
        /** True for an `object` declaration: it also has a global instance. */
        val isObject: Boolean,
        /** The instance size in bytes, filled by the checker. */
        var size: Int = 0,
        /** Each plain field's offset in the blob, filled by the checker. */
        val fieldOffsets: MutableMap<String, Int> = mutableMapOf(),
    ) : Ast

    /** `receiver.member` — a field read or a method call. */
    data class MemberAccess(
        val receiver: Expr,
        val name: String,
        override var type: WolinType,
    ) : Expr

    /** `this` — the receiver of the enclosing method. */
    data class ThisExpr(override var type: WolinType) : Expr
```

Add `classes: List<ClassDecl> = emptyList()` to `Ast.Program` and to `Ast.FileScope`.

Add the type aliases at the bottom of the file, next to the existing ones:

```kotlin
typealias ClassDecl = Ast.ClassDecl
typealias MemberAccess = Ast.MemberAccess
typealias ThisExpr = Ast.ThisExpr
```

- [ ] **Step 4: Build the class and object declarations**

In `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt`, replace lines 37-40 (the class and object rejections) with:

```kotlin
            top.classDeclaration()?.let { classes += buildClass(it); return@forEach }
            top.objectDeclaration()?.let { classes += buildObject(it); return@forEach }
```

and add `val classes = mutableListOf<ClassDecl>()` to `build`'s local declarations, and `classes = classes` to the returned `Program`.

Add the two builders, next to `buildFunction` (`AstBuilder.kt:82`):

```kotlin
    /**
     * A `class Foo { … }` declaration: the fields and the methods of the
     * class body.
     *
     * The members the first cut does not support — a supertype, type
     * parameters, a constructor, an `init` block — are rejected here, so
     * the diagnostic names the construct.
     */
    private fun buildClass(ctx: KotlinParser.ClassDeclarationContext): ClassDecl {
        val name = ctx.simpleIdentifier()?.text ?: unsupported("anonymous class")
        if (ctx.INTERFACE() != null) unsupported("interface")
        if (ctx.typeParameters() != null) unsupported("type parameters")
        if (ctx.primaryConstructor() != null) unsupported("constructor")
        if (ctx.delegationSpecifiers() != null) unsupported("supertype")
        val body = ctx.classBody() ?: unsupported("class body")
        return buildClassBody(name, body, isObject = false)
    }

    /** An `object Foo { … }` declaration: a class with a singleton instance. */
    private fun buildObject(ctx: KotlinParser.ObjectDeclarationContext): ClassDecl {
        val name = ctx.simpleIdentifier()?.text ?: unsupported("anonymous object")
        if (ctx.primaryConstructor() != null) unsupported("constructor")
        if (ctx.delegationSpecifiers() != null) unsupported("supertype")
        val body = ctx.classBody() ?: unsupported("object body")
        return buildClassBody(name, body, isObject = true)
    }

    /**
     * The body of a class or an object: its fields and its methods.
     *
     * A member that is neither a property nor a function — a nested class,
     * a companion object, an `init` block, a secondary constructor — is
     * rejected.
     */
    private fun buildClassBody(
        name: String,
        body: KotlinParser.ClassBodyContext,
        isObject: Boolean,
    ): ClassDecl {
        val fields = mutableListOf<GlobalDecl>()
        val methods = mutableListOf<FunDecl>()
        body.classMemberDeclaration().forEach { member ->
            member.propertyDeclaration()?.let { fields += buildGlobal(it); return@forEach }
            member.functionDeclaration()?.let { methods += buildFunction(it); return@forEach }
            member.classDeclaration()?.let { unsupported("nested class") }
            member.objectDeclaration()?.let { unsupported("nested object") }
            member.companionObject()?.let { unsupported("companion object") }
            member.anonymousInitializer()?.let { unsupported("init block") }
            member.secondaryConstructor()?.let { unsupported("constructor") }
            member.typeAlias()?.let { unsupported("type alias") }
            unsupported("class member")
        }
        return ClassDecl(name, fields, methods, isObject)
    }
```

- [ ] **Step 5: Build member accesses and `this`**

In `AstBuilder.buildPostfix` (`AstBuilder.kt:279`), the member-access branch at line 289-290 currently reads:

```kotlin
                operation.memberAccessOperator() != null ->
                    buildConversion(result, operation) ?: unsupported("member access")
```

Replace it with:

```kotlin
                operation.memberAccessOperator() != null ->
                    buildConversion(result, operation) ?: buildMemberAccess(result, operation)
```

and add, next to `buildConversion` (`AstBuilder.kt:305`):

```kotlin
    /**
     * `receiver.member` — a field read or a method call.
     *
     * A safe call (`?.`) is not supported: a class value is never null.
     */
    private fun buildMemberAccess(
        receiver: Expr,
        operation: KotlinParser.PostfixUnaryOperationContext,
    ): Expr {
        if (operation.memberAccessOperator()?.QUEST() != null) unsupported("safe call")
        val inner = operation.postfixUnaryExpression() ?: unsupported("member access")
        val name = inner.atomicExpression().simpleIdentifier()?.text ?: unsupported("member access")
        return MemberAccess(receiver, name, Types.unit)
    }
```

In `AstBuilder.buildAtomic` (`AstBuilder.kt:347`), the `thisExpression` branch at line 358 reads `unsupported("this expression")`. Replace it with:

```kotlin
        ctx.thisExpression()?.let { return ThisExpr(Types.unit) }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectAstTest'`
Expected: PASS — all five tests green.

- [ ] **Step 7: Commit**

```bash
d = await commit.draft()
await d.commit("feat(ast): ClassDecl MemberAccess ThisExpr nodes", [
  "Class and object declarations parse into the AST; member access and",
  "this become expression nodes. The checker still rejects them.",
])
```

---

### Task 2: The type kind, the symbol, and the import

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/Types.kt:3` (`TypeKind`), `:5-20` (`WolinType`), `:22-25` (`toString`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/Scope.kt:11-34` (`Symbol`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt:19-24` (`resolve`), add a class branch
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:525-539` (`baseType`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/TypeMapper.kt:13-22`
- Test: `src/test/kotlin/pl/qus/wolin/sema/ClassObjectTypeTest.kt` (new)

**Interfaces:**
- Consumes: `Ast.ClassDecl` from Task 1.
- Produces:
  - `TypeKind.CLASS` — a new type kind.
  - `WolinType.className: String? = null` — the class a `CLASS` type names.
  - `Types.classType(name: String): WolinType` — the class type constructor.
  - `sizeOf(t: WolinType): Int` — a top-level function in `sema/Types.kt`.
  - `Symbol.ClassSymbol(decl: ClassDecl, prefix: String = "")` — a new symbol case.
  - `ImportResolver.resolveClass(name: String, imports: List<ImportDecl>, program: Ast.Program): Symbol?` — public.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/sema/ClassObjectTypeTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassObjectTypeTest {
    private fun build(src: String): Ast.Program =
        AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!)

    @Test fun `a class type carries the class name`() {
        val type = Types.classType("Foo")
        assertEquals(TypeKind.CLASS, type.kind)
        assertEquals("Foo", type.className)
        assertEquals("Foo", type.toString())
    }

    @Test fun `a class type is not an integer type`() {
        assertTrue(!Types.classType("Foo").isInteger)
    }

    @Test fun `sizes are the field sizes`() {
        assertEquals(1, sizeOf(Types.ubyte))
        assertEquals(1, sizeOf(Types.bool))
        assertEquals(2, sizeOf(Types.uword))
        assertEquals(4, sizeOf(Types.float))
        assertEquals(2, sizeOf(Types.string))
    }

    @Test fun `a class type name is not a built-in type`() {
        val e = runCatching { build("var x: Foo") }.exceptionOrNull()
        assertTrue(e is SemaException, "expected a rejection, got $e")
        assertTrue(e.message!!.contains("Unknown type Foo"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectTypeTest'`
Expected: FAIL — compilation error, `Types.classType` and `sizeOf` do not exist.

- [ ] **Step 3: Add the type kind and the class type**

In `src/main/kotlin/pl/qus/wolin/sema/Types.kt`, change line 3:

```kotlin
enum class TypeKind { UBYTE, BYTE, UWORD, WORD, BOOL, UNIT, FLOAT, STRING, ARRAY, FUNCTION, CLASS }
```

Add the `className` field to `WolinType` (after `retType`):

```kotlin
    /** The class this type names, for [TypeKind.CLASS]. */
    val className: String? = null,
```

Add a branch to `toString` (`Types.kt:22`):

```kotlin
        TypeKind.CLASS -> className ?: "?"
```

Add to the `Types` object:

```kotlin
    /** The type of a class instance: `Foo`. */
    fun classType(name: String): WolinType =
        WolinType(TypeKind.CLASS, className = name)
```

Add the size function at the bottom of the file:

```kotlin
/**
 * The size of a type in bytes, for the class layout.
 *
 * A class value is addressed, not stored, so it costs a pointer's width.
 * The alignment of every type is 1: the 6502 has no alignment requirement.
 */
fun sizeOf(t: WolinType): Int = when (t.kind) {
    TypeKind.UBYTE, TypeKind.BYTE, TypeKind.BOOL -> 1
    TypeKind.UWORD, TypeKind.WORD -> 2
    TypeKind.FLOAT -> 4
    TypeKind.STRING, TypeKind.ARRAY, TypeKind.FUNCTION, TypeKind.CLASS -> 2
    TypeKind.UNIT -> 0
}
```

- [ ] **Step 4: Map the class type in the IR**

In `src/main/kotlin/pl/qus/wolin/ir/TypeMapper.kt`, add a branch to `ir`:

```kotlin
        TypeKind.CLASS -> IrType.Ptr
```

- [ ] **Step 5: Add the class symbol**

In `src/main/kotlin/pl/qus/wolin/sema/Scope.kt`, add to the sealed `Symbol`:

```kotlin
    /**
     * A class or object declaration.
     *
     * [prefix] is the mangled prefix of the file that declares the class:
     * the class's methods are emitted under `<prefix>.<class>.<method>`.
     */
    data class ClassSymbol(val decl: Ast.ClassDecl, val prefix: String = "") : Symbol()
```

The file imports `pl.qus.wolin.ast.Expr`, `FunDecl` and `Location` already; add `import pl.qus.wolin.ast.ClassDecl` and use `ClassDecl` directly.

- [ ] **Step 6: Resolve a class through an import**

In `src/main/kotlin/pl/qus/wolin/sema/ImportResolver.kt`, change `resolve` (line 19) to:

```kotlin
    fun resolve(name: String, imports: List<ImportDecl>, program: Ast.Program): Symbol? =
        resolveFunction(name, imports, program)
            ?: resolveGlobal(name, imports, program)
            ?: resolveClass(name, imports, program)
```

and add:

```kotlin
    /**
     * The class [name] refers to through [imports], or null when no import
     * brings it in.
     */
    fun resolveClass(name: String, imports: List<ImportDecl>, program: Ast.Program): Symbol? {
        for (import in imports) {
            val path = import.path
            if (path.endsWith(".*")) {
                val packageName = path.removeSuffix(".*")
                val scope = program.files.firstOrNull {
                    it.packageName == packageName && it.classes.any { c -> c.name == name }
                }
                if (scope != null) {
                    return Symbol.ClassSymbol(scope.classes.first { it.name == name }, scope.mangledPrefix)
                }
                continue
            }
            val separator = path.lastIndexOf('.')
            if (separator < 0) continue
            val packageName = path.substring(0, separator)
            val importedName = path.substring(separator + 1)
            if (importedName != name) continue
            val scope = program.files.firstOrNull {
                it.packageName == packageName && it.classes.any { c -> c.name == name }
            }
            if (scope != null) {
                return Symbol.ClassSymbol(scope.classes.first { it.name == name }, scope.mangledPrefix)
            }
        }
        return null
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectTypeTest'`
Expected: PASS — all four tests green.

- [ ] **Step 8: Commit**

```bash
d = await commit.draft()
await d.commit("feat(sema): class type kind symbol and import resolution", [
  "TypeKind.CLASS, WolinType.className, sizeOf, Symbol.ClassSymbol, and",
  "the import resolver's class branch.",
])
```

---

### Task 3: The layout — field offsets and the instance size

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:52-143` (`checkFile`), `:146-188` (`checkGlobal`), add a `checkClass` and a `layoutOf`
- Test: `src/test/kotlin/pl/qus/wolin/sema/ClassObjectLayoutTest.kt` (new)

**Interfaces:**
- Consumes: `Ast.ClassDecl` (Task 1), `Symbol.ClassSymbol` and `sizeOf` (Task 2).
- Produces:
  - `TypeChecker.checkClass(cls: ClassDecl, fileScope: FileScope, fileSyms: Scope)` — private.
  - `TypeChecker.layoutOf(cls: ClassDecl): Pair<Int, Map<String, Int>>` — private.
  - `Ast.ClassDecl.size` — the instance size in bytes.
  - `Ast.ClassDecl.fieldOffsets` — each plain field's offset.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/sema/ClassObjectLayoutTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassObjectLayoutTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `fields are laid out in declaration order`() {
        val program = check(
            """
            class Foo {
                var a: ubyte
                var b: uword
                var c: bool
            }
            """.trimIndent()
        )
        val cls = program.classes[0]
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 3), cls.fieldOffsets)
        assertEquals(4, cls.size)
    }

    @Test fun `the size is rounded up to an even number of bytes`() {
        val program = check(
            """
            class Foo {
                var a: ubyte
                var b: ubyte
                var c: ubyte
            }
            """.trimIndent()
        )
        assertEquals(4, program.classes[0].size)
    }

    @Test fun `an attached field takes no space in the blob`() {
        val program = check(
            """
            class Foo {
                var a: ubyte^0xd020
                var b: ubyte
            }
            """.trimIndent()
        )
        val cls = program.classes[0]
        assertEquals(mapOf("b" to 0), cls.fieldOffsets)
        assertEquals(2, cls.size)
    }

    @Test fun `a class of only attached fields has size zero`() {
        val program = check(
            """
            class Foo {
                var a: ubyte^0xd020
            }
            """.trimIndent()
        )
        assertEquals(0, program.classes[0].size)
    }

    @Test fun `a memory-attached instance may not have a plain field`() {
        val e = assertFailsWith<SemaException> {
            check(
                """
                class Foo {
                    var a: ubyte
                }
                var m: Foo^0x3000
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("memory-attached"),
            "message should name the rule, got: ${e.message}",
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectLayoutTest'`
Expected: FAIL — the class is not checked at all, so `fieldOffsets` is empty and `size` is 0.

- [ ] **Step 3: Declare the classes in the file scope**

In `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt`, in `checkFile` (`TypeChecker.kt:52`), after the file's own functions are declared (the block ending at line 62), add:

```kotlin
        // The file's own classes are declared before anything is checked, so
        // a declaration may name a class declared further down.
        fileScope.classes.forEach { cls ->
            globals.declare(cls.name, Symbol.ClassSymbol(cls, fileScope.mangledPrefix))
        }
```

and after the globals are checked (line 141), add:

```kotlin
        fileScope.classes.forEach { cls -> checkClass(cls, fileScope, globals) }
```

- [ ] **Step 4: Compute the layout**

Add to `TypeChecker`, next to `checkGlobal` (`TypeChecker.kt:146`):

```kotlin
    /**
     * Checks a class: its fields, its methods, and the layout the emitter
     * needs.
     */
    private fun checkClass(cls: ClassDecl, fileScope: FileScope, fileSyms: Scope) {
        val (size, offsets) = layoutOf(cls)
        cls.size = size
        cls.fieldOffsets.clear()
        cls.fieldOffsets.putAll(offsets)
        // A class's fields are visible to its methods and to each other.
        val classScope = fileSyms.child()
        cls.fields.forEach { f ->
            classScope.declare(
                f.name,
                Symbol.DataSymbol(f.type, f.location, f.init, f.isVal, fileScope.mangledPrefix),
            )
        }
        cls.methods.forEach { m -> checkFunction(m, classScope) }
    }

    /**
     * The instance size in bytes and each plain field's offset.
     *
     * Fields are laid out in declaration order. An attached field — one with
     * a fixed address — has no storage inside the instance, so it takes no
     * space and has no offset. The size is rounded up to an even number of
     * bytes; the offsets are not rounded.
     */
    private fun layoutOf(cls: ClassDecl): Pair<Int, Map<String, Int>> {
        var offset = 0
        val offsets = mutableMapOf<String, Int>()
        for (f in cls.fields) {
            if (f.location is FixedAddress) continue
            offsets[f.name] = offset
            offset += sizeOf(f.type)
        }
        return (offset + 1) and 1.inv() to offsets
    }
```

- [ ] **Step 5: Reject a plain field in a memory-attached instance**

The rule is about the *instance's* declaration, not the class, so it is
checked where the instance is declared. The class is named by the
declaration's *type*, so the lookup is by type name, not by the variable's
name.

In `checkGlobal` (`TypeChecker.kt:146`), after the type is resolved
(line 178), add:

```kotlin
        if (type.kind == TypeKind.CLASS && global.location is FixedAddress) {
            val cls = lookupClass(type.className ?: "")
            val plain = cls?.fields?.filter { it.location !is FixedAddress }?.map { it.name }
            if (!plain.isNullOrEmpty()) {
                throw SemaException(
                    "Field ${plain.first()} of a memory-attached class instance must be memory-attached"
                )
            }
        }
```

`lookupClass` is added in Task 4. To keep Task 3 self-contained, add it in
Task 3 instead — next to `layoutOf`:

```kotlin
    /**
     * The class a class-typed name refers to, or null when no class of
     * that name is declared.
     */
    private fun lookupClass(name: String): ClassDecl? {
        val symbol = scope.lookup(name)
            ?: importResolver.resolveClass(name, fileImports, program ?: return null)
        return (symbol as? Symbol.ClassSymbol)?.decl
    }
```

Task 4 then uses this same `lookupClass` — it does not add a second one.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectLayoutTest'`
Expected: PASS — all five tests green.

- [ ] **Step 7: Commit**

```bash
d = await commit.draft()
await d.commit("feat(sema): class layout field offsets and instance size", [
  "Fields are laid out in declaration order; attached fields take no",
  "space; the size is rounded up to even bytes.",
])
```

---

### Task 4: The checker — member resolution, `this`, and method calls

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt:19-22` (the checker's state), `:243-268` (`checkFunction`), `:570-588` (`checkExpr`), `:604-614` (`checkVarRef`), `:725-762` (`checkAssign`), `:816-870` (`checkCall`), add `checkMemberAccess` and `checkThis`
- Modify: `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt:541-553` (`buildLocation`)
- Modify: `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt:34-36` (delete the class entry), `:98-101` (split this/super), `:167-169` (re-pin the unknown member)
- Test: `src/test/kotlin/pl/qus/wolin/sema/ClassObjectCheckerTest.kt` (new)

**Interfaces:**
- Consumes: `Ast.ClassDecl`, `Ast.MemberAccess`, `Ast.ThisExpr` (Task 1); `Symbol.ClassSymbol` (Task 2); `checkClass` / `layoutOf` (Task 3).
- Produces:
  - `TypeChecker.currentReceiver: ClassDecl?` — the receiver of the method being checked.
  - `TypeChecker.checkMemberAccess(expr: MemberAccess): WolinType` — private.
  - `TypeChecker.checkThis(expr: ThisExpr): WolinType` — private.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/pl/qus/wolin/sema/ClassObjectCheckerTest.kt`:

```kotlin
package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClassObjectCheckerTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    private fun message(src: String): String {
        val e = assertFailsWith<SemaException> { check(src) }
        return e.message!!
    }

    @Test fun `a field read has the field's type`() {
        val program = check(
            """
            class Foo {
                var x: ubyte = 1
            }
            fun f() {
                var g: Foo
                val v = g.x
            }
            """.trimIndent()
        )
        val body = program.functions[0].body
        val decl = body[1] as Ast.LocalDecl
        assertEquals(TypeKind.UBYTE, decl.type.kind)
    }

    @Test fun `a field write is checked against the field's type`() {
        assertTrue(
            message(
                """
                class Foo {
                    var x: ubyte = 1
                }
                fun f() {
                    var g: Foo
                    g.x = 300
                }
                """.trimIndent()
            ).contains("out of range")
        )
    }

    @Test fun `an unknown member is rejected`() {
        assertTrue(
            message(
                """
                class Foo {
                    var x: ubyte = 1
                }
                fun f() {
                    var g: Foo
                    val v = g.nope
                }
                """.trimIndent()
            ).contains("Unknown member nope")
        )
    }

    @Test fun `a member of a non-class value is rejected`() {
        assertTrue(
            message("fun f() { var b: ubyte = 1; val v = b.x }")
                .contains("Cannot access member x")
        )
    }

    @Test fun `this is the receiver inside a method`() {
        val program = check(
            """
            class Foo {
                var x: ubyte = 1
                fun get(): ubyte { return this.x }
            }
            """.trimIndent()
        )
        val body = program.classes[0].methods[0].body
        val ret = body[0] as Ast.ReturnStmt
        assertEquals(TypeKind.UBYTE, ret.value!!.type.kind)
    }

    @Test fun `this outside a method is rejected`() {
        assertTrue(message("fun f() { val x = this }").contains("this is not allowed here"))
    }

    @Test fun `a method call has the method's return type`() {
        val program = check(
            """
            class Foo {
                fun get(): ubyte { return 1 }
            }
            fun f() {
                var g: Foo
                val v = g.get()
            }
            """.trimIndent()
        )
        val body = program.functions[0].body
        val decl = body[1] as Ast.LocalDecl
        assertEquals(TypeKind.UBYTE, decl.type.kind)
    }

    @Test fun `a method call checks its arguments`() {
        assertTrue(
            message(
                """
                class Foo {
                    fun add(a: ubyte, b: ubyte): ubyte { return a }
                }
                fun f() {
                    var g: Foo
                    val v = g.add(1)
                }
                """.trimIndent()
            ).contains("expects 2 arguments")
        )
    }

    @Test fun `assigning to a class value is rejected`() {
        assertTrue(
            message(
                """
                class Foo {
                    var x: ubyte = 1
                }
                fun f() {
                    var g: Foo
                    g = g
                }
                """.trimIndent()
            ).contains("Cannot assign to a class value")
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectCheckerTest'`
Expected: FAIL — the checker has no member-resolution path.

- [ ] **Step 3: Let the builder build a class type**

In `src/main/kotlin/pl/qus/wolin/sema/AstBuilder.kt`, `baseType` (line 525) raises "Unknown type" for a name it does not know. The builder cannot know which names are classes — that is the checker's job — so the builder must carry the name through. Change `baseType`'s `else` branch to produce a **named** type that the checker resolves:

```kotlin
            else -> throw SemaException("Unknown type $name")
```

becomes

```kotlin
            else -> Types.classType(name)
```

**But** this would make every typo a class type. To keep the "Unknown type" diagnostic, the checker resolves a class type and rejects it when no class of that name is declared. `baseType` is also used for array element types (`AstBuilder.kt:513`), where a class element type is legal.

Update `AstBuilder.buildTypeReference` so the class branch is taken only for a name that is not a built-in, and keep "Unknown type" for a name that resolves to nothing:

```kotlin
    private fun baseType(name: String): WolinType {
        if (name == "CPU") throw SemaException("CPU is a reserved namespace")
        if (name.startsWith("CPU.")) unsupported("type '$name'")
        return when (name) {
            "ubyte" -> Types.ubyte
            "byte" -> Types.byte
            "uword" -> Types.uword
            "word" -> Types.word
            "bool" -> Types.bool
            "unit" -> Types.unit
            "float" -> Types.float
            "string" -> Types.string
            // A name that is not a built-in may name a class; the checker
            // rejects it when no class of that name is declared.
            else -> Types.classType(name)
        }
    }
```

and in `TypeChecker.checkExpr` / the class-type resolution path, a `CLASS` type whose name resolves to no class is "Unknown type <name>".

- [ ] **Step 4: Resolve a class type name**

Add `TypeChecker` — but `lookupClass` was **already added in Task 3**, so
it is not added again here. Only the rejection rule is new:

```kotlin
    /** Rejects a class type that names no declared class. */
    private fun requireKnownClass(type: WolinType) {
        if (type.kind != TypeKind.CLASS) return
        val name = type.className ?: throw SemaException("Unknown type")
        if (lookupClass(name) == null) throw SemaException("Unknown type $name")
    }
```

Call `requireKnownClass(type)` at the end of `checkGlobal` (after line 178)
and in the `LocalDecl` branch of `checkStmt` (after line 391).

- [ ] **Step 5: Add the member resolution**

**The shape of the tree.** `obj.m(a, b)` parses as
`Call(callee = MemberAccess(obj, "m"), args = [a, b])`. The arguments live on
the `Call`, not on the `MemberAccess`. So `checkExpr` sees the `Call` first,
and the `Call` branch is where the method is resolved.

Add to `TypeChecker`:

```kotlin
    /**
     * `receiver.member` — a field read.
     *
     * The receiver's static type must be a class type, and the name must be
     * a field of that class. A method name reached without a call is an
     * error: a method is not a value.
     */
    private fun checkMemberAccess(expr: MemberAccess): WolinType {
        val cls = classOf(expr)
        expr.owner = cls
        if (cls.methods.any { it.name == expr.name }) {
            throw SemaException("Method ${expr.name} must be called")
        }
        val field = cls.fields.firstOrNull { it.name == expr.name }
            ?: throw SemaException("Unknown member ${expr.name}")
        expr.type = field.type
        return expr.type
    }

    /**
     * The class a member access names, resolved through the receiver's
     * static type.
     */
    private fun classOf(expr: MemberAccess): ClassDecl {
        val receiverType = checkExpr(expr.receiver, null)
        if (receiverType.kind != TypeKind.CLASS) {
            throw SemaException("Cannot access member ${expr.name} of $receiverType")
        }
        return lookupClass(receiverType.className ?: "")
            ?: throw SemaException("Unknown type ${receiverType.className}")
    }
```

In `checkCall` (`TypeChecker.kt:816`), add a branch **before** the
`callee !is VarRef` rejection, so a method call is recognised:

```kotlin
        val callee = expr.callee
        if (callee is MemberAccess) {
            // A method call: the receiver is the method's first argument,
            // so only the declared parameters are matched against the
            // arguments.
            val cls = classOf(callee)
            callee.owner = cls
            val method = cls.methods.firstOrNull { it.name == callee.name }
                ?: throw SemaException("Unknown member ${callee.name}")
            checkCallArgs(expr, callee.name, method.params)
            expr.type = method.retType ?: Types.unit
            return expr.type
        }
        if (callee !is VarRef) {
            throw SemaException("Unsupported call target")
        }
```

Add the `MemberAccess` and `ThisExpr` branches to `checkExpr`
(`TypeChecker.kt:570`):

```kotlin
        is MemberAccess -> checkMemberAccess(expr)
        is ThisExpr -> checkThis(expr)
```

- [ ] **Step 6: Handle `this` and the receiver**

Add to `TypeChecker`'s state (near lines 19-22):

```kotlin
    /** The receiver of the method being checked, for `this`. */
    private var currentReceiver: ClassDecl? = null
```

Add:

```kotlin
    /**
     * `this` — the receiver of the enclosing method.
     */
    private fun checkThis(expr: ThisExpr): WolinType {
        val receiver = currentReceiver
            ?: throw SemaException("this is not allowed here")
        expr.type = Types.classType(receiver.name)
        return expr.type
    }
```

`checkFunction` (`TypeChecker.kt:243`) must set `currentReceiver` for a
method and clear it afterwards. Change its signature to take the receiver:

```kotlin
    private fun checkFunction(
        fn: FunDecl,
        globals: Scope,
        receiver: ClassDecl? = null,
    ) {
```

and inside it, where the function scope is set up (line 254-256), add:

```kotlin
        val outerReceiver = currentReceiver
        currentReceiver = receiver
```

and in the `finally` block that restores the scope (line 263-267), add:

```kotlin
            currentReceiver = outerReceiver
```

`checkClass` (Task 3) passes the class as the receiver:

```kotlin
        cls.methods.forEach { m -> checkFunction(m, classScope, receiver = cls) }
```

- [ ] **Step 7: Reject assigning to a class value**

In `checkAssign` (`TypeChecker.kt:725`), the `VarRef` branch resolves the
target's symbol. Add, after the `data.isVal` check:

```kotlin
                if (data.type.kind == TypeKind.CLASS) {
                    throw SemaException("Cannot assign to a class value")
                }
```

- [ ] **Step 8: Re-pin the rejection catalogue**

In `src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt`:

- **Delete** the entry at lines 34-36:

```kotlin
    @Test fun `classes are rejected`() {
        assertRejected("class C { }")
    }
```

- **Replace** the entry at lines 98-101:

```kotlin
    @Test fun `this and super are rejected`() {
        assertRejected("fun f() { val x = this }")
        assertRejected("fun f() { val x = super.foo() }")
    }
```

with:

```kotlin
    @Test fun `super is rejected`() {
        assertRejected("fun f() { val x = super.foo() }")
    }
```

- **Replace** the entry at lines 167-169:

```kotlin
    @Test fun `an unknown member call stays rejected`() {
        assertRejected("fun f() { var b: byte = 1; var x = b.other() }")
    }
```

with:

```kotlin
    @Test fun `an unknown member call is rejected`() {
        assertFailsWith<SemaException> { check("fun f() { var b: byte = 1; var x = b.other() }") }
    }
```

- **Add** the new catalogue entries, next to the class entry's old position:

```kotlin
    @Test fun `interfaces are rejected`() {
        assertRejected("interface I { }")
    }

    @Test fun `enum classes are rejected`() {
        assertRejected("enum class E { }")
    }

    @Test fun `companion objects are rejected`() {
        assertRejected("class C { companion object { } }")
    }

    @Test fun `constructors are rejected`() {
        assertRejected("class C { constructor(x: ubyte) { } }")
    }

    @Test fun `init blocks are rejected`() {
        assertRejected("class C { init { } }")
    }

    @Test fun `a class with a supertype is rejected`() {
        assertRejected("class C : D { }")
    }
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.sema.ClassObjectCheckerTest' --tests 'pl.qus.wolin.sema.UnsupportedTest'`
Expected: PASS — the new checker tests green and the re-pinned catalogue green.

- [ ] **Step 10: Commit**

```bash
d = await commit.draft()
await d.commit("feat(sema): member resolution this and method calls", [
  "The checker resolves receiver.member against the receiver's class,",
  "this names the receiver, and a method call checks its arguments.",
])
```

---

### Task 5: The emitter — the blob, the fields, and the methods

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt:36-56` (`VarStorage`), `:111-129` (`emit`), `:179-225` (`emitVarRef`/`loadVar`/`storeVar`/`fixedPtr`), `:350-380` (`emitAssign`), `:494-522` (`emitCall`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt:71-112` (`startFunction`), `:186-212` (`emitLocalDecl`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt:51-100` (`emitFile`), `:103-132` (`emitGlobals`)
- Modify: `src/main/kotlin/pl/qus/wolin/ir/TypeMapper.kt`
- Test: `src/test/resources/golden/class_object.wtk` + `.ll` (new), `src/test/kotlin/pl/qus/wolin/ir/GoldenIrTest.kt:13` (add the entry), `src/test/kotlin/pl/qus/wolin/ir/ClassObjectIrTest.kt` (new)

**Interfaces:**
- Consumes: `Ast.ClassDecl` with `size` and `fieldOffsets` (Task 3), `Ast.MemberAccess` (Task 1).
- Produces:
  - `VarStorage.ClassGlobal(name: String)`, `VarStorage.ClassLocal(ptr: IrValue)`, `VarStorage.ClassFixed(address: Int)` — three new storage cases.
  - `ExprEmitter.emitClassBase(storage: VarStorage): IrValue` — the instance's base pointer.
  - `ExprEmitter.emitFieldAccess(storage: VarStorage, field: GlobalDecl, offset: Int): IrValue` — a field read.
  - `ExprEmitter.emitFieldStore(storage: VarStorage, field: GlobalDecl, offset: Int, value: IrValue)` — a field write.
  - `ExprEmitter.emitMethodCall(cls: ClassDecl, method: FunDecl, receiver: IrValue, args: List<IrValue>): IrValue`.

- [ ] **Step 1: Write the failing IR test**

Create `src/test/kotlin/pl/qus/wolin/ir/ClassObjectIrTest.kt`:

```kotlin
package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.it.emitIr
import kotlin.test.assertTrue

class ClassObjectIrTest {
    @Test fun `a global instance is a data section blob`() {
        val ir = emitIr(
            """
            class Foo {
                var x: ubyte = 1
                var y: uword
            }
            var g: Foo
            """.trimIndent()
        )
        assertTrue(ir.contains("\"t.g\" = global [4 x i8]"), ir)
    }

    @Test fun `a field read is a byte GEP into the blob`() {
        val ir = emitIr(
            """
            class Foo {
                var x: ubyte = 1
            }
            fun f() {
                var g: Foo
                val v = g.x
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("getelementptr i8, ptr @\"t.g\", i32 0"), ir)
    }

    @Test fun `a memory attached field is a direct access at its address`() {
        val ir = emitIr(
            """
            class Vic {
                var border: ubyte^0xd020
            }
            var vic: Vic^0xd000
            fun f() {
                vic.border = 6
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("inttoptr i64 53280 to i8*"), ir)
        assertTrue(ir.contains("store volatile i8"), ir)
        // No indirection through the anchor.
        assertTrue(!ir.contains("inttoptr i64 53248 to i8*"), ir)
    }

    @Test fun `a method takes the receiver as its first argument`() {
        val ir = emitIr(
            """
            class Foo {
                var x: ubyte = 1
                fun get(): ubyte { return x }
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("define i8 @\"t.Foo.get\"(ptr %this)"), ir)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.ClassObjectIrTest'`
Expected: FAIL — the IR has no instance blob and no field access.

- [ ] **Step 3: Add the storage cases**

In `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt`, add to the `VarStorage`
sealed interface (after `CpuFlag`, line 53):

```kotlin
    /** A class instance in the data section: the module global's name. */
    data class ClassGlobal(val name: String) : VarStorage

    /** A class instance on the stack: the entry-block `alloca`. */
    data class ClassLocal(val ptr: IrValue) : VarStorage

    /**
     * A memory-attached class instance: the anchor address.
     *
     * The instance has no storage of its own. The anchor is only the base
     * the plain fields' offsets would be measured from — and a
     * memory-attached instance may not have a plain field, so the anchor is
     * never used to address anything.
     */
    data class ClassFixed(val address: Int) : VarStorage

    /**
     * A class field, reached through the receiver.
     *
     * A field's storage is at the receiver's base plus the field's offset,
     * so the field's declaration and its offset are both needed.
     */
    data class ClassField(
        val field: GlobalDecl,
        val offset: Int,
    ) : VarStorage
```

- [ ] **Step 4: Emit the instance blob**

In `src/main/kotlin/pl/qus/wolin/ir/StmtEmitter.kt`, `startFunction` (line 71)
binds the file's globals to their storage (around lines 98-106). A global
whose type is a class is an instance, not a plain global:

```kotlin
            else -> {
                val storage = when (val loc = g.location) {
                    is FixedAddress -> VarStorage.Fixed(loc.address)
                    is FixedArray -> VarStorage.Fixed(loc.address)
                    is CpuRegister -> VarStorage.CpuFlag(loc.name)
                    else -> VarStorage.Global("$prefix.${g.name}")
                }
                vars.declare(g.name, storage)
            }
```

becomes

```kotlin
            else -> {
                val storage = when (val loc = g.location) {
                    is FixedAddress ->
                        if (g.type.kind == TypeKind.CLASS) {
                            VarStorage.ClassFixed(loc.address)
                        } else {
                            VarStorage.Fixed(loc.address)
                        }
                    is FixedArray -> VarStorage.Fixed(loc.address)
                    is CpuRegister -> VarStorage.CpuFlag(loc.name)
                    else ->
                        if (g.type.kind == TypeKind.CLASS) {
                            VarStorage.ClassGlobal("$prefix.${g.name}")
                        } else {
                            VarStorage.Global("$prefix.${g.name}")
                        }
                }
                vars.declare(g.name, storage)
            }
```

**An `object` declaration has a global instance named after the object.**
It has no `GlobalDecl` of its own, so it is declared separately:

```kotlin
        // An `object` declaration has a global instance, named after the
        // object itself.
        for (cls in classes.filter { it.isObject }) {
            vars.declare(cls.name, VarStorage.ClassGlobal("$prefix.${cls.name}"))
        }
```

`StmtEmitter.startFunction` needs the program's classes for this, so it
gains a `classes: List<ClassDecl> = emptyList()` parameter.

In `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt`, `emitGlobals`
(line 103) declares the module globals. A class instance is a byte blob of
the class size; add a branch before the array branch:

```kotlin
            if (g.type.kind == TypeKind.CLASS) {
                val cls = classDeclOf(g.type)
                val size = cls.size.coerceAtLeast(1)
                constants += "@\"$prefix.${g.name}\" = global [$size x i8] zeroinitializer"
                continue
            }
```

and the objects' blobs:

```kotlin
        for (cls in program.classes.filter { it.isObject }) {
            val size = cls.size.coerceAtLeast(1)
            constants += "@\"$prefix.${cls.name}\" = global [$size x i8] zeroinitializer"
        }
```

`ModuleEmitter` needs the program's classes: add a
`classes: List<ClassDecl> = emptyList()` parameter to `emitFile`, and pass
`program.classes` from `Main.emitFiles` (`Main.kt:206`) — the `Ast.Program`
rebuilt there gains `classes = scope.classes`.

The class a class-typed declaration names is found by name:

```kotlin
    /**
     * The class a class-typed declaration names.
     */
    private fun classDeclOf(type: WolinType): ClassDecl {
        val name = type.className ?: throw error("Class type $type has no name")
        return classes.firstOrNull { it.name == name }
            ?: throw error("Unknown class $name")
    }
```



- [ ] **Step 5: Emit the field access**

In `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt`, add:

```kotlin
    /**
     * The base pointer of a class instance: the module global, the stack
     * slot, or the anchor of a memory-attached instance.
     */
    private fun emitClassBase(storage: VarStorage): IrValue = when (storage) {
        is VarStorage.ClassGlobal -> IrValue("@${storage.name}", IrType.Ptr)
        is VarStorage.ClassLocal -> storage.ptr
        is VarStorage.ClassFixed -> fixedPtr(storage.address, IrType.I8)
        else -> throw error("Not a class instance: $storage")
    }

    /**
     * The address of a field inside an instance.
     *
     * An attached field is at its own address, exactly like a fixed-address
     * variable — the instance does not come into it, and the field's offset
     * does not apply to it.
     *
     * A plain field is at the instance's base plus its offset. The base is
     * first taken to its first byte, then the offset is applied with the
     * field's own element type, so a `uword` at an odd offset is an
     * unaligned 16-bit access — which the 6502 has.
     */

    private fun fieldAddress(storage: VarStorage, field: GlobalDecl, offset: Int): IrValue {
        return when (val loc = field.location) {
            is FixedAddress -> fixedPtr(loc.address, IrType.I8)
            else -> {
                val base = emitClassBase(storage)
                b.instr(IrType.Ptr, "getelementptr i8, ptr ${base.name}, i16 $offset")
            }
        }
    }


    /** A field read: the field's value. */
    private fun emitFieldRead(storage: VarStorage, field: GlobalDecl, offset: Int): IrValue {
        val ty = TypeMapper.ir(field.type)
        val element = if (ty == IrType.I16) "i16" else "i8"
        val p = fieldAddress(storage, field, offset)
        // An attached field is a hardware register: the access is volatile,
        // exactly like a fixed-address variable's.
        val vol = if (field.location is FixedAddress) "volatile " else ""
        return b.instr(ty, "load $vol$element, ptr ${p.name}")
    }

    /** A field write: the field's value is stored. */
    private fun emitFieldWrite(storage: VarStorage, field: GlobalDecl, offset: Int, value: IrValue) {
        val ty = TypeMapper.ir(field.type)
        val element = if (ty == IrType.I16) "i16" else "i8"
        val p = fieldAddress(storage, field, offset)
        val vol = if (field.location is FixedAddress) "volatile " else ""
        b.instr(IrType.Void, "store $vol$element ${value.name}, ptr ${p.name}")
    }
```

**Why the two-step GEP.** `getelementptr i8, ptr %base, i32 0` takes the
**Why a byte GEP.** Field offsets are byte offsets, so the GEP element type is `i8`. The form `getelementptr i8, ptr %base, i16 $offset` computes the address of the byte at offset `$offset`. The backend folds this into a direct `lda`/`sta` with an offset — the same shape the array GEP (`ExprEmitter.kt:444`) already produces.

**Why offset `i16`.** GEP index is `i16`, like the array GEP's. Offset is a compile-time constant, so it is spelled directly.


**An attached field's offset is never used.** Its address is absolute, so
nothing is added to it. This is why a memory-attached instance may not have
a plain field: a plain field's storage would have to be "inside" an
instance that has no inside.

- [ ] **Step 6: Wire the member access into the emitter**

**The emitter needs the class declaration** — for the layout and for the
field list. The cleanest route: the checker stores the resolved class on the
`MemberAccess` node, so the emitter reads it directly.

In `src/main/kotlin/pl/qus/wolin/ast/Ast.kt`, add to `MemberAccess`:

```kotlin
    /** The class the member belongs to, filled by the checker. */
    var owner: ClassDecl? = null,
```

The checker already sets it: Task 4's `checkMemberAccess` and `checkCall`
both assign `expr.owner = cls`.

In `src/main/kotlin/pl/qus/wolin/ir/ExprEmitter.kt`, add the `MemberAccess`
branch to `emit` (line 111):

```kotlin
        is MemberAccess -> emitMemberAccess(e)
```

and add:

```kotlin
    /**
     * `receiver.member` — a field read.
     *
     * A method call is handled by [emitCall], which passes the receiver as
     * the method's first argument.
     */
    private fun emitMemberAccess(e: MemberAccess): IrValue {
        val storage = storageOf(e.receiver)
        val owner = e.owner ?: throw error("Member access has no owner")
        val field = owner.fields.firstOrNull { it.name == e.name }
            ?: throw error("Unknown member ${e.name}")
        val offset = owner.fieldOffsets[e.name] ?: 0
        return emitFieldRead(storage, field, offset)
    }

    /**
     * The storage a class-value expression names.
     *
     * `this` is the receiver of the method being emitted: the method's
     * first parameter is the receiver, and [StmtEmitter] declares it under
     * the name `this`.
     */
    private fun storageOf(expr: Expr): VarStorage = when (expr) {
        is ThisExpr -> stmt.vars.lookup("this")
            ?: throw error("this outside a method")
        is VarRef -> stmt.vars.lookup(expr.name)
            ?: throw error("Unresolved variable ${expr.name}")
        else -> throw error("Unsupported member access receiver")
    }
```

**No extra emitter state is needed for the receiver.** `this` resolves
through the ordinary variable scope: the method's first parameter is
declared under the name `this` when the method's body is emitted (Step 9),
so `stmt.vars.lookup("this")` finds it. The scope is popped when the
method ends, so a `this` outside a method cannot resolve.

- [ ] **Step 7: Wire the field write into the assignment**

In `ExprEmitter.emitAssign` (line 350), add a `MemberAccess` branch to the
`when (val target = a.target)`:

```kotlin
            is MemberAccess -> {
                val storage = storageOf(target.receiver)
                val owner = target.owner ?: throw error("Member access has no owner")
                val field = owner.fields.firstOrNull { it.name == target.name }
                    ?: throw error("Unknown member ${target.name}")
                val offset = owner.fieldOffsets[target.name] ?: 0
                val ty = TypeMapper.ir(a.type)
                val v = widenIfNeeded(emit(a.value), a)
                if (a.op == "=") {
                    emitFieldWrite(storage, field, offset, v)
                    return v
                }
                val t = emitFieldRead(storage, field, offset)
                val r = compoundResult(a, t, v)
                emitFieldWrite(storage, field, offset, r)
                r
            }
```

- [ ] **Step 8: Emit the method call**

In `ExprEmitter.emitCall` (line 494), the callee is currently required to be
a `VarRef`. Add a `MemberAccess` branch:

```kotlin
        val callee = e.callee
        if (callee is MemberAccess) {
            val owner = callee.owner ?: throw error("Member call has no owner")
            val method = owner.methods.firstOrNull { it.name == callee.name }
                ?: throw error("Unknown member ${callee.name}")
            val storage = storageOf(callee.receiver)
            val base = emitClassBase(storage)
            val args = e.args.map { emit(it) }
            val retType = TypeMapper.ir(e.type)
            return b.call(retType, "${owner.prefix}.${owner.name}.${method.name}", listOf(base) + args)
        }
        if (callee !is VarRef) throw error("Unsupported call target")
```

The method's name is `"<prefix>.<class>.<method>"` — the class is a new
level in the mangling. The receiver is passed as the first argument, so the
method's IR signature is `define i8 @"pkg.Foo.sum"(ptr %this)`.

**The prefix.** `owner.prefix` is the mangled prefix of the file that
declares the class, which the checker stores in `Symbol.ClassSymbol.prefix`.
The `MemberAccess` needs it, so `Ast.ClassDecl` carries it:

```kotlin
    /** The mangled prefix of the file that declares the class. */
    var prefix: String = "",
```

and the checker sets it when it declares the class symbol (Task 3).

- [ ] **Step 9: Emit the method functions**

In `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt`, `emitFile` (line 51)
emits the file's functions. A class's methods are emitted after them, so a
method is never emitted while another function is open. Add, after the
functions loop (around line 87):

```kotlin
        // The class methods are emitted after the file's functions, because
        // a module holds one function open at a time.
        for (cls in program.classes) {
            for (method in cls.methods) {
                val params = listOf(IrValue("%this", IrType.Ptr)) +
                    method.params.map { IrValue("%${it.name}", TypeMapper.ir(it.type)) }
                b.startFunction(
                    "$mangledPrefix.${cls.name}.${method.name}",
                    TypeMapper.ir(method.retType ?: Types.unit),
                    params,
                )
                stmt.startFunction(
                    mangledPrefix, params, program.globals, method, program.visibleGlobals,
                    classDecl = cls,
                )
                b.endFunction()
            }
        }
```

The method's name is `"<prefix>.<class>.<method>"`. It is quoted by the
emitter (`IrBuilder.quoteIfNeeded`), and a dotted name is quoted, so
`@"pkg.Foo.sum"` is valid IR. (Verified: a quoted global name with dots
compiles and links on the MOS backend.)

**The receiver is the method's first parameter, named `this`.**
`StmtEmitter.startFunction` declares the parameters in the variable scope
(`params.forEach { vars.declare(...) }`), so `%this` is declared under the
name `this` — which is exactly what `this.x` in the body resolves
against. The scope is popped when the method ends, so a `this` outside a
method cannot resolve.

**The method's body sees the class's fields.** The fields are not among
the file's globals, so `StmtEmitter.startFunction` declares them. It gains
a `classDecl` parameter:

```kotlin
    fun startFunction(
        prefix: String,
        params: List<IrValue>,
        globals: List<GlobalDecl>,
        fn: FunDecl,
        foreignGlobals: List<ForeignGlobal> = emptyList(),
        /** The class whose method's body is being emitted, if any. */
        classDecl: ClassDecl? = null,
    ) {
```

with, next to the parameter declarations:

```kotlin
        classDecl?.fields?.forEach { f ->
            val offset = classDecl.fieldOffsets[f.name] ?: 0
            vars.declare(f.name, VarStorage.ClassField(f, offset))
        }
```

A field is not a variable — it lives inside an instance, so its storage is
not a plain slot. `VarStorage.ClassField` carries the field's declaration
and its offset.

**But a bare field name inside a method is `this.field`, not a plain
variable.** The scope entry above declares the field under its own name,
so `x` in a method's body resolves to it — and the emitter must read it
through the receiver, not as a plain slot. `ExprEmitter.loadVar` and
`storeVar` gain a `ClassField` case that reads through the receiver:

```kotlin
    private fun loadVar(s: VarStorage, ty: IrType): IrValue = when (s) {
        is VarStorage.Param -> s.value
        is VarStorage.Global -> b.instr(ty, "load ${ty.llvmName()}, ptr @${s.name}")
        is VarStorage.Local -> b.instr(ty, "load ${ty.llvmName()}, ptr ${s.ptr.name}")
        is VarStorage.Fixed -> b.instr(ty, "load ${ty.llvmName()}, ptr ${fixedPtr(s.address, ty).name}")
        is VarStorage.CpuFlag -> CpuAccessors.flagRead(b, s.reg)
        is VarStorage.ClassField -> {
            val receiver = stmt.vars.lookup("this")
                ?: throw error("A class field outside a method")
            emitFieldRead(receiver, s.field, s.offset)
        }
        ...
    }
```

The `...` stands for the new `ClassGlobal`, `ClassLocal` and `ClassFixed`
cases, which are not plain loads either — a class instance has no value.
They are only ever addressed, so they throw:

```kotlin
        is VarStorage.ClassGlobal, is VarStorage.ClassLocal, is VarStorage.ClassFixed ->
            throw error("A class instance has no value")
```

**A method's own receiver is the `this` parameter**, so `loadVar` on it is
not needed — the receiver is a pointer, never loaded.

- [ ] **Step 10: Emit a local instance**

In `StmtEmitter.startFunction`, the locals get their stack slots (around
line 110). A local class instance needs an `alloca` of the class size:

```kotlin
        decls.filter { it.location !is FixedAddress && it.location !is FixedArray && it.location !is CpuRegister }
             .forEach { localSlots[it] = b.alloca(TypeMapper.ir(it.type)) }
```

becomes

```kotlin
        decls.filter { it.location !is FixedAddress && it.location !is FixedArray && it.location !is CpuRegister }
             .forEach { local ->
                 localSlots[local] = if (local.type.kind == TypeKind.CLASS) {
                     b.alloca(IrType.Array(IrType.I8, localClassSize(local.type)))
                 } else {
                     b.alloca(TypeMapper.ir(local.type))
                 }
             }
```

`IrType.Array` exists (`ir/IrType.kt:16`) and `llvmName()` spells it
`[N x i8]`. `IrBuilder.value` and `IrBuilder.phi` reject it — they need a
*value*, and an array has none — but `alloca` only needs the spelling, so
`b.alloca(IrType.Array(...))` emits `alloca [N x i8]`, which is what the
backend wants for a blob on the stack.

`localClassSize` is a helper that finds the class's size:

```kotlin
    /** The size in bytes of a class-typed local's instance. */
    private fun localClassSize(type: WolinType): Int =
        classes.firstOrNull { it.name == type.className }?.size?.coerceAtLeast(1) ?: 1
```

The classes come from the program; `StmtEmitter` gets them from the
`classes` parameter added in Step 4.

In `StmtEmitter.emitLocalDecl` (line 186), the local's storage is bound
(line 206-211). Add the class branch:

```kotlin
            else -> {
                val ptr = localSlots[d] ?: throw error("Local ${d.name} has no slot")
                if (d.type.kind == TypeKind.CLASS) {
                    // A local instance's fields are initialised where the
                    // instance is declared.
                    vars.declare(d.name, VarStorage.ClassLocal(ptr))
                    return
                }
                d.init?.let { init ->
                    val v = expr.emit(init)
                    b.instr(IrType.Void, "store ${TypeMapper.ir(d.type).llvmName()} ${v.name}, ptr ${ptr.name}")
                }
                vars.declare(d.name, VarStorage.Local(ptr))
            }
```

A local class instance's field initialisers are emitted by the class
emitter: the fields' initialisers are folded and stored into the blob at
the declaration point.


- [ ] **Step 11: Run the IR test to verify it passes**

Run: `./gradlew test --tests 'pl.qus.wolin.ir.ClassObjectIrTest'`
Expected: PASS — all four tests green.

- [ ] **Step 12: Add the golden pair**

Create `src/test/resources/golden/class_object.wtk`:

```kotlin
class Foo {
    var x: ubyte = 1
    var y: uword
    var reg: ubyte^0xd020

    fun get(): ubyte {
        return x
    }
}

var g: Foo
var vic: Vic^0xd000

class Vic {
    var border: ubyte^0xd020
}

fun main() {
    g.x = 2
    while (true) {}
}
```

Create `src/test/resources/golden/class_object.ll` as a placeholder, add
`"class_object"` to the `@ValueSource` on `GoldenIrTest.kt:13`, and run:

```bash
java -cp "$(./gradlew -q printClasspath)" pl.qus.wolin.driver.MainKt \
    -e --keep-ir src/test/resources/golden/class_object.wtk
```

Copy the emitted IR into the `.ll`, then run
`./gradlew test --tests 'pl.qus.wolin.ir.GoldenIrTest'` and expect PASS.

- [ ] **Step 13: Commit**

```bash
d = await commit.draft()
await d.commit("feat(ir): class instances field access and methods", [
  "An instance is a byte blob; a field access is a GEP into it; an",
  "attached field is a direct access at its own address; a method takes",
  "the receiver as its first argument.",
])
```

---

### Task 6: The object singleton, the field initialisers, and the end-to-end tests

**Files:**
- Modify: `src/main/kotlin/pl/qus/wolin/ir/ModuleEmitter.kt:51-100` (`emitFile`)
- Modify: `src/main/kotlin/pl/qus/wolin/sema/TypeChecker.kt` (the object's instance)
- Modify: `src/test/resources/corpus/objects.ktk`
- Test: `src/test/kotlin/pl/qus/wolin/it/ClassObjectTest.kt` (new)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: the object singleton's global instance; the field initialisers of a global instance as static data.

- [ ] **Step 1: Write the failing end-to-end tests**

Create `src/test/kotlin/pl/qus/wolin/it/ClassObjectTest.kt`:

```kotlin
package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Classes and objects on the real 6502: the instance layout, the field
 * offsets, and the memory-attached access.
 */
class ClassObjectTest {
    @Test fun `a global instance's fields hold their own values`() {
        val bytes = runProgram("""
            class Foo {
                var a: ubyte = 11
                var b: ubyte = 22
            }
            var g: Foo
            fun main() {
                var out: ubyte^0x400 = 0
                out = g.a
                while (true) {}
            }
        """)
        assertEquals(11, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a local instance's fields are writable`() {
        val bytes = runProgram("""
            class Foo {
                var a: ubyte = 0
                var b: uword = 0
            }
            fun main() {
                var l: Foo
                l.a = 42
                l.b = 300
                var out: ubyte^0x400 = 0
                out = l.a
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a uword field after a ubyte field keeps its value`() {
        val bytes = runProgram("""
            class Foo {
                var a: ubyte = 0
                var b: uword = 0
            }
            fun main() {
                var l: Foo
                l.b = 4660
                var out: ubyte^0x400 = 0
                out = l.b.toUbyte()
                while (true) {}
            }
        """)
        assertEquals(0x34, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a memory attached field is written at its own address`() {
        val bytes = runProgram("""
            class Vic {
                var border: ubyte^0xd020
            }
            var vic: Vic^0xd000
            fun main() {
                vic.border = 6
                var out: ubyte^0x400 = 0
                out = vic.border
                while (true) {}
            }
        """)
        assertEquals(6, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a method writes the receiver's field`() {
        val bytes = runProgram("""
            class Foo {
                var count: ubyte = 0
                fun bump() {
                    count++
                }
            }
            var g: Foo
            fun main() {
                g.bump()
                g.bump()
                var out: ubyte^0x400 = 0
                out = g.count
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }

    @Test fun `an object's state survives across calls`() {
        val bytes = runProgram("""
            object Counter {
                var count: ubyte = 0
                fun inc(): ubyte {
                    count++
                    return count
                }
            }
            fun main() {
                val a = Counter.inc()
                val b = Counter.inc()
                var out: ubyte^0x400 = 0
                out = b
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'pl.qus.wolin.it.ClassObjectTest'`
Expected: FAIL — the object singleton and the field initialisers are not
emitted yet.

- [ ] **Step 3: Emit the object singleton**

An `object Foo { … }` declares a class `Foo` **and** a global instance
named `Foo`. The instance's module global is named `"<prefix>.Foo"`.

Task 5, Step 4 already emits the blob and declares the storage; what is
left here is the *checker* side: the object's name must resolve to the
class, so `Counter.inc()` finds the method.

In `TypeChecker.checkFile`, the classes are declared in the file scope
(Task 3). An object's instance is reached through the same name, so nothing
more is needed there — `lookupClass` finds the `ClassSymbol` either way.

**But the object's instance must be initialised.** The object's fields'
initialisers are folded like any other class's (Step 4), and its blob is
static data (Task 5, Step 4).

**The object's field access goes through the receiver.** `Counter.count` —
the receiver is a `VarRef` naming the object, and `storageOf` resolves it
through the variable scope, where Task 5, Step 4 declared it as
`ClassGlobal`.

**A method of an object mutates the singleton's fields.** `count++` inside
`inc` is a write to a `ClassField`, which `loadVar`/`storeVar` route
through the receiver — the `this` parameter, which points at the
singleton's blob. (Task 5, Step 9.)


- [ ] **Step 4: Emit the field initialisers**

A **global** instance's field initialisers are constant expressions, so
they become the blob's static data:

```
@"t.g" = global [4 x i8] [i8 1, i8 0, i8 0, i8 0]
```

The checker folds them. In `checkClass` (Task 3), after the fields are
declared, fold each field's initialiser:

```kotlin
        cls.fields.forEach { f ->
            f.init?.let { init ->
                f.constValue = ConstFolder.fold(init, emptyMap(), emptyMap())
                    ?: throw SemaException("global initialiser must be a constant expression")
            }
        }
```

`GlobalDecl.constValue` is the field the checker already fills for a
global's initialiser, so a class field reuses it.

In `ModuleEmitter.emitGlobals`, the class branch (added in Task 5, Step 4)
uses the folded values:

```kotlin
            if (g.type.kind == TypeKind.CLASS) {
                val cls = classDeclOf(g.type)
                val size = cls.size.coerceAtLeast(1)
                constants += "@\"$prefix.${g.name}\" = global [$size x i8] ${classBlobBody(cls)}"
                continue
            }
```

with:

```kotlin
    /**
     * The static data of an instance's blob: each plain field's initial
     * value at its offset, zero elsewhere.
     *
     * A field initialiser is a constant expression, so the checker folds
     * it and the blob is static data.
     */
    private fun classBlobBody(cls: ClassDecl): String {
        val bytes = IntArray(cls.size.coerceAtLeast(1))
        cls.fields.forEach { f ->
            if (f.location is FixedAddress) return@forEach
            val offset = cls.fieldOffsets[f.name] ?: return@forEach
            val value = (f.constValue as? ConstValue.IntValue)?.bits?.toInt() ?: 0
            when (sizeOf(f.type)) {
                1 -> bytes[offset] = value and 0xFF
                2 -> {
                    bytes[offset] = value and 0xFF
                    bytes[offset + 1] = (value shr 8) and 0xFF
                }
                4 -> {
                    bytes[offset] = value and 0xFF
                    bytes[offset + 1] = (value shr 8) and 0xFF
                    bytes[offset + 2] = (value shr 16) and 0xFF
                    bytes[offset + 3] = (value shr 24) and 0xFF
                }
            }
        }
        return "[" + bytes.joinToString(", ") { "i8 $it" } + "]"
    }
```

**A local** instance's field initialisers are runtime stores at the point
of declaration. In `StmtEmitter.emitLocalDecl`, the class branch (Task 5,
Step 10) stores each field's initial value into the blob:

```kotlin
                if (d.type.kind == TypeKind.CLASS) {
                    vars.declare(d.name, VarStorage.ClassLocal(ptr))
                    emitLocalClassInit(d, ptr)
                    return
                }
```

with:

```kotlin
    /**

    /**
     * The field initialisers of a local instance: each plain field's value
     * is stored into the blob at the point of declaration.
     */
    private fun emitLocalClassInit(d: LocalDecl, ptr: IrValue) {
        val cls = classes.firstOrNull { it.name == d.type.className } ?: return
        cls.fields.forEach { f ->
            if (f.location is FixedAddress) return@forEach
            val offset = cls.fieldOffsets[f.name] ?: return@forEach
            val init = f.init ?: return@forEach
            val v = expr.emit(init)
            val element = if (TypeMapper.ir(f.type) == IrType.I16) "i16" else "i8"
            val p = b.instr(IrType.Ptr, "getelementptr i8, ptr ${ptr.name}, i16 $offset")
            b.instr(IrType.Void, "store $element ${v.name}, ptr ${p.name}")
        }
    }

```


- [ ] **Step 5: Update the corpus file**

`src/test/resources/corpus/objects.ktk` currently reads:

```kotlin
class SummingClass {
    var x: ubyte = 1
    var y: ubyte = 1

    fun sum(): ubyte {
        return x+y
    }
}

fun main() {
    val obj: SummingClass

    obj = SummingClass()

    obj.x = 2
    obj.y = 4

    border = 30000
}
```

Replace the constructor call with the new syntax — a declaration creates the
instance:

```kotlin
package pl.qus.wolin

var border: ubyte^53280

class SummingClass {
    var x: ubyte = 1
    var y: ubyte = 1

    fun sum(): ubyte {
        return x + y
    }
}

var obj: SummingClass

fun main() {
    obj.x = 2
    obj.y = 4

    border = obj.sum()

    while (true) {}
}
```

The file stays a parse fixture (`CorpusParseTest`), and the class
declaration keeps it valid.

- [ ] **Step 6: Run the end-to-end tests to verify they pass**

Run: `./gradlew test --tests 'pl.qus.wolin.it.ClassObjectTest'`
Expected: PASS — all six tests green.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: PASS — no test regressed. The `UnsupportedTest` re-pinning
(Task 4) and the golden pair (Task 5) are included.

- [ ] **Step 8: Update the documentation**

In `REFERENCE.md`, add a section "## Classes and objects" after the
"## Arrays" section, documenting:

- the syntax of `class` and `object`;
- that a declaration creates the instance (global, local, memory-attached);
- the field kinds (plain, attached) and the layout rules (declaration
  order, 1-byte alignment, size rounded up to even);
- that a memory-attached instance may not have a plain field;
- that a method takes the receiver as its first argument, and `this` names
  it;
- what is **not** supported: inheritance, `interface`, `enum class`,
  `companion object`, `constructor`, `init { }`, `super`, generics, `is`,
  nullable class types, and assigning to a class value.

In `TODO.md`, move "**Classes and objects** — needs a struct layout, an
object model, and a dispatch decision." out of the "Design-level v2 items"
list, and record what landed and what is still deferred (inheritance,
dynamic allocation, `is` tests).

In `README.md`, add a line to the "The language, v1" section:

```markdown
### Classes and objects

A `class` is a layout: named fields and named methods. A declaration
creates the instance — `var g: Foo` is a global, `var l: Foo` is a local,
and `var m: Foo^0x3000` is a memory-attached instance whose fields are
the hardware registers they name. See
[REFERENCE.md](REFERENCE.md#classes-and-objects).
```

- [ ] **Step 9: Commit**

```bash
d = await commit.draft()
await d.commit("feat: object singleton field initialisers and the class tests", [
  "An object's state survives across calls; a global instance's field",
  "initialisers are static data; a local instance's are stores at the",
  "declaration. The corpus file and the docs are updated.",
])
```

---

## Self-review

**Spec coverage.** §1 intent → Task 5 (the memory-attached form is the point
of the feature). §2 goals → Tasks 1-6. §3 non-goals → the rejection
catalogue in Task 4. §4 the decision → Task 3 (a declaration creates the
instance) and Task 6 (the object singleton). §5 the rejected approaches →
no task. §6 the layout → Task 3 (offsets, size) and Task 5 (the blob, the
GEPs). §7 the storage kinds → Task 5 (global, local, memory-attached) and
Task 6 (the object). §8 the syntax → Task 1. §9 the type system → Task 2.
§10 the checker → Tasks 3 and 4. §11 the emitter → Task 5. §12 the
diagnostics → Task 4. §13 the order → the six tasks. §14 the verification →
Tasks 5 and 6. §15 the risks → the Review Focus. §16 the relation to the
old Wolin → no task (documented, not implemented).

**Step scan.** Every step names a file, a function, a signature, or a test
name. No step says "handle the edge cases" or "write appropriate tests".

**Type consistency.** `Ast.ClassDecl(name, fields, methods, isObject, size,
fieldOffsets)` is used identically in Tasks 1, 3, 5, 6. `Symbol.ClassSymbol(decl,
prefix)` in Tasks 2, 3, 4. `VarStorage.ClassGlobal` / `ClassLocal` /
`ClassFixed` in Task 5. `Types.classType(name)` and `sizeOf` in Task 2 and
used in Task 3.

**Review Focus.** All ten lines have a home: 1 → Task 5's `uword` test; 2
→ Task 5's `ClassObjectIrTest`; 3 → Task 3's layout test; 4 → Task 3's
attached-field test; 5 → Task 6's method test; 6 → Task 6's object test; 7
→ Task 6's local test; 8 → Task 4's catalogue re-pin; 9 → Task 4's split;
10 → Task 2's import test.

**Proportion.** The plan is longer than the spec's implementation surface,
because the compiler has no class support at all and every layer needs its
own change. The code blocks are the signatures and the test bodies — the
bodies the implementer would otherwise have to guess.
