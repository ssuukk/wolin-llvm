# Kernal routines with return values — design (2026-11-17)

## Intent

`TODO.md` known gap: a fixed-address function **with a return type crashes the
compiler** (`emitFixedFunction` ends in `ret(null)`, `IrBuilder.ret` asserts
void). Kernal routines that return a value in a CPU register — e.g.
`readst^0xffc7(): ubyte` — cannot be declared. This blocks the stdlib kernal
file from growing past void functions.

The motivating routines (user-provided survey of the C64 kernal):

| Routine | Address | Return register(s) |
|---|---|---|
| PLOT | `$FFF0` | X = column, Y = row |
| SCREEN | `$FFED` | X = columns, Y = rows |
| RDTIM | `$FFDE` | A, X, Y (jiffy clock components) |
| MEMBOT | `$FF9C` | X = high byte, Y = low byte of address |
| READST | `$FFC7` | A |
| GETIN | `$FFE4` | A |

## Verified facts (spiked against `mos-c64-clang` SDK v23.2.0)

1. **LLVM-MOS 6502 ABI** (from compiling C and disassembling — deterministic,
   no runtime needed). i8 arguments arrive in A, X, Y in declaration order;
   i8 returns in A; i16 returns in A (low) / X (high). **A, X, Y are all
   caller-saved**: the caller saves its live values around a call and reloads
   them where needed (the C-compiled caller emits `sty __rc2` before `jsr`).
   Consequence: the existing wrapper architecture needs no manual register
   save/restore — the caller's live X/Y are protected by the backend, and a
   register read placed after the call sees the ROM's value.
2. **The wrapper shape codegens correctly.**
   `define i8 @readst(i8,i8,i8) { call void asm "jsr $$ffc7","a,y,x"(…);
   %r = call i8 asm "pha\0Apla","=a"(); ret i8 %r }` is valid IR, and the
   object disassembles to exactly `… jsr $ffc7 / pha / pla / rts`, result in
   A at `rts`. Runtime confirmation is the implementation's VICE test (below),
   because — see fact 5 — the raw-`.ll` harness cannot be trusted for runtime
   reads.
3. **Multi-output struct asm: treat as unusable.**
   `call { i8, i8, i8 } asm "jsr $$ffde", "=a,=x,=y"()` compiles, but the
   object disassembles suspiciously (an unexplained `sty` to a scratch slot
   after the `jsr`, no visible materialization of the A/X outputs). The
   design never uses it; a future feature wanting it must re-verify with a
   proper runtime harness first.
4. **Y register access.** `call i8 asm "pha\0Atya\0Atay\0Apla", "=y"()` (read)
   and `call void asm "", "y"(i8 %v)` (write) codegen to exactly the right
   6502 (`ldy #$6b / pha tya tay pla / sty $0400`); the A- and X-equivalents
   are already in `CpuAccessors`. Runtime covered by the implementation's
   VICE tests.
5. **Tooling caveat (discovered while spiking):** a hand-written
   single-module `.ll` compiled with the default (LTO) link produces a `.prg`
   whose CRT entry trampolines land mid-instruction — the user code never
   runs, and monitor reads of `$0400` show nondeterministic boot-screen
   residue (stable `03 03 …`, then varying bytes per run). Root cause is in
   the SDK linker/CRT, not Wolin. **All runtime verification goes through the
   Wolin driver pipeline** (the proven path — the existing VICE test suite is
   green through it).

## Decisions

### D1 — Codegen: the wrapper returns the loaded register (approach A)

The fixed-address function stays a real LLVM function; its body is the ROM
call. The body gains one load of the annotated result register and a
non-null `ret`. Call sites remain ordinary `call` instructions; the result is
a first-class SSA value the optimizer can see. This is the existing
architecture (the wrapper is already the translation layer between the
LLVM-MOS ABI and the ROM's register convention) extended by one instruction.

Rejected:
- *One asm with combined constraints* (`"=a,a,y,x"`): relies on the broken
  multi-output path (fact 3).
- *Void call + user reads `CPU.A`*: the declared return type would be a lie,
  the value doesn't compose into expressions.

### D2 — Syntax: the return type carries the register annotation

```kotlin
fun readst^0xffc7(lfn: ubyte^CPU.A, ch: ubyte^CPU.Y, dev: ubyte^CPU.X): ubyte^CPU.A
fun rdtim^0xffde(): ubyte^CPU.A
```

Symmetric with parameter annotations. The parser **already parses** the
location suffix on return types — `buildType` runs at `AstBuilder.kt:89`; the
code simply drops the `.location`. The change is to keep it, not to extend the
grammar. The annotation is required (a bare `ubyte` return on a ROM call is
rejected: which register must be named).

### D3 — Secondary result registers: CPU location variables

A function returns one value; routines like RDTIM (A, X, Y) or PLOT (X, Y)
leave the rest in their registers. The caller reads them through the
documented-but-unwired CPU location variable mechanism (`REFERENCE.md`
"Registers"): `var x: ubyte^CPU.X`, then `val minutes = x` after the call.

This is a second implementation part: `CpuAccessors` (backend-verified
register read/write asm) exists with **zero production call sites** —
`VarStorage` has no register variant and `emitLocalDecl` allocates plain
memory for `^CPU.*` variables, silently ignoring the location.

The alternative — new multi-result function syntax (`: ubyte^CPU.A,
ubyte^CPU.X, …`) — needs tuples or out-parameters the language doesn't have.
Register variables already exist in the documented syntax and in the corpus
(`word^CPU.XY` in `corpus/misc.ktk`); wiring them is the boring path.

### D4 — Scope, v1

- Return register: `ubyte` in `^CPU.A`, `^CPU.X`, or `^CPU.Y`.
- CPU location variables: `ubyte` (and `bool` for the three single-bit
  registers is **not** included — see below) reads **and** writes for
  `^CPU.A`, `^CPU.X`, `^CPU.Y` locals and globals.
- Not in v1 (separate follow-ups, each needs new unverified asm):
  flag variables (`bool^CPU.C/I/N/Z` — no flag-*write* sequence exists),
  pair variables (`uword^CPU.AX/XY`), 16-bit ROM returns
  (`uword^CPU.AX`-style result), `++`/`--` on register variables.

  MEMBOT's 16-bit result is covered as two reads:
  `var hi: ubyte^CPU.X; var lo: ubyte^CPU.Y; membot(); val addr: uword = (hi shl 8) or lo`.

  My call, flagged: writes included in v1 because the write primitive
  (`call void asm "", "c"(i8 %v)`) is already proven and a read-only
  register variable is a half-feature; `REFERENCE.md` documents writes.

## The change, part by part

### Part 1 — ROM call return value

- **`ast/Ast.kt`**: `FunDecl` gains `retLocation: Location?` (the return
  type's register annotation).
- **`sema/AstBuilder.kt`** (`buildFunction`): keep `buildType(...).location`
  for the return type instead of dropping it.
- **`sema/TypeChecker.kt`**: for a body-less fixed-address function, a return
  type (when present) must be `ubyte` annotated `^CPU.A`, `^CPU.X` or
  `^CPU.Y`; otherwise a diagnostic naming the rule.
- **`ir/CpuAccessors.kt`**: add `readY` (`"pha\0Atya\0Atay\0Apla"`, `"=y"` —
  codegen-verified, needs its `CpuAccessorTest` entry); `readX` reuses the
  existing `readAxHigh` sequence (rename to `readX` — same operation, clearer
  name; `readAxLow` stays as-is for the AX pair's future).
- **`ir/ModuleEmitter.kt`** (`emitFixedFunction`): after
  `ExternalCalls().emitCall(...)`, if `retLocation` is present, load the
  register into an SSA value and `b.ret(value)`; otherwise `b.ret(null)` as
  today.
- **Tests**: `RomCallTest` gains an IR-level test (`readst^0xffc7():
  ubyte^CPU.A` emits the jsr, the `pha/pla` load, and `ret i8`) and a VICE
  test: `setLfs(5,9,8); val s = readst(0,0,8)`. Expected `3` (kernal status
  code "device not present" — VICE `-default` has no device 8); pin the
  observed value, which must be deterministic run-to-run.

### Part 2 — CPU location variables (A, X, Y)

- **`ir/ExprEmitter.kt`**: `VarStorage.Register(reg: String)` variant.
  `emitVarRef` on it: `CpuAccessors` read for A/X/Y, error for
  flags/pairs. `emitAssign` (store path) on it: `CpuAccessors` write for
  A/X/Y, error otherwise. `emitIncDec` rejects register variables in v1.
- **`ir/StmtEmitter.kt`**: local and global declaration of a
  `^CPU.A/X/Y` variable → `VarStorage.Register` (no `alloca`, no `@global`).
- **`ir/ModuleEmitter.kt`**: the global section skips `CpuRegister` globals
  (no `@prefix.name = global …` line for them).
- **`ir/GlobalInitEmitter.kt`**: init skips `CpuRegister` globals (joins the
  existing `FixedAddress`/`FixedArray` skip).
- **Tests**: IR-level round-trip (`var x: ubyte^CPU.X; x = 5; val y = x`
  emits the write asm, the `=x` read, no alloca) and a VICE test: write a
  sentinel to the X variable, call `rdtim^0xffde(): ubyte^CPU.A`, assert the
  return value is not the A sentinel and the X/Y variable reads are not the
  X/Y sentinels (the ROM wrote all three).

### Docs

- `REFERENCE.md` "ROM calls": return-type annotation + the secondary-register
  readback, with the RDTIM example.
- `TODO.md`: the crash entry is resolved; a new entry for the deferred v2
  scope (flags, pairs, 16-bit returns).

## Known caveat (documented, accepted)

A ROM call may clobber the caller's A/X/Y (caller-saved ABI, fact 1). The
backend protects the caller's *live* values (save/reload around the call). A
register-variable read placed after the call yields the ROM's value; the one
edge where it yields the caller's old value is if the caller's own live value
was parked in that register and the backend reloads it before the user's
read — i.e. the same SSA value pinned to the same register on both sides of
the call. Rare; same class of caveat as "don't assume X survives a ROM
call", which is how 6502 programmers have always treated the kernal.

## Left to the builder

- Exact diagnostic wording for the rejected return-type shapes.
- Whether `readAxHigh` is renamed `readX` or aliased (rename preferred — one
  caller, a test file).
- The VICE rdtim test's exact assertions (sentinel-absence vs value bounds).

## Outcome — what shipped

The plan changed twice during implementation; this is the record of the
final state.

- **Part 1 shipped, and wider than D4.** 16-bit ROM returns
  (`uword^CPU.AX` and `uword^CPU.XY`) are implemented via
  `CpuAccessors.readRegisterPair` (two reads joined with `zext`/`shl`/`or`),
  though D4 listed them as a follow-up. The AX-pair runtime test was demoted
  to IR level: RDTIM under a running kernal does not return a deterministic
  value, so the pair layout is pinned in IR and the XY pair carries the VICE
  runtime tests (SCREEN: 40×25, MEMBOT: `$003E`).
- **Part 2 was rejected, not shipped.** D4 planned CPU-location variables
  (A/X/Y reads and writes) into v1. On evaluation the register plumbing
  (`VarStorage.Register`, the `writeRegister` call sites, the global/
  pre-scan skips) was reverted, and the checker now rejects *every*
  CPU-located variable (`var x: T^CPU.REG`) with a diagnostic. Registers
  appear only in ROM-call signatures. Re-landing is a follow-up; the
  accessor primitives (`readX`/`readY`/`writeRegister`) remain in
  `CpuAccessors`.

