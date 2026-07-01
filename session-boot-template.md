# session-boot-template.md — xrdroiddesk Orchestrator Boot Templates
# Location: /xrdroiddesk/session-boot-template.md
# Usage: Copy the relevant template, fill in the bracketed fields, paste as
#        your opening message in Claude Code.
#
# Input is a plain-text feature description + done definition — no Jira/GitHub required.
# For the extended input format (hints, constraints, hardware state), see prompt_template.md.

---

## TEMPLATE 1 — Fresh Boot (new feature, no state files exist)

Use when: starting a brand new feature from scratch.

**Before pasting this template:** fill out `current_feature.md` using the format in
`prompt_template.md`. The Architect reads `current_feature.md` once and produces
`context_packet.json`. No other agent reads it.

```
## xrdroiddesk — Orchestrator Boot

**current_feature.md:** [one-line title for reference, e.g. "Wire gesture pipeline — pinch/swipe → desktop click"]

**Current State:**
- context_packet.json: not present
- diff_summary.md: not present
- observation_form.md: not present
- critic_report.md: not present

**Last agent run:** none
**Last result:** n/a
**Awaiting human response:** no

Read current_feature.md. Read AGENTS.md. Begin routing now.
```

---

## TEMPLATE 2 — Resume After Architect (no blockers, Builder not yet run)

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]

**Current State:**
- context_packet.json: exists
- diff_summary.md: not present
- observation_form.md: not present
- critic_report.md: not present

**Last agent run:** Architect
**Last result:** complete — no open questions
**Awaiting human response:** no

[paste context_packet.json contents here]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 3 — Resume After Architect (BLOCKED — open questions)

Use when: Architect halted with a 🟡 block and you have answers.

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]

**Current State:**
- context_packet.json: exists — BLOCKED
- diff_summary.md: not present
- observation_form.md: not present
- critic_report.md: not present

**Last agent run:** Architect
**Last result:** blocked — open questions outstanding
**Awaiting human response:** yes — response below

**Human response:**
[paste your answers to the Architect's open questions verbatim]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 4 — Resume After Builder — Quick loop (Critic not yet run)

Use when: loop is Quick, Builder produced diff_summary. Skip Device Tester.

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]
**Loop:** Quick

**Current State:**
- context_packet.json: exists
- diff_summary.md: exists
- observation_form.md: not present (Quick loop — skipped)
- critic_report.md: not present

**Last agent run:** Builder
**Last result:** complete

**Diff Summary:**
[paste diff_summary.md contents here]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 5 — Resume After Builder — Full loop (Device Tester not yet run)

Use when: loop is Full, Builder produced diff_summary, device session not yet done.

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]
**Loop:** Full

**Current State:**
- context_packet.json: exists
- diff_summary.md: exists
- observation_form.md: not present
- critic_report.md: not present

**Last agent run:** Builder
**Last result:** complete

**Diff Summary:**
[paste diff_summary.md contents here]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 6 — Resume After Device Tester Halt (observation form filled)

Use when: Device Tester printed the 🔵 block, you ran the device session, and you have results.

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]
**Loop:** Full

**Current State:**
- context_packet.json: exists
- diff_summary.md: exists
- observation_form.md: exists — filled
- critic_report.md: not present

**Last agent run:** Device Tester
**Last result:** halted — device session complete, observation form filled
**Awaiting human response:** yes — response below

**Completed observation form:**
[paste filled observation_form.md here]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 7 — Resume After Critic FAIL (Builder re-run needed)

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]

**Current State:**
- context_packet.json: exists
- diff_summary.md: exists
- observation_form.md: exists / not present  ← delete one
- critic_report.md: exists — FAIL

**Last agent run:** Critic
**Last result:** fail

**Fix List:**
1. [paste from critic_report.md]
2. [paste from critic_report.md]
3. [paste from critic_report.md]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 8 — Resume After Critic PASS (Sync to run)

```
## xrdroiddesk — Orchestrator Resume

**Feature:** [title]
**Milestone:** [number and name]

**Current State:**
- context_packet.json: exists
- diff_summary.md: exists
- observation_form.md: exists / not present  ← delete one
- critic_report.md: exists — PASS

**Last agent run:** Critic
**Last result:** pass

**Critic Report:**
[paste critic_report.md contents here]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 9 — Milestone Gate (awaiting APPROVE/HOLD)

```
## xrdroiddesk — Orchestrator Resume

**Milestone:** [number and name]

**Current State:**
- All features in milestone: Done
- Milestone gate: awaiting human approval

**Last agent run:** Sync
**Last result:** milestone complete — halted for approval
**Awaiting human response:** yes — response below

**Human response:**
[APPROVE / HOLD]

**Next milestone:** [number — title]

Read AGENTS.md. Resume routing now.
```

---

## TEMPLATE 10 — Requirement Correction (output was wrong, you know what you need)

Use when: the loop completed but the result missed the mark and you can clearly describe
what you actually wanted.

```
## xrdroiddesk — Requirement Correction

**Feature:** [title]
**Milestone:** [number and name]
**Intervention type:** requirement correction

**What was produced:**
[1–2 sentences describing what the loop built]

**What I actually need:**
- [correct behaviour]
- [correct behaviour]

**Criteria changes:**
- REMOVE: [paste the criterion that was wrong or too vague]
- ADD: [paste the corrected criterion with testable assertion]
- ADD: [paste any missing criterion — 📱 if device-testable]

**State reset:**
- context_packet.json: invalidate — Architect to rewrite
- diff_summary.md: discard
- observation_form.md: discard
- critic_report.md: discard

Trigger Architect Agent to rewrite context_packet.json with corrected criteria.
Do not proceed to Builder until I confirm the new context_packet.
```

---

## TEMPLATE 11 — Requirement Discovery (output was wrong, need help defining what's right)

Use when: the result felt wrong but you can't yet precisely describe what right looks like.
Puts Claude into Architect interview mode — one question at a time to draw out the correct spec.

```
## xrdroiddesk — Requirement Discovery

**Feature:** [title]
**Milestone:** [number and name]
**Intervention type:** requirement discovery

**What was produced:**
[describe what the loop built]

**What felt wrong:**
- [the part that didn't land]
- [the part that didn't land]

**My intent (rough):**
[describe the goal in plain language — not spec language, just what you're trying to achieve]

**Hardware context (if relevant):**
[USB state, which gestures, what the display should show — whatever you observed]

**State reset:**
- context_packet.json: invalidate
- diff_summary.md: discard
- observation_form.md: discard
- critic_report.md: discard

Switch to Architect Agent.
Ask me clarifying questions to produce a corrected context_packet.json.
One question at a time. Do not write any spec until I confirm it is correct.
Do not trigger Builder until I explicitly say "spec confirmed".
```

---

## TEMPLATE 12 — Criteria Strengthening (build looks right but tests are too shallow)

Use when: the build seems correct but acceptance criteria didn't force the right coverage —
missing edge cases, failure modes, or device behaviours.

```
## xrdroiddesk — Criteria Strengthening

**Feature:** [title]
**Milestone:** [number and name]
**Intervention type:** criteria strengthening

**Current criteria (too shallow):**
- [paste existing criterion]
- [paste existing criterion]

**What they miss:**
- Edge case not covered: [describe]
- Device behaviour not validated: [describe — gesture threshold, timing, USB state]
- Failure mode not tested: [describe]

**State reset:**
- context_packet.json: invalidate — Architect to rewrite criteria only
- diff_summary.md: keep — code does not change unless new criteria force it
- observation_form.md: discard
- critic_report.md: discard

Switch to Architect Agent.
Rewrite acceptance criteria to cover the gaps listed above.
For each criterion output a testable assertion in this format:
  GIVEN [context] WHEN [action] THEN [expected result]
  Mark 📱 if it requires physical glasses/phone.
Output updated context_packet.json only.
Do not trigger Builder until I confirm the rewritten criteria.
```

---

## TEMPLATE 13 — Post-Correction Clean Resume

Use after any intervention (Templates 10, 11, or 12) once you have confirmed the rewritten
context_packet.json is correct. Clears correction state and restarts the loop cleanly.

```
## xrdroiddesk — Post-Correction Resume

**Feature:** [title]
**Milestone:** [number and name]
**Correction applied:** requirement correction / requirement discovery / criteria strengthening  ← delete two

**Confirmed spec changes:**
- [summarise what changed — 1–3 bullets]

**Current State:**
- context_packet.json: exists — corrected and confirmed
- diff_summary.md: not present
- observation_form.md: not present
- critic_report.md: not present

**Last agent run:** Architect (correction run)
**Last result:** complete — spec confirmed by human
**Awaiting human response:** no

Read AGENTS.md. Resume routing now.
```

---

## Quick Reference — Which Template to Use

```
Starting a brand new feature?                            → Template 1
Architect ran, no blockers, continuing?                  → Template 2
Architect ran, blocked on questions, got answer?         → Template 3
Builder ran — Quick loop, need Critic?                   → Template 4
Builder ran — Full loop, Device Tester not yet run?      → Template 5
Device Tester halted, ran device session, have results?  → Template 6
Critic returned FAIL, fixing?                            → Template 7
Critic returned PASS, syncing?                           → Template 8
Milestone finished, got APPROVE/HOLD?                    → Template 9

Result was wrong — I know what I need instead?           → Template 10
Result was wrong — I need help defining what's right?    → Template 11
Build looks right but tests are too shallow?             → Template 12
Correction confirmed, ready to re-run loop?              → Template 13
```

---

## Quick Reference — State File Locations

| File | Path | Gitignored |
|---|---|---|
| `context_packet.json` | `/xrdroiddesk/.agent-state/context_packet.json` | ✅ yes |
| `diff_summary.md` | `/xrdroiddesk/.agent-state/diff_summary.md` | ✅ yes |
| `observation_form.md` | `/xrdroiddesk/.agent-state/observation_form.md` | ✅ yes |
| `critic_report.md` | `/xrdroiddesk/.agent-state/critic_report.md` | ✅ yes |

---

## Claude Code Terminal Shorthand

```bash
# Fresh boot — current_feature.md already filled out
claude "Read current_feature.md. Read AGENTS.md. All state files absent. Begin routing."

# Resume with state
claude "Read AGENTS.md. Feature: [title].
State: context_packet exists, diff_summary exists, critic_report FAIL.
Fix list: [paste]. Resume routing."
```

---

*Keep this file at /xrdroiddesk/ — do not gitignore it.
See prompt_template.md for the extended feature input format with hints and hardware constraints.*
