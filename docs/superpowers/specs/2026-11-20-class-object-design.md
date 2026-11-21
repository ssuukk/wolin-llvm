# Classes and Objects — Design

**Status:** proposed, not approved.
**Date:** 2026-11-20
**Scope:** `class`, `object`, class fields, class methods, and the three
storage kinds an instance can live in — global, local, and *memory-attached*
(fixed address).

---

## 1. Intent

Wolin v2 must grow the "introductory OO" of the original Wolin: classes with
constructors and methods (the old compiler's README lists it as feature #13),
plus the memory-attached form that the rest of this compiler already has for
variables (`var x: ubyte^0xd020`) and functions (`fun chrout^0xffd2(...)`).

The point of the memory-attached form is the same as for variables and
functions: a C64 program is full of hardware that lives at a known address —
VIC-II registers, screen RAM, the KERNAL's I/O channels. A class that
describes such a device should be able to name the device's registers as
fields and reach them without an indirection:

```kotlin
class Vic {
    var border: ubyte^0xd020
    var background: ubyte^0xd021
}

var vic: Vic^0xd000
vic.border = 6
```

The instance is not *stored* at `0xd000` — it has no storage of its own. Its
fields are the two registers at their own addresses. The instance is a
*description*; each field carries its own attachment.

## 2. Goals

- `class Foo { … }` declares a **layout**: named fields and named methods.
- `object Foo { … }` declares a **singleton**: one instance, with global
  storage, plus its methods.
- A class value has three storage kinds, matching the three a variable has
  today: a **global** instance (`var g: Foo`), a **local** instance
  (`var l: Foo`), and a **memory-attached** instance (`var m: Foo^0x3000`).
- Field access: `obj.f` reads, `obj.f = v` writes.
- Method calls: `obj.m(args)` reads and writes the receiver's fields.
- `this` inside a method names the receiver.
- Everything works with the existing `^` syntax, with **no grammar change**.

## 3. Non-goals

- **No heap, no allocator, no constructor call.** A declaration creates the
  instance. (Decision below.)
- No inheritance, no `interface`, no `enum class`, no `companion object`, no
  `constructor`, no `init { }` block, no `super`, no generics, no type tests
  (`is`), no nullable class types, no `as` casts on class values.
- No virtual dispatch: a method is called on the static type of the receiver.
  A subclass does not exist, so there is nothing to dispatch on.
- No class-typed collections, no arrays of class values, no class values as
  function parameters in the first cut (see §9).
- No copying: a class value is not copyable, not assignable from another
  class value of a different type, and has no equality operator.

## 4. Decision: how an instance comes into being

**Decision: a declaration creates the instance. There is no `Foo()` call.**

```kotlin
class Foo { var x: ubyte = 1 }
var g: Foo            // global instance, storage in the data section
var l: Foo            // local instance, storage on the stack
var m: Foo^0x3000     // memory-attached instance, fields carry their own addresses
```

Why not a constructor:

- The compiler has **no allocator** today. `TODO.md` lists "array bounds
  checking and a dynamic allocator" as a v2 item. A constructor that returns
  a fresh instance needs one.
- A heap instance means a class value is a *handle* — a pointer that can be
  null, and that every field access dereferences. On a 6502 that is an
  extra load per access and a crash class the language would have to define.
- The old Wolin solved this by making the **program** supply an allocator
  (`allocMem(size, count)`) that the constructor called. That is a real
  design, and it is in the corpus file `src/test/resources/corpus/objects.ktk`.
  It was not chosen here because it puts a runtime obligation on every
  program that uses a class, for a feature whose point is static layout.
- A declaration-created instance keeps the class type *structural*: the
  compiler knows the size and the field offsets at compile time, so an
  instance can be a compile-time-sized blob. That is what makes the
  memory-attached form work at all.

**Consequence:** the corpus file's `obj = SummingClass()` does not compile.
The corpus file is a *parse* fixture (`CorpusParseTest`), not a compile
fixture, so nothing breaks — but a new compile fixture is written for the
feature, and `objects.ktk` is updated to the new syntax so it stays a valid
example of the dialect.

**Consequence:** an `object` is not a special case of a class with a hidden
global. An `object Foo { … }` declares a class *and* a single global
instance of it, named `Foo`. Referring to `Foo` inside the file refers to
that instance. This is the singleton form, and it needs no extra machinery
beyond "a class can have a default global instance".

## 5. Approach considered and rejected

### 5.1 Constructor call with `malloc` (rejected)

`var obj: Foo = Foo()` — the compiler emits a `malloc` of the class size,
initialises the fields, returns a handle.

Rejected: it introduces a libc dependency into every program that uses a
class, it makes the class value nullable in practice (an uninitialised
handle is a null deref at run time), it costs an indirection per field
access, and it makes the *memory-attached* form incoherent — a
memory-attached instance has no storage to allocate, so `Foo()` would have to
mean something different for it.

The SDK's `malloc` does link and run on the MOS backend (verified by probe),
so this is not a technical barrier — it is a cost the feature does not need
to pay.

### 5.2 A user-supplied `allocMem` (rejected)

The old Wolin's model: the constructor calls `allocMem(size, count)` for
storage.

Rejected for the same reasons, plus one more: it makes the *program*
responsible for a contract the compiler cannot check. A program that forgets
`allocMem` fails at link time with an obscure error.

### 5.3 LLVM structs as the instance representation (rejected)

The natural IR would be `{ i8, i16, i1 }` for a class with a `ubyte`, a
`uword` and a `bool` field.

Rejected as the *storage* representation because the compiler emits LLVM IR
as **text** with a hand-rolled type system (`IrType`), and there is no
machinery for named struct types: `IrType` has no name field, `llvmName()`
returns a bare string, and a named struct needs a `%Name = type { … }` line
in the module header. Adding that is real work, and it buys nothing on the
6502 — the struct's field offsets are what matter, and a byte blob with
computed offsets gives the same offsets.

Structs *do* work on the backend (verified by probe: `{ i8, i16, i1, float }`
globals, `getelementptr` on struct types and by-value struct returns all
compile and run under VICE). The rejection is about the compiler's IR
emitter, not the backend.

## 6. The layout model

A class is a **byte blob** with a computed field layout. Each field has an
offset and a type; the class has a total size.

### 6.1 Field offsets

Fields are laid out in declaration order, each at the next offset that
satisfies its alignment. Alignment on the 6502:

| Field type | Size | Alignment |
|---|---|---|
| `ubyte`, `byte` | 1 | 1 |
| `bool` | 1 | 1 |
| `uword`, `word` | 2 | 1 |
| `float` | 4 | 1 |
| `string` | 2 (pointer) | 1 |
| array | 2 (pointer) | 1 |
| class | 2 (pointer) | 1 |

Alignment is 1 for every field type: the 6502 has no alignment requirement,
and a packed layout means the offset arithmetic is trivial and the blob is
byte-addressable — which matters, because a memory-attached field is *at an
absolute address*, not at an offset within the instance.

**A `uword` field at an odd offset is legal.** The 6502 reads it with an
unaligned load; the backend generates one. (Verified by probe: an `i16`
stored at byte offset 1 of a blob reads back correctly under VICE.)

### 6.2 Two kinds of field

A field is either:

- **plain** — `var x: ubyte = 1`. The field's storage is a slot *inside* the
  instance blob, at the computed offset.
- **attached** — `var border: ubyte^0xd020`. The field has **no storage
  inside the instance**. Its storage is the absolute address `0xd020`, and
  the instance blob does not reserve space for it.

This mirrors the compiler's existing split: a variable with a `^` location has
no storage of its own (`ModuleEmitter.fixedGlobalLine` emits only the address
constant), while a variable without one does.

An attached field contributes **zero bytes** to the instance size.

### 6.3 Instance size

The instance size is the sum of the plain fields' sizes, rounded up to the
next even byte. Rounding to even keeps the blob's size a whole number of
`uword`s for the 16-bit pointer cases, and costs at most one byte.

An instance of a class with only attached fields has size 0.

## 7. Storage kinds

### 7.1 Global instance

```kotlin
class Foo { var x: ubyte = 1 }
var g: Foo
```

The instance is a module global. Its storage is a byte blob of the class
size, in the data section.

**Instance values are not first class.** A class value in this design is not
a pointer to storage; it is the *storage itself*, and the compiler tracks
where it lives statically. So there is no `ptr`-typed class value, and
`var g2: Foo = g` is a copy of the blob (or, in the first cut, an error).

*Decision:* in the first cut, **assigning a class value is not supported**.
`var g: Foo` declares an instance; there is no expression that produces a
class value, so there is nothing to assign. `g = ...` is rejected with
"Cannot assign to a class value". This is the smallest coherent thing, and
it removes the need for a pointer-to-instance representation entirely.

### 7.2 Local instance

```kotlin
fun main() {
    var l: Foo
    while (true) {}
}
```

The instance is an `alloca` of the class size, allocated at function entry
alongside the other locals — exactly how `StmtEmitter.startFunction` already
handles locals (`b.alloca(TypeMapper.ir(it.type))`).

A local instance's fields are initialised at the point of declaration, in
source order, like any local.

### 7.3 Memory-attached instance

```kotlin
class Vic {
    var border: ubyte^0xd020
    var background: ubyte^0xd021
}
var vic: Vic^0xd000
```

The instance has **no storage of its own**. `0xd000` names nothing — it is
the *anchor* the instance is described from. Each **plain** field would be at
`anchor + offset`; each **attached** field is at its own absolute address,
and the anchor is irrelevant to it.

This is the direct analogue of the existing mechanisms:

- a memory-attached **variable** (`var x: ubyte^0xd020`) stores at `0xd020`
  and has no storage of its own;
- a memory-attached **function** (`fun chrout^0xffd2(...)`) is a call to the
  routine at `0xffd2`, with no body of its own.

A memory-attached **class instance** is the union: it is a set of
descriptions, of which the attached fields carry their own addresses.

**Consequence: a memory-attached instance with a plain field is an error.**
A plain field's storage is "inside the instance", and a memory-attached
instance has no inside. The diagnostic is "Class <name> has a plain field
but the instance is memory-attached" — or, phrased per field:
"Field <name> of a memory-attached class instance must be memory-attached".

**Consequence: the anchor address is checked for overlap, but not
otherwise used.** Two memory-attached instances may share an anchor (they
then describe the same fields at the same addresses).

### 7.4 What a memory-attached instance is *for*

The hardware case:

```kotlin
class Vic {
    var border: ubyte^0xd020
    var background: ubyte^0xd021
}

var vic: Vic^0xd000

fun main() {
    vic.border = 6
    while (true) {}
}
```

`vic.border` compiles to exactly what `var border: ubyte^0xd020` compiles to
today — a direct `load`/`store volatile` at `0xd020`. The class adds the
*grouping* and the *naming*, not a level of indirection.

This is the honest answer to "memory-attached objects, too": an instance
whose fields are each memory-attached is a **description of a hardware
device**, and reading or writing a field is a direct register access.

## 8. Syntax

```kotlin
class Foo {
    var x: ubyte = 1        // plain field, one byte in the blob
    var y: uword            // plain field, two bytes
    var z: bool = true      // plain field, one byte
    var reg: ubyte^0xd020  // attached field, no storage in the blob
    fun sum(): ubyte {     // method
        return x + y.toUbyte()
    }
    fun bump() {
        x++
    }
}

object Counter {
    var count: ubyte = 0
    fun inc(): ubyte {
        count++
        return count
    }
}

var g: Foo                 // global instance
var m: Foo^0x3000          // memory-attached instance
object Counter             // declares class Counter + global instance Counter

fun main() {
    var l: Foo             // local instance
    l.x = 2
    val s = l.sum()
    while (true) {}
}
```

### 8.1 Method calls and `this`

`obj.m(args)` is a call to the method `m` with the receiver as the first
argument. The receiver is passed **by pointer** — the address of the
instance's storage — because the method may write to the receiver's fields
and the blob is not a value the callee could copy.

Inside a method, `this` names the receiver. A bare field name (`x` in `sum`)
resolves to `this.x`.

### 8.2 The grammar needs no change

Everything above already parses:

- `classDeclaration` and `objectDeclaration` exist in `KotlinParser.g4`
  (lines 61 and 168).
- `classBody` / `classMemberDeclaration` exist (lines 99 and 103).
- `memberAccessOperator` is `DOT | QUEST DOT` (line 649) — `obj.f` parses as
  a postfix unary operation with a member-access operator.
- `thisExpression` exists (line 491).
- The `^` suffix rides on the **type** (`typeReference: userType
  (arrayDeclaration)? (NL* CARON locationReference)?`, line 245), so
  `var m: Foo^0x3000` needs no grammar change — only that `Foo` resolve to
  a type the builder knows.

The `^` on a *class* type is therefore parsed by the same `locationReference`
rule that handles `var x: ubyte^0xd020`. The builder's `buildLocation`
(`AstBuilder.kt:541`) already turns a literal location into
`FixedAddress(address)` (or `FixedArray` for an array type) — a class type
must take the `FixedAddress` branch, not the `FixedArray` one.

## 9. The type system

### 9.1 A new type kind

`TypeKind` (`sema/Types.kt:3`) gains `CLASS`. `WolinType` gains a field
carrying the class's identity:

```kotlin
data class WolinType(
    val kind: TypeKind,
    ...
    /** The class this type names, for [TypeKind.CLASS]. */
    val className: String? = null,
)
```

The class *name* is what the type carries, not a resolved reference: the
checker resolves it to the class declaration, the emitter needs the layout.
This mirrors how a `WolinType` carries `elementType`/`indexType` rather than
a resolved symbol.

`WolinType.toString` gains a branch: `foo` for a class type.

`TypeMapper.ir` maps `CLASS` to `IrType.Ptr` — a class value is addressed,
not first class. (The pointer is never actually produced: a class value has
no expression form in the first cut, per §7.1. The mapping exists so the
exhaustive `when` compiles and so a later cut has a home.)

`ConstValue.llvmName` (`sema/ConstValue.kt:85`) is a `when` over `TypeKind`
with an `else` branch — a class type is not a valid constant, and the `else`
already throws.

### 9.2 A new symbol kind

`Scope.Symbol` (`sema/Scope.kt:11`) is sealed with two cases: `DataSymbol`
and `FunSymbol`. It gains `ClassSymbol`:

```kotlin
data class ClassSymbol(
    val decl: ClassDecl,
    /** The mangled prefix of the file that declares the class. */
    val prefix: String = "",
) : Symbol()
```

`ImportResolver` (`sema/ImportResolver.kt:22`) resolves functions and
globals. It gains a class branch, so `import pkg.Foo` makes `Foo` a usable
type name.

### 9.3 The new AST nodes

`ast/Ast.kt` gains:

```kotlin
/** A class or object declaration: a layout plus its methods. */
data class ClassDecl(
    val name: String,
    val fields: List<GlobalDecl>,
    val methods: List<FunDecl>,
    /** True for an `object` declaration: it also has a global instance. */
    val isObject: Boolean,
    /** The mangled prefix of the file that declares the class. */
    var prefix: String = "",
    /** The class's own size in bytes, filled by the checker. */
    var size: Int = 0,
    /** Each plain field's offset in the blob, filled by the checker. */
    val fieldOffsets: MutableMap<String, Int> = mutableMapOf(),
) : Ast
```

and a new expression node:

```kotlin
/** `receiver.member` — a field read or a method call. */
data class MemberAccess(
    val receiver: Expr,
    val name: String,
    override var type: WolinType,
    /** The class the member belongs to, filled by the checker. */
    var owner: ClassDecl? = null,
) : Expr
```

and the receiver of a method body:

```kotlin
/** The `this` receiver of the enclosing method. */
data class ThisExpr(override var type: WolinType) : Expr
```

`Ast.Program` and `Ast.FileScope` gain a `classes: List<ClassDecl>` list.

`prefix` and `owner` are filled by the checker and read by the emitter:
the emitter needs the class declaration to find a member's layout, and the
mangled prefix to spell a method's IR name `"<prefix>.<class>.<method>"`.


### 9.4 The exhaustive `when`s

Adding an `Expr` node breaks every exhaustive `when` over `Ast.Expr`. Only
two sites are exhaustive (verified):

| file | line | what |
|---|---|---|
| `sema/TypeChecker.kt` | 570 | `checkExpr` — the expression dispatch |
| `ir/ExprEmitter.kt` | 111 | `emit` — the expression emitter |

The reference-collecting walks all have `else -> Unit` and therefore
compile unchanged: `varRefs` (`TypeChecker.kt:190`), `walkStmtVarRefs`
(`TypeChecker.kt:217`), `checkArrayInit`'s inner `when`
(`TypeChecker.kt:545`), `collectLocalDeclsInExpr` (`StmtEmitter.kt:479`),
`collectVarRefs` (`ModuleEmitter.kt:180`). `collectStmtVarRefs`
(`ModuleEmitter.kt:203`) dispatches over `Stmt`, not `Expr`.

`ConstFolder.fold` (`sema/ConstFolder.kt:35`) and
`GlobalInitEmitter.referencesAnotherEntity` (`ir/GlobalInitEmitter.kt:98`)
have `else` branches — they need a case for `MemberAccess` only if a member
access can appear in a constant expression. It cannot (a field read of a
global instance *is* constant-foldable in principle, but the first cut does
not fold it), so `else -> null` / `else -> false` stand.

A `MemberAccess` falling into a walker's `else` is correct: the first cut
has no global initialiser that reads a field, so no ordering dependency is
lost.


## 10. The checker

### 10.1 Where a class declaration is checked

`TypeChecker.checkFile` (`TypeChecker.kt:52`) builds the file's scope in
steps. A class is declared in step 2, alongside the file's functions:

```kotlin
fileScope.classes.forEach { cls ->
    globals.declare(cls.name, Symbol.ClassSymbol(cls, fileScope.mangledPrefix))
}
```

Class *methods* are **not** declared in the file scope — a method is reached
only through its receiver. But a method's body must see the class's fields,
so a method is checked in a scope whose parent is the class scope, and the
class scope's parent is the file scope:

```kotlin
private fun checkClass(cls: ClassDecl, fileScope: FileScope, fileScopeSyms: Scope) {
    val classScope = fileScopeSyms.child()
    cls.fields.forEach { f -> classScope.declare(f.name, Symbol.DataSymbol(f.type, f.location, f.init, f.isVal)) }
    cls.methods.forEach { m -> checkFunction(m, classScope, receiver = cls) }
}
```

`checkFunction` (`TypeChecker.kt:243`) currently does
`val functionScope = globals.child()` — the parent is the file scope. It
gains a parameter so a method's scope parent is the class scope.

### 10.2 Field offsets

A class's layout is computed once, when the class is checked:

```kotlin
private fun layoutOf(cls: ClassDecl): Pair<Int, Map<String, Int>> {
    var offset = 0
    val offsets = mutableMapOf<String, Int>()
    for (f in cls.fields) {
        if (f.location is FixedAddress) continue   // attached: no storage
        offsets[f.name] = offset
        offset += sizeOf(f.type)
    }
    return ((offset + 1) and 1.inv()) to offsets   // round up to even
}
```

`sizeOf` is a new function in `sema/Types.kt`:

```kotlin
/** The size of a type in bytes, for the class layout. */
fun sizeOf(t: WolinType): Int = when (t.kind) {
    TypeKind.UBYTE, TypeKind.BYTE, TypeKind.BOOL -> 1
    TypeKind.UWORD, TypeKind.WORD -> 2
    TypeKind.FLOAT -> 4
    TypeKind.STRING, TypeKind.ARRAY, TypeKind.FUNCTION, TypeKind.CLASS -> 2
    TypeKind.UNIT -> 0
}
```

### 10.3 Member resolution

`obj.f` and `obj.m()` are the same syntax. The checker resolves the name
against the receiver's class:

- If the name is a **field** → the expression is a field read.
- If the name is a **method** → the expression is a method call, and the
  call's arguments are checked against the method's parameters.

A name that is neither is "Unknown member <name>".

**The receiver's static type must be known.** `obj.f` requires `obj`'s type
to be a class type. A member access on a non-class value is
"Cannot access member <name> of <type>".

### 10.4 `this`

`this` inside a method resolves to the receiver. It is a `MemberAccess`-free
special: the checker tracks a `currentReceiver: ClassDecl?` alongside
`currentReturnType` / `currentFunctionName` (`TypeChecker.kt:20-22`).

`this` outside a method is "this is not allowed here" — the existing
`Unsupported construct: this expression` diagnostic is replaced.

`super` stays rejected.

### 10.5 Method calls

`obj.m(a, b)`:

- The receiver's address is the first argument.
- The remaining arguments are checked against the method's parameters.
- The result type is the method's return type.

## 11. The emitter

### 11.1 Storage

The emitter needs four new `VarStorage` cases (`ir/ExprEmitter.kt:36`):

```kotlin
/** A class instance in the data section: the module global's name. */
class ClassGlobal(val name: String) : VarStorage

/** A class instance on the stack: the entry-block alloca. */
class ClassLocal(val ptr: IrValue) : VarStorage

/** A class instance at a fixed address: its plain fields are at anchor+offset. */
class ClassFixed(val address: Int) : VarStorage

/** A class field, reached through the receiver. */
class ClassField(
    val field: GlobalDecl,
    val offset: Int,
) : VarStorage
```
### 11.2 Field access

A **plain** field instance at base pointer `p`, offset `k`:

```
%f = getelementptr i8, ptr %p, i16 k
%v = load <ty>, ptr %f          ; or store volatile for attached fields
```

For a 16-bit field at an odd offset the GEP element type is `i16` and the
backend emits an unaligned load. (Verified: an `i16` at byte offset 1 of a
blob reads back correctly under VICE.)

The GEP form the backend folds best is the two-step one:
`getelementptr i8, ptr %base, i32 0` then `getelementptr i8, ptr %that, i16 k`.

A **local** instance's base is its `alloca`; a **global** instance's base is
the module global symbol; a **fixed** instance's base is
`inttoptr i64 <anchor> to i8*`.

### 11.3 Attached fields

An attached field's address is its own `FixedAddress` — the same path a
fixed-address variable takes today:

```
%p = inttoptr i64 53280 to i8*
%v = load i8, ptr %p
store volatile i8 %v, ptr %p
```

So `vic.border = 6` emits exactly the same IR as `border = 6` for
`var border: ubyte^0xd020`. **The class adds no indirection** — this is the
property that makes the memory-attached form worth having.

### 11.4 The instance blob

A global instance is a module global of the class size:

```
@"pkg.g" = global [4 x i8] zeroinitializer
```

A local instance is an `alloca`:

```
%v0 = alloca [4 x i8]
```

A fixed instance has **no blob** — only the plain fields' anchor-relative
addresses, and (per §7.3) a fixed instance may not have plain fields at all,
so it emits nothing.

### 11.5 Field initialisers

A plain field's initialiser runs where the instance is created:

- **Global instance**: the field initialisers are folded at compile time
  (they are constant expressions) and become the blob's static data:
  `@"pkg.g" = global [4 x i8] [i8 1, i8 0, i8 0, i8 0]`.
  A field initialiser that is not constant is an error: "global
  initialiser must be a constant expression" — the same rule that applies
  to a global array.
- **Local instance**: the initialisers are emitted as stores at the point of
  declaration, like a local's initialiser.

An attached field's initialiser is a store to its fixed address, at the point
of declaration for a local, and in the file's init function for a global.

### 11.6 Methods

A method is a module-level function whose first parameter is the receiver:

```
define i8 @"pkg.Foo.sum"(ptr %this) {
entry:
  ...
}
```

The name is `"<prefix>.<class>.<method>"` — the class is a new level in the
mangling. The mangler (`driver/Mangler.kt:11`) mangles
`<package>.<file>.<symbol>`; a class method adds one more component. The
emitter quotes the name (`IrBuilder.quoteIfNeeded`), and a dotted name is
quoted — so `@"pkg.Foo.sum"` is valid IR. (Verified: a quoted global name
with dots compiles and links.)

The methods of a class are emitted with the file's other functions, but
**after** them — the same collect-then-emit pattern `LambdaEmitter` uses
(`ir/LambdaEmitter.kt:37-48`), because a module holds one function open at a
time and a method may be emitted while the file's functions are not.

### 11.7 The object singleton

An `object Foo { … }` declares a class `Foo` and a global instance `Foo`.
The instance's module global is named `"<prefix>.Foo"` — the same name as
the class, because the class itself has no module-level symbol.

Inside the file, `Foo` names the instance. Inside a method of the object,
`count` resolves to the field of the singleton instance — the class scope
declares the fields, and the object instance is *the* instance.

## 12. The diagnostics

The `Unsupported construct:` catalogue (`AstBuilder.unsupported`,
`AstBuilder.kt:670`) is the mechanism for "not supported in this version".
The class feature **removes** these entries and **adds** these:

| construct | diagnostic |
|---|---|
| `interface` | `Unsupported construct: interface` |
| `enum class` | `Unsupported construct: enum class` |
| `companion object` | `Unsupported construct: companion object` |
| `constructor` | `Unsupported construct: constructor` |
| `init { }` | `Unsupported construct: init block` |
| `super` | `Unsupported construct: super expression` (kept) |
| a class with a supertype | `Unsupported construct: supertype` |
| a class with type parameters | `Unsupported construct: type parameters` |

And these **new** semantic errors:

| situation | diagnostic |
|---|---|
| a member of a non-class value | `Cannot access member <name> of <type>` |
| an unknown member | `Unknown member <name>` |
| `this` outside a method | `this is not allowed here` |
| a plain field in a memory-attached instance | `Field <name> of a memory-attached class instance must be memory-attached` |
| assigning to a class value | `Cannot assign to a class value` |
| a class value as an expression | `A class value cannot be used as an expression` |
| a method called on a non-class | `Cannot call a method on <type>` |

The `UnsupportedTest` catalogue (`src/test/kotlin/pl/qus/wolin/sema/UnsupportedTest.kt`)
is the pinned list of rejected constructs. The entry "classes are rejected"
(`:34-36`) is **deleted**; the entries "this and super are rejected"
(`:98-101`) are **split** — `super` stays, `this` moves to a new test; the
entry "an unknown member call stays rejected" (`:167-169`) is **re-pinned**,
because `b.other()` is no longer an "Unsupported construct" but an "Unknown
member".

The catalogue is currently *incomplete* relative to the spec's table of
rejected constructs — `object`, `interface`, `enum class`, `companion`,
`constructor` and `init` have no entries. The class feature adds them.

## 13. What lands, and in what order

Six tasks, each independently testable:

1. **Grammar and AST** — the `ClassDecl` node, the `MemberAccess` node, the
   `Program.classes` / `FileScope.classes` lists, and the builder's
   class/object parsing. `class`/`object` parse into the AST; the checker
   still rejects them ("Unsupported construct: class" moves from the builder
   to the checker).
2. **Types and symbols** — `TypeKind.CLASS`, `WolinType.className`,
   `sizeOf`, `Symbol.ClassSymbol`, the import resolver's class branch, and
   the checker's class scope.
3. **Layout** — field offsets, the instance size, and the checker's layout
   computation.
4. **The checker** — member resolution, `this`, method calls, and the
   class-value rules.
5. **The emitter** — the three `VarStorage` cases, the blob, the field GEPs,
   the attached-field access, the method functions, and the object singleton.
6. **The tests and the docs** — the golden IR, the VICE integration tests, and
   the `REFERENCE.md` / `TODO.md` / `README.md` updates.

## 14. Verification

The layout rules are the part most likely to be wrong, so they are pinned by
running real 6502 code:

- **Byte offsets.** A class with a `ubyte`, a `uword` and a `bool` field
  writes distinct values into each field and reads them back — the test
  asserts each field's value, so a wrong offset fails.
- **Unaligned 16-bit.** A `uword` field at an odd offset (after a `ubyte`
  field) reads back its value. (Verified by probe: an `i16` at offset 1 of a
  blob reads back correctly.)
- **Attached fields.** A memory-attached class instance's field writes to the
  absolute address and reads back — the test writes to `0xd020` and reads
  `$d020` under VICE.
- **Local instance.** A local instance's fields are initialised at the
  declaration and are writable.
- **Object singleton.** An `object`'s method mutates the singleton's field,
  and the value survives across two calls.
- **The corpus file.** `corpus/objects.ktk` is updated to the new syntax and
  compiles.

## 15. Risks

| risk | why it matters | mitigation |
|---|---|---|
| The `^` suffix on a class type is parsed but not understood | `AstBuilder.buildLocation` picks `FixedArray` for array types and `FixedAddress` otherwise — a class type must take the `FixedAddress` branch, or the instance's anchor is mis-typed | the builder's `buildLocation` is the single place that decides; the class case is a one-line branch, pinned by a test |
| An unaligned `uword` field | the 6502 has no alignment requirement, but the backend must emit an unaligned load/store | verified by probe; pinned by a VICE test |
| The blob's zero-padding | a class of size 3 rounds to 4 bytes; a field at offset 3 must not exist | the layout rounds the *size* up, not the offsets; pinned by the layout test |
| An attached field inside a *plain* instance | a plain instance's attached field points outside the blob — legal, but surprising | allowed: an attached field always means "at its own address", regardless of the instance kind. Only a memory-attached *instance* with a plain field is an error |
| A class value used as an expression | the first cut has no expression form for a class value, so any use is a compile error, not a runtime one | the checker's member-resolution path rejects it with a named diagnostic |

## 16. The relation to the old Wolin

The old Wolin (at `/home/qus/dev/_c64/wolin`) had classes: a constructor
function named after the class, a heap (`setup HEAP = 176`), and
`allocMem(size, count)` supplied by the program. Its `Klasa` carried a
`heap` of `Zmienna` — a per-class stack of fields — and the class's size
came from `getStackSize`.

This design **does not port** that model:

- The old model allocated from a heap, so a class value was a reference.
  This design makes a class value a static blob, so there is nothing to
  allocate and no handle to dereference.
- The old model's `classUniqId` (a per-class counter at the top of the
  heap) existed to tell instances apart at run time. This design has no
  run-time identity, so it has no need for one.
- The old model found a method by walking the class-deref stack
  (`findFunction`, `WolinStateObject.kt:661`). This design resolves a method
  statically, from the receiver's class.

The old model was chosen for a machine with a heap; this one is chosen for a
compiler that has none. The syntax (`class`, fields, methods, `this`) is
kept, so old source reads the same; the semantics are simpler.
