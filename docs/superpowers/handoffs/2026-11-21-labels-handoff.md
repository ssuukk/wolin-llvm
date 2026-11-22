# Handoff — Labels and labelled jumps, mid-execution

Wolin C64 compiler (`/home/qus/dev/_c64/wolin-new`). Feature: label statements
(`name@`) + labelled `break@`/`continue@`/`return@` + the phi-predecessor fix.
Design approved, plan written, **Task 1 of 3 complete and committed, Task 2
not started**. Execution framework: `superpowers:executing-plans` (native,
inline), TDD, ledger at `.superpowers/sdd/2026-11-21-labels/progress.md`.

## Goal

Implement `docs/superpowers/plans/2026-11-21-labels.md` (3 tasks) per its spec
`docs/superpowers/specs/2026-11-21-labels-design.md`, then final whole-branch
review, then `superpowers:finishing-a-development-branch`.

**Standing user instruction: do not stop or pause, do not check in between
tasks, work until finished.** The prior instance kept ending turns on
narration lines without a tool call ("stalled turns"); the user demanded a
permanent fix — the rule now lives in `/home/qus/.omp/agent/AGENTS.md`
section 5 "No Stalled Turns": while work remains, every response must end
with a tool call; narration and the action ship in the same turn. Respect it.

## Progress

### Done

- [x] Design doc (approach A: named jump points, all three labelled forms =
  unconditional `jmp` to the label block; function-local; forward+backward).
  Builder-checked (its keyword-rejection premise was falsified — no reserved
  label names).
- [x] Plan (3 tasks, TDD-ordered, Review Focus with 5 failure modes each
  mapped to a test).
- [x] **Task 1 complete — commit `a2e12a1`** (base `35f1b38`): grammar
  (`statement | labelDefinition`, appended last), AST (`LabelStmt(name)`,
  sealed `ReturnAtTarget` = `ReturnAtAddress(address)`/`ReturnAtLabel(name)`,
  `ReturnAtStmt(target)`), AstBuilder (label + the three `*_AT` jump tokens;
  address form `return@0xea31` unchanged), TypeChecker (per-function
  `currentLabels`, `collectLabels` pre-scan with `FunctionLit` boundary,
  `Unknown label X` / `Label X already defined`), three `UnsupportedTest`
  pins flipped, `ScopeTest` walk gained a `LabelStmt` arm. New tests:
  `LabelGrammarTest` (6), `LabelTest` (15). Full suite 304/304.
- [x] Ledger rulings recorded (save/restore for `currentLabels` in
  `checkFunctionLiteral`; temporary `ReturnAtLabel` emitter arm; explicit
  `git add` list instead of the eval commit namespace).

### In progress

- [ ] **Task 2 (base `a2e12a1`)** — `task-start` already ran; brief at
  `.superpowers/sdd/2026-11-21-labels/task-2-brief.md`. **No code written
  yet.** The old session was about to write the RED tests (appends to
  `GoldenIrTest.kt` + new `src/test/kotlin/pl/qus/wolin/it/LabelJumpTest.kt`),
  watch them fail, then implement:
  - `StmtEmitter`: `labelBlocks` map; pre-scan `collectLabelNames` in
    `startFunction` + `reserveLabel(name)` per label; `LabelStmt` arm in
    `emit` **before** the `terminated` guard (`ensureTerminatedOr(target)`
    then `openReserved(target)`); `ReturnAtStmt` arm becomes the real
    `ReturnAtLabel` branch (replacing the temp `throw error("labelled return
    not emitted yet")`); `BreakStmt`/`ContinueStmt` label paths.
  - Phi-predecessor fix: `whenFlow` **three** pairing sites (`:324` else-only,
    `:347`, `:351` → `b.block()` captured after `emitBody` returns, before the
    next `openReserved`) and `ExprEmitter.emitIfExpr:549` (`v1 to thenBlock,
    v2 to elseBlock`, blocks captured after each `emitValue`).
  - Then: `./gradlew test` (full suite via the `task-done` script **after**
    the commit, see Task 1 slip), commit per the brief.
  - RED state to expect before implementation: any program with a label
    statement crashes the emitter with `Unknown statement LabelStmt`;
    `return@<label>` throws the temp error; the phi repro
    `var x: ubyte = if (true) { while (false) {}; 5 } else { 3 }` emits
    `phi i8 [ 5, %if.then ]` (invalid IR).
- [ ] **Task 3** — REFERENCE.md / README.md / TODO.md updates +
  `examples/labels.wtk` + `make examples/labels.prg`. **Include the two
  untracked docs** (`docs/superpowers/specs/2026-11-21-labels-design.md`,
  `docs/superpowers/plans/2026-11-21-labels.md`) in the Task 3 `docs:` commit
  — prior cycle kept spec/plan in a separate docs commit (see `35f1b38`).

### Pending

- [ ] Final whole-branch review: `review-package` script, fresh reviewer
  subagent with the plan's Review Focus verbatim + ledger rulings pointer.
  Re-grade findings; Critical/Important → one TDD fix pass; Minor → ledger
  "Deferred minors".
- [ ] Delete `.superpowers/sdd/2026-11-21-labels/` when clean;
  `superpowers:finishing-a-development-branch`.

## Key decisions (rulings — all in the ledger too)

- `currentLabels` in `checkFunctionLiteral`: **save/restore**, not null
  (literals nest inside an active function scope). Pinned by
  `an outer label stays visible after a function literal`.
- Emitter at Task 1's commit: `LabelStmt` not handled (crashes, documented
  intermediate state — no test emits one), `ReturnAtLabel` arm throws
  explicitly. Both replaced in Task 2.
- Commits: explicit `git add <list>` + `git commit` per the plan's commit
  steps (repo convention is direct commits to `main`; no worktree; no other
  agents in this checkout).
- Task 1 commit slip: `task-done` ran before the commit, so its ledger line
  said `35f1b38..35f1b38`; corrected to `35f1b38..a2e12a1` with a note.
  Order for Task 2: commit first, then `task-done PLAN 2 a2e12a1 -- ./gradlew test`.

## Critical context

- Commands (cwd `/home/qus/dev/_c64/wolin-new`): `./gradlew test` (304 tests,
  ~3 min, VICE integration needs `x64sc` — installed), `make` (examples),
  `CP=$(./gradlew -q printClasspath); java -cp "$CP" pl.qus.wolin.driver.MainKt -e prog.wtk` (IR dump).
- Verified emitter mechanics (do not re-derive): `emitWhile` reserves
  `loop`/`body`/`end` (cond block = `loop`, do-while swaps order);
  `emitValue` = dropLast(1) emit, `terminated → null`, last is ExprStmt →
  value / BlockStmt → recurse / else → emit+null; `reserveLabel` returns the
  wanted name or `bbN`; `openReserved` = addBlock + `terminated=false`;
  `ensureTerminatedOr` branches unless terminated; `b.block()`
  (`IrBuilder.kt:118`) = current block label.
- Task 2 test list (brief has exact sources): IR — forward label jump,
  consecutive-label fall-through, label after return, interrupt attribute
  kept (label) vs dropped (address), label named `loop`, unlabelled continue
  ignores body-top label, trailing label on non-void → `must return a value`
  (`IllegalStateException`), phi-after-loop `[ 5, %end ]`, label-in-if-expr
  `[ 5, %p ]`, label-in-when-expr `[ 7, %q ]`, branch-leaving-via-jump no
  value; integration — labelled continue retry (`^0x400` == 3), labelled
  break nested loops (== 2, == 1), labelled return in helper (== 9, == 7).
- `checkBranch`/`checkIfExpr` unification: branch not ending in an
  expression → `unit`; `unit` vs non-`unit` → `unit`; declaration mismatch →
  `Attempt reassign X Y`.
- Untracked and harmless: `.codebase-memory/` (index cache, leave alone).
- The user's tone: impatient with stalling. Terse, no ceremony, no recap
  questions; act.

## Next steps

1. Read the brief `.superpowers/sdd/2026-11-21-labels/task-2-brief.md`.
2. Write Task 2 RED tests (GoldenIrTest appends + LabelJumpTest), run, watch
   fail for the expected reasons.
3. Implement per the brief; run `./gradlew test`; commit;
   `task-done PLAN 2 a2e12a1 -- ./gradlew test`.
4. Task 3 (brief: `task-start PLAN 3`), including the docs commit.
5. Final review + fix pass + finish.
