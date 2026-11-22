# Labels and labeled jumps — design

Date: 2026-11-21. Status: awaiting approval.

## Intent

Wolin's control flow is loop-bound: `break`/`continue` target only the
innermost loop, and the only named jump target is a raw address
(`return@0xea31`). Scene code needs named points — a retry loop, a state
machine, a dispatch with a shared exit — and the "continue in the ROM"
idiom read better with a name than a hex constant.

This adds **labels** — named jump targets inside a function body — and
gives `break@`, `continue@` and `return@` a label form.

The old Wolin never implemented this: its visitor falls into "Unknown jump
expression" for all three labeled forms, and the grammar's
`labelDefinition` rule sat unused as a statement. The semantics here are
new, chosen for the 6502 idiom: a label is a named point, and every labeled
form is an unconditional jump (a `jmp`) to that point.

## Approaches

Three candidates were considered; the user picked A.

- **A (chosen) — named jump points.** `name@` is a statement that defines a
  jump target in the function; `break@name`, `continue@name`,
  `return@name` are all unconditional jumps to it. Function-local, forward
  and backward references. Subsumes labeled loops: `scan@` before the loop
  for `continue@scan`, `done@` after it for `break@done`.
- **B (rejected) — Kotlin labeled loops.** `scan@ while (…)` binds the
  label to the loop; `break@scan`/`continue@scan` target that loop.
  `return@scan` has no natural meaning and would stay rejected; standalone
  labels (the other half of the TODO item) would not exist.
- **C (rejected) — A + B hybrid.** Both forms live; a label before a loop
  also targets the loop implicitly. Most expressive, most magic: when both
  readings exist, the meaning of a jump depends on label placement.

## Goals

- A label statement `name@` in any function-body statement position:
  blocks, loop bodies, `if`/`when` branches, and the branches of `if`/`when`
  **expressions** (a branch is a statement list).
- `break@name`, `continue@name`, `return@name` — an unconditional jump to
  the label.
- Function-local scope with forward and backward references.
- Unlabeled `break`/`continue` (innermost loop) and `return@<address>`
  unchanged; the address form stays the way to jump out of a function.
- Real-6502 verification of labeled jumps under VICE.

## Non-goals

- No cross-function labels: a label names a point in its own function.
  Jumping to ROM or another function stays the address form.
- No new jump spelling (`goto@`): the three existing labeled forms are the
  only label references.
- A label binds nothing: it does not mark a loop, a block or a scope.
  Placement is the user's (see the placement idiom below).
- No block-local labels; a label is visible function-wide.
- No label values or label arithmetic; a label has no type and no storage.

## Constraints

- The grammar is the Kotlin grammar; a minimal, isolated grammar tweak is
  acceptable (one `statement` alternative).
- `break@foo` / `continue@foo` / `return@foo` are already single lexer
  tokens — `BREAK_AT`, `CONTINUE_AT`, `RETURN_AT`
  (`KotlinLexer.g4:99-101`) — and `jumpExpression` already accepts them
  (`KotlinParser.g4:567-572`). Only the AST builder rejects them
  (`AstBuilder.kt:439-441`), pinned by three `UnsupportedTest` tests.
- `return@0xea31` lexes by maximal munch as a `LabelDefinition` `return@`
  followed by the literal (the prefix-`return@` path in
  `AstBuilder.buildPrefix`, `AstBuilder.kt:253-277`). It must keep
  working; pinned by `ReturnAtGrammarTest`.
- The emitter is block-based; `reserveLabel`/`openReserved`
  (`StmtEmitter.kt:424-445`) already support forward branches to blocks
  that are opened later.
- The `interrupt` function attribute is dropped when the function carries a
  `return@<address>` site (`ModuleEmitter.kt:76`, via
  `FunDecl.returnAt`). A label is in-function, so the attribute rule must
  stay address-only.

## Syntax

```kotlin
fun main() {
    var i: ubyte = 0
    scan@
    while (i < 4) {
        i = i + 1
        if (i == 2) continue@scan
        if (i == 3) break@done
    }
    done@
    // reached by break@done (i == 3) or when the loop runs out
}
```

- A label statement is `name@` — an identifier, a trailing `@`, on its
  own as a statement (the grammar's existing `LabelDefinition` token).
- The name is any identifier, including keyword spellings: the lexer's
  `LabelDefinition` and `*_AT` rules embed the `Identifier` *pattern*,
  which matches raw words, so `var@` is a label named `var` and
  `break@var` is one labeled-break token (verified against the generated
  lexer). No name is reserved.
- The three labeled forms take a label name: `break@name`,
  `continue@name`, `return@name`.

## Semantics

- **A label is a point.** The statement defines a named jump target in the
  current function. It has no type, no value, no storage.
- **Scope is the function.** A label is visible from every statement of
  the function, forward or backward. A function literal is a separate
  function: its labels are its own and it cannot see the enclosing
  function's.
- **The three forms are one jump.** `break@L`, `continue@L` and
  `return@L` each emit an unconditional branch to the label's block (a
  6502 `jmp`) and terminate the enclosing block — statements after them in
  the same block are dead code, exactly as with unlabeled
  `break`/`continue`/`return`. The names are source-level intent:
  `break@` — leave a region at the label; `continue@` — resume at the
  label; `return@` — the label form of the `return@<address>` tail-jump
  idiom.
- **Placement idiom (labeled loops).** `scan@` placed before a loop with
  `continue@scan` re-tests the condition; `done@` placed after the loop
  with `break@done` leaves it. Note that a single label cannot serve both:
  `break@scan` on the pre-loop label would re-enter the loop.
- **Interrupt handlers.** A `return@<label>` inside an `interrupt fun` is
  an in-function branch: the backend's prologue/epilogue (the A/X/Y save
  and the `rti` exit) stays balanced on every path, so the function keeps
  its `interrupt` attribute. Only address-form sites drop it, unchanged.
- **A label is not a value.** A branch of an `if`/`when` expression that
  ends in a label is a `unit` branch — the existing "the branch does not
  end in an expression" rule, exactly as a branch that ends in a
  `return`. Used where a non-`unit` value is expected it is the existing
  "Attempt reassign X Y" error; in `unit` context the jump is emitted and
  the branch produces no value, as it does today for a `return`-ending
  branch.
- **Emission.** A label is an IR basic block. When a function is prepared,
  its body is pre-scanned for labels (statement walk: `BlockStmt`,
  `IfStmt` branches, `WhileStmt` bodies, `WhenStmt` entries; expressions:
  `IfExpr`/`WhenExpr` branch statements; `FunctionLit` is a boundary — its
  body is a different function) and a block is reserved for each label in
  source order. A label statement opens its reserved block; a labeled jump
  branches to it. When a label opens, the block being left first receives a
  fall-through branch to the new label block unless it is already
  terminated — consecutive labels fall through to each other, and a label
  after a `return` in the same block still opens its block so forward
  references resolve, with the code after the label emitted normally.
- **Trailing label on a non-void function.** A label that is the function's
  last statement leaves its block unterminated; the function's implicit
  exit then hits the existing "Function X must return a value" compiler
  error, the same error a non-void function gets today when it falls off
  the end.

## Diagnostics (new; added to the REFERENCE.md table)

| Message (shape) | Cause |
|---|---|
| `Unknown label X` | a labeled `break`/`continue`/`return` names a label not defined in the function |
| `Label X already defined` | two labels with the same name in one function |

The three `UnsupportedTest` pins (`a labelled break is rejected`,
`a labelled continue is rejected`, `a labelled return is rejected`) are
removed: the forms are now accepted, and their tests become the
`Unknown label X` rejections.

Docs: REFERENCE.md — the two statement bullets ("`break`/`continue` take
no label…", "`return@<label>` is a compile error…") are replaced; a Labels
subsection joins the `return@<address>` section; the Diagnostics table
gains the two rows. README.md — the statements line gains the label
forms. TODO.md — the item moves to the landed cycle with this design's
link.

## Implementation shape

Left to the builder, in dependency order:

- `src/main/antlr/KotlinParser.g4` — the `statement` rule gains the
  alternative `| labelDefinition` (the rule itself already exists at
  `:722`); append it last, so the `declaration` alternative's
  `labelDefinition*` prefix still wins for a label before a declaration
  (verified: the `LabelDefinition` token is unparseable at statement
  start today, so the alternative adds no ambiguity); regenerate the
  ANTLR sources.
- `ast/Ast.kt` — new `Stmt` node `LabelStmt(name: String)` (+ type
  alias); `ReturnAtStmt` takes a sealed `ReturnAtTarget` —
  `ReturnAtAddress(address: Int)` / `ReturnAtLabel(name: String)` — in
  place of the bare `address: Int`.
- `sema/AstBuilder.kt` — `buildStatement` builds `LabelStmt` from the new
  `labelDefinition` alternative (name = token text minus the trailing
  `@`); `buildJump` builds the labeled nodes from `BREAK_AT` /
  `CONTINUE_AT` / `RETURN_AT` (name = token text minus the form's
  prefix) instead of `unsupported(…)`; the prefix-`return@` address path
  builds `ReturnAtStmt(ReturnAtAddress(…))` — the function-level
  `returnAt` sink collects address sites only, so the `interrupt`
  attribute rule needs no change.
- `sema/TypeChecker.kt` — a `currentLabels: Set<String>?` state; a
  pre-scan (the walk described in Semantics) runs in `checkFunction` and
  `checkFunctionLiteral` before the body is checked, throwing the
  duplicate-label error; `checkStmt` gains a no-op `LabelStmt` arm, and
  the labeled `break`/`continue`/`return` forms resolve their name
  against `currentLabels` (`Unknown label X`).
- `ir/StmtEmitter.kt` — a `labelBlocks` map (label name → reserved block
  label), cleared and filled in `startFunction` by the same pre-scan plus
  `reserveLabel(name)` per label in source order; `emit` gains a
  `LabelStmt` arm (fall-through branch, then open the reserved block) that
  runs before the `terminated` guard, and the `BreakStmt` /
  `ContinueStmt` / `ReturnAtStmt` arms take the label path (branch to the
  reserved block, terminate) when the label is set.
- `ir/ExprEmitter.kt` + `ir/StmtEmitter.whenFlow` — the phi-predecessor
  fix (next section).
- Tests:
  - parser (`LabelGrammarTest`): `name@` parses as a statement; the three
    labeled forms parse; a keyword-spelled label name
    (`var@` / `break@var`) parses; `return@0xea31` still parses as the
    address form (existing pin); the old corpus still parses
    (`CorpusParseTest`).
  - sema (`LabelTest`): a labeled `break`/`continue`/`return` to a
    defined label passes; forward and backward references;
    `Unknown label X` for all three forms; duplicate label; a label in a
    function literal is local to it; an if-expression branch ending in a
    label is a `unit` branch (type error on a non-`unit` destination);
    unlabeled `break`/`continue` unchanged.
  - IR golden: a function with a label and a forward labeled jump — the
    label's block exists, the jump's branch targets it, and the
    fall-through branch between consecutive labels is present.
  - integration (VICE, real 6502, the `^0x400` read-back pattern of
    `ControlFlowTest`): a labeled `continue` retry loop, a labeled
    `break` out of nested loops, and a `return@<label>` path in a
    non-`main` function.
- `examples/labels.wtk` — a small program using all three forms (the
  Makefile's wildcard picks it up; the README examples list gains a line).

## Adjacent gap (fixed as part of this work)

**If/when-expression phis name the wrong predecessor when a branch's value
is produced after control flow in the branch.** `emitIfExpr`
(`ExprEmitter.kt:528-554`) pairs each branch value with the branch's first
block label (`if.then`/`if.else`), and `whenFlow`
(`StmtEmitter.kt:308-355`) pairs with `when.b<i>`. When an earlier
statement of the branch opens a block — a `while`, an `if`, or (with this
feature) a label — the value is produced in that later block, and the
branch into the `…end` block comes from there, so the phi's incoming
label is not a true predecessor. Repro on today's `main`:

```kotlin
var x: ubyte = if (true) { while (false) {}; 5 } else { 3 }
```

emits

```
if.then: br label %loop
...
end: br label %if.end
if.end: %v1 = phi i8 [ 5, %if.then ], [ 3, %if.else ]
```

— the only branch into `%if.end` comes from the loop's `%end` block, and
`mos-c64-clang` rejects the IR with an `LLVM IR PHI` error. A label inside
an `if`/`when` expression branch produces the identical shape, so the
feature requires the fix: pair each branch value with the block that
actually produced it — the current block at the moment the value is
emitted (`b.block()`, captured after the branch's value emission and
before the next `openReserved`) — in both `emitIfExpr` and `whenFlow`.
Programs whose branch value is produced in the branch's first block emit
byte-identical IR to today (the golden tests confirm).

## Designer's calls (review while reading)

1. **All three labeled forms are the same jump.** `break@`/`continue@`/
   `return@` differ only in intent; mechanically each is a `jmp` to the
   label. The 6502 has no break or continue, and the three spellings
   document intent for the human reader.
2. **Labels are function-wide, forward references included.** The natural
   placement of a break target is *after* the loop it breaks out of, which
   is a forward reference; the pre-scan makes both directions free.
3. **`return@<label>` keeps the `interrupt` attribute** — the target is
   in-function, so the prologue/epilogue stays balanced; only address
   sites drop the attribute (today's rule, unchanged).
4. **No reserved label names.** A builder check measured the generated
   lexer: the `LabelDefinition` and `*_AT` rules embed the `Identifier`
   pattern, so keyword spellings (`var@`, `break@var`) lex cleanly on both
   sides; nothing to reject.
5. **The phi-predecessor fix ships with this feature** — required for
   labels in `if`/`when` expression branches, and it repairs the pre-
   existing repro above.
6. **A label opens its block even at a dead-code position** (after a
   terminator in the same block) so forward references always resolve; an
   unreachable label block is legal LLVM.
7. **An example program ships** — `examples/labels.wtk`, matching the
   repo's convention of a runnable example per landed feature.
