---
description: Explain a technology, pattern or design decision and why SmartSilk uses it
argument-hint: "<topic, e.g. pessimistic locking | JWT refresh rotation | why UUID>"
---
Explain **$ARGUMENTS** in the context of the SmartSilk/Nexora project.

Structure:
1. **What it is**: plain-language definition, with a small diagram if it helps.
2. **The problem it solves in SmartSilk**: a concrete scenario using our domain (silk kg, payments, orders…).
3. **Why we chose it**: cite the ADR in `docs/DECISIONS.md` if one exists. If no ADR exists and this is a real decision, propose one.
4. **Alternatives & trade-offs**: what we rejected and when that alternative *would* be the right choice.
5. **How it looks in our code**: where it lives (package/file) plus a short snippet in Spring Boot 4 / Java 21 style, or Kotlin / Compose Multiplatform style for client topics.
6. **Common mistakes**.
7. **Interview answer**: how I'd explain it in 3–4 sentences.

Keep it tight. Prefer concrete examples over theory.
