# 09 - Security

Scope: authentication, authorization, data protection and operational security of the Nexora backend (one business, one
database, a desktop client on Windows and Linux). Precedence: `DECISIONS.md` ADR-014 (auth), ADR-021 (secrets), ADR-022 (observability), and the
design notes in `smart-ledger-docs/api_backenddesign_authentication_autherization.txt` (section 8.8.23 checklist). Requirement
NFR 20.3 (authentication required; only the owner accesses the system in the MVP; financial modifications protected).
Architecture context: `03-ARCHITECTURE.md`; deployment controls: `10-DEPLOYMENT.md`.

Everything marked **(implemented)** exists in `com.nexora.shared.security` today; everything else is the specified behaviour
for the named phase.

## 1. Principles
1. The backend is the only authority: clients send intent, never derived values (stock, balances, outstanding).
2. Authenticate every request except login/refresh/logout and health probes; authorize by **permission**, not by role name.
3. Defence in depth: DTO validation -> domain rules -> database constraints and role grants.
4. Financial and inventory history is append-only (Rule R5); corrections are reversals.
5. Secrets exist only in the environment; nothing sensitive is logged or returned.
6. Fail closed: a missing secret, a weak secret, or a missing property stops startup.

## 2. Authentication

### 2.1 Login (`POST /api/v1/auth/login`, Phase 1)
Request `{username, password}`; response `{accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900}`. Never returns the hash.

Algorithm (`AuthService.login`, one transaction, `noRollbackFor = BusinessException` so the failure counter is saved even
though an exception is thrown):
1. Normalize the username: trim + lower case (the DB has `CHECK (username = lower(username))`).
2. Load the user with `SELECT ... FOR UPDATE` (concurrent attempts for one user are serialized).
3. Unknown user, inactive user, or currently locked user: perform a dummy `PasswordEncoder.matches` against a pre-computed
   hash (equalizes timing), then fail with **401 `INVALID_CREDENTIALS`**. The response is identical for all three cases
   (no user enumeration, no "account locked" signal).
4. Wrong password: `failed_login_attempts += 1`; when it reaches `max-failed-logins` (5) set `locked_until = now + 15 min`; fail
   with 401 `INVALID_CREDENTIALS`.
5. Correct password: `failed_login_attempts = 0`, `locked_until = null`, `last_login_at = now`; issue tokens.
6. A lockout writes a WARN log line (`event=login_locked userId=...`, never the password) and, from Phase 3 when `audit_log` exists, an `audit_log` row (`LOGIN_LOCKED`).

### 2.2 Access token (implemented: `JwtConfig`, `SecurityProperties`)
| Property | Value |
|---|---|
| Format | JWT (JWS compact), algorithm **HS256** |
| Key | `nexora.security.jwt-secret` (env `JWT_SECRET`), UTF-8 bytes, **minimum 32 bytes** (startup fails otherwise: `SecurityProperties` constructor) |
| Library | Spring Security `NimbusJwtEncoder` / `NimbusJwtDecoder` (Nimbus JOSE 10.9.1); no other JWT library |
| Lifetime | 15 minutes (`access-token-ttl`) |
| Claims | `iss = "nexora"`, `sub` = user UUID, `iat`, `exp`, `jti` = random UUID, `username`, `roles` (array of role names), `permissions` (array of `Permission` names) |
| Never in claims | password or hash, balances, bank details, phone numbers, any party data |
| Validation on every request | signature, `exp` (clock skew 60 s, Nimbus default), `iss` equals `nexora` |
| Authorities | claim `permissions` -> Spring authorities **without prefix** (`hasAuthority('PAYMENT_CREATE')`); (implemented: `SecurityConfig.jwtAuthenticationConverter`) |
| Transport | `Authorization: Bearer <token>` over HTTPS only |

### 2.3 Refresh token (Phase 1)
- Random opaque value: 32 bytes from `SecureRandom`, Base64-URL without padding. Only its **SHA-256 hex hash** (64 chars) is stored
  in `refresh_tokens.token_hash` (`UNIQUE`); the raw value is returned once and never logged.
- Lifetime 7 days (`refresh-token-ttl`); stored `expires_at`; `revoked_at` null while valid.
- `POST /api/v1/auth/refresh {refreshToken}` (transaction, `noRollbackFor = BusinessException`):
  1. hash the presented token, load the row `FOR UPDATE`; not found -> 401 `UNAUTHENTICATED`;
  2. **reuse detection:** if `revoked_at` is not null the token was already rotated: revoke **every** active refresh token of that
     user (`UPDATE ... SET revoked_at = now WHERE user_id = ? AND revoked_at IS NULL`), write audit `REFRESH_TOKEN_REUSE`, log WARN,
     fail 401 `UNAUTHENTICATED` (a thief and the owner both have to log in again);
  3. expired -> 401 `TOKEN_EXPIRED`; user inactive -> 401 `UNAUTHENTICATED`;
  4. **rotation:** set `revoked_at = now` on the presented token, issue a new access + refresh pair.
- `POST /api/v1/auth/logout {refreshToken}`: revoke that token; unknown or already revoked token still returns 204 (idempotent).
- Password change (future) and user deactivation revoke all of the user's refresh tokens.
- A daily job deletes rows with `expires_at < now() - 30 days`.
- Why opaque and not a JWT (ADR-014): it must be revocable, which needs a database lookup anyway.

### 2.4 Passwords
| Rule | Value |
|---|---|
| Algorithm | BCrypt via `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (implemented: `SecurityConfig.passwordEncoder`); stored as `{bcrypt}$2a$10$...`, column `VARCHAR(255)`; the prefix allows upgrading the algorithm later without breaking old hashes |
| Policy (on create/change) | length 12-72 **bytes** (BCrypt ignores input beyond 72 bytes, so longer values are rejected), at least one letter and one digit, must differ from the username |
| Storage | hash only; plain text never persisted, logged, or returned |
| Initial owner | created once at startup from `BOOTSTRAP_OWNER_USERNAME`/`BOOTSTRAP_OWNER_PASSWORD` **only if the `users` table is empty**; the variables are removed from the environment after the first start; a weak bootstrap password stops startup |

### 2.5 401 versus 403 (implemented: `SecurityErrorHandlers`, `GlobalExceptionHandler`)
| Situation | HTTP | `code` |
|---|---|---|
| No `Authorization` header, malformed token, bad signature, wrong issuer | 401 | `UNAUTHENTICATED` |
| Token valid but expired (message contains "expired") | 401 | `TOKEN_EXPIRED` |
| Wrong username/password, locked, inactive | 401 | `INVALID_CREDENTIALS` |
| Valid token, missing permission (`@PreAuthorize`) | 403 | `ACCESS_DENIED` |
Both are written as `application/problem+json` with `code` and `timestamp`, from the filter chain by `ProblemJsonWriter` and from controllers by
`GlobalExceptionHandler`. Interview line: 401 = "who are you?", 403 = "I know who you are, but no".

## 3. Authorization

### 3.1 Model
Permissions are the `Permission` enum (implemented) mirrored in the `permissions` table (seeded by migration `V5`); roles are bundles in
`role_permissions`. A startup/integration test asserts every enum value exists in the table and every table row exists in the enum.
Every controller method carries `@PreAuthorize("hasAuthority('<PERMISSION>')")`. A test scans all `@RestController` methods and fails when
a mapped method has no `@PreAuthorize`, or names an authority that is not in `Permission` (guards typos the compiler cannot see).

Roles seeded in the MVP (PLAN_DECISIONS Q8): `OWNER` (all 23 permissions) and `STAFF` (`PARTY_VIEW`, `INVENTORY_VIEW`, `ORDER_VIEW`,
`PRODUCTION_VIEW`; read-only). Only `OWNER` logs in at go-live; no user-management endpoint exists in the MVP (`USER_MANAGE` is reserved).

### 3.2 Permission matrix (endpoint groups; exact routes in `05-API-SPEC.md`)
`R` = read (GET), `W` = write (POST/PATCH). Columns show whether the role can call the group.

| Endpoint group | Permission required | OWNER | STAFF |
|---|---|---|---|
| `/auth/login`, `/auth/refresh`, `/auth/logout` | public | yes | yes |
| `GET /auth/me` | authenticated | yes | yes |
| Parties R | `PARTY_VIEW` | yes | yes |
| Parties W (create, update, activate, deactivate) | `PARTY_MANAGE` | yes | no |
| Purchases R | `PURCHASE_VIEW` | yes | no |
| Purchases W, receipts, returns | `PURCHASE_CREATE` | yes | no |
| Inventory summary, movements, external WIP | `INVENTORY_VIEW` | yes | yes |
| Inventory adjustment, manual wastage, opening stock | `INVENTORY_ADJUST` | yes | no |
| Orders R | `ORDER_VIEW` | yes | yes |
| Orders W (create, update, confirm, cancel, ready, deliver, complete) | `ORDER_MANAGE` | yes | no |
| Production batches R | `PRODUCTION_VIEW` | yes | yes |
| Production batches W (create, start, complete, cancel) | `PRODUCTION_MANAGE` | yes | no |
| Outsourcing jobs (R and W) | `OUTSOURCING_MANAGE` | yes | no |
| Work records (R and W) | `WORK_RECORD_MANAGE` | yes | no |
| Payments R, customer outstanding, supplier / manufacturer / worker payables | `PAYMENT_VIEW` | yes | no |
| Payments W (receive/pay) | `PAYMENT_CREATE` | yes | no |
| Payment reversal | `PAYMENT_REVERSE` | yes | no |
| Finance accounts, transactions, transfers, cash position R; opening obligations R; notification configuration R | `FINANCE_VIEW` | yes | no |
| Finance accounts W (create, close), account adjustments, transfers, opening obligations W, notification configuration W | `FINANCE_MANAGE` | yes | no |
| Expenses W and R | `EXPENSE_CREATE` | yes | no |
| Loans (R and W) | `LOAN_MANAGE` | yes | no |
| Chits (R and W) | `CHIT_MANAGE` | yes | no |
| Dashboard, reports, customer statement, notification list | `REPORT_VIEW` | yes | no |
| Documents upload/download | `DOCUMENT_MANAGE` | yes | no |
| User management (future) | `USER_MANAGE` | yes | no |
| `/actuator/health/**`, `/actuator/info` | public (health details hidden in prod) | yes | yes |
| `/v3/api-docs`, `/swagger-ui/**` | public in `dev`; **404 in prod** (springdoc disabled) | - | - |
| Other actuator endpoints (Phase 7: metrics, prometheus) | management port 8081, not published | - | - |

Each cell is verified by a web test: a STAFF token on every OWNER-only group returns 403 `ACCESS_DENIED`; no token returns 401 `UNAUTHENTICATED`.

## 4. Session and request handling
- Stateless: `SessionCreationPolicy.STATELESS`, no cookies; CSRF protection disabled because no browser session credentials exist
  (implemented in `SecurityConfig`).
- Method security on (`@EnableMethodSecurity`).
- Unknown URLs return 401 when unauthenticated (the chain denies before routing), 404 only after authentication.

## 5. Input validation
Three layers (DTO, domain, database), per `api_backenddesign_dto_validation.txt`:

| Layer | Rule | Failure |
|---|---|---|
| DTO (Jakarta Validation on `record`s) | `@NotNull` on required fields, `@NotBlank` + `@Size(max=...)` on text (names 200, notes 1000, address lines 255, phone 30), `@Positive`/`@PositiveOrZero`, `@Digits(integer=12, fraction=2)` for money and `@Digits(integer=9, fraction=3)` for kg, `@Size(min=1)` on item lists, `@Pattern` for phone (`^[0-9+()\- ]{6,30}$`), enums parsed from fixed values | 400 `VALIDATION_FAILED` with `errors[]` |
| Domain (entity methods/services) | state transitions, receipt limits, role checks, sufficiency, allocation sums | 409/422 with the stable code |
| Database | `NOT NULL`, `CHECK`, `UNIQUE`, FK, partial unique indexes | 409 `DUPLICATE_RESOURCE` (unique) / 500 (anything else = missed validation) |

Additional rules:
- Request DTOs contain only client-owned fields (no ids of new rows, no business numbers, statuses, totals, timestamps) - mass assignment is impossible.
- Path ids are typed `UUID`; a malformed id is a 400.
- All queries use parameters (JPQL/Specification/native with named parameters). String concatenation into SQL is forbidden; `LIKE` input has `%`, `_`, `\` escaped.
- Body size: requests over **1 MiB** (10 MiB on `/documents`) are rejected with 413 before parsing (Phase 7 filter); header size limit 8 KiB (Tomcat default).
- Uploaded documents (Phase 6): allow-list of content types (`application/pdf`, `image/jpeg`, `image/png`); content type verified from file bytes, not the header; stored name is a generated UUID, never the client file name.
- Dates: business dates may not be more than 366 days in the future or 10 years in the past (rejects typos such as year 2206).

## 6. Rate limiting and brute-force protection (Phase 7 for the filter; login lock in Phase 1)
| Control | Where | Limit | Response |
|---|---|---|---|
| Per-account login lock | `AuthService` + `users.failed_login_attempts/locked_until` (section 2.1) | 5 consecutive failures -> locked 15 min | 401 `INVALID_CREDENTIALS` |
| Per-IP limit on `/api/v1/auth/**` | `RateLimitFilter` (servlet filter placed before the Spring Security chain; in-memory sliding window per IP, no new dependency) | 10 requests per minute | 429 `RATE_LIMITED`, header `Retry-After` |
| Per-user limit on other API calls | same filter, key = JWT `sub` (or IP when unauthenticated) | 300 requests per minute | 429 `RATE_LIMITED` |
| Pagination cap | `spring.data.web.pageable.max-page-size: 100` | 100 rows | silently capped |
| DB protection | Hikari `maximum-pool-size 10`, `connection-timeout 5s`, `lock_timeout 5s`, `statement_timeout 30s` | - | 409/500 as in `03-ARCHITECTURE.md` section 6 |

The client IP comes from `X-Forwarded-For` set by Caddy: the app runs with `server.forward-headers-strategy: framework` in `prod`, and the
application container is reachable **only** from the compose network (port not published), so the header cannot be forged by outside clients.
Ceiling: the in-memory window is per instance - correct for the single-instance deployment (ADR-020); introduce a shared store only if a second instance is ever added.

## 7. CORS
The MVP client is a native desktop application (Kotlin + Compose Desktop on the JVM), which does not use CORS. Default policy: **no CORS headers** (browsers cannot call the API cross-origin).
If a browser client is added, set `nexora.cors.allowed-origins` (comma-separated exact origins, no `*`): allowed methods `GET, POST, PATCH`,
headers `Authorization, Content-Type, Idempotency-Key, X-Request-Id`, exposed headers `X-Request-Id, Idempotency-Replayed, Location`,
`allowCredentials = false`, `maxAge = 3600`. Swagger UI is served by the same origin as the API, so it needs no CORS.

## 8. Transport and security headers
- HTTPS everywhere in `prod`: Caddy terminates TLS (automatic certificates), redirects HTTP -> HTTPS, sets `Strict-Transport-Security: max-age=31536000`.
  Minimum TLS 1.2 (Caddy default).
- Spring Security default response headers stay on (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`).
- Added in `SecurityConfig` (Phase 1): `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` for API responses
  (the `dev` profile relaxes CSP only for `/swagger-ui/**`), `Permissions-Policy: geolocation=(), camera=(), microphone=()`.
- Server banner hidden: `server.server-header` empty; error responses never contain stack traces (`server.error.include-stacktrace: never`, `include-message: never`).

## 9. Secrets handling (ADR-021)
| Secret | Where it lives | Rule |
|---|---|---|
| `JWT_SECRET` | environment of the app container (`.env` on the VPS, mode 600, owned by the deploy user) | >= 32 random bytes (`openssl rand -base64 48`); never committed; different value per environment |
| `DB_PASSWORD`, `FLYWAY_PASSWORD` | same | >= 24 random characters; two different roles |
| `BOOTSTRAP_OWNER_PASSWORD` | environment, first start only | removed afterwards; owner changes it on first login |
| `NVD_API_KEY`, registry/SSH deploy keys, backup keys | GitHub Actions secrets / VPS | never printed in logs (masked) |
| Dev defaults | `application-dev.yml` only (local DB, dev JWT constant) | prod profile has no defaults |
Repository rules: `.env` is git-ignored; `.env.example` holds names and fake values only; a CI step greps the diff for
`password=`, `secret=`, `BEGIN PRIVATE KEY`, `AKIA` and fails on a hit.

**JWT secret rotation (procedure, takes < 5 minutes):** (1) generate a new secret; (2) change `JWT_SECRET` in the VPS `.env`; (3) `docker compose up -d app`
(restart); (4) every existing access token (<= 15 min old) is rejected with `UNAUTHENTICATED`, the desktop client calls `/auth/refresh`
(refresh tokens are opaque and stored in the DB, so they survive) and receives a token signed with the new key; (5) record the rotation in `docs/` operations log.
Rotate on a schedule of every 6 months and immediately after any suspected leak; after a leak also revoke all refresh tokens
(`UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL`).

## 10. Audit trail and data protection

### 10.1 `created_by` everywhere
Every table extending `AuditableEntity` stores `created_at`, `updated_at`, `created_by` (the JWT `sub`, filled by `AuthenticatedUserAuditor`) and `version`.
Rows created at startup or by scheduled jobs have `created_by = NULL` (documented, expected).

### 10.2 `audit_log` (Phase 3, migration V10, table owned by `shared`)
`audit_log(id UUID, occurred_at TIMESTAMPTZ, actor_user_id UUID, action VARCHAR(50), entity_type VARCHAR(50), entity_id UUID, reason VARCHAR(255), request_id VARCHAR(64), details JSONB)`
(exact definition: `04-DATA-MODEL.md` section 12.4); indexes on `(entity_type, entity_id)`, `actor_user_id` and `occurred_at`. Written by `AuditService.record(...)` **in the same transaction** as the change, so an audited
action and its audit row commit or roll back together. `details` holds non-sensitive facts only (amounts, business numbers, reasons), never passwords or tokens.

Audited actions (enum `AuditAction`, same list as `04-DATA-MODEL.md` section 12.4): `PAYMENT_REVERSED`, `EXPENSE_REVERSED`, `WORK_RECORD_REVERSED`, `INVENTORY_ADJUSTED`,
`PARTY_DEACTIVATED`, `PARTY_ACTIVATED`, `PURCHASE_CANCELLED`, `ORDER_CANCELLED`, `PRODUCTION_CANCELLED`, `OUTSOURCING_CANCELLED`, `ACCOUNT_CREATED`, `TRANSFER_RECORDED`,
`LOAN_CREATED`, `LOGIN_LOCKED`, `REFRESH_TOKEN_REUSE`, `USER_CREATED`, `USER_DEACTIVATED`, `USER_ROLE_CHANGED`, `OPENING_BALANCE_IMPORTED`,
`ACCOUNT_ADJUSTED`, `LOAN_PAYMENT_REVERSED`, `CHIT_ENTRY_REVERSED`. Retention: forever (small volume).

### 10.3 Immutability enforced by the database (ADR-032)
Production uses two PostgreSQL roles: `nexora_owner` (owns the schema; used only by Flyway) and `nexora_app` (used by the application). A migration
(guarded so it is skipped when the role does not exist, e.g. in dev and Testcontainers) executes
`REVOKE UPDATE, DELETE, TRUNCATE ON material_movements, financial_transactions, audit_log FROM nexora_app`. Result: even a bug or SQL injection through the app
cannot rewrite the ledgers. A deployment test connects as `nexora_app` and asserts `UPDATE financial_transactions ...` fails with SQLSTATE `42501`.
Code-level counterpart: ledger repositories expose no delete or update methods, and a test scans controllers and fails if a `PUT`/`DELETE` mapping exists
under the finance, inventory, payments, expenses, or transfers paths (Rule R5).

### 10.4 Personal data
Stored personal data is limited to party name, address, phone numbers, and user login names. No national IDs, no full card or bank account numbers
(`financial_accounts.masked_account_number` stores the last 4 digits only). Responses expose party data only to roles with the relevant view permission.

## 11. Financial-operation protections
| Threat | Control |
|---|---|
| Duplicate submit / retry on flaky network | `Idempotency-Key` required on 7 POST endpoints (`03-ARCHITECTURE.md` section 7.2); same key + same body replays the stored response |
| Editing history | no `PUT`/`DELETE` on money or stock; reversal = new compensating row; DB grants (10.3) |
| Over-spending / negative stock | row locks + check inside one transaction; DB `CHECK (quantity_kg >= 0)`; account balance check under lock |
| Client-supplied results | DTOs exclude derived fields; backend recomputes |
| Hidden changes | `audit_log` for reversals, adjustments, deactivations; `created_by` on every row |
| Concurrent double allocation | party row lock during `recordPayment` (ADR-027) |
| Money rounding drift | `BigDecimal`, `HALF_UP`, `NUMERIC(14,2)`, historical rates copied into rows |

## 12. Dependency and supply-chain security
- Versions are pinned by the Spring Boot BOM and `build.gradle` (`03-ARCHITECTURE.md` section 2); no dynamic versions (`+`, `latest`).
- OWASP dependency-check (Gradle plugin `org.owasp.dependencycheck` 13.0.0): `./gradlew dependencyCheckAnalyze` with `failBuildOnCVSS = 7.0` (fails on High and Critical),
  `formats = HTML, JSON`, `nvdApiKey` from env `NVD_API_KEY`, suppressions only in `config/dependency-check-suppressions.xml`, each with a reason and an expiry date <= 90 days.
  CI runs it on every pull request and on a nightly schedule. If the plugin proves incompatible with Gradle 9.7.1 when added, the same gate is run with the
  dependency-check CLI action in CI; the failure threshold stays CVSS >= 7.
- GitHub Dependabot (`.github/dependabot.yml`) opens weekly update pull requests for Gradle, GitHub Actions and Docker base images; security updates are merged within 7 days.
- Docker base images are pinned by major tag (`eclipse-temurin:21-jre`, `postgres:17`, `caddy:2`) and rebuilt weekly in CI to pick up OS patches; the image is scanned with Trivy
  (`aquasecurity/trivy-action`, fail on HIGH/CRITICAL fixable vulnerabilities).
- Third-party GitHub Actions are pinned to a major version at minimum (`@v4`).

## 13. Logging and monitoring rules (ADR-022)
- Never log: passwords, hashes, tokens, `Authorization` header, secrets, full account numbers, request bodies of `/auth/**`, party phone numbers.
- Every log line of a request carries `requestId` (MDC) and, once authenticated, `userId`; the response returns `X-Request-Id` so a user report maps to log lines.
- Security events logged at WARN with structured fields: `login_locked`, `refresh_token_reuse`, `access_denied` (user, path, required permission), `rate_limited` (ip).
- Alerts on security signals (`10-DEPLOYMENT.md` section 11): more than 20 `401` responses from one IP in 5 minutes; any `refresh_token_reuse`; any 5xx burst.

## 14. Backup security
Backups contain all business data and are encrypted before leaving the VPS: `pg_dump -Fc` piped to `age` (public-key encryption, key pair generated offline; the
private key is stored by the owner offline and in a sealed copy, never on the VPS or in the repository). Upload uses a bucket credential with write-only permission
(no delete, no list of other objects) so a compromised server cannot erase history; retention (30 days) is enforced by the bucket lifecycle rule. Restore is
rehearsed monthly (`10-DEPLOYMENT.md` section 9).

## 15. Threat list (STRIDE)
| # | Category | Threat | Control | Test / evidence |
|---|---|---|---|---|
| T1 | Spoofing | Password guessing | BCrypt, 5-failure lock (15 min), identical 401 for all failures, per-IP limit | `AuthIntegrationTest`: lock after 5 wrong passwords; same body for unknown user |
| T2 | Spoofing | Forged or tampered token | HS256 signature + issuer + expiry validation, 256-bit secret, startup check | test: token signed with another key -> 401 `UNAUTHENTICATED` |
| T3 | Spoofing | Stolen access token | 15-minute lifetime, HTTPS only, no token in logs | expiry test -> 401 `TOKEN_EXPIRED` |
| T4 | Spoofing | Stolen refresh token | rotation, reuse detection revokes the whole family, hash-only storage | test: reuse of a rotated token -> 401 and all tokens revoked |
| T5 | Tampering | Client sends derived values (stock, outstanding) | DTO records without those fields; unknown JSON properties ignored; backend recomputes | web tests per endpoint |
| T6 | Tampering | SQL injection | parameterized queries only, escaped `LIKE`, typed UUID path variables | code review rule + grep check in the checklist |
| T7 | Tampering | Rewriting ledger rows | no update/delete endpoints, no repository methods, `REVOKE UPDATE, DELETE` for `nexora_app` | grant test (SQLSTATE 42501), controller scan test |
| T8 | Tampering | Replay/duplicate of a payment | idempotency key + request hash | concurrency + replay tests |
| T9 | Repudiation | "I never did that" | `created_by` on all rows, `audit_log` in the same transaction, request id in logs | audit assertion in reversal tests |
| T10 | Information disclosure | Stack traces / internals in responses | generic 500 body, `include-stacktrace: never`, handler never returns `ex.getMessage()` for unexpected errors | `GlobalExceptionHandlerTest` (no leak of exception text) |
| T11 | Information disclosure | Endpoint map via Swagger in production | springdoc disabled outside `dev` | `OpenApiProdProfileTest` (404 under `prod`) |
| T12 | Information disclosure | Secrets in repo/logs | env-only secrets, `.gitignore`, CI secret grep, log rules | CI grep step, review checklist |
| T13 | Information disclosure | Backup theft | `age` encryption, write-only upload credential | restore drill needs the offline private key |
| T14 | Denial of service | Request flood / expensive queries | rate limits, page size cap 100, body size cap, `statement_timeout`, pool limits | rate-limit filter test (429), pageable cap test |
| T15 | Denial of service | Lock pile-up on hot rows | short transactions, `lock_timeout 5s`, global lock order | concurrency tests; timeout test -> 409 |
| T16 | Elevation of privilege | STAFF calls owner-only endpoint | `@PreAuthorize` on every controller method + scan test, permission claims only from a signed token | matrix test: STAFF -> 403 on all OWNER groups |
| T17 | Elevation of privilege | Role escalation through data edits | no user-management endpoint in MVP; role changes audited later; DB role grants | review |
| T18 | Tampering / supply chain | Vulnerable or malicious dependency | pinned BOM, dependency-check gate (CVSS >= 7), Dependabot, image scan | CI jobs |
| T19 | Repudiation / insider | Owner reverses payments to hide money | reversals are rows, never edits; audit log; both original and reversal stay visible in reports | report test |

## 16. Security checklist (checkable)
Authentication
- [ ] Login returns tokens; wrong password and unknown user return the same 401 body (`AuthIntegrationTest`).
- [ ] 5 wrong passwords lock the account for 15 minutes, even with the right password.
- [ ] Access token expires after 900 s; expired -> 401 `TOKEN_EXPIRED`; wrong key -> 401 `UNAUTHENTICATED`.
- [ ] Refresh rotates; the old token fails; reuse revokes all tokens of the user.
- [ ] `grep -R "javax\." Nexora-backend/src` returns nothing.
- [ ] Application refuses to start with `JWT_SECRET` shorter than 32 bytes (test of `SecurityProperties`).
Authorization
- [ ] Controller scan test: every mapped method has `@PreAuthorize` with an authority present in `Permission`.
- [ ] Matrix test: STAFF token gets 403 on every OWNER-only group; no token gets 401.
- [ ] `Permission` enum and `permissions` table are identical (test).
Data protection
- [ ] `.env` is ignored by git; `.env.example` has no real secret; CI secret scan passes.
- [ ] `grep -R "System.out\|printStackTrace" Nexora-backend/src/main` returns nothing; no log statement prints a token, password or `Authorization`.
- [ ] Production profile starts only when `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` are set.
- [ ] `/v3/api-docs` and `/swagger-ui.html` return 404 under `prod`.
- [ ] Error responses never contain a stack trace (test).
- [ ] `nexora_app` cannot `UPDATE`/`DELETE` `material_movements`, `financial_transactions`, `audit_log` (grant test).
Business protection
- [ ] Every money/stock POST listed in `03-ARCHITECTURE.md` section 7.2 rejects a missing `Idempotency-Key` (400) and replays on a repeated key.
- [ ] No `PUT`/`DELETE` mapping exists on finance, inventory, payments, expenses, or transfers (scan test).
- [ ] Reversal tests assert an `audit_log` row and a compensating ledger row.
Operations
- [ ] `./gradlew dependencyCheckAnalyze` passes (no CVSS >= 7, no expired suppression).
- [ ] Trivy image scan has no fixable HIGH/CRITICAL findings.
- [ ] Backups are encrypted (`file backup.dump.age` is not a PostgreSQL dump) and a restore drill succeeded in the last 31 days.
- [ ] Caddy serves HTTPS with a valid certificate; HTTP redirects to HTTPS; HSTS header present.
- [ ] JWT secret last rotated less than 6 months ago.

## 17. Decisions made while writing
| Decision | Reason |
|---|---|
| Identical 401 `INVALID_CREDENTIALS` for unknown user, inactive user, locked account | prevents user enumeration; no 423 status or `ACCOUNT_LOCKED` code introduced |
| Lockout state kept in the `users` row under a pessimistic lock | exact counting under parallel attempts; no external store |
| Refresh-token reuse revokes all of the user's tokens | standard rotation theft response; acceptable for a single-owner system |
| Notification configuration uses `FINANCE_MANAGE` | no dedicated permission exists; supplier due reminders are a finance concern |
| Loans, chits, outsourcing, and work records have a single `*_MANAGE` permission for read and write | the `Permission` enum has no separate view permissions for them; they are owner-only in the MVP |
| New error code `RATE_LIMITED` (429) | needed by the rate-limit filter; DOMAIN_RULES section 7 change log must record it |
| `audit_log` table and `AuditAction` enum introduced (Phase 3) | NFR 20.4 and the design's section 8.8.14 require an audit trail beyond `created_by`; reversals/adjustments need a durable record |
| Two database roles with `REVOKE UPDATE, DELETE` on ledger tables (ADR-032) | makes Rule R5 enforceable in the database, not only in code |
| Password policy 12-72 bytes, letter + digit | BCrypt input limit is 72 bytes; short owner-chosen passwords are the main residual risk |
| In-memory per-instance rate limiting | single instance by design (ADR-020); no new dependency |
| Body-size limits 1 MiB (10 MiB for documents) | simple DoS control; values chosen above any legitimate JSON request |
