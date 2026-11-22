# String interpolation — feasibility and design

Date: 2026-11-22. Status: implemented (first cut); plan in
`docs/superpowers/plans/2026-11-22-string-interpolation.md`.

Implementation deviations: soft-float normalisation treats a mantissa in
`[0.9999999, 1.0)` as exactly `1.0` (otherwise `1e-4` printed as
`9.999999E-5`); `9.999999f` prints `9.999999` because its stored 8th digit
is below 5; and the VICE test settle time was raised from 2.5 s to 4 s for
suite stability. Float interpolation remains a 7-significant-digit
formatter, not Kotlin's shortest round-trip.

## Intent

`TODO.md` lists string interpolation (`${...}`) as a v2 item. The parser
already accepts the Kotlin forms — `${expr}` and `$name` — but the AST
builder takes the literal's source text verbatim and the checker rejects
`${`. The question is whether interpolation can exist at all without a
dynamic allocator, given:

- Wolin has no objects and therefore no `toString()`;
- a Wolin `string` is a raw `ptr i8` to a NUL-terminated byte sequence;
- the compiler has no heap, and `TODO.md` keeps a dynamic allocator out of
  scope.

## Verdict

**Yes, but not with Kotlin value semantics.** Interpolation always needs
*somewhere* to put the result. Without allocation, that somewhere has to be
chosen at compile time. The feasible contract is:

> An interpolated expression evaluates to a pointer to a **bounded scratch
> buffer** owned by the template site. The bytes are valid until the next
> evaluation of the same site. Distinct template sites have distinct
> buffers.

That is enough for the normal use — `print("x=${x}")`, a local that is
consumed before the next evaluation of the same site, a global built once
during init — and it needs no heap. It is not enough to make every
interpolated result an independently-lived immutable string.

A faithful Kotlin implementation (each evaluation returns a fresh,
immutable string that stays valid as long as a reference exists) is
**impossible without allocation**. Even the SDK's `malloc` would only move
the problem into the heap; it does not provide value semantics.

## What interpolation requires

1. **Parsing.** The grammar already has the structure: `lineStringLiteral`
   is a sequence of `lineStringContent` and `lineStringExpression` nodes;
   `LineStrRef` is `$name`, `LineStrExprStart` is `${`. The current
   `AstBuilder` throws this structure away by reading `ctx.text`.
2. **Type-directed formatting.** Each part is formatted by its *static*
   type. There is no `toString` to dispatch to, and no user-defined types
   to dispatch on.
3. **Storage.** A buffer large enough for the worst case, plus a cursor.
4. **Termination.** A NUL byte after the last part.

Step 2 is where Kotlin's `toString()` disappears. In Kotlin every value is
an object (or boxed), so `StringBuilder.append(Object)` calls
`toString()`. Wolin has a closed set of scalar types, so each type gets a
fixed formatter:

| Part type | Rendering | Max bytes |
|---|---|---|
| `ubyte` | unsigned decimal | 3 |
| `byte` | signed decimal | 4 (`-128`) |
| `uword` | unsigned decimal | 5 |
| `word` | signed decimal | 6 (`-32768`) |
| `bool` | `true` / `false` | 5 |
| `string` | copy up to NUL | runtime; capped (below) |
| `float` | custom IR, 7 significant digits | 16 |
| everything else | compile error | — |

`float` has no cheap `Float.toString`: Kotlin prints the shortest
decimal that round-trips. The first cut instead formats in compiler-emitted
IR using the soft-float operations a float program already links: normalise
to `[1, 10)`, extract 7 significant digits, round on the 8th, trim trailing
zeros, and emit fixed notation when the decimal exponent is `-3..6` and
scientific notation (`E`) otherwise. `NaN`, `Infinity` and `-Infinity` are
recognised from the value and written directly. No C library is used.
This is 7 significant digits, not Kotlin's shortest form: `3.1415927f`
prints `3.141593`; `1e-4f` prints `1.0E-4`.

There is no `char` type: `'A'` is a `ubyte`, so `${'A'}` renders `65`,
not `A`. That follows from the type, and must be documented.

## Storage options

Five candidates were considered. Sizes assume the 255-byte cap argued
below.

| | Storage | No heap | Nested / multiple templates | Same site evaluated twice | Returned result |
|---|---|---|---|---|---|
| **A** | static buffer per template site | yes | distinct sites are independent | aliases; second evaluation overwrites | valid forever, content may be replaced |
| **B** | entry-block `alloca` | yes | recursion-safe when dynamic | aliases within one activation | dangling when dynamic, shared when static |
| **C** | caller-supplied buffer (hidden ABI) | yes | safe | aliases unless caller allocates | safe |
| **D** | stream to a sink, no buffer | yes | safe | safe | no string value at all |
| **E** | `malloc` | no | safe | safe | Kotlin-like |

### A (recommended) — one static buffer per template site

Each syntactic template gets its own `private global [256 x i8]`. Evaluation
writes into it and returns its address. This is the C `inet_ntoa` /
`localtime` contract, made explicit.

- No stack cost, no heap, no allocation.
- Distinct sites never interfere, so `"a=${x} b=${y}"` and nested templates
  work. Each part is copied into the outer buffer at the point it is
  evaluated, before the next part runs.
- Evaluating the **same** site again (a loop, another call of the enclosing
  function, a recursive call, an interrupt handler that calls the same
  function) overwrites the previous result.
- The pointer never dangles; the content changes.
- Deterministic: behavior does not depend on whether LLVM-MOS decided to
  place something on the stack or in `.Lstatic_stack`.

### B (alternative) — `alloca` in the function entry

A block-local `alloca [N x i8]` per site. Measured behavior of the MOS
backend (probe, LLVM-MOS 23.2.0):

- Non-recursive function, 256-byte `alloca` with its address escaping:
  allocated in `.noinit..Lstatic_stack`, 0x100 bytes, **not** on the soft
  stack.
- Recursive function, 4-byte `alloca`: no static storage; the prologue
  saves the software stack pointer and subtracts the frame
  (`clc; adc #$fc; sta __rc0 …`), addresses are `(sp),y`.

So the storage class depends on the optimizer's recursion analysis. For a
recursive function a returned pointer dangles; for a non-recursive one it
points into a shared static slot. A is simpler to specify and implement.

### C — caller-supplied buffer

A hidden return-buffer parameter on every function returning `string`
(`fun f(...): string` becomes `f(..., ptr __retbuf) string`). This gives
per-call storage without a heap. Cost: a second internal ABI, function
pointers and literals need care, and the buffer size still has to be
chosen at the call site. It is the right follow-up if retained results
must be reliable, not the first cut.

### D — pure streaming

Lower `"a=${x}b"` only when the consumer writes bytes immediately (`print`,
`printAt`). No buffer at all. Rejected as the primary design because the
language cannot know every consumer, and `val s = "…"` would remain
unsupported.

### E — `malloc`

The SDK's heap exists and links (`libc.a`, `malloc`/`free`), but using it
contradicts the no-dynamic-allocation constraint and does not give value
semantics by itself. Rejected.

## Buffer size and the 255-byte cap

The existing string surface is byte-oriented: `wolin.print` indexes with a
`ubyte`, so a string longer than 255 bytes already wraps and prints
forever. Aligning interpolation with that limit is honest:

- **All parts compile-time constant** → fold to a `StringLit`; no buffer,
  no limit beyond the existing literal limit.
- **Any runtime part** → a `[256 x i8]` buffer: at most 255 result bytes
  plus NUL. A `string` part is copied until its NUL or until the cursor
  reaches 255; silently truncated at the cap. Fixed parts have a
  compile-time maximum; if that maximum alone exceeds 255, compile error
  `String interpolation is limited to 255 bytes`.

A single 256-byte size for every site is deliberate: the helpers then take
only `(buffer, cursor, value)`, need no capacity argument, and every store
checks the global 255-byte cap. A program with many templates pays 256
bytes per site in `.bss`; on a 64 KB machine that is the honest cost of
retained storage. Distinct sites each get their own buffer.

The cursor is an `i16` (a `uword`), because a `ubyte` GEP index would be
sign-extended: 200 as `i8` addresses backwards. The value is capped at
255, and every store checks before incrementing.

Literal `\0` inside a template is rejected: the result is NUL-terminated,
so later parts could never be observed. Escaping `$` (`\$`) is added to
the string decoder at the same time.

## IR shape

For a site `print("n=${n}")`:

```llvm
@__wolin_interp_0 = private global [256 x i8] zeroinitializer
@str_0 = private unnamed_addr constant [3 x i8] c"n=\00"

; expression
%buf = getelementptr [256 x i8], ptr @__wolin_interp_0, i32 0, i32 0
%p1  = call i16 @__wolin_interp_append_string(ptr %buf, i16 0, ptr @str_0) ; returns 2
%p2  = call i16 @__wolin_interp_append_ubyte(ptr %buf, i16 %p1, i8 %n)
%end = getelementptr i8, ptr %buf, i16 %p2
store i8 0, ptr %end
; result: %buf
```

`append_string` copies until NUL; the literal is NUL-terminated anyway.
`append_ubyte` and friends write their rendering and return the new cursor.
They are definitions in the driver module and `declare`d by the file
modules that use them. Integer formatting calls no library. Float
formatting is a self-contained IR helper over `fcmp`, `fptoui`, `uitofp`,
`fsub`, `fmul` and `fdiv`; the soft-float routines are the same
compiler-runtime code float arithmetic already links. The float helper is
emitted only when a float part exists. No C library, no `printf`, no
`malloc`.

Decimal formatting does not need a division routine: powers of ten are
compile-time constants. The helper walks `10000, 1000, 100, 10, 1`,
subtracting each power repeatedly to get a digit. Signed values render the
sign, then the magnitude in an unsigned width (`-128`/`-32768` included).

PETSCII matters for generated text: digits and `-` are the same as ASCII,
but `true`/`false` must use the compile-time PETSCII mapping
(`t` → `$54`, not `$74`), or they will not match a source string on
screen.

## Language semantics

- A template expression has type `string`.
- Parts are evaluated left to right, like Kotlin.
- The result is a borrowed pointer into the site's buffer, not a fresh
  string. Document this in `REFERENCE.md` next to the string section.
- Distinct sites are independent; nested templates and several
  interpolations in one expression are safe because each part is consumed
  immediately (copied into the enclosing buffer).
- Re-evaluating one site invalidates its previous result. A program that
  needs to retain two results must copy one, or use two distinct sites.
- A template inside an interrupt-shared function is not reentrant. The
  current language already has static/`.Lstatic_stack` locals with the same
  caveat; this adds no new class of problem, but it must be stated.
- Global initialisers: constant templates fold; a template with runtime
  parts in a global initialiser is still "global initialiser must be a
  constant expression".

## Compiler changes

1. **AST**: `StringTemplate(parts: [Literal(bytes) | Expr(expr)], type)`.
   `AstBuilder` walks `LineStrText` / `LineStrEscapedChar` / `LineStrRef` /
   `LineStrExprStart` instead of reading `ctx.text`. Reuse the escape
   decoder, adding `\$`.
2. **Sema**: check each part; reject unsupported part types; reject NUL in
   literal parts; reject a fixed part whose maximum exceeds 255.
3. **Const folder**: fold all-literal templates to `StringLit`; templates
   with value parts stay non-constant (a global initialiser containing one
   is rejected by the existing constant-expression rule).
4. **IR**: register one `private global [256 x i8] zeroinitializer` per
   site (names like `@__wolin_interp_<n>`), emit the append sequence, emit
   helper declarations; mark the module when it uses float interpolation.
5. **Driver module**: emit only the append helpers a file uses, under
   reserved `__wolin_interp_*` names; emit the float helper only when a
   file uses it.
6. **Diagnostics/tests**: replace the two `UnsupportedTest` interpolation
   rejections with positive coverage; pin unsupported part types and the
   255-byte error.

## Test plan

- Parser: `${x}`, `$x`, `\$`, nested braces, expressions with calls and
  indexing, triple-quoted templates.
- Sema: each allowed type; `unit`/array/function rejected; NUL literal
  rejected; fixed part over 255 rejected.
- IR: one 256-byte buffer per site; helper declarations; no `malloc`, no
  `printf`; the float helper appears only for a float part; integer-only
  programs carry no float helper.
- VICE: `print("n=${x}")` for 0, 9, 10, 255; `byte` -128; `word` -32768;
  `bool` both values; string part; nested template; empty template;
  literal-only template (no buffer); same-site loop overwrite documented;
  `1.5f` → `1.5`, `1.0f` → `1.0`, `-2.25f` → `-2.25`,
  `3.1415927f` → `3.141593`, `1e7f` → `1.0E7`, `1e-4f` → `1.0E-4`,
  `1e-5f` → `1.0E-5`, `1e-20f` → `1.0E-20`,
  `NaN`/`Infinity`/`-Infinity`.
- Multi-file: template in an imported function links with the helper
  declared once.
- Regression: existing string literal escapes and `StringTest` unchanged.

## Non-goals for the first cut

- Kotlin value semantics for interpolated results.
- Kotlin's shortest-round-trip `Float.toString`; the first cut is 7
  significant digits with fixed notation for decimal exponents `-3..6`
  and `E` notation outside that range.
- Any user-defined `toString`.
- An escaping analysis that rejects retaining a site buffer.
- Dynamic sizing, heap allocation, or a general `StringBuilder`.
- A public formatting API; the append helpers stay internal.

## Evidence collected

- `mos-platform/c64/lib/link.ld`: `__stack = 0xD000`; the software stack
  grows down from there.
- 256-byte `alloca` in a non-recursive function with the address escaping:
  `.noinit..Lstatic_stack`, size 0x100, no stack adjustment.
- 4-byte `alloca` in a recursive function: static storage absent; frame
  prologue subtracts from `__rc0/1`; buffer addressed through the software
  stack pointer.
- `malloc` is present in the SDK but only linked when called; the compiler
  currently emits no heap symbols.
- Grammar already has `LineStrRef` and `LineStrExprStart`
  (`KotlinLexer.g4:487,500`); the current rejection lives in
  `AstBuilder.stringBytes` (`AstBuilder.kt:661`) and `UnsupportedTest`.
- **Generated Wolin code calls no C library function today.** A probe with
  runtime `uword` division links `__udivmodhi4` (compiler-rt) and the SDK
  startup `_start`/`__do_init_stack`/`exit`; there is no `printf`,
  `memcpy`, `malloc` or other libc call in the IR or the map. The SDK
  startup is the platform entry point, not an API the compiler calls.
- Float arithmetic is compiler-rt soft-float, not libc; the float
  interpolation helper uses the same `fcmp`/`fptoui`/`uitofp`/`fsub`/
  `fmul`/`fdiv` operations and adds no library dependency. A probe using
  `fptoui` and `fcmp` linked `__fixunssfsi`, `__gesf2`, `__ashlsi3` and
  `__lshrsi3` — compiler-rt helpers, no libc symbol.
- The SDK's C `printf` would be the first real libc dependency and adds
  ~4 KB; it is rejected. A custom IR formatter keeps the “no C library”
  rule and gives controlled output.

## Decisions

1. **Buffer ownership**: static per site (A). Deterministic, no dangling
   pointer, no dependence on the backend's static-stack analysis.
2. **Size**: 256 bytes per site; 255-byte result cap, silent truncation
   for runtime string parts. One size keeps the helper signatures small.
3. **Float**: supported by a self-contained IR formatter using compiler-rt
   soft-float; 7 significant digits, fixed notation for decimal exponents
   `-3..6`, `E` notation otherwise, `.0` for integral values. No C library.
4. **Same-site overwrite** is the documented contract for v2; the hidden
   caller-buffer ABI (C) is the follow-up if retained results must be
   reliable.
