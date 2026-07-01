# AGENTS.md — xrdroiddesk
# Agent Role Definitions & Loop Structure
# Version: 1.2
# Location: /xrdroiddesk/AGENTS.md

---

## Project Overview

xrdroiddesk is a native Android app (Kotlin) that uses XReal One Pro glasses hand gestures
and voice input to control Android Desktop mode on a Google Pixel 10 Pro. The glasses expose
a UVC camera over USB-C; MediaPipe processes frames into hand landmarks; an AccessibilityService
dispatches gestures to the desktop. No proprietary XReal SDK is used.

Work is driven by feature prompts — the user provides a description and acceptance criteria
directly to the Orchestrator. No external tracker required. Development follows a structured
agent loop — each agent has a narrow role, minimal context, and a defined handoff format.

---

## Human Oversight

All agents that require human input or approval print a formatted gate block to the session
and halt. No agent proceeds past a gate until the response is typed or pasted back.

### When Each Agent Halts

| Agent | Trigger | Action Required |
|---|---|---|
| Architect | open_questions is non-empty | Answer questions inline |
| Device Tester | device_required = true | Run device session script, fill in observation form |
| Orchestrator | Milestone complete | Type APPROVE or HOLD |

### Gate Templates

**Architect — open questions (BLOCKING):**
```
🟡 HALT — Architect: Input Required
Feature: [title]
Questions:
1. [question]
2. [question]
Reply inline, then type: resume
```

**Device Tester — device session (BLOCKING):**
```
🔵 HALT — Device Session Required
Feature: [title]
USB state going in: [UVC active | non-UVC | first-plug]

Steps:
  1. [action]
  2. [action]
  3. [action]

Observation form — fill in each blank:
  [1] USB dialog appeared:           [ yes / no ]
  [2] App shown in chooser:          [          ]
  [3] adb logcat -s Tag1:D Tag2:D    [ paste 5-10 lines ]
  [4] Glasses display shows:         [          ]
  [5] Hand tracking visible:         [ yes / no ]

Paste completed form below, then type: resume
```

**Orchestrator — milestone gate (BLOCKING):**
```
🏁 HALT — Milestone Complete
Milestone: [name]
Features completed: [list]
Next: [name]
Type APPROVE to proceed or HOLD to pause.
```

### Resuming

Paste the completed form or answer directly into the session and type `resume`.

---

## Folder Structure

```
/xrdroiddesk/
  AGENTS.md                          ← this file
  CLAUDE.md                          ← hardware findings, architecture (Architect reads; others don't)
  Makefile                           ← build, install, check, logcat targets
  .agent-state/                      ← handoff files (gitignored)
    context_packet.json              ← Architect → all downstream agents
    diff_summary.md                  ← Builder → Device Tester, Critic, Sync
    observation_form.md              ← Device Tester → human → Critic
    critic_report.md                 ← Critic → Sync, Orchestrator
  app/src/main/kotlin/.../
    camera/                          ← UVC frame acquisition, HID enable, MediaPipe
    gesture/                         ← HandData, GestureRecognizer, GestureConfig
    controller/                      ← DesktopAction, GestureActionDispatcher, AccessibilityDesktopController
    service/                         ← GestureAccessibilityService, UsbSetupAutomator
    MainActivity.kt
    GrantUsbPermissionActivity.kt
```

---

## Two-Tier Loop

Choose the right loop before starting. Wrong choice wastes tokens.

### Quick Loop — pure code changes

Use when: change is single-layer, unit-testable, touches no UVC/HID/USB/AccessibilityService.
Typical: gesture math, dispatcher logic, config, refactors.

```
You write a 3-line inline spec
    ↓
[Builder]   — reads inline spec + file_targets only
    ↓
make check  — unit tests + ktlint (Builder runs this directly)
    ↓
[Critic]    — reads diff_summary + test output only; no source files
    ↓
  PASS → [Sync]
  FAIL → [Builder] with fix list
```

**No Architect. No Device Tester. No context_packet.json. No device needed.**

---

### Full Loop — hardware-touching features

Use when: any acceptance criterion requires physical glasses or phone interaction.
Typical: UVC, HID, USB permission flow, AccessibilityService dispatch, display output.

```
Feature prompt (description + acceptance criteria)
    ↓
[Architect]      — reads prompt + CLAUDE.md once → context_packet.json
    ↓
[Builder]        — reads context_packet.json only (never CLAUDE.md)
    ↓
[Device Tester]  — produces batched device session script; halts for human
    ↓
human fills observation form (one round trip)
    ↓
[Critic]         — reads diff_summary + observation_form; source only on doubt
    ↓
  PASS → [Sync]
  FAIL → [Builder] with fix list → Device Tester re-runs only changed criteria
```

**CLAUDE.md is read exactly once per issue — by Architect. It must not be re-read by any other agent.**

---

## Graphify — Per-Phase Reference

`graphify-out/graph.json` is built from AST extraction (no API cost). Keep it current
with `graphify update .` after any code change. Raw file reads are only permitted after
graphify has oriented you, or when modifying/debugging a specific known line.

### Command reference

| Command | When to use |
|---|---|
| `graphify query "<topic>"` | Orient on a subsystem — returns nodes + edges within 2 hops |
| `graphify explain "<Class>"` | Understand what one class does before modifying it |
| `graphify path "<A>" "<B>"` | Find how two classes connect — spot missing or unexpected edges |

### Architect — planning use

Run graphify *before* deciding file_targets or writing acceptance criteria:

```bash
# 1. Find which nodes the feature touches
graphify query "<feature area>"

# 2. Understand integration points — how new code must connect to existing code
graphify path "<entry class>" "<destination class>"

# 3. Understand any class you plan to modify
graphify explain "<ClassName>"
```

Use the output to:
- Set `affected_layers` and `file_targets` based on actual graph communities, not guesses
- Identify nodes that sit between the entry and destination — these may also need changes
- Spot unexpected paths that could be broken by the change
- Populate `graphify_context` in context_packet.json with key paths and communities found

### Builder — implementation use

Before writing code:
```bash
# Orient on each file_target
graphify query "<class or concept from file_target>"

# Confirm the integration path matches the spec
graphify path "<class you're modifying>" "<class it must reach>"
```

After writing code:
```bash
# Rebuild the graph to include new nodes/edges
graphify update .

# Verify new edges exist and flow in the right direction
graphify path "<new class>" "<integration point>"
graphify query "<new class>"
```

The post-implementation check is mandatory — new nodes that appear isolated or connected
to wrong communities indicate a wiring mistake. Record the check result in `diff_summary.md`.

### Critic — verification use

Before reading any source file, run:
```bash
graphify path "<changed class>" "<expected integration point>"
```

If the path exists with the expected edge type, the integration criterion is confirmed
from the graph alone — no raw source read needed. Only open source files when the graph
path is missing, unexpected, or ambiguous.

---

## Agent Roles

---

### 1. Architect Agent  *(full loop only)*

**Trigger:** A feature prompt arrives with no `context_packet.json` in `.agent-state/`.

**Purpose:**
Translate the feature prompt into a minimal spec. Distill all relevant hardware context from
CLAUDE.md into context_packet.json so no downstream agent ever needs to read CLAUDE.md.

**Reads:**
- Feature prompt: title, description, acceptance criteria (from user's session message)
- CLAUDE.md — hardware findings, timing constraints, USB state notes
- Previous context_packet.json (continuity across features in a milestone)
- graphify — run the planning workflow from the Graphify section before setting file_targets

**Graphify planning workflow (required):**
1. `graphify query "<feature area>"` — identify affected nodes and communities
2. `graphify path "<entry class>" "<destination class>"` — find the integration path; note intermediate nodes
3. `graphify explain "<class>"` — for any class you plan to modify
4. Populate `graphify_context` in context_packet.json from the results

**Never reads:** source files directly (use graphify), session history, Builder output.

**Output:** `.agent-state/context_packet.json`

```json
{
  "milestone": "string",
  "feature": "short title from prompt",
  "loop": "full",
  "scope": ["in-scope items"],
  "out_of_scope": ["explicit exclusions"],
  "affected_layers": ["camera|gesture|controller|service|ui"],
  "file_targets": ["files to create or modify"],
  "hardware_constraints": {
    "usb_state_needed": "UVC active | non-UVC | first-plug",
    "timing_notes": ["e.g. HOST_TYPE must arrive <100ms after attach"],
    "permission_state": "granted | first-plug | either"
  },
  "acceptance_criteria": ["each item independently verifiable"],
  "device_testable_criteria": ["subset requiring physical device — be minimal"],
  "open_questions": ["blocks Builder — surface immediately"],
  "graphify_context": {
    "key_paths": ["e.g. UsbSetupAutomator → GlassesUvcEnabler (2 hops via .launchHidEnable)"],
    "communities": ["community IDs relevant to this change"],
    "isolated_risk": ["nodes that need new edges — currently disconnected from destination"]
  }
}
```

**Token budget:** Low. No prose. Compress hardware constraints to facts only.

**Human gate:** If open_questions non-empty → print 🟡 block and halt.

---

### 2. Builder Agent

**Trigger:** Quick loop: feature prompt is self-contained (≤ 3 criteria, no hardware). Full loop: `context_packet.json` with no open_questions.

**Purpose:** Implement exactly what the spec defines. No scope expansion.

**Reads:**
- Quick loop: feature prompt directly (3 criteria max) + file_targets from prompt
- Full loop: `.agent-state/context_packet.json` only (including `graphify_context`)
- graphify — run the implementation workflow before touching any file
- Raw source files in file_targets only (after graphify orientation)
- Fix list from Critic (on re-runs)

**Graphify implementation workflow (required):**

*Before writing code:*
1. `graphify query "<class or concept>"` for each file_target — understand current shape
2. `graphify path "<class to modify>" "<integration point from spec>"` — confirm path matches spec; flag if missing
3. `graphify explain "<class>"` for any unfamiliar class in the path

*After writing code:*
1. `graphify update .` — rebuild graph to include new nodes/edges
2. `graphify path "<new/modified class>" "<integration point>"` — verify edge exists and flows correctly
3. `graphify query "<new class>"` — check it's in the expected community, not isolated
4. Record result in `diff_summary.md` under **Graphify check**

**Never reads:** CLAUDE.md, GitHub issues, files outside file_targets, previous session history.

**Rules:**
- `make fmt && make check` must pass before producing diff_summary
- Tests required for gesture logic and any new public API
- Hardware-specific code must cite the CLAUDE.md finding in a one-line comment (e.g., `// CLAUDE.md: "MCU config window <100ms"`)
- No fallback paths for hardware states not in hardware_constraints

**Output:**
- Modified/created source files
- `.agent-state/diff_summary.md`

```markdown
## Diff Summary
- **Feature:** [title] / inline spec
- **Loop:** quick | full
- **Files changed:** list
- **Layers:** camera|gesture|controller|service|ui
- **Built:** [2 sentences max]
- **Deviations:** none | list
- **Gaps:** none | list
- **Tests added:** [file — what they cover]
- **Lint:** PASS
- **Graphify check:** [path verified: A → B (N hops) | isolated node detected: ClassName | community correct: yes/no]
```

**Token budget:** Medium. No prose outside the structured fields.

---

### 3. Device Tester Agent  *(full loop only)*

**Trigger:** Builder produces diff_summary.md and device_testable_criteria is non-empty.

**Purpose:**
Compile ALL device-testable criteria into a single batched session script. One human
interaction covers the entire issue. If all criteria are unit-testable, run `make check`
directly and skip the gate.

**Reads:**
- `.agent-state/context_packet.json` (device_testable_criteria, hardware_constraints)
- `.agent-state/diff_summary.md`
- Makefile (for check target)

**Never reads:** source files, CLAUDE.md, GitHub issues.

**Rules:**
- Run `make check` immediately and record result — always, regardless of device_required
- Produce exactly one observation form covering all device_testable_criteria — never two halts for one issue
- Each `adb logcat` step must specify the exact filter command, not a tag name:
  `adb logcat -s Tag1:D Tag2:D *:S` — human pastes the filtered output only (~5–10 lines)
- Visual observations (what's on the glasses display) are a first-class evidence type — use them
- Group steps so the human never needs to unplug/replug mid-session unless the criterion specifically requires it

**Output:** `.agent-state/observation_form.md` (pre-filled where possible; blanks for human)

```markdown
## Observation Form
- **Issue:** #XX
- **Unit tests:** PASS / FAIL — [N passing]
- **USB state going in:** [UVC active | non-UVC | first-plug]

### Steps
1. [action]
2. [action]

### Observations
| # | What to observe | Your answer |
|---|---|---|
| 1 | USB dialog appeared? | |
| 2 | App shown in chooser | |
| 3 | `adb logcat -s Requirements:D GlassesUvcEnabler:D *:S` output | |
| 4 | Glasses display shows | |
| 5 | Hand tracking visible? | |

### Criteria map (for Critic)
- [criterion A] → confirmed by observation [#]
- [criterion B] → confirmed by observation [#]
```

**Human gate:** If device_required = true → print 🔵 block (steps + observation form) and halt.
On resume: fill in confirmed/failed per criterion and hand to Critic.

---

### 4. Critic Agent

**Trigger:**
- Quick loop: Builder produces diff_summary with lint PASS and `make check` PASS
- Full loop: observation_form.md is complete (no blank required fields)

**Purpose:**
Contract check — does the build satisfy acceptance_criteria? Not a code review.

**Reads (in order — stop when sufficient):**
1. `.agent-state/diff_summary.md` — verify lint, tests, graphify check result, deviations
2. `.agent-state/observation_form.md` — match observations to device_testable_criteria
3. `.agent-state/context_packet.json` — acceptance_criteria and graphify_context reference
4. `graphify path "<changed class>" "<integration point from graphify_context>"` — confirm integration edges exist; a valid path satisfies structural criteria without opening source
5. Raw source files in file_targets — **only if a criterion cannot be confirmed from steps 1–4**

**Never reads:** CLAUDE.md, GitHub issues, full codebase, session history.

**Output:** `.agent-state/critic_report.md`

```markdown
## Critic Report
- **Issue:** #XX
- **Loop:** quick | full
- **Result:** PASS | FAIL
- **Criteria:**
  - ✅/❌ [criterion] — [evidence: diff line / observation # / source ref]
- **Lint/tests:** PASS | FAIL
- **Scope creep:** none | [list]
- **Fix list:** (FAIL only) numbered, specific, actionable
```

**Token budget:** Low. Binary result. Fix list only — no explanation of passing items.

**On FAIL:** Send fix list to Builder. Device Tester re-runs only for criteria whose fix
touches device_testable_criteria — skip re-running criteria already confirmed.

---

### 5. Sync Agent

**Trigger:** critic_report.md result = PASS.

**Purpose:** Commit and (optionally) open PR. Never touches source code.

**Reads:**
- `.agent-state/critic_report.md`
- `.agent-state/diff_summary.md`
- Current git status

**Actions:**
1. `make fmt` — final lint pass
2. Commit: `[type]: [description]`
3. Push branch
4. Open PR if requested by user (title matches commit; body lists ✅ criteria + observation evidence)
5. If milestone complete → print 🏁 block and halt

**Token budget:** Minimal.

---

### 6. Orchestrator

**Trigger:** Start of every loop iteration.

**Reads:** Presence/absence and content of `.agent-state/` handoff files only.

**Never reads:** source code, CLAUDE.md, GitHub issues.

**Routing:**
```
feature prompt received, no context_packet.json
  + prompt is ≤ 3 criteria, no hardware         → quick loop → Builder directly
  + prompt has hardware criteria                → full loop → Architect
context_packet.json present
  + open_questions unresolved                   → HALT 🟡
  + no diff_summary.md                         → Builder
  + diff_summary.md present
      + loop = quick                            → Critic (skip Device Tester)
      + loop = full
          + device_testable_criteria empty      → Critic (skip Device Tester)
          + observation_form incomplete         → Device Tester → HALT 🔵
          + observation_form complete           → Critic
  + critic_report = PASS                        → Sync
  + critic_report = FAIL                        → Builder (fix list); Device Tester only if needed
milestone complete                              → HALT 🏁
```

**Token budget:** Minimal. Routing only.

---

## Handoff File Reference

| File | Written by | Read by | Notes |
|---|---|---|---|
| `context_packet.json` | Architect | Builder, Device Tester, Critic | Only way hardware context propagates; Architect reads CLAUDE.md so others don't have to |
| `diff_summary.md` | Builder | Device Tester, Critic, Sync | Lint status must be PASS |
| `observation_form.md` | Device Tester + human | Critic | One form per issue; no re-halts |
| `critic_report.md` | Critic | Sync, Orchestrator | |

All files live in `.agent-state/` (gitignored). Clear between issues.

---

## Subsystem Layer Reference

| Layer | Files | Responsibility |
|---|---|---|
| `camera` | `camera/` | UVC bulk acquisition, HID UVC-enable, MJPEG decode, MediaPipe MPImage |
| `gesture` | `gesture/` | HandData, landmark → joint mapping, GestureRecognizer, GestureConfig |
| `controller` | `controller/` | DesktopAction enum, GestureActionDispatcher, AccessibilityDesktopController |
| `service` | `service/` | GestureAccessibilityService, UsbSetupAutomator, lifecycle |
| `ui` | `MainActivity.kt`, `GrantUsbPermissionActivity.kt` | Requirements display, USB permission flow |

---

## Hardware Testing Ground Rules

- UVC, HID, USB permissions, AccessibilityService dispatch — always device_required = true
- Emulator can satisfy gesture logic and controller logic only
- `adb logcat -s Requirements:D *:S` is the six-prerequisite state view — always include it
- Specify USB state going in: `first-plug` (permission dialog expected), `fast-path` (permission cached), `UVC active`
- Visual observations on the glasses display are valid evidence — describe what you see
- Never promote PENDING to PASS — one confirmed logcat line or visual is enough; a guess is not

---

## Ground Rules for All Agents

1. **Choose the right loop.** Quick for pure code; full for hardware. Wrong choice = wasted tokens.
2. **CLAUDE.md is read once.** Architect distills it; no other agent touches it.
3. **Graphify drives planning and verification.** Architect uses it to set file_targets. Builder uses it before and after writing code. Critic uses it before opening source files. Raw reads are always the last resort.
4. **One device session per issue.** Device Tester batches everything; never halts twice.
5. **Exact adb commands.** `adb logcat -s Tag:D *:S` — not tag names, not `make logcat`.
6. **Critic reads diff first.** Source files only when criteria can't be confirmed otherwise.
7. **Stay in role.** Do not reason about inputs outside your defined reads.
8. **Flag don't fix.** Out-of-scope findings are flagged, not silently added.
9. **Lint must pass.** `make fmt` before any handoff (sets correct `JAVA_HOME` — raw `./gradlew` fails outside Android Studio).
10. **Milestone gates need APPROVE.** No agent starts a new milestone autonomously.

---

## How to Start a Loop

Open a fresh Claude Code session in this repo and paste:

```
Read AGENTS.md. Run as Orchestrator.

Feature: [short title]

Description:
[2–5 sentences — what this does and why]

Acceptance criteria:
- [ ] [criterion 1]
- [ ] [criterion 2]
- [ ] make check passes
```

The Orchestrator reads the prompt, decides quick vs full loop, and routes to the right agent.
No `.agent-state/` setup required — Orchestrator creates it on first run.

---

## Current Milestones

| # | Milestone | Status |
|---|---|---|
| 1 | Zero-interaction UVC enable + camera open | **Done** |
| 2 | Full gesture pipeline — pinch/swipe → desktop click | In progress |
| 3 | Phone screen off — glasses desktop remains active | Not started |
| 4 | Voice keyboard input in glasses desktop | Not started |
| 5 | macOS companion app (KMP + Swift/CGEvent) | Stretch |

---

*This file is the canonical agent reference for xrdroiddesk.
All Claude sessions and Claude Code instances should treat this as authoritative.
Update via PR — do not edit directly on main.*
