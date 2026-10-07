# AGENTS.md

Instructions for AI coding agents (Codex, Cursor, Copilot, Gemini, etc.) helping **Developer B**.

**The canonical project context is [`CLAUDE.md`](CLAUDE.md). Read it in full before doing anything.**
It covers the product, the two-developer split, the stack (Kotlin + Compose Multiplatform desktop client; Spring Boot 4 /
Java 21 / PostgreSQL backend), the non-negotiable business rules, and how Developer B wants to be helped (mentor mode:
explain the *why*, guide first, write full code only when asked).

Then read, in this order:
- `docs/ROADMAP_DEV_B.md` for the current milestone and next task (start here)
- `docs/TEAM_SPLIT.md` for ownership, contracts with Developer A and the rules that prevent conflicts
- `docs/AI_OPERATING_MANUAL.md` for modes, verified stack facts and the review checklist
- `docs/DOMAIN_RULES.md` (business rules, authoritative), `docs/DECISIONS.md` (ADRs), `docs/CONVENTIONS.md` (patterns)
- the numbered docs the task needs: `05-API-SPEC.md` (endpoints), `06-UI-SPEC.md` (screens), `07-TASKS.md` (backend task details),
  `08-TESTING.md` (tests), `04-DATA-MODEL.md` (tables)

Backend: `cd Nexora-backend && docker compose up -d && ./gradlew build`. Client (after M0): `cd nexora-client && ./gradlew check`.
