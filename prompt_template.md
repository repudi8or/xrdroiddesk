# xrdroiddesk — Feature Prompt Template
# Version: 1.0

## How to use

1. Copy the template block below into `current_feature.md` (repo root).
2. Fill it in — Feature, Milestone, Loop, Done when, Scope, Hardware state.
3. Boot the loop with Template 1 from `session-boot-template.md`:

```
Read current_feature.md. Read AGENTS.md. Begin routing now.
```

The Architect reads `current_feature.md` **once** and distills it into `.agent-state/context_packet.json`.
No other agent reads `current_feature.md`.
Replace `current_feature.md` contents when starting the next feature.

---

## Loop type decision

| Condition | Loop |
|---|---|
| Change touches only gesture math, dispatcher logic, config, or pure refactors | **Quick** |
| Any acceptance criterion requires physical glasses, phone, USB, or AccessibilityService | **Full** |

When unsure, default to **Full**.

---

## Template (copy from here)

---

### Feature: [title — one line, verb-noun form]

**Milestone:** [milestone number and name, e.g. "2 — Full gesture pipeline"]

**Loop:** Quick / Full  *(delete one)*

---

#### What
*One paragraph. What this feature does from the user's perspective. No implementation details.*

---

#### Done when
*Each criterion must be independently verifiable. Mark device-observable criteria with 📱.*

- [ ] [criterion]
- [ ] [criterion — 📱 requires physical glasses/phone]

---

#### Scope

**In:**
- [item]

**Out:**
- [item — explicit exclusions prevent scope creep]

---

#### Hardware state going in *(Full loop only — delete section for Quick loop)*

- [ ] UVC active — camera open, MediaPipe running, Milestone 1 complete
- [ ] Non-UVC — glasses attached, no camera yet
- [ ] First-plug — permission dialog expected
- [ ] N/A — no USB interaction

**Permission state:** granted / first-plug / either  *(delete two)*

---

#### Known constraints *(optional)*

*Timing notes, library version pins, ordering dependencies, edge cases the Builder must not ignore.*

- [constraint]

---

#### Hints *(optional — Architect will verify these against graphify)*

*Layers you think are affected, class names you've thought about. Architect confirms or corrects.*

**Layers:** camera / gesture / controller / service / ui  *(circle or delete)*

**Classes likely touched:**
- [ClassName]

---

## Worked example — Mileston
