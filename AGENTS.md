# AGENTS.md

Instructions for AI coding agents (Codex, Cursor, Copilot, Gemini, etc.).

**The canonical project context is [`CLAUDE.md`](CLAUDE.md). Read it in full before doing anything.**
It covers the product, stack (Spring Boot 4 / Java 21 / PostgreSQL), architecture, non-negotiable
business rules, and how the developer wants to be helped (mentor mode: explain the *why*, guide
first, write full code only when asked).

Then read, as needed:
- `docs/ROADMAP.md` for the current phase and next task
- `docs/DOMAIN_RULES.md` for business rules, state machines and error codes (authoritative)
- `docs/DECISIONS.md` for technology and architecture decisions with reasons
- `docs/CONVENTIONS.md` for code patterns, migrations, tests and Definition of Done

Build and test from `Nexora-backend/`: `docker compose up -d`, `./gradlew build`.
