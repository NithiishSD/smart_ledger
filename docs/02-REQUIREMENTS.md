# SmartSilk (Nexora) — Functional and Non-Functional Requirements

**Version:** 1.0 · **Companion documents:** `01-PRD.md`, `DOMAIN_RULES.md` (authoritative rules and error codes), `DECISIONS.md` (ADRs), `CONVENTIONS.md`.

## How to read this document

- **ID scheme.** Functional requirements are `FR-<MODULE>-<nn>` with MODULE in `AUTH, PARTY, PROC, INV, ORD, PAY, PROD, OUT, WORK, FIN, LOAN, CHIT, RPT, NTF, DOC`. Non-functional requirements are `NFR-<AREA>-<nn>` with AREA in `PERF, SEC, REL, OBS, USE, DATA, OPS, TEST`.
- **Priority.** `MVP` = part of the first release. `POST-MVP` = specified so the design leaves room for it, but not built in the first release.
- **Acceptance criteria** are written as Given / When / Then. Every requirement has at least one criterion; where a business rule can be violated, a rejection criterion cites the stable error `code` from `DOMAIN_RULES.md §7`.
- **Error response.** Every error is an RFC 9457 problem body: `{type, title, status, detail, instance, code, timestamp}` plus `errors[]` (`field`, `message`) for validation failures.
- **Units and formats.** Weight is kilograms with 3 decimals. Money is rupees with 2 decimals. Business dates are `yyyy-MM-dd` in the Asia/Kolkata calendar. Timestamps are ISO-8601 UTC. Lists are paginated: `page` (0-based), `size` (default 20, maximum 100), `sort`.
- **Derived values** (stock, balances, outstanding, payables) are never accepted from a client and never stored as editable fields (the only projection is `inventory_balances`, updated in the same transaction as its movement).
- **Assumptions.** Items marked **ASSUMPTION** are business defaults chosen for the first release and must be confirmed with the owner before go-live (see `01-PRD.md` §8).
- **Reference endpoints** are shown only to make a requirement concrete; the full request/response contract is defined in the API specification.

Roles used in the criteria: **Owner** = user with role `OWNER` (all permissions); **Staff** = user with role `STAFF` (read-only: `PARTY_VIEW`, `INVENTORY_VIEW`, `ORDER_VIEW`, `PRODUCTION_VIEW`).

---

# 1. Functional requirements

## 1.1 Authentication and authorization (AUTH)

### FR-AUTH-01 — Log in with username and password
**Priority:** MVP
**Description:** A user exchanges a username and password for an access token and a refresh token (`POST /api/v1/auth/login`). Usernames are case-insensitive (stored lower-case). The response never contains a password or hash.
- **AC1** Given an active user `owner` with a correct password, when the client posts valid credentials, then the response is 200 with `accessToken` (a signed JWT), `refreshToken` (an opaque string), `tokenType` `Bearer` and `expiresIn` 900.
- **AC2 (rejection)** Given a wrong password, when the client logs in, then the response is 401 `INVALID_CREDENTIALS`.
- **AC3 (rejection)** Given a username that does not exist, when the client logs in, then the response is 401 `INVALID_CREDENTIALS` with exactly the same message as AC2 (no user enumeration).
- **AC4 (rejection)** Given a deactivated user with the correct password, when the client logs in, then the response is 401 `INVALID_CREDENTIALS`.

### FR-AUTH-02 — Access token content and lifetime
**Priority:** MVP
**Description:** The access token is a JWT signed with HS256 using a secret of at least 32 bytes from the environment. Claims: `iss` = `nexora`, `sub` = user id, `username`, `roles`, `permissions`, `iat`, `exp` = `iat` + 15 minutes. It contains no balances, bank details or password data.
- **AC1** Given a token issued at 10:00:00, when it is decoded, then `exp` is 10:15:00 and `permissions` lists every permission of the user's roles.
- **AC2 (rejection)** Given a token signed with a different secret, when it is sent in the `Authorization: Bearer` header, then the response is 401 `UNAUTHENTICATED`.
- **AC3 (rejection)** Given the application is started with a secret shorter than 32 bytes, when it boots, then startup fails with a configuration error (fail fast).

### FR-AUTH-03 — Unauthenticated and expired requests
**Priority:** MVP
**Description:** Any protected endpoint called without a valid token returns a JSON problem body in the standard format, not an HTML page.
- **AC1 (rejection)** Given no `Authorization` header, when `GET /api/v1/parties` is called, then the response is 401 `UNAUTHENTICATED` with content type `application/problem+json`.
- **AC2 (rejection)** Given an access token that expired one second ago, when any protected endpoint is called, then the response is 401 `TOKEN_EXPIRED`.
- **AC3 (rejection)** Given a malformed token string, when any protected endpoint is called, then the response is 401 `UNAUTHENTICATED`.

### FR-AUTH-04 — Refresh with rotation
**Priority:** MVP
**Description:** `POST /api/v1/auth/refresh` exchanges a valid refresh token for a new access token and a new refresh token. The presented refresh token is revoked in the same transaction. Only the SHA-256 hash of a refresh token is stored. A refresh token lives 7 days.
- **AC1** Given a valid refresh token R1, when the client refreshes, then it receives a new pair (A2, R2), R1 is marked revoked and R2 is usable once.
- **AC2 (rejection)** Given a refresh token whose expiry is in the past, when the client refreshes, then the response is 401 `TOKEN_EXPIRED`.
- **AC3 (rejection)** Given a refresh token string that does not exist, when the client refreshes, then the response is 401 `UNAUTHENTICATED`.
- **AC4 (rejection)** Given the user was deactivated after the token was issued, when the client refreshes, then the response is 401 `UNAUTHENTICATED`.

### FR-AUTH-05 — Refresh-token reuse detection
**Priority:** MVP
**Description:** Presenting a refresh token that was already rotated is treated as theft: every refresh token of that user is revoked and the request fails.
- **AC1 (rejection)** Given R1 was rotated into R2, when R1 is presented again, then the response is 401 `UNAUTHENTICATED`.
- **AC2** Given the reuse in AC1, when R2 is presented afterwards, then it is also rejected with 401 `UNAUTHENTICATED` (all tokens of the user are revoked).

### FR-AUTH-06 — Log out
**Priority:** MVP
**Description:** `POST /api/v1/auth/logout` revokes the presented refresh token. It is idempotent.
- **AC1** Given a valid refresh token, when the client logs out, then the response is 204 and the token can no longer be refreshed.
- **AC2** Given an unknown or already revoked token, when the client logs out, then the response is still 204.
- **AC3 (rejection)** Given a refresh token that was used to log out, when it is presented to `/api/v1/auth/refresh`, then the response is 401 `UNAUTHENTICATED`.

### FR-AUTH-07 — Brute-force protection
**Priority:** MVP
**Description:** After 5 consecutive failed logins for one username the account is locked for 15 minutes. While locked, even the correct password is rejected with the same generic error. A successful login resets the counter. The failure counter is persisted even though the request itself fails.
- **AC1 (rejection)** Given 5 consecutive wrong passwords for `owner`, when the 6th attempt uses the correct password within 15 minutes, then the response is 401 `INVALID_CREDENTIALS`.
- **AC2** Given the lock from AC1 and 15 minutes have passed, when the correct password is used, then the login succeeds and the failure counter is 0.
- **AC3** Given 4 failures followed by 1 success, when 4 more failures occur, then the account is not locked (the counter was reset).
- **AC4 (rejection)** Given 10 requests to `/api/v1/auth/**` from one IP address within one minute, when an 11th request arrives in that minute, then the response is 429 `RATE_LIMITED` with a `Retry-After` header (see NFR-SEC-11).

### FR-AUTH-08 — Password storage
**Priority:** MVP
**Description:** Passwords are hashed with BCrypt through Spring's delegating encoder (stored as `{bcrypt}…`). Hashes are never returned by any endpoint and never logged.
- **AC1** Given a created user, when the `users` row is inspected, then `password_hash` starts with `{bcrypt}` and is not equal to the plain password.
- **AC2 (rejection)** Given any API response or log line produced by login, when searched for the password or hash, then neither is present.

### FR-AUTH-09 — Permission-based authorization
**Priority:** MVP
**Description:** Every protected endpoint requires a named permission (for example `PAYMENT_CREATE`). Roles are bundles of permissions: `OWNER` has all, `STAFF` has `PARTY_VIEW`, `INVENTORY_VIEW`, `ORDER_VIEW`, `PRODUCTION_VIEW`.
- **AC1** Given an Owner token, when any endpoint is called, then the permission check passes.
- **AC2 (rejection)** Given a Staff token, when `POST /api/v1/payments` is called, then the response is 403 `ACCESS_DENIED`.
- **AC3 (rejection)** Given a Staff token, when `GET /api/v1/parties` is called, then the response is 200 (the permission `PARTY_VIEW` is held).
- **AC4** Given the list of `Permission` values in code, when compared with the `permissions` table, then every value exists in the table (checked by an automated test).

### FR-AUTH-10 — Bootstrap of the first owner
**Priority:** MVP
**Description:** On startup, when the `users` table is empty and the environment variables `BOOTSTRAP_OWNER_USERNAME` and `BOOTSTRAP_OWNER_PASSWORD` are set, one user with role `OWNER` is created. The password must be at least 10 characters. If users already exist, nothing happens. No password is committed to the repository; development defaults exist only in the dev profile.
- **AC1** Given an empty database and both variables set, when the application starts, then exactly one active user with role `OWNER` exists.
- **AC2** Given a database with one user, when the application starts with the variables set to different values, then no user is created or changed.
- **AC3 (rejection)** Given an empty database and a bootstrap password of 6 characters, when the application starts, then startup fails with a clear configuration error and no user is created.

### FR-AUTH-11 — Current user
**Priority:** MVP
**Description:** `GET /api/v1/auth/me` returns the id, username, display name, roles and permissions of the caller.
- **AC1** Given a valid token, when the endpoint is called, then the response is 200 with those fields and no hash.
- **AC2 (rejection)** Given no token, when the endpoint is called, then the response is 401 `UNAUTHENTICATED`.

### FR-AUTH-12 — Authenticated endpoints only
**Priority:** MVP
**Description:** Only `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, the health and info actuator endpoints, and (dev profile only) the API documentation are reachable without a token.
- **AC1** Given no token, when `GET /actuator/health` is called, then the response is 200.
- **AC2 (rejection)** Given no token, when `GET /api/v1/anything-unknown` is called, then the response is 401 (an unauthenticated caller learns nothing about which URLs exist).

### FR-AUTH-13 — Audit identity on records
**Priority:** MVP
**Description:** Every business record stores `created_at`, `updated_at`, `created_by` (the user id from the token) and a `version`. System-created rows (startup bootstrap, scheduled jobs) have `created_by` empty.
- **AC1** Given the Owner creates a party, when the row is read, then `created_by` equals the Owner's user id and `created_at` is within 5 seconds of the request.
- **AC2** Given two concurrent updates of the same record, when the second commits, then it fails with 409 `CONCURRENT_MODIFICATION`.
- **AC3 (rejection)** Given a request without a token that would create a record, when it is sent, then the response is 401 `UNAUTHENTICATED` and no row is written.

### FR-AUTH-14 — User management
**Priority:** POST-MVP
**Description:** The Owner can create staff users, assign roles, deactivate users and reset passwords (`USER_MANAGE`). Not built in the first release (only the Owner logs in at go-live, ASSUMPTION Q8).
- **AC1** Given the post-MVP release, when the Owner creates a Staff user with a 12-character password, then that user can log in and has only the Staff permissions.
- **AC2 (rejection)** Given a Staff token, when a user is created, then the response is 403 `ACCESS_DENIED`.

### FR-AUTH-15 — Change own password
**Priority:** POST-MVP
**Description:** A logged-in user can change their password by supplying the current one; all refresh tokens are revoked afterwards.
- **AC1** Given the correct current password and a new password of at least 10 characters, when the user changes it, then old refresh tokens stop working.
- **AC2 (rejection)** Given a wrong current password, when the user changes it, then the response is 401 `INVALID_CREDENTIALS`.

---

## 1.2 Parties (PARTY)

Customers, suppliers, manufacturers (job workers), workers and lenders are all **parties**; one party can hold several roles. A user (someone who logs in) is not a party.

### FR-PARTY-01 — Create a party
**Priority:** MVP
**Description:** `POST /api/v1/parties` creates a party with a name (required, at most 200 characters), at least one role, optional address fields (address line, area, city, district, state, pincode), an optional note, and optional phone numbers. The backend generates the party code and the status `ACTIVE`.
- **AC1** Given name "Kumar Silks" and role `CUSTOMER`, when the Owner creates it, then the response is 201 with a `Location` header, a party code like `PTY-2026-0001` and status `ACTIVE`.
- **AC2 (rejection)** Given an empty role list, when the Owner creates a party, then the response is 400 `VALIDATION_FAILED` with an entry for field `roles`.
- **AC3 (rejection)** Given a blank name, when the Owner creates a party, then the response is 400 `VALIDATION_FAILED` with an entry for field `name`.
- **AC4 (rejection)** Given a Staff token, when a party is created, then the response is 403 `ACCESS_DENIED`.

### FR-PARTY-02 — Roles of a party
**Priority:** MVP
**Description:** The allowed roles are `CUSTOMER`, `SUPPLIER`, `MANUFACTURER`, `WORKER`, `LENDER`. A party may hold any combination (for example a supplier who is also a customer).
- **AC1** Given roles `SUPPLIER` and `CUSTOMER`, when the party is created and fetched, then both roles are returned.
- **AC2 (rejection)** Given the role value `ADMIN`, when the party is created, then the response is 400 `MALFORMED_REQUEST` and nothing is stored.

### FR-PARTY-03 — Unique, gap-free party codes
**Priority:** MVP
**Description:** Party codes use the format `PTY-<year>-<4 digits>`, generated by the shared business-number generator under a row lock, unique across the system.
- **AC1** Given 20 parties created in parallel, when all requests finish, then all 20 codes are distinct and form the sequence 0001 to 0020 for the year.
- **AC2** Given a creation request that fails validation, when the next party is created, then the failed request did not consume a number.
- **AC3 (rejection)** Given a party code inserted directly that duplicates an existing one, when the insert runs, then the database rejects it (unique constraint).

### FR-PARTY-04 — Phone numbers
**Priority:** MVP
**Description:** A party may have several phone numbers, each with a label (for example "mobile", "WhatsApp") and a primary flag. When at least one number exists, exactly one is primary; if none is flagged the first becomes primary. Phone numbers are not unique across parties (family members share numbers).
- **AC1** Given two parties created with the same phone number, when both are saved, then both succeed.
- **AC2** Given three numbers none flagged primary, when the party is created, then the first is primary and the others are not.
- **AC3 (rejection)** Given two numbers both flagged primary, when the party is created, then the response is 400 `VALIDATION_FAILED`.

### FR-PARTY-05 — Optional address
**Priority:** MVP
**Description:** All address fields are optional (a worker may have no pincode). When provided, each field respects its maximum length (address line 255, area 155, city 100, district 100, state 50, pincode 20).
- **AC1** Given only a name and a role, when the party is created, then it is stored with empty address fields.
- **AC2 (rejection)** Given a pincode of 25 characters, when the party is created, then the response is 400 `VALIDATION_FAILED` for field `pincode`.

### FR-PARTY-06 — View a party
**Priority:** MVP
**Description:** `GET /api/v1/parties/{id}` returns the party with roles, phone numbers, status, defaults and audit fields.
- **AC1** Given an existing party id, when fetched with `PARTY_VIEW`, then the response is 200 with all fields.
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-PARTY-07 — List and search parties
**Priority:** MVP
**Description:** `GET /api/v1/parties` returns a page of party summaries (id, code, name, roles, status, primary phone, city). Filters: `role`, `status`, `search` (case-insensitive substring of name, code or any phone number; `%` and `_` are treated literally). Default sort is newest first.
- **AC1** Given parties "Ramesh Silks" and "Rama Yarns", when searching `search=ram`, then both are returned; searching `search=RAMESH` returns only the first.
- **AC2** Given 150 parties, when `size=500` is requested, then the page size is capped at 100.
- **AC3 (rejection)** Given `sort=doesNotExist`, when listing, then the response is 400 `VALIDATION_FAILED`.
- **AC4** Given 20 parties on a page, when the page is built, then roles and phone numbers are loaded with a constant number of queries (no per-row query).

### FR-PARTY-08 — Update a party
**Priority:** MVP
**Description:** `PATCH /api/v1/parties/{id}` changes name, address, note, phone numbers (full replacement of the list) and the defaults in FR-PARTY-13/14. The party code, status and creation data cannot be changed here.
- **AC1** Given a party, when its name and city are patched, then the response returns the new values and `updated_at` advances.
- **AC2 (rejection)** Given two concurrent patches of the same party, when the second commits, then it fails with 409 `CONCURRENT_MODIFICATION`.
- **AC3 (rejection)** Given an unknown id, when patched, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-PARTY-09 — Add a role to an existing party
**Priority:** MVP
**Description:** A role can be added to a party later (a customer who starts supplying). Roles cannot be removed in the first release, because history may reference them.
- **AC1** Given a `CUSTOMER`, when the role `SUPPLIER` is added, then the party can be used on purchases.
- **AC2** Given a party that already has the role, when the same role is added again, then the request succeeds without duplicating it.
- **AC3 (rejection)** Given an unknown role value, when it is added, then the response is 400 `MALFORMED_REQUEST`.

### FR-PARTY-10 — Deactivate and reactivate
**Priority:** MVP
**Description:** `POST /api/v1/parties/{id}/deactivate` and `/activate` switch the status. Parties are never hard-deleted; history stays visible.
- **AC1** Given an ACTIVE party, when deactivated, then its status is `INACTIVE` and its past orders and payments are still listed.
- **AC2 (rejection)** Given an INACTIVE party, when deactivated again, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given an ACTIVE party, when activated, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PARTY-11 — Inactive parties cannot be used in new transactions
**Priority:** MVP
**Description:** Every module that creates a transaction for a party calls one shared check, `requireActiveWithRole(partyId, role)`.
- **AC1 (rejection)** Given an INACTIVE supplier, when a purchase is created for it, then the response is 422 `PARTY_INACTIVE`.
- **AC2** Given the same supplier, when its earlier purchases are listed, then they are still returned.

### FR-PARTY-12 — Role must match the operation
**Priority:** MVP
**Description:** Purchases require `SUPPLIER`, orders require `CUSTOMER`, outsourcing requires `MANUFACTURER`, work records require `WORKER`, loans require `LENDER`. Customer and supplier payments require the matching role.
- **AC1 (rejection)** Given a party with only the role `CUSTOMER`, when a purchase is created for it, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC2 (rejection)** Given a party with only the role `WORKER`, when an order is created for it, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PARTY-13 — Supplier default credit days
**Priority:** MVP
**Description:** A party with the role `SUPPLIER` may have `defaultCreditDays` (an integer of at least 0) used to pre-fill the credit period of new purchases.
- **AC1** Given a supplier with 10 default credit days, when a credit purchase is created without `creditDays`, then the purchase uses 10.
- **AC2 (rejection)** Given `defaultCreditDays` = -1, when saved, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a party without the `SUPPLIER` role, when `defaultCreditDays` is set, then the response is 400 `VALIDATION_FAILED`.

### FR-PARTY-14 — Worker default rates
**Priority:** MVP
**Description:** A party with the role `WORKER` may have `defaultRollingRatePerHour` and `defaultWarpingRatePerKg` (each at least 0.00). They are copied into new work records; changing them later never changes old records.
- **AC1** Given a worker with a rolling rate of 100.00 per hour, when a rolling work record omits the rate, then the record stores 100.00.
- **AC2 (rejection)** Given a negative rate, when saved, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a party without the `WORKER` role, when a worker rate is set, then the response is 400 `VALIDATION_FAILED`.

### FR-PARTY-15 — No hard delete
**Priority:** MVP
**Description:** No endpoint deletes a party.
- **AC1 (rejection)** Given any party, when `DELETE /api/v1/parties/{id}` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PARTY-16 — Lender role
**Priority:** MVP
**Description:** Banks and private individuals who lend money are parties with the role `LENDER` (see FR-LOAN-01).
- **AC1** Given a party "SBI" with role `LENDER`, when a loan is created for it, then the loan is accepted.
- **AC2 (rejection)** Given a `CUSTOMER` only, when a loan is created for it, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PARTY-17 — Customer-specific credit period
**Priority:** POST-MVP
**Description:** A customer may have an agreed credit period used for outstanding-age alerts. The first release tracks the age of outstanding amounts only and creates no customer due dates.
- **AC1** Given a customer with a 30-day period (post-MVP), when an order is 31 days old and unpaid, then an alert is raised.
- **AC2 (rejection)** Given a negative period, when saved, then the response is 400 `VALIDATION_FAILED`.

---

## 1.3 Procurement (PROC)

A **purchase** is what the business agreed to buy from a supplier. A **material receipt** is what physically arrived. Payment is separate from both.

### FR-PROC-01 — Create a purchase
**Priority:** MVP
**Description:** `POST /api/v1/purchases` records a purchase: supplier (role `SUPPLIER`, active), product type (`RAW_SILK` or `VUDA_WARP`), purchase date, ordered weight (kg, greater than 0), rate per kg (at least 0.00), payment type (`CASH` or `CREDIT`), credit days (0 or more; defaults to the supplier's default; 0 for `CASH`), optional supplier bill number and notes. Status starts at `ORDERED`.
- **AC1** Given supplier S (10 default credit days), 250.000 kg at 800.00 per kg on credit, when created, then the response is 201 with status `ORDERED`, credit days 10 and number `PUR-2026-0001`.
- **AC2 (rejection)** Given ordered weight 0, when created, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a party that is not a supplier, when created, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC4 (rejection)** Given an inactive supplier, when created, then the response is 422 `PARTY_INACTIVE`.
- **AC5 (rejection)** Given a negative rate, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-02 — Purchase numbers
**Priority:** MVP
**Description:** Purchase numbers have the format `PUR-<year>-<4 digits>` and are unique and gap-free per year, including under concurrency and when a request rolls back.
- **AC1** Given 10 purchases created in parallel, when all finish, then the numbers are 10 distinct consecutive values.
- **AC2** Given a creation that rolls back after the number was taken, when the next purchase is created, then it receives the number that the failed one took.
- **AC3 (rejection)** Given a purchase number inserted directly that duplicates an existing one, when the insert runs, then the database rejects it (unique constraint).

### FR-PROC-03 — Purchase value is derived
**Priority:** MVP
**Description:** The ordered value (`ordered weight × rate`, rounded HALF_UP to 2 decimals) is calculated by the backend and returned; clients cannot send or override it.
- **AC1** Given 250.000 kg at 800.00, when the purchase is fetched, then `orderedValue` is 200000.00.
- **AC2** Given a request body that also contains a field `orderedValue` = 1.00, when the purchase is created, then the stored and returned value is still 200000.00.
- **AC3 (rejection)** Given an ordered weight with four decimals (12.3456), when created, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-04 — Due date
**Priority:** MVP
**Description:** `dueDate = purchaseDate + creditDays`, computed by the backend. For `CASH` purchases the due date equals the purchase date.
- **AC1** Given purchase date 2026-10-01 and 10 credit days, when created, then `dueDate` is 2026-10-11.
- **AC2** Given a `CASH` purchase dated 2026-10-01, when created, then `dueDate` is 2026-10-01 and credit days is 0.
- **AC3 (rejection)** Given credit days -3, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-05 — Purchase price and weight are historical
**Priority:** MVP
**Description:** The rate and ordered weight of a purchase never change after creation and are never recalculated from current prices. Only notes and the supplier bill number may be changed (`PATCH /api/v1/purchases/{id}`). A wrong purchase is corrected by cancelling it (FR-PROC-12) and creating a new one.
- **AC1** Given a purchase, when only `notes` is patched, then the response is 200 and rate and weight are unchanged.
- **AC2 (rejection)** Given a purchase, when a patch contains `ratePerKg`, then the response is 400 `VALIDATION_FAILED` and the rate is unchanged.
- **AC3** Given a rate change on the supplier's master data, when an old purchase is read, then its stored rate is unchanged.

### FR-PROC-06 — View a purchase
**Priority:** MVP
**Description:** `GET /api/v1/purchases/{id}` returns the purchase with its receipts and returns, and derived values: received weight, accepted weight, rejected weight, returned weight, remaining weight to receive, payable amount and payment status.
- **AC1** Given a purchase of 250 kg with one receipt of 100 kg, when fetched, then received is 100.000 and remaining is 150.000.
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no `PURCHASE_VIEW` permission, when fetched, then the response is 403 `ACCESS_DENIED`.

### FR-PROC-07 — List purchases
**Priority:** MVP
**Description:** `GET /api/v1/purchases` returns a page filtered by `supplierId`, `status`, `productType`, `from` and `to` (purchase date range, inclusive). Default sort is newest first.
- **AC1** Given purchases in three months, when `from=2026-09-01&to=2026-09-30` is requested, then only September purchases are returned.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-08 — Record a material receipt
**Priority:** MVP
**Description:** `POST /api/v1/purchases/{id}/receipts` records what arrived: receipt date, received weight, accepted weight, rejected weight, notes. A purchase may have several receipts (partial deliveries). The cumulative received weight (accepted plus rejected counts as received) may never exceed the ordered weight. The receipt number has the format `RCT-<year>-<4 digits>`.
- **AC1** Given a purchase of 250 kg with no receipts, when a receipt of 80 kg (accepted 80) is recorded, then the purchase shows received 80, remaining 170 and status `PARTIALLY_RECEIVED`.
- **AC2 (rejection)** Given 180 kg already received of 250 kg, when a receipt of 100 kg is recorded, then the response is 409 `PURCHASE_RECEIPT_EXCEEDED` and nothing changes.
- **AC3** Given 180 kg already received, when a receipt of exactly 70 kg is recorded, then the status becomes `FULLY_RECEIVED`.
- **AC4 (rejection)** Given a receipt with received weight 0, when recorded, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-09 — Receipt weights are consistent
**Priority:** MVP
**Description:** For each receipt `accepted + rejected ≤ received`, and all three are at least 0. Accepted and rejected need not add up to received (the remainder is unclassified), but cannot exceed it.
- **AC1** Given received 100, accepted 95, rejected 5, when recorded, then it is accepted.
- **AC2 (rejection)** Given received 100, accepted 90, rejected 20, when recorded, then the response is 400 `VALIDATION_FAILED` for the weight fields.
- **AC3 (rejection)** Given a negative rejected weight, when recorded, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-10 — Only accepted material enters stock
**Priority:** MVP
**Description:** In the same transaction as the receipt, a `RECEIPT` material movement from `SUPPLIER` to `RAW_STOCK` is created for the accepted weight only (product type of the purchase). If accepted weight is 0 no movement is created. If the movement fails, the receipt is not stored.
- **AC1** Given a receipt with accepted 95 kg, when recorded, then raw-silk stock increases by exactly 95.000 kg and one `RECEIPT` movement references the receipt.
- **AC2** Given a receipt with accepted 0 kg and rejected 40 kg, when recorded, then stock is unchanged and no movement exists.
- **AC3 (rejection)** Given a failure while writing the movement, when the receipt is recorded, then no receipt row and no purchase status change are stored.

### FR-PROC-11 — Purchase receiving status
**Priority:** MVP
**Description:** Status moves `ORDERED → PARTIALLY_RECEIVED → FULLY_RECEIVED` through the purchase's own methods; payment status is separate and derived (`UNPAID`, `PARTIALLY_PAID`, `PAID`, plus `OVERDUE` as a flag).
- **AC1** Given a fully received purchase with no payment, when fetched, then status is `FULLY_RECEIVED` and payment status is `UNPAID`.
- **AC2 (rejection)** Given a `CANCELLED` purchase, when a receipt is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given a `FULLY_RECEIVED` purchase, when another receipt of any weight is recorded, then the response is 409 `PURCHASE_RECEIPT_EXCEEDED`.

### FR-PROC-12 — Cancel a purchase
**Priority:** MVP
**Description:** `POST /api/v1/purchases/{id}/cancel` cancels a purchase only while nothing has been received and nothing has been allocated to it. The record is kept.
- **AC1** Given an `ORDERED` purchase with no receipts, when cancelled, then status is `CANCELLED` and it no longer counts toward payables.
- **AC2 (rejection)** Given a purchase with one receipt, when cancelled, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given a `CANCELLED` purchase, when cancelled again, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PROC-13 — Return material to the supplier
**Priority:** MVP
**Description:** `POST /api/v1/purchases/{id}/returns` records material sent back (weight greater than 0, date, reason). It creates a `PURCHASE_RETURN` movement from `RAW_STOCK` to `RETURNED`. The returnable weight is `accepted − already returned` for that purchase, and the stock balance must stay at or above 0.
- **AC1** Given a purchase with 100 kg accepted and 100 kg in stock, when 59 kg is returned, then stock falls by 59.000 and returned total is 59.000.
- **AC2 (rejection)** Given 100 kg accepted and 60 kg already returned, when 50 kg is returned, then the response is 409 `INSUFFICIENT_INVENTORY` (only 40 kg returnable).
- **AC3 (rejection)** Given 100 kg accepted but only 20 kg left in stock (rest consumed), when 30 kg is returned, then the response is 409 `INSUFFICIENT_INVENTORY` and stock is unchanged.
- **AC4 (rejection)** Given weight 0, when returned, then the response is 400 `VALIDATION_FAILED`.

### FR-PROC-14 — Receipts and returns are never rewritten
**Priority:** MVP
**Description:** Original receipts are immutable. A return is a separate record; the receipt keeps its received, accepted and rejected figures.
- **AC1** Given a receipt of 100 kg and a return of 99 kg, when the purchase is fetched, then the receipt still shows 100 and the return shows 99.
- **AC2 (rejection)** Given any receipt, when `PUT` or `DELETE` is called on it, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PROC-15 — Supplier payable per purchase (ASSUMPTION Q1)
**Priority:** MVP
**Description:** Payable for a purchase = `(accepted weight − returned weight) × the purchase's rate`, rounded HALF_UP to 2 decimals, minus the sum of payment allocations to it. It is computed on read and never stored. **ASSUMPTION - confirm with owner:** rejected kg are not paid, and returns credit the original rate.
- **AC1** Given 100 kg accepted at 800.00 and 10 kg returned, when fetched, then payable is 72000.00.
- **AC2** Given payments allocated of 50000.00 to it, when fetched, then the remaining payable is 22000.00 and payment status `PARTIALLY_PAID`.
- **AC3 (rejection)** Given no receipt yet, when fetched, then payable is 0.00 (nothing accepted).

### FR-PROC-16 — Overdue purchases
**Priority:** MVP
**Description:** A purchase is overdue when its remaining payable is greater than 0.00 and `dueDate` is earlier than today (Asia/Kolkata, from the injected clock). The response includes `overdue` and `overdueDays`.
- **AC1** Given due date 2026-10-11, remaining payable 5000.00 and today 2026-10-14, when fetched, then `overdue` is true and `overdueDays` is 3.
- **AC2** Given the same purchase fully paid, when fetched, then `overdue` is false.
- **AC3 (rejection)** Given a `CANCELLED` purchase past its due date, when fetched, then `overdue` is false.

### FR-PROC-17 — Vuda warp purchases
**Priority:** MVP
**Description:** A purchase with product type `VUDA_WARP` is received into the trading stock location `RAW_STOCK` under product type `VUDA_WARP` and can only be sold, never consumed or issued (see FR-INV-10).
- **AC1** Given a `VUDA_WARP` purchase received 50 kg, when stock is read, then `VUDA_WARP` shows 50.000 in `RAW_STOCK`.
- **AC2 (rejection)** Given product type `SILK_WARP`, when a purchase is created, then the response is 422 `INVALID_PRODUCT_FOR_OPERATION`.

### FR-PROC-18 — Purchase history is preserved
**Priority:** MVP
**Description:** Purchases are never hard-deleted. Corrections use cancel or return.
- **AC1 (rejection)** Given any purchase, when `DELETE /api/v1/purchases/{id}` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PROC-19 — Retry-safe receipts
**Priority:** MVP
**Description:** `POST /api/v1/purchases/{id}/receipts` accepts an `Idempotency-Key` header. A replay with the same key and body returns the original response without a second receipt or movement.
- **AC1** Given a receipt posted twice with the same key and body, when both complete, then exactly one receipt and one movement exist and both responses are identical.
- **AC2 (rejection)** Given the same key with a different body, when posted, then the response is 409 `DUPLICATE_REQUEST`.

### FR-PROC-20 — Low-stock indication when buying
**Priority:** POST-MVP
**Description:** The purchase screen shows current raw-silk stock and the configured low-stock threshold. Buying remains the owner's decision.
- **AC1** Given stock below its threshold, when the purchase form is opened, then the low-stock hint is shown.
- **AC2 (rejection)** Given a threshold below 0, when configured, then the response is 400 `VALIDATION_FAILED`.

---
## 1.4 Inventory (INV)

Stock is tracked in kilograms per **product type** (`RAW_SILK`, `SILK_WARP`, `KORA_WARP`, `VUDA_WARP`) and **location**: internal locations `RAW_STOCK` (raw silk and vuda trading stock), `INTERNAL_WIP`, `EXTERNAL_WIP`, `FINISHED_STOCK` (silk and kora warp); outside locations `SUPPLIER`, `CUSTOMER`, `WASTAGE`, `RETURNED`. Balances exist only for internal locations. Every change is a **material movement**; no client endpoint writes movements or balances.

### FR-INV-01 — Stock changes only through movements
**Priority:** MVP
**Description:** Every change of stock creates exactly one `material_movement` through the inventory service, called by other modules' use cases. There is no endpoint to create, edit or delete a movement or to set a balance.
- **AC1** Given a receipt of 95 kg accepted, when it is recorded, then one `RECEIPT` movement of 95.000 kg exists and the balance increased by 95.000.
- **AC2 (rejection)** Given any caller, when `POST /api/v1/inventory/movements` is called, then the response is 405 `METHOD_NOT_ALLOWED` (only `GET` exists).
- **AC3 (rejection)** Given any movement, when `PUT`, `PATCH` or `DELETE` is called on it, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-INV-02 — Movement record
**Priority:** MVP
**Description:** A movement stores a number `MOV-<year>-<4 digits>`, product type, movement type, quantity (greater than 0, scale 3), from location, to location, movement date, reference type and id of the business record that caused it, notes or reason, and `created_by`. Quantities are always positive; direction comes from from/to.
- **AC1** Given a consumption of 40 kg, when read, then the movement shows from `RAW_STOCK`, to `INTERNAL_WIP`, quantity 40.000 and a reference to the production batch.
- **AC2 (rejection)** Given a quantity of 0 or negative, when a movement is requested by any use case, then it is rejected and no movement or balance change is stored.

### FR-INV-03 — Valid movement types
**Priority:** MVP
**Description:** Allowed movement types and their from → to pairs: `OPENING_BALANCE` (none → internal), `RECEIPT` (`SUPPLIER` → `RAW_STOCK`), `PURCHASE_RETURN` (`RAW_STOCK` → `RETURNED`), `DIRECT_SALE` (`RAW_STOCK` → `CUSTOMER`), `CONSUMPTION` (`RAW_STOCK` → `INTERNAL_WIP`, raw silk arriving as the target warp type), `PRODUCTION_OUTPUT` (`INTERNAL_WIP` → `FINISHED_STOCK`), `WASTAGE` (`INTERNAL_WIP`, `EXTERNAL_WIP` or `RAW_STOCK` → `WASTAGE`), `OUTSOURCE_ISSUE` (`RAW_STOCK` → `EXTERNAL_WIP`, raw silk arriving as the target warp type), `OUTSOURCE_RECEIPT` (`EXTERNAL_WIP` → `FINISHED_STOCK`), `DELIVERY` (`FINISHED_STOCK` → `CUSTOMER`), `CUSTOMER_RETURN` (`CUSTOMER` → `FINISHED_STOCK` for silk/kora warp or `RAW_STOCK` for raw silk/vuda warp, post-MVP), `ADJUSTMENT` (any ↔ any, reason required).
- **AC1** Given the table above, when each business use case runs, then it produces exactly the listed type and pair.
- **AC2 (rejection)** Given an adjustment from `SUPPLIER` to `CUSTOMER` (neither internal), when requested, then the response is 400 `VALIDATION_FAILED` because at least one side must be an internal location.

### FR-INV-04 — Balances are a projection updated atomically
**Priority:** MVP
**Description:** `inventory_balances(product_type, location, quantity_kg)` is updated in the same transaction as its movement. It can always be rebuilt from movements.
- **AC1** Given a movement is saved, when the balance is read in the same transaction, then it already includes the movement.
- **AC2 (rejection)** Given a failure after the balance update but before commit, when the transaction rolls back, then neither the movement nor the balance change exists.

### FR-INV-05 — Stock never goes negative
**Priority:** MVP
**Description:** Before decreasing a balance the inventory service locks the balance row (`PESSIMISTIC_WRITE`) and checks the quantity inside the same transaction. A database `CHECK (quantity_kg >= 0)` is the last line of defence.
- **AC1** Given 100.000 kg in `RAW_STOCK`, when a consumption of 80.000 kg is recorded, then the balance is 20.000.
- **AC2 (rejection)** Given 100.000 kg, when a consumption of 120.000 kg is attempted, then the response is 409 `INSUFFICIENT_INVENTORY` naming the available quantity, and the balance remains 100.000.
- **AC3 (rejection)** Given 100.000 kg and two parallel requests of 80.000 and 70.000, when both run, then exactly one succeeds, the other fails with 409 `INSUFFICIENT_INVENTORY`, and the final balance is 20.000 or 30.000 (never negative).

### FR-INV-06 — Deadlock-free locking
**Priority:** MVP
**Description:** A movement that touches two balances locks them in a fixed global order (product type, then location) so concurrent movements cannot deadlock.
- **AC1** Given two parallel transactions moving stock in opposite directions between the same two locations, when both run 100 times, then none fails with a deadlock error.
- **AC2 (rejection)** Given a balance row locked by another transaction for longer than 5 seconds, when a movement waits for it, then the request fails with 409 `CONCURRENT_MODIFICATION` and nothing is stored.

### FR-INV-07 — Stock summary
**Priority:** MVP
**Description:** `GET /api/v1/inventory/summary` returns the balance per product type and internal location plus totals for: raw silk available, silk in internal WIP, silk with manufacturers (external WIP), finished warp (silk and kora), vuda warp.
- **AC1** Given balances RAW_SILK/RAW_STOCK 20.000, KORA_WARP/FINISHED_STOCK 55.500, when the summary is read, then those values appear with unit `kg`.
- **AC2 (rejection)** Given no `INVENTORY_VIEW` permission, when the summary is read, then the response is 403 `ACCESS_DENIED`.
- **AC3** Given an empty database, when the summary is read, then all balances are 0.000 and the response is 200.

### FR-INV-08 — Movement history
**Priority:** MVP
**Description:** `GET /api/v1/inventory/movements` returns a page filtered by `productType`, `location` (matches either side), `movementType`, `referenceType`, `referenceId`, `from`, `to`. Default sort newest first.
- **AC1** Given movements across months, when filtered by `movementType=RECEIPT&from=2026-09-01&to=2026-09-30`, then only September receipts are returned.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.
- **AC3** Given a stock figure, when the movements of that product and location are summed (in minus out), then the sum equals the balance.

### FR-INV-09 — Balance reconciliation check
**Priority:** MVP
**Description:** An automated check compares every `inventory_balances` row with the sum of movements and reports any difference. It runs in the test suite and daily in production, writing an error log line per mismatch.
- **AC1** Given consistent data, when the check runs, then it reports 0 differences.
- **AC2 (rejection)** Given a balance row edited manually to a wrong value, when the check runs, then it reports that product and location with expected and actual quantity.

### FR-INV-10 — Vuda warp is trade-only
**Priority:** MVP
**Description:** `VUDA_WARP` can be received, adjusted, wasted (manual) and sold, but never consumed by production, issued to a manufacturer, or produced.
- **AC1 (rejection)** Given `VUDA_WARP` stock, when a `CONSUMPTION` or `OUTSOURCE_ISSUE` movement is requested, then the response is 422 `INVALID_PRODUCT_FOR_OPERATION`.
- **AC2** Given `VUDA_WARP` stock, when an order of that type is delivered, then a `DIRECT_SALE` movement is created.

### FR-INV-11 — Record wastage manually
**Priority:** MVP
**Description:** `POST /api/v1/inventory/wastage` records wastage the owner identifies outside production completion: product type, source location (`RAW_STOCK`, `INTERNAL_WIP` or `EXTERNAL_WIP`), weight (greater than 0), date, mandatory reason. It creates a `WASTAGE` movement. Permission `INVENTORY_ADJUST`.
- **AC1** Given 20.000 kg in `RAW_STOCK`, when 1.500 kg wastage with reason "moisture loss" is recorded, then the balance is 18.500 and the wastage balance view shows 1.500.
- **AC2 (rejection)** Given a blank reason, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given 1.000 kg available, when 2.000 kg is recorded, then the response is 409 `INSUFFICIENT_INVENTORY`.

### FR-INV-12 — Stock adjustment (Owner only)
**Priority:** MVP
**Description:** `POST /api/v1/inventory/adjustments` corrects stock after a physical count: product type, from and to (at least one internal), weight greater than 0, mandatory reason, date. Creates an `ADJUSTMENT` movement. Permission `INVENTORY_ADJUST`. No adjustment can take a balance below zero (ADR-026).
- **AC1** Given a physical count 3.000 kg higher than the system, when an adjustment from `SUPPLIER` to `RAW_STOCK` of 3.000 kg with reason "stock count 2026-10-01" is recorded, then the balance rises by 3.000 and the movement keeps the reason.
- **AC2 (rejection)** Given a missing reason, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an adjustment that would make `RAW_STOCK` negative, when recorded, then the response is 409 `INSUFFICIENT_INVENTORY`.
- **AC4 (rejection)** Given a Staff token, when an adjustment is posted, then the response is 403 `ACCESS_DENIED`.

### FR-INV-13 — Opening stock at go-live
**Priority:** MVP
**Description:** Existing stock is entered once per product type and internal location as `OPENING_BALANCE` movements (permission `INVENTORY_ADJUST`). Opening balances are not purchases, revenue or expense.
- **AC1** Given 120.000 kg raw silk counted on go-live day, when the opening balance is entered, then `RAW_STOCK` shows 120.000 and no purchase or financial transaction exists for it.
- **AC2 (rejection)** Given an opening balance already entered for that product and location, when another is entered, then the response is 409 `DUPLICATE_RESOURCE` (later corrections use adjustments).

### FR-INV-14 — Material outside with manufacturers
**Priority:** MVP
**Description:** `GET /api/v1/inventory/external-wip` returns, per manufacturer and per outsourcing job, the kilograms issued, received, recorded as wastage or difference, and still outside (`issued − received − wastage`).
- **AC1** Given a job issuing 200.000 kg with 150.000 received and 5.000 wastage, when read, then it shows 45.000 kg still outside.
- **AC2** Given the sum over all jobs, when compared with the `EXTERNAL_WIP` balance, then they are equal.
- **AC3 (rejection)** Given a job still in status `CREATED` (nothing issued), when external WIP is read, then it contributes 0.000.

### FR-INV-15 — Weight precision
**Priority:** MVP
**Description:** Weights are accepted with at most 3 decimal places. More decimals are rejected rather than silently rounded.
- **AC1** Given a weight of 12.345, when submitted, then it is stored as 12.345.
- **AC2 (rejection)** Given a weight of 12.3456, when submitted, then the response is 400 `VALIDATION_FAILED` for that field.

### FR-INV-16 — Wastage and discrepancy visibility
**Priority:** MVP
**Description:** The summary shows cumulative wastage per product type, and production and outsourcing reports show wastage and discrepancy separately, so material loss is visible and never merged into available stock.
- **AC1** Given wastage movements totalling 12.000 kg in September, when the wastage report is read for September, then it shows 12.000 kg split by source (production, outsourcing, manual).
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-INV-17 — Customer returns
**Priority:** POST-MVP
**Description:** Returned goods become a `CUSTOMER_RETURN` movement into `FINISHED_STOCK` (usable) or a `WASTAGE` movement (unusable); an unjustified return leaves the customer's payable unchanged.
- **AC1** Given 5.000 kg of usable returned warp, when recorded, then finished stock rises by 5.000.
- **AC2 (rejection)** Given a return larger than the quantity delivered on that order, when recorded, then the response is 409 `INSUFFICIENT_INVENTORY`.

### FR-INV-18 — Low-stock threshold and lot costing
**Priority:** POST-MVP
**Description:** Configurable thresholds per product (alerts), and lot-level traceability or FIFO costing, are specified for later.
- **AC1** Given a threshold of 50 kg and stock of 40 kg, when evaluated (post-MVP), then an alert is created.
- **AC2 (rejection)** Given a negative threshold, when configured, then the response is 400 `VALIDATION_FAILED`.

---

## 1.5 Customer orders (ORD)

Operational status and payment status are separate. Operational: `PLACED → CONFIRMED → IN_PROGRESS → READY → DELIVERED → COMPLETED`, and `CANCELLED`. Payment status is derived: `UNPAID`, `PARTIALLY_PAID`, `PAID`.

### FR-ORD-01 — Create an order
**Priority:** MVP
**Description:** `POST /api/v1/orders` creates an order for an active party with role `CUSTOMER`: order date, optional expected delivery date (not before the order date), notes, and at least one item (product type `RAW_SILK`, `SILK_WARP`, `KORA_WARP` or `VUDA_WARP`; required weight in kg greater than 0; rate per kg at least 0.00; optional warp specification of at most 500 characters). Status starts `PLACED`; number `ORD-<year>-<4 digits>`.
- **AC1** Given customer C and one item of 100.000 kg `KORA_WARP` at 850.00, when created, then the response is 201 with status `PLACED`, number `ORD-2026-0001` and item amount 85000.00.
- **AC2 (rejection)** Given no items, when created, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an item weight of 0, when created, then the response is 400 `VALIDATION_FAILED`.
- **AC4 (rejection)** Given a party without the `CUSTOMER` role, when created, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC5 (rejection)** Given an inactive customer, when created, then the response is 422 `PARTY_INACTIVE`.
- **AC6 (rejection)** Given an expected delivery date before the order date, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-ORD-02 — Item amounts are derived
**Priority:** MVP
**Description:** `amount = round(requiredWeightKg × ratePerKg, 2, HALF_UP)` per item; the order total is the sum of the rounded item amounts (ASSUMPTION Q5: paise kept, no whole-rupee rounding). Clients cannot send amounts.
- **AC1** Given 12.345 kg at 333.33, when created, then the item amount is 4114.96.
- **AC2** Given two items of 85000.00 and 4114.96, when fetched, then the total is 89114.96.
- **AC3** Given a request containing `amount` = 1.00, when created, then the stored amount is still computed from weight and rate.
- **AC4 (rejection)** Given a weight with four decimals (12.3456), when created, then the response is 400 `VALIDATION_FAILED`.

### FR-ORD-03 — Rates are historical
**Priority:** MVP
**Description:** The agreed rate is stored on the item and never recalculated from later prices.
- **AC1** Given an order placed at 850.00, when the owner later creates another order at 900.00, then the first order's amount is unchanged.
- **AC2 (rejection)** Given an order that already has allocations, when an item rate is patched, then the response is 409 `ORDER_LOCKED` (history is not rewritten).

### FR-ORD-04 — View an order
**Priority:** MVP
**Description:** `GET /api/v1/orders/{id}` returns the order, items, derived payment status, amount paid, outstanding, the linked production batches and outsourcing jobs, and whether the order is locked.
- **AC1** Given an order of 85000.00 with 30000.00 allocated, when fetched, then payment status is `PARTIALLY_PAID` and outstanding is 55000.00.
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no `ORDER_VIEW` permission, when fetched, then the response is 403 `ACCESS_DENIED`.

### FR-ORD-05 — List and filter orders
**Priority:** MVP
**Description:** `GET /api/v1/orders` returns a page filtered by `customerId`, `status`, `paymentStatus`, `productType`, `from`, `to` (order date), and `pending=true` (statuses `PLACED`, `CONFIRMED`, `IN_PROGRESS`, `READY`).
- **AC1** Given orders in several statuses, when `pending=true` is requested, then only those four statuses are returned.
- **AC2 (rejection)** Given `status=UNKNOWN`, when requested, then the response is 400 `MALFORMED_REQUEST`.

### FR-ORD-06 — Edit an order before it is locked
**Priority:** MVP
**Description:** `PATCH /api/v1/orders/{id}` may change items, rates, expected date and notes while the order is `PLACED` or `CONFIRMED`, not locked, and has no payment allocations. Amounts are recalculated by the backend.
- **AC1** Given a `PLACED` order, when an item's weight is changed to 110.000, then the amount is recalculated.
- **AC2 (rejection)** Given an `IN_PROGRESS` order, when patched, then the response is 409 `ORDER_LOCKED`.
- **AC3 (rejection)** Given an order that already has a payment allocation, when an item rate is changed, then the response is 409 `ORDER_LOCKED`.

### FR-ORD-07 — Confirm an order
**Priority:** MVP
**Description:** `POST /api/v1/orders/{id}/confirm` moves `PLACED → CONFIRMED`. From this point the order's items are customer obligations (ASSUMPTION Q2) and any unallocated advance of the customer is applied to them oldest-first (see FR-PAY-05).
- **AC1** Given a `PLACED` order, when confirmed, then status is `CONFIRMED` and it appears in the customer's outstanding.
- **AC2 (rejection)** Given a `DELIVERED` order, when confirmed, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-ORD-08 — Order locking when work starts
**Priority:** MVP
**Description:** When a production batch containing any item of the order is started, or an outsourcing job linked to it issues material, the orders module marks the order `IN_PROGRESS` and locked. `canModify()` is the single check used for edit and cancel. Other modules call the orders application service, never its repository.
- **AC1** Given a `CONFIRMED` order, when a linked production batch starts, then the order becomes `IN_PROGRESS` and locked.
- **AC2 (rejection)** Given a locked order, when edit or cancel is requested, then the response is 409 `ORDER_LOCKED`.

### FR-ORD-09 — Cancel an order
**Priority:** MVP
**Description:** `POST /api/v1/orders/{id}/cancel` cancels from `PLACED` or `CONFIRMED` while not locked. An order with payment allocations cannot be cancelled until those payments are reversed (ASSUMPTION Q2). The record is kept.
- **AC1** Given a `CONFIRMED` order with no allocations, when cancelled, then status is `CANCELLED` and it leaves the customer's outstanding.
- **AC2 (rejection)** Given an order with an allocation, when cancelled, then the response is 409 `INVALID_STATE_TRANSITION` with the message "reverse the payment first".
- **AC3 (rejection)** Given an `IN_PROGRESS` order, when cancelled, then the response is 409 `ORDER_LOCKED`.
- **AC4 (rejection)** Given a `DELIVERED` order, when cancelled, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-ORD-10 — Mark an order ready
**Priority:** MVP
**Description:** `POST /api/v1/orders/{id}/ready` moves `IN_PROGRESS → READY` (finished goods checked and weighed) or `CONFIRMED → READY` (order filled from existing stock without production).
- **AC1** Given an `IN_PROGRESS` order, when marked ready, then status is `READY`.
- **AC2 (rejection)** Given a `PLACED` order, when marked ready, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-ORD-11 — Deliver an order
**Priority:** MVP
**Description:** `POST /api/v1/orders/{id}/deliver` moves `READY → DELIVERED` and, in one transaction, creates one movement per item: `DELIVERY` from `FINISHED_STOCK` to `CUSTOMER` for `SILK_WARP` and `KORA_WARP`; `DIRECT_SALE` from `RAW_STOCK` to `CUSTOMER` for `RAW_SILK` and `VUDA_WARP` (ASSUMPTION Q7). The delivery date is recorded.
- **AC1** Given a `READY` order of 100.000 kg `KORA_WARP` and 100.000 kg in finished stock, when delivered, then finished stock is 0.000 and one `DELIVERY` movement of 100.000 exists.
- **AC2 (rejection)** Given only 80.000 kg in finished stock, when delivered, then the response is 409 `INSUFFICIENT_INVENTORY`, the order stays `READY` and no movement is stored.
- **AC3 (rejection)** Given a `CONFIRMED` order that was not marked ready, when delivered, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-ORD-12 — No partial delivery of an item
**Priority:** MVP
**Description:** All items of an order are delivered together; a single warp item is never delivered in arbitrary pieces.
- **AC1 (rejection)** Given two items where stock covers only the first, when the order is delivered, then the whole delivery is rejected with 409 `INSUFFICIENT_INVENTORY` and no item moves.

### FR-ORD-13 — Complete an order
**Priority:** MVP
**Description:** `POST /api/v1/orders/{id}/complete` moves `DELIVERED → COMPLETED`, independent of payment status. Payment status shows whether it is settled.
- **AC1** Given a `DELIVERED` order with outstanding 5000.00, when completed, then status is `COMPLETED` and payment status stays `PARTIALLY_PAID`.
- **AC2 (rejection)** Given a `READY` order, when completed, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-ORD-14 — Payment status is derived
**Priority:** MVP
**Description:** Order and item payment status is computed from allocations: `UNPAID` (none), `PARTIALLY_PAID`, `PAID` (allocated equals amount). It is never set by a client.
- **AC1** Given allocations equal to the item amount, when read, then payment status is `PAID`.
- **AC2 (rejection)** Given a request body with `paymentStatus`, when an order is created or patched, then the field has no effect.

### FR-ORD-15 — Direct sale of raw silk and vuda warp
**Priority:** MVP
**Description:** Selling raw silk or vuda warp uses the same order flow with item types `RAW_SILK` or `VUDA_WARP`; no separate sale entity exists (ASSUMPTION Q7).
- **AC1** Given an order for 20.000 kg `RAW_SILK` with 50.000 kg stock, when delivered, then a `DIRECT_SALE` movement of 20.000 exists and stock is 30.000.
- **AC2 (rejection)** Given 10.000 kg stock, when delivered, then the response is 409 `INSUFFICIENT_INVENTORY`.

### FR-ORD-16 — Pending and late orders
**Priority:** MVP
**Description:** Each order exposes `late` = true when its expected delivery date is before today (Asia/Kolkata) and its status is before `DELIVERED`.
- **AC1** Given expected date 2026-10-10, status `IN_PROGRESS`, today 2026-10-12, when fetched, then `late` is true.
- **AC2** Given the same order delivered, when fetched, then `late` is false.
- **AC3 (rejection)** Given a `CANCELLED` order past its expected date, when fetched, then `late` is false.

### FR-ORD-17 — Orders are never deleted
**Priority:** MVP
**Description:** No endpoint deletes an order or an item.
- **AC1 (rejection)** Given any order, when `DELETE /api/v1/orders/{id}` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-ORD-18 — Order numbers
**Priority:** MVP
**Description:** Order numbers are unique and gap-free per year (`ORD-<year>-<4 digits>`).
- **AC1** Given 10 orders created in parallel, when finished, then the numbers are 10 distinct consecutive values.
- **AC2 (rejection)** Given an order number inserted directly that duplicates an existing one, when the insert runs, then the database rejects it (unique constraint).

### FR-ORD-19 — Per-item delivery, customer returns and final bill
**Priority:** POST-MVP
**Description:** Delivery per item or partial quantity (where the business allows it), customer returns of damaged goods, and generating the final bill are post-MVP.
- **AC1** Given the post-MVP release, when a damaged return is recorded, then stock or wastage and the customer's payable are adjusted per FR-INV-17.
- **AC2 (rejection)** Given a return larger than delivered quantity, when recorded, then the response is 409 `INSUFFICIENT_INVENTORY`.

---

## 1.6 Payments and settlement (PAY)

A **payment** is a settlement event with allocations to obligations; every payment also produces one **financial transaction** (see FIN). Direction `IN` is money received (customers); `OUT` is money paid (suppliers, manufacturers, workers). Payment methods: `CASH`, `UPI`, `BANK_TRANSFER`, `CHEQUE` (a cheque is treated as cleared when recorded; clearance tracking is post-MVP).

### FR-PAY-01 — Record a customer payment
**Priority:** MVP
**Description:** `POST /api/v1/payments` with direction `IN` records money received from an active `CUSTOMER`: amount (greater than 0, 2 decimals), method, financial account, payment date, notes, and optional explicit allocations. In one transaction it creates the payment (`PAY-<year>-<4 digits>`), its allocations, and an `IN` financial transaction on the account.
- **AC1** Given customer C with outstanding items and bank account A, when a payment of 45000.00 by `UPI` is recorded, then account A's balance rises by 45000.00 and the response lists the allocations.
- **AC2 (rejection)** Given an amount of 0 or negative, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an inactive customer, when recorded, then the response is 422 `PARTY_INACTIVE`.
- **AC4 (rejection)** Given a closed account, when recorded, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC5 (rejection)** Given a Staff token, when recorded, then the response is 403 `ACCESS_DENIED`.

### FR-PAY-02 — Oldest-first allocation
**Priority:** MVP
**Description:** Unless explicit allocations are supplied, a customer payment is allocated to the customer's outstanding order items ordered by order date, then order number, then item order. An item is filled completely before the next one receives anything.
- **AC1** Given outstanding A 20000.00 (oldest), B 30000.00, C 40000.00, when 45000.00 is paid, then A is allocated 20000.00 and settled, B is allocated 25000.00 with 5000.00 still outstanding, and C is untouched.
- **AC2** Given one payment larger than a single item, when recorded, then it settles several items and each allocation row is stored.
- **AC3** Given two items with the same order date, when a payment arrives, then the one with the lower order number is filled first.
- **AC4 (rejection)** Given a `PLACED` order (not yet an obligation), when a payment is recorded, then that order receives no allocation.

### FR-PAY-03 — Explicit allocations are validated
**Priority:** MVP
**Description:** A client may supply allocations `[{orderItemId, amount}]`. The backend validates that each item belongs to this customer, is still outstanding, that each amount does not exceed its outstanding, and that the sum does not exceed the payment amount. The unallocated remainder becomes advance.
- **AC1** Given allocations of 10000.00 to item X and 5000.00 to item Y for a 20000.00 payment, when recorded, then those allocations are stored and 5000.00 is advance.
- **AC2 (rejection)** Given an allocation larger than the item's outstanding, when recorded, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC3 (rejection)** Given allocations summing to more than the payment amount, when recorded, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC4 (rejection)** Given an item that belongs to another customer, when recorded, then the response is 422 `INVALID_PAYMENT_ALLOCATION`.

### FR-PAY-04 — Customer advance
**Priority:** MVP
**Description:** The part of a customer payment that cannot be allocated is stored as an unallocated amount (advance). It is visible on the customer's outstanding view, is not revenue, and is never discarded.
- **AC1** Given outstanding of 60000.00, when 100000.00 is paid, then 60000.00 is allocated and 40000.00 is shown as advance.
- **AC2** Given a customer with no obligations, when 5000.00 is paid, then the whole amount is advance.
- **AC3 (rejection)** Given an advance of 40000.00 and no deliveries, when the sales report is read, then revenue from it is 0.00 (an advance is not revenue).

### FR-PAY-05 — Advance is applied when an order is confirmed (ASSUMPTION Q2)
**Priority:** MVP
**Description:** When an order is confirmed, the customer's available advance is allocated to the new order's items oldest-first, in the same transaction, using existing payments' unallocated amounts oldest payment first.
- **AC1** Given a 40000.00 advance and a confirmed order of 25000.00, when confirmed, then 25000.00 is allocated, the order is `PAID` and 15000.00 advance remains.
- **AC2** Given no advance, when an order is confirmed, then no allocation is created.
- **AC3 (rejection)** Given an advance that belongs to a reversed payment, when an order is confirmed, then that amount is not applied.

### FR-PAY-06 — Atomic settlement
**Priority:** MVP
**Description:** Payment, allocations and financial transaction commit together or not at all.
- **AC1 (rejection)** Given a failure while storing the third allocation, when the payment is recorded, then no payment, allocation or financial transaction row exists and the account balance is unchanged.

### FR-PAY-07 — Idempotent payment requests
**Priority:** MVP
**Description:** `POST /api/v1/payments` (and expenses, transfers, receipts) accept an `Idempotency-Key` header. The response of the first successful call is stored with a hash of the request body in the same transaction; replays return it. Keys are kept for 48 hours.
- **AC1** Given the same key and body posted twice, when both complete, then one payment exists and both responses are identical.
- **AC2 (rejection)** Given the same key with a different amount, when posted, then the response is 409 `DUPLICATE_REQUEST`.
- **AC3** Given two simultaneous requests with the same key, when both run, then exactly one payment exists.
- **AC4** Given a key older than 48 hours, when reused, then it is treated as a new request.

### FR-PAY-08 — Record a supplier payment
**Priority:** MVP
**Description:** A payment with direction `OUT` to an active `SUPPLIER` is allocated oldest-first to the supplier's purchases that have remaining payable, by purchase date then purchase number (explicit allocations allowed and validated as in FR-PAY-03). The total cannot exceed the supplier's total payable (supplier advances are post-MVP). It creates an `OUT` financial transaction.
- **AC1** Given purchases P1 (due first, payable 50000.00) and P2 (30000.00), when 60000.00 is paid, then P1 is settled and P2 receives 10000.00.
- **AC2 (rejection)** Given a total payable of 80000.00, when 90000.00 is paid, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC3 (rejection)** Given a cash account balance of 5000.00, when 20000.00 is paid in cash, then the response is 409 `INSUFFICIENT_FUNDS` and nothing is stored.
- **AC4 (rejection)** Given a party without the `SUPPLIER` role, when paid as a supplier, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-09 — Record a manufacturer payment
**Priority:** MVP
**Description:** A payment with direction `OUT` to a `MANUFACTURER` is allocated oldest-first to outsourcing jobs with remaining payable (by job issue date, then job number). Payable per job is `received kg × the job's rate`.
- **AC1** Given job J1 payable 18000.00 and J2 12000.00, when 20000.00 is paid, then J1 is settled and J2 receives 2000.00.
- **AC2 (rejection)** Given a total payable of 30000.00, when 35000.00 is paid, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.

### FR-PAY-10 — Record a worker payment
**Priority:** MVP
**Description:** A payment with direction `OUT` to a `WORKER` is allocated oldest-first to the worker's unpaid work records (by work date). Weekly payroll is one payment against many records.
- **AC1** Given records of 3000.00, 2500.00 and 3200.00, when 5500.00 is paid, then the first two are paid in full.
- **AC2 (rejection)** Given a total payable of 8700.00, when 9000.00 is paid, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC3 (rejection)** Given only a reversed work record, when a payment is made, then the payable is 0.00 and the payment is rejected with 409 `PAYMENT_ALLOCATION_EXCEEDED`.

### FR-PAY-11 — Party role must match the direction
**Priority:** MVP
**Description:** Direction `IN` accepts only customers; direction `OUT` accepts suppliers, manufacturers and workers. Refunds to customers are post-MVP.
- **AC1 (rejection)** Given direction `OUT` and a customer, when recorded, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC2 (rejection)** Given direction `IN` and a supplier, when recorded, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-12 — Method and account must be consistent
**Priority:** MVP
**Description:** Method `CASH` requires an account of type `CASH`; methods `UPI`, `BANK_TRANSFER` and `CHEQUE` require an account of type `BANK`.
- **AC1** Given method `UPI` and a bank account, when recorded, then it is accepted.
- **AC2 (rejection)** Given method `CASH` and a bank account, when recorded, then the response is 422 `INVALID_PAYMENT_METHOD`.

### FR-PAY-13 — Money precision
**Priority:** MVP
**Description:** Amounts have at most 2 decimals, are at most 999999999999.99, and are compared with `compareTo`.
- **AC1** Given 1234.50, when posted, then it is stored as 1234.50.
- **AC2 (rejection)** Given 10.005, when posted, then the response is 400 `VALIDATION_FAILED`.

### FR-PAY-14 — View and list payments
**Priority:** MVP
**Description:** `GET /api/v1/payments/{id}` returns the payment, status, allocations and the financial transaction id. `GET /api/v1/payments` returns a page filtered by `partyId`, `direction`, `method`, `accountId`, `status`, `from`, `to`.
- **AC1** Given a payment with two allocations, when fetched, then both appear with order or purchase numbers.
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no `PAYMENT_VIEW` permission, when listed, then the response is 403 `ACCESS_DENIED`.

### FR-PAY-15 — Reverse a payment
**Priority:** MVP
**Description:** `POST /api/v1/payments/{id}/reverse` (permission `PAYMENT_REVERSE`, with a reason) sets the payment `REVERSED`, records a compensating financial transaction in the opposite direction on the same account, and releases all its allocations (the obligations become outstanding again; allocation history is kept). Amounts are never edited.
- **AC1** Given a customer payment of 45000.00 settling A and part of B, when reversed, then A and B are outstanding again by the allocated amounts and the account balance falls by 45000.00.
- **AC2 (rejection)** Given a payment already `REVERSED`, when reversed again, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given a customer payment whose account no longer holds enough, when reversed, then the response is 409 `INSUFFICIENT_FUNDS`.
- **AC4 (rejection)** Given a blank reason, when reversed, then the response is 400 `VALIDATION_FAILED`.

### FR-PAY-16 — Payments are immutable
**Priority:** MVP
**Description:** There is no endpoint to change a payment's amount, party, account or allocations, or to delete it.
- **AC1 (rejection)** Given any payment, when `PUT`, `PATCH` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PAY-17 — Customer outstanding (ASSUMPTION Q2)
**Priority:** MVP
**Description:** `GET /api/v1/customers/{id}/outstanding` returns total outstanding = sum of item amounts of orders in `CONFIRMED` to `COMPLETED` minus allocations, with a breakdown per order (amount, allocated, outstanding, order date, age in days) and the unallocated advance. `PLACED` and `CANCELLED` orders are excluded. Computed with SQL aggregation.
- **AC1** Given confirmed orders of 20000.00, 30000.00 and 40000.00 with 45000.00 allocated, when read, then outstanding is 45000.00 with the per-order breakdown from FR-PAY-02.
- **AC2** Given a `PLACED` order of 10000.00, when read, then it is not counted.
- **AC3 (rejection)** Given a party that is not a customer, when read, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-18 — Customer outstanding ageing
**Priority:** MVP
**Description:** The outstanding view groups amounts by age of the order date: 0–30, 31–60, 61–90 and over 90 days, using today's date from the clock.
- **AC1** Given outstanding of 10000.00 from 40 days ago and 5000.00 from 5 days ago, when read, then bucket 31–60 shows 10000.00 and bucket 0–30 shows 5000.00.
- **AC2 (rejection)** Given a `CANCELLED` order, when ageing is read, then it is in no bucket.

### FR-PAY-19 — Supplier payables
**Priority:** MVP
**Description:** `GET /api/v1/suppliers/{id}/payables` and `GET /api/v1/payables/suppliers` return payable per purchase (FR-PROC-15), due date, overdue days and totals (all suppliers: total payable, total overdue, due within 7 days).
- **AC1** Given two purchases of one supplier, when read, then each payable and the total are shown and overdue items are flagged.
- **AC2 (rejection)** Given a party without the `SUPPLIER` role, when read, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-20 — Manufacturer payable
**Priority:** MVP
**Description:** `GET /api/v1/manufacturers/{id}/payable` returns, per job, `received kg × rate` minus allocations, and the total.
- **AC1** Given a job with 150.000 kg received at 120.00 and 10000.00 paid, when read, then payable is 8000.00.
- **AC2 (rejection)** Given a party without the `MANUFACTURER` role, when read, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-21 — Worker payable
**Priority:** MVP
**Description:** `GET /api/v1/workers/{id}/payable` returns total work value, total paid and pending, optionally limited to a date range (a payroll week).
- **AC1** Given weekly work of 8700.00 and 5500.00 paid, when read for that week, then pending is 3200.00.
- **AC2 (rejection)** Given `from` later than `to`, when read, then the response is 400 `VALIDATION_FAILED`.

### FR-PAY-22 — Opening obligations at go-live
**Priority:** MVP
**Description:** Existing customer receivables and supplier payables are entered as dated **opening obligations** (party, role, amount, obligation date on or before today, reference label). They take part in oldest-first allocation, are not revenue, expense or purchases, and change no stock.
- **AC1** Given an opening receivable of 25000.00 dated 2026-03-31 for customer C, when C pays 10000.00, then the opening obligation is allocated first (it is the oldest).
- **AC2 (rejection)** Given an obligation date in the future, when entered, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a party without the matching role, when entered, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-PAY-23 — Settlement history
**Priority:** MVP
**Description:** Allocation records are never changed or deleted; reversal marks them released. `GET /api/v1/payments/{id}/allocations` lists them.
- **AC1** Given a reversed payment, when its allocations are listed, then they are present and marked released.
- **AC2 (rejection)** Given any allocation, when `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PAY-24 — Concurrent payments cannot over-allocate
**Priority:** MVP
**Description:** The obligation rows touched by a payment are locked (in ascending id order) before outstanding is computed, so two simultaneous payments cannot allocate more than is outstanding.
- **AC1** Given an outstanding item of 40000.00 and two parallel customer payments of 30000.00 each, when both run, then total allocated to it is exactly 40000.00 and the rest of the second payment is advance.
- **AC2 (rejection)** Given two parallel supplier payments totalling more than the payable, when both run, then one succeeds and the other fails with 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC3 (rejection)** Given any interleaving of two payments, when allocations are summed per obligation, then no obligation is allocated more than its amount.

### FR-PAY-25 — Refunds, cheque clearance, supplier advances
**Priority:** POST-MVP
**Description:** Refunds to customers, pending and bounced cheque states (a pending cheque would not affect available money until cleared), and advances paid to suppliers are specified for later releases.
- **AC1** Given the post-MVP release, when a cheque is recorded as pending, then available money does not change until it is cleared.
- **AC2 (rejection)** Given a bounced cheque, when it is marked bounced, then the allocated obligations return to outstanding.

---
## 1.7 In-house production (PROD)

Warp is produced in-house in **batches**: raw silk is consumed into internal WIP and the completed batch produces finished warp and recorded wastage. A batch may serve several order items and an order item may be served by several batches. Batch status: `PLANNED → IN_PROGRESS → COMPLETED`, or `PLANNED → CANCELLED`.

### FR-PROD-01 — Create a production batch
**Priority:** MVP
**Description:** `POST /api/v1/production-batches` creates a `PLANNED` batch: warp product type (`SILK_WARP` or `KORA_WARP`), production date, input weight (kg, greater than 0, the raw silk to be consumed), optional notes, and optional links to order items with allocated weight (greater than 0). Number `BAT-<year>-<4 digits>`.
- **AC1** Given product `KORA_WARP`, input 100.000 kg and a link of 60.000 kg to an item of a confirmed order, when created, then the response is 201 with status `PLANNED`, number `BAT-2026-0001` and no stock change.
- **AC2 (rejection)** Given product type `VUDA_WARP`, when created, then the response is 422 `INVALID_PRODUCT_FOR_OPERATION`.
- **AC3 (rejection)** Given input weight 0, when created, then the response is 400 `VALIDATION_FAILED`.
- **AC4 (rejection)** Given a link to an item of a `CANCELLED` or `DELIVERED` order, when created, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC5 (rejection)** Given a link to an unknown order item, when created, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-PROD-02 — Start a batch (consume raw silk)
**Priority:** MVP
**Description:** `POST /api/v1/production-batches/{id}/start` moves `PLANNED → IN_PROGRESS` and, in one transaction, creates a `CONSUMPTION` movement of the input weight: raw silk leaves `RAW_STOCK` and arrives in `INTERNAL_WIP` recorded as the batch's warp type (`SILK_WARP` or `KORA_WARP`), and marks every linked order `IN_PROGRESS` and locked through the orders service.
- **AC1** Given 120.000 kg raw silk and a batch input of 100.000, when started, then raw silk in `RAW_STOCK` is 20.000, the batch's warp type in `INTERNAL_WIP` is 100.000 and linked orders are locked.
- **AC2 (rejection)** Given only 80.000 kg raw silk, when started, then the response is 409 `INSUFFICIENT_INVENTORY`, the batch stays `PLANNED` and no order is locked.
- **AC3 (rejection)** Given an `IN_PROGRESS` batch, when started again, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC4 (rejection)** Given a linked order that was cancelled after the batch was created, when started, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PROD-03 — Complete a batch (reconciliation)
**Priority:** MVP
**Description:** `POST /api/v1/production-batches/{id}/complete` records output weight, wastage weight and discrepancy weight (each at least 0) and requires `input = output + wastage + discrepancy`. In one transaction it removes the batch's input from `INTERNAL_WIP`, adds the output to `FINISHED_STOCK` as the batch's warp type (`PRODUCTION_OUTPUT`), and records wastage and discrepancy as `WASTAGE` movements (discrepancy flagged so it stays visible). Status becomes `COMPLETED`.
- **AC1** Given input 100.000, when completed with output 94.000, wastage 4.000 and discrepancy 2.000, then finished warp rises by 94.000, wastage by 6.000 (of which 2.000 discrepancy) and `INTERNAL_WIP` falls by 100.000.
- **AC2 (rejection)** Given input 100.000, when completed with output 94.000, wastage 10.000 and discrepancy 0.000, then the response is 422 `PRODUCTION_NOT_RECONCILED` and nothing changes.
- **AC3 (rejection)** Given a negative output, when completed, then the response is 400 `VALIDATION_FAILED`.
- **AC4 (rejection)** Given a `PLANNED` batch, when completed, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PROD-04 — Discrepancy is explicit
**Priority:** MVP
**Description:** A difference between input and (output + wastage) is never silently absorbed; it must be entered as a discrepancy and appears in production and wastage reports.
- **AC1** Given a completed batch with discrepancy 2.000, when the batch is fetched, then it shows `discrepancyWeightKg` 2.000.
- **AC2 (rejection)** Given output and wastage summing to 96.000 for an input of 100.000 and no discrepancy entered, when completed, then the response is 422 `PRODUCTION_NOT_RECONCILED`.

### FR-PROD-05 — Cancel a batch
**Priority:** MVP
**Description:** `POST /api/v1/production-batches/{id}/cancel` is allowed only from `PLANNED` (no stock was consumed). A started batch cannot be cancelled; its outcome is recorded by completing it.
- **AC1** Given a `PLANNED` batch, when cancelled, then status is `CANCELLED`.
- **AC2 (rejection)** Given an `IN_PROGRESS` batch, when cancelled, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PROD-06 — View and list batches
**Priority:** MVP
**Description:** `GET /api/v1/production-batches/{id}` returns the batch, its order links, status, input, output, wastage, discrepancy and work records. `GET /api/v1/production-batches` returns a page filtered by `status`, `productType`, `orderId`, `from`, `to`.
- **AC1** Given batches in several statuses, when `status=IN_PROGRESS` is requested, then only started batches are returned (the internal WIP list).
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no `PRODUCTION_VIEW` permission, when listed, then the response is 403 `ACCESS_DENIED`.

### FR-PROD-07 — Many-to-many with order items
**Priority:** MVP
**Description:** One batch can be linked to several order items and one order item to several batches, each link with an allocated weight.
- **AC1** Given two batches linked to the same item, when the item's order is fetched, then both batches are listed.
- **AC2** Given one batch linked to items of two orders, when started, then both orders become `IN_PROGRESS` and locked.
- **AC3 (rejection)** Given a link with allocated weight 0, when a batch is created, then the response is 400 `VALIDATION_FAILED`.

### FR-PROD-08 — Atomic start and completion
**Priority:** MVP
**Description:** Start and complete each run as one transaction including all movements and the status change.
- **AC1 (rejection)** Given a failure while writing the wastage movement during completion, when the request runs, then the batch is still `IN_PROGRESS` and no output movement exists.

### FR-PROD-09 — Concurrent starts cannot oversubscribe stock
**Priority:** MVP
**Description:** Two batches started at the same moment compete for the same raw-silk balance under the row lock.
- **AC1 (rejection)** Given 100.000 kg and two parallel starts of 80.000 and 70.000, when both run, then exactly one succeeds and the other fails with 409 `INSUFFICIENT_INVENTORY`.

### FR-PROD-10 — Completed batches are immutable
**Priority:** MVP
**Description:** A completed or cancelled batch cannot be edited or deleted. Mistakes are corrected with a stock adjustment (FR-INV-12) plus a note.
- **AC1 (rejection)** Given a completed batch, when `PUT`, `PATCH` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-PROD-11 — Edit a planned batch
**Priority:** MVP
**Description:** `PATCH /api/v1/production-batches/{id}` may change date, input weight, links and notes while the batch is `PLANNED`.
- **AC1** Given a `PLANNED` batch, when input is changed to 110.000, then it is stored.
- **AC2 (rejection)** Given an `IN_PROGRESS` batch, when patched, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-PROD-12 — Production analytics
**Priority:** POST-MVP
**Description:** Wastage percentage trends, separate rolling and warping wastage, worker efficiency and in-house versus outsourced cost comparison. The first release records one wastage figure per batch.
- **AC1** Given the post-MVP release, when the production report is read for a month, then wastage percentage per batch is shown.
- **AC2 (rejection)** Given a period with no batches, when read, then the response is 200 with zero totals.

---

## 1.8 Outsourcing / job work (OUT)

Raw silk is given to an external manufacturer who returns finished warp. The manufacturer is paid for labour on **received warp kilograms**, not for silk. Job status: `CREATED → MATERIAL_ISSUED → PARTIALLY_RECEIVED → COMPLETED`, or `CREATED → CANCELLED`.

### FR-OUT-01 — Create an outsourcing job
**Priority:** MVP
**Description:** `POST /api/v1/outsourcing-jobs` creates a job: manufacturer (active, role `MANUFACTURER`), warp product type expected back (`SILK_WARP` or `KORA_WARP`), raw-silk weight to issue (kg, greater than 0), manufacturing rate per kg (at least 0.00), optional links to order items with allocated weight, optional notes. Number `JOB-<year>-<4 digits>`; status `CREATED`.
- **AC1** Given manufacturer M, `KORA_WARP`, 200.000 kg and rate 120.00, when created, then the response is 201 with status `CREATED` and no stock change.
- **AC2 (rejection)** Given a party without the `MANUFACTURER` role, when created, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC3 (rejection)** Given product type `VUDA_WARP`, when created, then the response is 422 `INVALID_PRODUCT_FOR_OPERATION`.
- **AC4 (rejection)** Given an inactive manufacturer, when created, then the response is 422 `PARTY_INACTIVE`.
- **AC5 (rejection)** Given a negative rate, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-OUT-02 — Issue raw silk to the manufacturer
**Priority:** MVP
**Description:** `POST /api/v1/outsourcing-jobs/{id}/issue` (with the issue date) moves `CREATED → MATERIAL_ISSUED` and creates an `OUTSOURCE_ISSUE` movement of the job's weight in the same transaction: raw silk leaves `RAW_STOCK` and arrives in `EXTERNAL_WIP` recorded as the job's warp type (`SILK_WARP` or `KORA_WARP`). Linked orders become `IN_PROGRESS` and locked.
- **AC1** Given 300.000 kg raw silk and a job of 200.000, when issued, then raw silk in `RAW_STOCK` is 100.000 and the job's warp type in `EXTERNAL_WIP` is 200.000.
- **AC2 (rejection)** Given 150.000 kg raw silk, when issued, then the response is 409 `INSUFFICIENT_INVENTORY` and the job stays `CREATED`.
- **AC3 (rejection)** Given a job already issued, when issued again, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-OUT-03 — Receive finished warp
**Priority:** MVP
**Description:** `POST /api/v1/outsourcing-jobs/{id}/receive` records a receipt: receipt date, received warp weight (greater than 0), wastage or difference weight (at least 0), notes. A job can have several receipts. It creates an `OUTSOURCE_RECEIPT` movement from `EXTERNAL_WIP` to `FINISHED_STOCK` (the job's warp type) for the received weight and a `WASTAGE` movement for the wastage weight.
- **AC1** Given a job issued 200.000, when 150.000 received with wastage 3.000, then finished warp rises by 150.000, `EXTERNAL_WIP` falls by 153.000 and status is `PARTIALLY_RECEIVED`.
- **AC2 (rejection)** Given 150.000 received and 3.000 wastage so far, when a receipt of 60.000 with wastage 0 is recorded, then the response is 409 `OUTSOURCE_RECEIPT_EXCEEDED` (only 47.000 left to account for).
- **AC3 (rejection)** Given a job in status `CREATED`, when a receipt is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC4 (rejection)** Given received weight 0, when recorded, then the response is 400 `VALIDATION_FAILED`.

### FR-OUT-04 — Job completion
**Priority:** MVP
**Description:** When cumulative received plus wastage equals the issued weight the job becomes `COMPLETED`. A remaining unexplained difference is closed by entering it as wastage/difference on the final receipt.
- **AC1** Given issued 200.000, received 195.000 and wastage 5.000 in total, when the last receipt is recorded, then the status is `COMPLETED`.
- **AC2 (rejection)** Given a `COMPLETED` job, when another receipt is recorded, then the response is 409 `OUTSOURCE_RECEIPT_EXCEEDED`.

### FR-OUT-05 — Manufacturer payable is historical
**Priority:** MVP
**Description:** Payable for a job is `received kg × the job's rate` (rate copied at creation and never recalculated) minus payment allocations. Payment status is derived (`UNPAID`, `PARTIALLY_PAID`, `PAID`).
- **AC1** Given 150.000 kg received at 120.00 and 10000.00 allocated, when the job is fetched, then payable is 8000.00 and status `PARTIALLY_PAID`.
- **AC2** Given the manufacturer's rate changed on a later job, when an earlier job is fetched, then its rate and payable are unchanged.
- **AC3 (rejection)** Given an allocation larger than the job's remaining payable, when a payment is recorded, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.

### FR-OUT-06 — Cancel a job
**Priority:** MVP
**Description:** `POST /api/v1/outsourcing-jobs/{id}/cancel` is allowed only from `CREATED`.
- **AC1** Given a `CREATED` job, when cancelled, then status is `CANCELLED`.
- **AC2 (rejection)** Given a `MATERIAL_ISSUED` job, when cancelled, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-OUT-07 — View and list jobs
**Priority:** MVP
**Description:** `GET /api/v1/outsourcing-jobs/{id}` returns the job with issued, received, wastage, remaining outside, payable and receipts. `GET /api/v1/outsourcing-jobs` returns a page filtered by `manufacturerId`, `status`, `orderId`, `from`, `to`.
- **AC1** Given jobs for two manufacturers, when `manufacturerId` is given, then only that manufacturer's jobs are returned.
- **AC2 (rejection)** Given an unknown id, when fetched, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no `OUTSOURCING_MANAGE` permission, when a job is created, then the response is 403 `ACCESS_DENIED`.

### FR-OUT-08 — The order link is optional
**Priority:** MVP
**Description:** A job may be created for expected demand with no order; a linked order is locked when material is issued.
- **AC1** Given a job with no order, when issued, then it succeeds and no order changes.
- **AC2 (rejection)** Given a link to a `CANCELLED` order, when the job is created, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-OUT-09 — Atomic issue and receipt
**Priority:** MVP
**Description:** Issue and receive each run in one transaction including all movements, balance changes and the status change.
- **AC1 (rejection)** Given a failure while writing the wastage movement of a receipt, when the request runs, then no receipt, no movement and no status change exists.

### FR-OUT-10 — Concurrent issues cannot oversubscribe stock
**Priority:** MVP
**Description:** Issues compete for the raw-silk balance under the row lock.
- **AC1 (rejection)** Given 100.000 kg and two parallel issues of 80.000 and 70.000, when both run, then exactly one succeeds and the other fails with 409 `INSUFFICIENT_INVENTORY`.

### FR-OUT-11 — Receipts are immutable
**Priority:** MVP
**Description:** Job receipts and movements are never edited or deleted; mistakes are corrected by an inventory adjustment with a reason.
- **AC1 (rejection)** Given any receipt, when `PUT` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-OUT-12 — Outsourcing cost analysis
**Priority:** POST-MVP
**Description:** Comparison of in-house production cost and outsourcing cost per kilogram, and manufacturer comparison, are specified for later.
- **AC1** Given the post-MVP release, when the cost report is read, then in-house and outsourced cost per kg are shown side by side.
- **AC2 (rejection)** Given a period with no jobs, when read, then the response is 200 with empty comparison rows.

---

## 1.9 Workforce (WORK)

A **work record** is work performed by a worker; it is separate from the **payment** that settles it. Rolling is paid per hour, warping per kilogram. The rate is copied into each record.

### FR-WORK-01 — Record work
**Priority:** MVP
**Description:** `POST /api/v1/work-records` records work by an active `WORKER`: work date, work type `ROLLING` or `WARPING`, hours (for rolling: greater than 0, 2 decimals) or quantity in kg (for warping: greater than 0, 3 decimals), optional rate (at least 0.00; defaults to the worker's default rate for that type), optional production batch, notes. `amount = round(hours or kg × rate, 2, HALF_UP)`, calculated by the backend.
- **AC1** Given a rolling record of 8.00 hours at 100.00, when recorded, then `amount` is 800.00 and payment status is `UNPAID`.
- **AC2** Given a warping record of 25.500 kg for a worker whose default warping rate is 40.00, when recorded without a rate, then the stored rate is 40.00 and `amount` is 1020.00.
- **AC3 (rejection)** Given a rolling record without hours, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC4 (rejection)** Given a warping record without quantity, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC5 (rejection)** Given no rate and no default rate for that type, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC6 (rejection)** Given a party without the `WORKER` role, when recorded, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC7 (rejection)** Given an inactive worker, when recorded, then the response is 422 `PARTY_INACTIVE`.

### FR-WORK-02 — Only the relevant measure is allowed
**Priority:** MVP
**Description:** A rolling record carries hours only; a warping record carries quantity only.
- **AC1 (rejection)** Given a rolling record that also contains a quantity, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC2 (rejection)** Given a warping record that also contains hours, when recorded, then the response is 400 `VALIDATION_FAILED`.

### FR-WORK-03 — Historical rate
**Priority:** MVP
**Description:** The rate in force when the work was recorded is stored on the record and never recalculated.
- **AC1** Given a record at 100.00 per hour and a later change of the worker's default to 110.00, when the old record is fetched, then rate and amount are unchanged.
- **AC2 (rejection)** Given an existing record, when a `PATCH` changes its rate, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-WORK-04 — Payment status is derived
**Priority:** MVP
**Description:** A record is `UNPAID`, `PARTIALLY_PAID` or `PAID` according to the allocations of worker payments; `REVERSED` records are excluded.
- **AC1** Given a record of 800.00 and an allocation of 500.00, when fetched, then status is `PARTIALLY_PAID` and pending is 300.00.
- **AC2 (rejection)** Given a request body containing `status`, when a record is created, then the field has no effect.

### FR-WORK-05 — List and filter work records
**Priority:** MVP
**Description:** `GET /api/v1/work-records` returns a page filtered by `workerId`, `workType`, `status`, `from`, `to`, `productionBatchId`.
- **AC1** Given records across weeks, when a week is requested, then only that week's records and a total amount are returned.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-WORK-06 — Reverse a mistaken record
**Priority:** MVP
**Description:** `POST /api/v1/work-records/{id}/reverse` (with a reason) marks a record `REVERSED` and removes it from payables, allowed only when it has no active payment allocation. Amounts are never edited.
- **AC1** Given an unpaid record, when reversed, then it no longer counts in the worker's payable and remains visible.
- **AC2 (rejection)** Given a record with an allocation, when reversed, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given a blank reason, when reversed, then the response is 400 `VALIDATION_FAILED`.

### FR-WORK-07 — Records are immutable
**Priority:** MVP
**Description:** No endpoint changes a record's amount, hours, quantity or rate, or deletes it.
- **AC1 (rejection)** Given any record, when `PUT`, `PATCH` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-WORK-08 — Link to a production batch
**Priority:** MVP
**Description:** A record may reference a production batch (not required).
- **AC1** Given a batch id, when recorded, then the batch lists the record.
- **AC2 (rejection)** Given an unknown batch id, when recorded, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-WORK-09 — Permissions
**Priority:** MVP
**Description:** Recording and reversing work requires `WORK_RECORD_MANAGE`.
- **AC1 (rejection)** Given a Staff token, when work is recorded, then the response is 403 `ACCESS_DENIED`.

### FR-WORK-10 — Worker efficiency analysis
**Priority:** POST-MVP
**Description:** Production per hour and per worker comparisons are specified for later.
- **AC1** Given the post-MVP release, when the efficiency report is read, then kilograms per hour per worker are shown.
- **AC2 (rejection)** Given a worker with no records, when read, then the response is 200 with zero values.

---

## 1.10 Finance: accounts, transactions, transfers, expenses (FIN)

A **financial account** is `CASH` or `BANK` (each bank account is its own row). Every change of money is a **financial transaction** created through the finance service; there is no client endpoint that writes transactions. Balances are always derived. Neither cash nor bank accounts may go negative (ASSUMPTION Q4: no overdraft).

### FR-FIN-01 — Create an account
**Priority:** MVP
**Description:** `POST /api/v1/finance/accounts` creates an account: name (unique, at most 100 characters), type `CASH` or `BANK`, for banks the bank name and the last 4 digits of the account number, optional opening balance (at least 0.00) and opening date. Permission `FINANCE_MANAGE`.
- **AC1** Given name "SBI Current", type `BANK`, last digits "4821", when created, then the response is 201 with status `ACTIVE`.
- **AC2 (rejection)** Given a name that already exists, when created, then the response is 409 `DUPLICATE_RESOURCE`.
- **AC3 (rejection)** Given an account number field longer than 4 characters, when created, then the response is 400 `VALIDATION_FAILED` (full account numbers are never stored).
- **AC4 (rejection)** Given type `CASH` with a bank name, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-FIN-02 — Opening balance is a transaction
**Priority:** MVP
**Description:** An opening balance is stored as an `OPENING_BALANCE` transaction, never as a column, and is neither revenue nor income.
- **AC1** Given an account created with opening balance 80000.00, when its transactions are listed, then one `OPENING_BALANCE` `IN` transaction of 80000.00 exists and the balance is 80000.00.
- **AC2 (rejection)** Given a negative opening balance, when created, then the response is 400 `VALIDATION_FAILED`.

### FR-FIN-03 — Balances and available money are derived
**Priority:** MVP
**Description:** The balance of an account is the sum of its `IN` transactions minus its `OUT` transactions. `GET /api/v1/finance/accounts` returns each account with its balance. Total available money is cash plus all bank balances; receivables and expected inflows are excluded.
- **AC1** Given cash 80000.00 and banks 200000.00 and 50000.00, when read, then total available money is 330000.00.
- **AC2** Given customer receivables of 500000.00, when total available money is read, then it is unchanged.
- **AC3 (rejection)** Given no `FINANCE_VIEW` permission, when read, then the response is 403 `ACCESS_DENIED`.

### FR-FIN-04 — Transactions are written only by the system
**Priority:** MVP
**Description:** Payments, expenses, transfers, loans, chits and adjustments create transactions through the finance service. Clients cannot post, edit or delete a transaction.
- **AC1 (rejection)** Given any caller, when `POST /api/v1/finance/transactions` is called, then the response is 405 `METHOD_NOT_ALLOWED`.
- **AC2 (rejection)** Given any transaction, when `PUT`, `PATCH` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-FIN-05 — Transaction content
**Priority:** MVP
**Description:** A transaction has account, date, type, direction (`IN`/`OUT`), amount (greater than 0), description, `created_by`, and exactly one business reference (payment, expense, loan, chit or transfer) except `OPENING_BALANCE` and `ADJUSTMENT`, which have none. Types: `CUSTOMER_RECEIPT`, `SUPPLIER_PAYMENT`, `MANUFACTURER_PAYMENT`, `WORKER_PAYMENT`, `EXPENSE`, `PERSONAL_DRAWING`, `LOAN_RECEIVED`, `LOAN_PRINCIPAL_REPAYMENT`, `LOAN_INTEREST_PAYMENT`, `CHIT_CONTRIBUTION`, `CHIT_PAYOUT`, `CHIT_ORGANISER_PAYMENT`, `TRANSFER_OUT`, `TRANSFER_IN`, `OPENING_BALANCE`, `ADJUSTMENT`, `REVERSAL`.
- **AC1** Given a customer payment, when its transaction is read, then type is `CUSTOMER_RECEIPT`, direction `IN` and the payment id is the only reference.
- **AC2 (rejection)** Given a row with two references set, when it is inserted directly into the database, then the database rejects it (check constraint).

### FR-FIN-06 — Money never goes negative
**Priority:** MVP
**Description:** Before an `OUT` transaction the finance service locks the account row and checks `balance ≥ amount`. A database-level safeguard exists as the last line of defence.
- **AC1** Given cash of 10000.00, when an expense of 4000.00 is paid in cash, then the balance is 6000.00.
- **AC2 (rejection)** Given cash of 10000.00, when a cash expense of 12000.00 is recorded, then the response is 409 `INSUFFICIENT_FUNDS`, no expense or transaction is stored.
- **AC3 (rejection)** Given a bank balance of 5000.00, when a bank transfer out of 6000.00 is attempted, then the response is 409 `INSUFFICIENT_FUNDS`.
- **AC4 (rejection)** Given cash of 10000.00 and two parallel cash-outs of 7000.00, when both run, then exactly one succeeds, the other fails with 409 `INSUFFICIENT_FUNDS`, and the balance is 3000.00.

### FR-FIN-07 — Transaction history
**Priority:** MVP
**Description:** `GET /api/v1/finance/transactions` returns a page filtered by `accountId`, `type`, `direction`, `from`, `to`, `referenceType`, `referenceId`. `GET /api/v1/finance/accounts/{id}/transactions` lists one account's history with a running balance.
- **AC1** Given transactions across accounts, when filtered by `accountId` and a month, then only that account's transactions of that month are returned.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an unknown account id, when requested, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-FIN-08 — Cash position
**Priority:** MVP
**Description:** `GET /api/v1/finance/cash-position` returns physical cash, each bank account balance and the total available money.
- **AC1** Given the data in FR-FIN-03, when read, then it shows cash, both banks and the total.
- **AC2 (rejection)** Given no token, when read, then the response is 401 `UNAUTHENTICATED`.

### FR-FIN-09 — Transfer between own accounts
**Priority:** MVP
**Description:** `POST /api/v1/finance/transfers` moves money between two active accounts of the business (bank to cash, cash to bank, bank to bank): source, destination, amount (greater than 0), date, notes. Number `TRF-<year>-<4 digits>`. In one transaction it creates one `TRANSFER_OUT` and one `TRANSFER_IN` transaction. A transfer is neither revenue nor expense.
- **AC1** Given bank 50000.00 and cash 0.00, when 20000.00 is transferred bank to cash, then bank is 30000.00 and cash is 20000.00 and total available money is unchanged.
- **AC2 (rejection)** Given source equal to destination, when posted, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given the source balance 10000.00, when 15000.00 is transferred, then the response is 409 `INSUFFICIENT_FUNDS` and nothing is stored.
- **AC4 (rejection)** Given a closed account on either side, when posted, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC5 (rejection)** Given a failure while writing the `TRANSFER_IN` row, when posted, then the `TRANSFER_OUT` row is not stored either.

### FR-FIN-10 — Transfers cannot deadlock
**Priority:** MVP
**Description:** Both accounts are locked in ascending id order regardless of direction.
- **AC1** Given parallel transfers A to B and B to A repeated 100 times, when all finish, then none fails with a deadlock error and the totals are consistent.
- **AC2 (rejection)** Given an account row locked by another transaction for longer than 5 seconds, when a transfer waits for it, then the transfer fails with 409 `CONCURRENT_MODIFICATION` and nothing is stored.

### FR-FIN-11 — Record an expense
**Priority:** MVP
**Description:** `POST /api/v1/expenses` records spending: category (`TRANSPORT`, `ELECTRICITY`, `RENT`, `WORKER`, `MAINTENANCE`, `PACKING`, `OTHER`), amount (greater than 0), expense date (the actual payment date), account, payment method (consistent with the account type as in FR-PAY-12), description, kind (`BUSINESS` or `PERSONAL_DRAWING`), recurrence (`ONE_TIME` or `MONTHLY`), optional document reference. Number `EXP-<year>-<4 digits>`. It creates one `OUT` transaction (type `EXPENSE`, or `PERSONAL_DRAWING` for personal).
- **AC1** Given a business expense of 1500.00 by `UPI` from a bank account, when recorded, then the bank balance falls by 1500.00 and the expense is listed.
- **AC2 (rejection)** Given category `GAMBLING`, when recorded, then the response is 400 `MALFORMED_REQUEST`.
- **AC3 (rejection)** Given amount 0, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC4 (rejection)** Given method `CASH` with a bank account, when recorded, then the response is 422 `INVALID_PAYMENT_METHOD`.
- **AC5 (rejection)** Given a Staff token, when recorded, then the response is 403 `ACCESS_DENIED`.

### FR-FIN-12 — Personal drawings stay separate
**Priority:** MVP
**Description:** Expenses of kind `PERSONAL_DRAWING` reduce cash like any expense but are reported separately and are excluded from business expense totals and profitability.
- **AC1** Given business expenses of 5000.00 and a personal drawing of 20000.00 in a month, when the expense summary is read, then business expense is 5000.00 and personal drawings are 20000.00.
- **AC2 (rejection)** Given a Staff token, when a personal drawing is recorded, then the response is 403 `ACCESS_DENIED`.

### FR-FIN-13 — Expense list and summary
**Priority:** MVP
**Description:** `GET /api/v1/expenses` returns a page filtered by `category`, `kind`, `recurrence`, `accountId`, `status`, `from`, `to`. `GET /api/v1/expenses/summary` returns totals per category and per month.
- **AC1** Given expenses in two months, when the summary is read for a quarter, then totals per category and month are returned.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-FIN-14 — Reverse an expense
**Priority:** MVP
**Description:** `POST /api/v1/expenses/{id}/reverse` (with a reason) marks the expense `REVERSED` and records a compensating `IN` transaction (type `REVERSAL`). Amounts are never edited.
- **AC1** Given an expense of 1500.00, when reversed, then the account balance returns by 1500.00 and the expense shows `REVERSED`.
- **AC2 (rejection)** Given an already reversed expense, when reversed again, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given any expense, when `PUT`, `PATCH` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-FIN-15 — Balance adjustment with reason
**Priority:** MVP
**Description:** `POST /api/v1/finance/accounts/{id}/adjustments` corrects a cash or bank balance after reconciliation with a physical count or statement: direction, amount (greater than 0), mandatory reason, date. It creates an `ADJUSTMENT` transaction and keeps all history. Permission `FINANCE_MANAGE`.
- **AC1** Given bank balance 49000.00 against a statement of 50000.00, when an `IN` adjustment of 1000.00 with reason "statement 2026-09-30" is recorded, then the balance is 50000.00.
- **AC2 (rejection)** Given a missing reason, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an `OUT` adjustment larger than the balance, when recorded, then the response is 409 `INSUFFICIENT_FUNDS`.

### FR-FIN-16 — Close an account
**Priority:** MVP
**Description:** `POST /api/v1/finance/accounts/{id}/close` sets the account `CLOSED` only when its balance is 0.00; history stays. Closed accounts cannot receive new transactions.
- **AC1** Given a zero-balance account, when closed, then status is `CLOSED` and its past transactions are still listed.
- **AC2 (rejection)** Given a balance of 100.00, when closed, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-FIN-17 — Reversal compensates, never deletes
**Priority:** MVP
**Description:** Reversing a payment or expense adds a `REVERSAL` transaction; the original transaction is unchanged.
- **AC1** Given a reversed payment, when the account's transactions are listed, then both the original and the `REVERSAL` appear and net to zero.
- **AC2 (rejection)** Given an original transaction, when its amount is edited through the API, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-FIN-18 — Retry-safe expenses and transfers
**Priority:** MVP
**Description:** `POST /api/v1/expenses` and `POST /api/v1/finance/transfers` accept an `Idempotency-Key` with the behaviour of FR-PAY-07.
- **AC1** Given the same key and body posted twice, when both complete, then exactly one expense (or transfer) exists.
- **AC2 (rejection)** Given the same key with a different amount, when posted, then the response is 409 `DUPLICATE_REQUEST`.

### FR-FIN-19 — Configurable expense categories
**Priority:** POST-MVP
**Description:** The owner can add or deactivate expense categories. The first release uses the fixed list in FR-FIN-11.
- **AC1** Given the post-MVP release, when a category "FUEL" is added, then it can be used on expenses.
- **AC2 (rejection)** Given a duplicate category name, when added, then the response is 409 `DUPLICATE_RESOURCE`.

### FR-FIN-20 — Bank integration and mismatch alerts
**Priority:** POST-MVP
**Description:** Bank API integration, statement import, automatic reconciliation and automatic EMI detection are specified for later; manual entry always remains available.
- **AC1** Given the post-MVP release, when a statement is imported, then matching transactions are suggested for reconciliation.
- **AC2 (rejection)** Given an unparseable statement, when imported, then the response is 400 `MALFORMED_REQUEST`.

---
## 1.11 Loans (LOAN)

Loans are money the business borrowed from a bank or a private person (a party with role `LENDER`). Principal and interest are tracked separately: a loan received is a liability (not revenue), principal repayment reduces the liability (not an expense), interest is an expense. Interest amounts are entered manually in the first release.

### FR-LOAN-01 — Register a new loan
**Priority:** MVP
**Description:** `POST /api/v1/loans` records borrowed money: lender (active party with role `LENDER`), loan type (`BANK` or `PRIVATE`), principal (greater than 0), start date, interest notes (text, at most 500 characters), optional informational annual interest rate, receiving account and payment method (consistent with the account). Number `LON-<year>-<4 digits>`, status `ACTIVE`. In one transaction it creates a `LOAN_RECEIVED` `IN` transaction on the account.
- **AC1** Given a bank loan of 500000.00 received into account A, when recorded, then A's balance rises by 500000.00 and the loan shows outstanding principal 500000.00.
- **AC2 (rejection)** Given principal 0, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a lender without the `LENDER` role, when recorded, then the response is 422 `INVALID_PARTY_ROLE`.
- **AC4 (rejection)** Given a closed receiving account, when recorded, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC5 (rejection)** Given a Staff token, when recorded, then the response is 403 `ACCESS_DENIED`.

### FR-LOAN-02 — Register an existing loan at go-live
**Priority:** MVP
**Description:** A loan already running when the system starts is entered with its original principal, current outstanding principal and start date. No cash transaction is created (the money was received before go-live).
- **AC1** Given original 500000.00 and outstanding 320000.00, when registered as existing, then outstanding principal is 320000.00 and no financial transaction exists for it.
- **AC2 (rejection)** Given an outstanding principal greater than the original principal, when registered, then the response is 400 `VALIDATION_FAILED`.

### FR-LOAN-03 — Repay principal
**Priority:** MVP
**Description:** `POST /api/v1/loans/{id}/principal-repayments` records a repayment (amount greater than 0 and not more than the outstanding principal, account, method, date). Partial and early repayment are allowed with no penalty. It creates a `LOAN_PRINCIPAL_REPAYMENT` `OUT` transaction.
- **AC1** Given outstanding 400000.00, when 100000.00 is repaid from a bank account with enough balance, then outstanding is 300000.00 and the account falls by 100000.00.
- **AC2 (rejection)** Given outstanding 50000.00, when 60000.00 is repaid, then the response is 409 `PAYMENT_ALLOCATION_EXCEEDED`.
- **AC3 (rejection)** Given an account balance of 20000.00, when 30000.00 is repaid, then the response is 409 `INSUFFICIENT_FUNDS`.
- **AC4 (rejection)** Given a `CLOSED` loan, when a repayment is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-LOAN-04 — Pay interest
**Priority:** MVP
**Description:** `POST /api/v1/loans/{id}/interest-payments` records interest paid (amount greater than 0, account, method, date, optional period note). It creates a `LOAN_INTEREST_PAYMENT` `OUT` transaction. Interest does not change outstanding principal.
- **AC1** Given outstanding 300000.00, when interest of 6000.00 is paid, then outstanding principal is still 300000.00 and the interest paid total rises by 6000.00.
- **AC2 (rejection)** Given an amount of 0, when recorded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a `CLOSED` loan, when interest is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-LOAN-05 — Outstanding principal is derived
**Priority:** MVP
**Description:** Outstanding principal = original principal − sum of principal repayments (not reversed). `GET /api/v1/loans/{id}` and `GET /api/v1/loans` return it with total interest paid and the payment history.
- **AC1** Given 500000.00 original and repayments of 100000.00 and 50000.00, when read, then outstanding is 350000.00.
- **AC2 (rejection)** Given an unknown id, when read, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-LOAN-06 — Close a loan
**Priority:** MVP
**Description:** `POST /api/v1/loans/{id}/close` moves `ACTIVE → CLOSED` only when outstanding principal is 0.00. Closed loans remain in history.
- **AC1** Given outstanding 0.00, when closed, then status is `CLOSED` and the loan is still listed.
- **AC2 (rejection)** Given outstanding 1000.00, when closed, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-LOAN-07 — Correct a mistaken loan payment
**Priority:** MVP
**Description:** `POST /api/v1/loans/{id}/payments/{paymentId}/reverse` (with a reason) marks a principal or interest payment `REVERSED` with a compensating `IN` transaction; outstanding principal is recomputed.
- **AC1** Given a principal repayment of 100000.00, when reversed, then outstanding rises by 100000.00 and the account balance returns by 100000.00.
- **AC2 (rejection)** Given an already reversed payment, when reversed again, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given no `LOAN_MANAGE` permission, when reversed, then the response is 403 `ACCESS_DENIED`.

### FR-LOAN-08 — Loan accounting classification
**Priority:** MVP
**Description:** Reports treat `LOAN_RECEIVED` as neither revenue nor income, `LOAN_PRINCIPAL_REPAYMENT` as neither an expense nor a cost, and `LOAN_INTEREST_PAYMENT` as an expense (interest cost).
- **AC1** Given a loan of 500000.00 received and 100000.00 repaid in a month, when the sales and expense reports are read, then neither amount appears in revenue or business expenses.
- **AC2** Given 6000.00 interest paid, when the expense report is read, then interest cost shows 6000.00.
- **AC3 (rejection)** Given a principal repayment of 100000.00, when the expense report is read, then it does not appear as an expense.

### FR-LOAN-09 — Loans are never deleted
**Priority:** MVP
**Description:** No endpoint deletes or edits a loan's principal or a recorded payment.
- **AC1 (rejection)** Given any loan, when `DELETE` or `PUT` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-LOAN-10 — Interest calculation, reminders and repayment advice
**Priority:** POST-MVP
**Description:** Automatic interest computation, repayment schedules, loan payment reminders and a recommendation of whether available money should pay interest, part of the principal or the whole loan (the system only recommends, it never pays automatically).
- **AC1** Given the post-MVP release and a monthly rate, when the month ends, then the interest due is suggested.
- **AC2 (rejection)** Given any recommendation, when displayed, then no payment is created without an explicit owner action.

---

## 1.12 Chits (CHIT)

A chit is an investment: monthly contributions go out, a lump-sum payout comes in once, all in **physical cash only**. Contributions are not operating expenses and payouts are not revenue. The bidding or dividend calculation is not modelled (ASSUMPTION Q6): amounts are entered by the owner.

### FR-CHIT-01 — Create a chit
**Priority:** MVP
**Description:** `POST /api/v1/chits` creates a chit: name (at most 100 characters), total amount (greater than 0), standard monthly amount (greater than 0), duration in months (greater than 0), start date, notes. Number `CHT-<year>-<4 digits>`, status `ACTIVE`.
- **AC1** Given name "Chit A", total 500000.00, monthly 25000.00, 20 months, when created, then the response is 201 with status `ACTIVE`.
- **AC2 (rejection)** Given duration 0, when created, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a Staff token, when created, then the response is 403 `ACCESS_DENIED`.

### FR-CHIT-02 — Record a contribution (cash only)
**Priority:** MVP
**Description:** `POST /api/v1/chits/{id}/contributions` records a monthly contribution: amount (greater than 0, may differ from the standard amount), date, and the physical cash account. It creates a `CHIT_CONTRIBUTION` `OUT` transaction.
- **AC1** Given cash of 100000.00, when a contribution of 25000.00 is recorded, then cash is 75000.00 and the chit shows one contribution.
- **AC2 (rejection)** Given a bank account or method `UPI`, when recorded, then the response is 422 `INVALID_PAYMENT_METHOD`.
- **AC3 (rejection)** Given cash of 10000.00, when 25000.00 is recorded, then the response is 409 `INSUFFICIENT_FUNDS`.
- **AC4 (rejection)** Given a `CLOSED` chit, when a contribution is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-CHIT-03 — Record the payout (cash only)
**Priority:** MVP
**Description:** `POST /api/v1/chits/{id}/payout` records the lump sum received (amount greater than 0, date, cash account). It creates a `CHIT_PAYOUT` `IN` transaction and moves the chit `ACTIVE → MATURED`. The purpose of the money is not recorded.
- **AC1** Given an `ACTIVE` chit, when a payout of 460000.00 is recorded, then cash rises by 460000.00 and status is `MATURED`; further monthly contributions remain allowed.
- **AC2 (rejection)** Given a payout already recorded, when another is recorded, then the response is 409 `INVALID_STATE_TRANSITION`.
- **AC3 (rejection)** Given a bank account, when the payout is recorded, then the response is 422 `INVALID_PAYMENT_METHOD`.

### FR-CHIT-04 — Organiser payment
**Priority:** MVP
**Description:** `POST /api/v1/chits/{id}/organiser-payments` records an extra payment to the organiser, separately from contributions, as a cash `CHIT_ORGANISER_PAYMENT` `OUT` transaction.
- **AC1** Given cash of 50000.00, when 2000.00 is recorded, then it appears under organiser payments, not contributions.
- **AC2 (rejection)** Given a bank account, when recorded, then the response is 422 `INVALID_PAYMENT_METHOD`.

### FR-CHIT-05 — Chit summary is derived
**Priority:** MVP
**Description:** `GET /api/v1/chits/{id}` returns total contributed, number of contributions, remaining expected contributions (`duration − contributions`, minimum 0), organiser payments, payout received, and the history. `GET /api/v1/chits` lists chits with these totals.
- **AC1** Given 20 months and 8 contributions of 25000.00, when read, then total contributed is 200000.00 and remaining expected contributions is 12.
- **AC2 (rejection)** Given an unknown id, when read, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-CHIT-06 — Close a chit
**Priority:** MVP
**Description:** `POST /api/v1/chits/{id}/close` sets `CLOSED` from `ACTIVE` or `MATURED`. History stays.
- **AC1** Given a `MATURED` chit with all contributions paid, when closed, then status is `CLOSED`.
- **AC2 (rejection)** Given a `CLOSED` chit, when closed again, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-CHIT-07 — Classification in reports
**Priority:** MVP
**Description:** Contributions, organiser payments and payouts are shown as investment movements, never as operating expenses or sales revenue.
- **AC1** Given a payout of 460000.00 in a month, when the sales and expense reports are read, then it appears in neither.
- **AC2 (rejection)** Given a chit contribution of 25000.00, when the expense report is read, then it does not appear as an expense.

### FR-CHIT-08 — Existing chit at go-live
**Priority:** MVP
**Description:** A chit already running is entered with the number of contributions and total amount paid before go-live; no cash transaction is created for them.
- **AC1** Given 6 prior contributions totalling 150000.00, when registered, then remaining expected contributions reflect them.
- **AC2 (rejection)** Given prior contributions greater than the duration, when registered, then the response is 400 `VALIDATION_FAILED`.

### FR-CHIT-09 — Correct a mistaken chit entry
**Priority:** MVP
**Description:** `POST /api/v1/chits/{id}/entries/{entryId}/reverse` (with a reason) reverses a contribution, payout or organiser payment with a compensating transaction.
- **AC1** Given a mistaken contribution of 25000.00, when reversed, then cash returns by 25000.00 and it no longer counts in the total.
- **AC2 (rejection)** Given an already reversed entry, when reversed again, then the response is 409 `INVALID_STATE_TRANSITION`.

### FR-CHIT-10 — Chit calculation and reminders
**Priority:** POST-MVP
**Description:** The bidding/dividend calculation (`CHIT_BIDDING_CALCULATION`), contribution forecasts and chit payment reminders are specified for later.
- **AC1** Given the post-MVP release, when the monthly bid is entered, then the contribution due is calculated by the agreed formula.
- **AC2 (rejection)** Given a bid larger than the chit total, when entered, then the response is 400 `VALIDATION_FAILED`.

---

## 1.13 Reports and dashboard (RPT)

All reports read derived data with SQL aggregation (no per-row queries), accept inclusive date ranges (`from`, `to`), are paginated where they list rows, require `REPORT_VIEW`, and can be exported as CSV (FR-RPT-16).

### FR-RPT-01 — Owner dashboard
**Priority:** MVP
**Description:** `GET /api/v1/dashboard/summary` returns in one call: physical cash; each bank balance; total available money; customer receivables (and advance total); supplier payables with overdue total; manufacturer payable; worker payable; outstanding loan principal; active chit commitments (sum of standard monthly amounts of `ACTIVE` chits); raw silk available; silk in internal WIP; silk with manufacturers; finished warp (silk and kora); vuda warp stock; counts of orders by status for pending statuses; production batches `PLANNED` and `IN_PROGRESS`; outsourcing jobs not completed; supplier dues in the next 7 days; the 10 most recent financial transactions; and an `asOf` timestamp.
- **AC1** Given seeded data, when the dashboard is read, then each figure equals the matching dedicated endpoint (stock summary, cash position, outstanding, payables, loans, chits).
- **AC2 (rejection)** Given a Staff token, when read, then the response is 403 `ACCESS_DENIED`.
- **AC3** Given an empty database, when read, then every amount is 0.00 or 0.000 and every list is empty.

### FR-RPT-02 — Dashboard separates available money from obligations
**Priority:** MVP
**Description:** Receivables, payables, loans and chit commitments are shown as separate figures and are never added to or subtracted from available money.
- **AC1** Given cash 80000.00 and receivables 500000.00, when read, then available money is 80000.00.
- **AC2 (rejection)** Given payables of 300000.00, when available money is read, then they are not subtracted from it.

### FR-RPT-03 — Customer outstanding report
**Priority:** MVP
**Description:** `GET /api/v1/reports/customer-outstanding` lists customers with outstanding, advance, number of unpaid orders and age of the oldest unpaid order, sortable by outstanding, filtered by minimum amount.
- **AC1** Given three customers, when `minOutstanding=10000` is requested, then only those at or above 10000.00 are returned.
- **AC2 (rejection)** Given `sort=unknownField`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-RPT-04 — Customer statement
**Priority:** MVP
**Description:** `GET /api/v1/customers/{id}/statement?from&to` returns, in date order, orders (debits), payments (credits) and allocations, with an opening and closing balance and a running balance.
- **AC1** Given two orders and two payments, when read, then closing balance equals the customer's outstanding minus advance.
- **AC2 (rejection)** Given a party that is not a customer, when read, then the response is 422 `INVALID_PARTY_ROLE`.

### FR-RPT-05 — Supplier payables report
**Priority:** MVP
**Description:** `GET /api/v1/reports/supplier-payables` lists purchases with payable, due date, overdue days and ageing buckets (not due, 1–7, 8–15, over 15 days overdue), grouped per supplier.
- **AC1** Given a purchase 10 days overdue, when read, then it is in the 8–15 bucket.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-RPT-06 — Purchase report
**Priority:** MVP
**Description:** `GET /api/v1/reports/purchases` returns purchases for a period grouped by supplier and product type: ordered, received, accepted, rejected, returned kg and value.
- **AC1** Given purchases in September, when requested for September, then totals per supplier match the purchase list.
- **AC2 (rejection)** Given an invalid `productType`, when requested, then the response is 400 `MALFORMED_REQUEST`.

### FR-RPT-07 — Sales and orders report
**Priority:** MVP
**Description:** `GET /api/v1/reports/sales` returns, per period, customer and product type, the order value placed, value delivered (revenue is recognised at delivery date), kilograms delivered, and outstanding. Loans, transfers, chit payouts, opening balances and advances are not revenue.
- **AC1** Given orders delivered in October, when requested for October, then revenue equals the sum of their item amounts.
- **AC2** Given a loan received in October, when requested, then revenue is unchanged.
- **AC3 (rejection)** Given a `PLACED` order that is not delivered, when sales are read, then it contributes no revenue.

### FR-RPT-08 — Expense report
**Priority:** MVP
**Description:** `GET /api/v1/reports/expenses` returns expenses by category, kind (business versus personal drawing) and month, plus interest cost from loans; reversed expenses are excluded.
- **AC1** Given business expenses, a personal drawing and loan interest in a month, when read, then business expense, personal drawings and interest cost are three separate figures.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-RPT-09 — Inventory movement report
**Priority:** MVP
**Description:** `GET /api/v1/reports/inventory-movements` returns, for a period and product type, the opening balance, movements grouped by type, and the closing balance per location.
- **AC1** Given an opening 100.000, receipts 50.000 and consumption 30.000, when read, then the closing balance is 120.000.
- **AC2 (rejection)** Given an unknown `location`, when requested, then the response is 400 `MALFORMED_REQUEST`.

### FR-RPT-10 — Production and wastage report
**Priority:** MVP
**Description:** `GET /api/v1/reports/production` returns per batch (and totals for a period) the input, output, wastage and discrepancy in kg and the wastage percentage (`wastage ÷ input`).
- **AC1** Given a batch of input 100.000 and wastage 4.000, when read, then wastage percentage is 4.00.
- **AC2** Given a period with no completed batches, when read, then totals are 0.000 and the response is 200.
- **AC3 (rejection)** Given an `IN_PROGRESS` batch, when the report is read, then it is not counted as completed output.

### FR-RPT-11 — Outsourcing report
**Priority:** MVP
**Description:** `GET /api/v1/reports/outsourcing` returns per manufacturer and job: issued, received, wastage, still outside, payable, paid and pending.
- **AC1** Given a job of 200.000 issued, 150.000 received, when read, then still outside is 50.000 less recorded wastage.
- **AC2 (rejection)** Given an unknown `manufacturerId`, when requested, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-RPT-12 — Cash and bank report
**Priority:** MVP
**Description:** `GET /api/v1/reports/cash-bank` returns per account for a period: opening balance, total in and total out per transaction type, closing balance.
- **AC1** Given an account with an opening 10000.00, 5000.00 in and 3000.00 out in the period, when read, then the closing balance is 12000.00.
- **AC2 (rejection)** Given `accountId` of an unknown account, when requested, then the response is 404 `RESOURCE_NOT_FOUND`.

### FR-RPT-13 — Worker payable and payroll report
**Priority:** MVP
**Description:** `GET /api/v1/reports/worker-payable` returns per worker for a date range: work value earned, paid and pending, split by rolling and warping.
- **AC1** Given a week's records and one payment, when read for that week, then earned, paid and pending are returned per worker.
- **AC2 (rejection)** Given `from` later than `to`, when requested, then the response is 400 `VALIDATION_FAILED`.

### FR-RPT-14 — Loans and chits report
**Priority:** MVP
**Description:** `GET /api/v1/reports/loans-and-chits` returns each loan's original and outstanding principal and interest paid, and each chit's contributions, remaining expected contributions and payout.
- **AC1** Given one loan and one chit, when read, then both appear with derived figures matching their detail endpoints.
- **AC2 (rejection)** Given no `REPORT_VIEW` permission, when read, then the response is 403 `ACCESS_DENIED`.

### FR-RPT-15 — Report consistency
**Priority:** MVP
**Description:** Totals in reports equal the totals of the underlying detail endpoints for the same filters; reports never read stored derived values.
- **AC1** Given the same period, when the purchase report total is compared with the sum of listed purchases, then they are equal.
- **AC2 (rejection)** Given a mismatch found by the automated consistency test, when the test runs, then the build fails.

### FR-RPT-16 — CSV export for audit
**Priority:** MVP
**Description:** Each report and the main lists (purchases, orders, payments, expenses, inventory movements, financial transactions) can be exported as CSV with `format=csv` (UTF-8, header row, amounts with 2 decimals, weights with 3), using the same filters, up to 50000 rows.
- **AC1** Given a filtered payments list of 120 rows, when exported, then the CSV has 120 data rows and the same filters applied.
- **AC2 (rejection)** Given a filter matching more than 50000 rows, when exported, then the response is 400 `VALIDATION_FAILED` asking to narrow the period.
- **AC3 (rejection)** Given no permission for the underlying list, when exported, then the response is 403 `ACCESS_DENIED`.

### FR-RPT-17 — Profit and loss and profitability
**Priority:** POST-MVP
**Description:** Monthly and yearly profit and loss, product-wise, customer-wise and manufacturer-wise profitability, and cost allocation are specified for later.
- **AC1** Given the post-MVP release, when the monthly profit and loss is read, then revenue, business expenses, interest and production cost are shown, excluding personal drawings.
- **AC2 (rejection)** Given a month with no data, when read, then the response is 200 with zero values.

---

## 1.14 Notifications (NTF)

Notifications observe the system; they never own financial or operational truth. Sending happens after the business transaction commits.

### FR-NTF-01 — Notification configuration
**Priority:** MVP
**Description:** `GET /api/v1/notifications/configurations` and `PUT /api/v1/notifications/configurations/{type}` manage per type: `enabled`, `daysBeforeDue` (0 to 30, default 3), `overdueRepeatDays` (at least 1, default 1) and priority. The type `SUPPLIER_DUE` is enabled by default with 3 days before due and a daily overdue repeat.
- **AC1** Given `daysBeforeDue` = 5, when saved, then supplier reminders start 5 days before the due date.
- **AC2 (rejection)** Given `daysBeforeDue` = 45, when saved, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given an unknown type, when saved, then the response is 400 `MALFORMED_REQUEST`.

### FR-NTF-02 — Supplier due reminders
**Priority:** MVP
**Description:** A scheduled job runs once a day at 08:00 Asia/Kolkata. For every purchase with remaining payable greater than 0 it creates a notification: `DUE_SOON` when today equals `dueDate − daysBeforeDue`, `DUE_TODAY` on the due date, and `OVERDUE` every `overdueRepeatDays` days (default 1) after the due date until paid. Re-running the job on the same day creates no duplicates.
- **AC1** Given a purchase due 2026-10-11 with 3 days configured, when the job runs on 2026-10-08, then one `DUE_SOON` notification exists.
- **AC2** Given the same purchase on 2026-10-13, when the job runs, then one `OVERDUE` notification for that date exists.
- **AC3 (rejection)** Given the job runs twice on 2026-10-08, when finished, then still exactly one notification exists for that purchase and kind and date.
- **AC4 (rejection)** Given a fully paid purchase, when the job runs, then no notification is created.

### FR-NTF-03 — Notification lifecycle
**Priority:** MVP
**Description:** A notification has type, reference (purchase), party, scheduled time, sent time, status (`PENDING`, `SENT`, `RESOLVED`, `FAILED`) and message text. When the underlying purchase becomes fully paid, its open notifications become `RESOLVED`.
- **AC1** Given an `OVERDUE` notification and a payment that settles the purchase, when the payment commits, then the notification is `RESOLVED`.
- **AC2 (rejection)** Given a `RESOLVED` notification, when the job runs later, then it is not sent again.

### FR-NTF-04 — Sending is a side effect after commit
**Priority:** MVP
**Description:** Delivery goes through a `NotificationProvider` interface (the first release logs the message). Sending runs after the notification row is committed, retries up to 3 times with increasing delay, and a delivery failure never rolls back a business transaction.
- **AC1** Given the provider fails twice and succeeds on the third attempt, when sending, then the status is `SENT`.
- **AC2 (rejection)** Given the provider fails 3 times, when sending, then the status is `FAILED` and the originating payment is unaffected.

### FR-NTF-05 — View notifications in the app
**Priority:** MVP
**Description:** `GET /api/v1/notifications` returns a page filtered by `status`, `type`, `from`, `to`, newest first (requires `REPORT_VIEW`).
- **AC1** Given notifications in several statuses, when `status=PENDING` is requested, then only pending ones are returned.
- **AC2 (rejection)** Given a Staff token, when requested, then the response is 403 `ACCESS_DENIED`.

### FR-NTF-06 — Upcoming dues on the dashboard
**Priority:** MVP
**Description:** The dashboard (FR-RPT-01) lists supplier purchases due within the next 7 days and all overdue ones, computed live and independent of whether notifications were sent.
- **AC1** Given a purchase due in 3 days, when the dashboard is read, then it is listed even if the reminder job has not run.
- **AC2 (rejection)** Given a purchase due in 20 days, when the dashboard is read, then it is not in the upcoming list.

### FR-NTF-07 — Other alerts
**Priority:** POST-MVP
**Description:** Low raw-silk stock alerts (below a threshold or insufficient for confirmed orders), customer outstanding alerts, loan payment reminders, chit payment reminders (naming the chit, not the amount), low available money, large upcoming obligations and cash/bank mismatch alerts.
- **AC1** Given the post-MVP release and raw silk below its threshold, when the daily job runs, then a low-stock notification is created.
- **AC2 (rejection)** Given a threshold below 0, when configured, then the response is 400 `VALIDATION_FAILED`.

### FR-NTF-08 — WhatsApp delivery
**Priority:** POST-MVP
**Description:** A WhatsApp implementation of `NotificationProvider` and customer-facing messages (bill sharing, payment reminders).
- **AC1** Given the post-MVP release, when a bill is shared, then a WhatsApp message with the document is sent after commit.
- **AC2 (rejection)** Given WhatsApp is unavailable, when sending, then the business transaction is unaffected and the notification is `FAILED`.

---

## 1.15 Documents (DOC)

Documents (bills, statements, receipts) are **supporting records**; the transactions remain the source of truth.

### FR-DOC-01 — Attach a document
**Priority:** MVP
**Description:** `POST /api/v1/documents` (multipart) stores a file with a document type (`PURCHASE_BILL`, `SALES_BILL`, `BANK_STATEMENT`, `EXPENSE_RECEIPT`, `OTHER`) and a reference (type `PURCHASE`, `ORDER`, `EXPENSE`, `PAYMENT` or `NONE`, and id). Allowed content types: PDF, JPEG, PNG. Maximum size 10 MB. Only metadata is stored in the database; the file is stored through a `FileStorage` interface (local disk in the first release). Permission `DOCUMENT_MANAGE`.
- **AC1** Given a 2 MB PDF bill for purchase P1, when uploaded, then the response is 201 with id, file name, size, content type and the reference.
- **AC2 (rejection)** Given an 11 MB file, when uploaded, then the response is 400 `VALIDATION_FAILED`.
- **AC3 (rejection)** Given a `.exe` file, when uploaded, then the response is 415 `UNSUPPORTED_MEDIA_TYPE`.
- **AC4 (rejection)** Given a reference id that does not exist, when uploaded, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC5 (rejection)** Given a Staff token, when uploaded, then the response is 403 `ACCESS_DENIED`.

### FR-DOC-02 — Safe storage
**Priority:** MVP
**Description:** Files are stored under a generated id, never under the client's file name; the original name is kept only as metadata after sanitising.
- **AC1** Given a client file name `../../etc/passwd.pdf`, when uploaded, then the file is stored inside the storage root under its generated id.
- **AC2 (rejection)** Given a file whose declared type is PDF but whose content is not a PDF, when uploaded, then the response is 415 `UNSUPPORTED_MEDIA_TYPE`.

### FR-DOC-03 — List and download
**Priority:** MVP
**Description:** `GET /api/v1/documents` returns a page filtered by `referenceType`, `referenceId`, `documentType`, `from`, `to`. `GET /api/v1/documents/{id}/content` streams the file with its content type and a `Content-Disposition` header.
- **AC1** Given two documents for purchase P1, when filtered by that reference, then both are returned.
- **AC2 (rejection)** Given an unknown id, when downloaded, then the response is 404 `RESOURCE_NOT_FOUND`.
- **AC3 (rejection)** Given no token, when downloaded, then the response is 401 `UNAUTHENTICATED`.

### FR-DOC-04 — Documents are immutable
**Priority:** MVP
**Description:** Documents cannot be overwritten or deleted; a corrected file is uploaded as a new document that references the old one.
- **AC1** Given a document, when a corrected one is uploaded with `supersedes` set, then both remain listed.
- **AC2 (rejection)** Given any document, when `PUT` or `DELETE` is called, then the response is 405 `METHOD_NOT_ALLOWED`.

### FR-DOC-05 — Attaching does not change data
**Priority:** MVP
**Description:** Uploading a document never changes the amounts, status or stock of the record it references.
- **AC1** Given a purchase, when a bill is attached, then the purchase's payable and status are unchanged.
- **AC2 (rejection)** Given an upload whose form data contains an `amount` field, when posted, then the field is ignored and no amount of the referenced record changes.

### FR-DOC-06 — Sales bills, WhatsApp sharing, OCR and GST
**Priority:** POST-MVP
**Description:** Generating a numbered sales bill (normally after the order is fully paid, or earlier on customer request, with corrections kept as versions), sharing it through WhatsApp, extracting data from supplier bills (OCR) and GST fields (HSN/SAC, CGST/SGST/IGST, credit and debit notes).
- **AC1** Given the post-MVP release and a fully paid order, when a bill is generated, then it has a unique number and a PDF is stored.
- **AC2 (rejection)** Given a bill that was issued, when the amount must change, then a corrected version is created and the original is kept.

---

# 2. Cross-cutting go-live requirements

The following are delivered by requirements above and listed here so the go-live plan can be checked in one place: opening stock (FR-INV-13), opening account balances (FR-FIN-02), opening customer and supplier obligations (FR-PAY-22), existing loans (FR-LOAN-02), existing chits (FR-CHIT-08), reconciliation and sign-off (NFR-DATA-04), backups before import (NFR-OPS-05).

# 3. Non-functional requirements

Reference workload used by every performance requirement ("5 years of data"): up to 500 parties; 600 purchases, 2500 orders, 6000 payments, 25000 material movements and 20000 financial transactions per year, so about 100000 movements and 100000 financial transactions after five years. Reference server: 2 vCPU, 4 GB RAM, PostgreSQL 17 on the same host.

## 3.1 Performance (PERF)

### NFR-PERF-01 — Realistic test dataset
**Priority:** MVP
**Description:** A data generator creates the reference workload (5 years) for performance tests.
- **AC1** Given the generator, when run on an empty database, then it creates at least 100000 movements and 100000 financial transactions with consistent balances in under 10 minutes.

### NFR-PERF-02 — Read latency
**Priority:** MVP
**Description:** On the reference workload, `GET` requests for a single record or one page of a list (page size 20) respond with p95 latency of at most 300 ms, including `GET /api/v1/inventory/summary`.
- **AC1** Given 200 sequential requests to each of five representative read endpoints, when latency is measured, then the 95th percentile of each is at most 300 ms.

### NFR-PERF-03 — Dashboard latency
**Priority:** MVP
**Description:** `GET /api/v1/dashboard/summary` responds with p95 latency of at most 500 ms.
- **AC1** Given 100 sequential dashboard requests on the reference workload, when measured, then p95 is at most 500 ms.

### NFR-PERF-04 — Write latency
**Priority:** MVP
**Description:** Create, receive, deliver and payment requests respond with p95 latency of at most 500 ms; a payment allocated across 20 obligations at most 800 ms.
- **AC1** Given 100 payments with 20 allocations each, when measured, then p95 is at most 800 ms.

### NFR-PERF-05 — Concurrency
**Priority:** MVP
**Description:** With 20 simultaneous clients issuing mixed reads and writes, no request fails except with documented 409 business conflicts, no deadlock occurs, and no balance goes negative.
- **AC1** Given 20 threads each running 100 mixed operations, when finished, then zero responses are 500 and every invariant check of NFR-REL-01 passes.

### NFR-PERF-06 — Reports and exports
**Priority:** MVP
**Description:** Any report over a one-year period responds with p95 latency of at most 2 seconds; a CSV export of 50000 rows completes within 10 seconds.
- **AC1** Given a one-year report on the reference workload, when measured over 20 runs, then p95 is at most 2 s.
- **AC2** Given a 50000-row export, when requested, then it completes in at most 10 s.

### NFR-PERF-07 — No per-row queries
**Priority:** MVP
**Description:** A list endpoint issues a number of SQL statements that does not grow with the page size.
- **AC1** Given a list of 20 rows and a list of 100 rows, when the statement counts are compared, then they are equal (at most 6).

### NFR-PERF-08 — Start-up time
**Priority:** MVP
**Description:** The application is ready to serve within 60 seconds of start on the reference server, including applying pending migrations; migrating an empty database takes at most 30 seconds.
- **AC1** Given a production-sized database, when the container starts, then `/actuator/health` reports `UP` within 60 s.

## 3.2 Security (SEC)

### NFR-SEC-01 — Transport security
**Priority:** MVP
**Description:** In production all traffic uses HTTPS (TLS 1.2 or newer), plain HTTP redirects to HTTPS, and responses carry `Strict-Transport-Security` with `max-age` of at least 15552000 seconds.
- **AC1** Given the production deployment, when `http://` is requested, then the response is a redirect to `https://`.
- **AC2 (rejection)** Given a client offering only TLS 1.0, when it connects, then the handshake fails.

### NFR-SEC-02 — Secrets
**Priority:** MVP
**Description:** Secrets (database password, JWT secret, bootstrap owner password) come only from environment variables; none is committed; the JWT secret is at least 32 bytes.
- **AC1** Given the repository history, when scanned with a secret scanner, then it reports no secret other than the documented local-development defaults.
- **AC2 (rejection)** Given the production profile and a missing `JWT_SECRET`, when the application starts, then startup fails.

### NFR-SEC-03 — Input limits
**Priority:** MVP
**Description:** JSON request bodies are limited to 1 MB and uploads to 10 MB; every string field has a maximum length; page size is capped at 100.
- **AC1 (rejection)** Given a 2 MB JSON body, when posted, then the response is a 4xx problem body, not a 5xx.
- **AC2 (rejection)** Given a name of 201 characters, when a party is created, then the response is 400 `VALIDATION_FAILED`.

### NFR-SEC-04 — Response headers
**Priority:** MVP
**Description:** API responses carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
- **AC1** Given any API response, when headers are inspected, then all four are present.

### NFR-SEC-05 — Cross-origin access
**Priority:** MVP
**Description:** The API enables no cross-origin (CORS) access, because the client is a native application.
- **AC1 (rejection)** Given a request with an `Origin` header of another site, when sent, then no `Access-Control-Allow-Origin` header is returned.

### NFR-SEC-06 — Sensitive data in logs and responses
**Priority:** MVP
**Description:** Passwords, password hashes, tokens and full bank account numbers never appear in logs or API responses; only the last 4 account digits are stored.
- **AC1** Given a login and refresh sequence with log capture, when the log is searched for the password and tokens, then there are no matches.
- **AC2 (rejection)** Given an error caused by a request containing a password field, when the 500 handler logs it, then the password is not in the log line.

### NFR-SEC-07 — Dependency vulnerabilities
**Priority:** MVP
**Description:** The build fails when a dependency has a known vulnerability with CVSS score 7.0 or higher; a scheduled weekly scan runs against the default branch.
- **AC1** Given a dependency with CVSS 9.8, when the build runs, then it fails with the advisory id.
- **AC2** Given a clean dependency set, when the scan runs, then the build passes.

### NFR-SEC-08 — Database privileges
**Priority:** MVP
**Description:** Production uses a dedicated application database user that is not a superuser and cannot create or drop schemas; migrations run with a separate migration user.
- **AC1 (rejection)** Given the application user, when it tries `DROP TABLE`, then PostgreSQL rejects it.

### NFR-SEC-09 — Authorization is tested everywhere
**Priority:** MVP
**Description:** Every endpoint has a test proving 401 without a token and 403 without the required permission (except the public endpoints of FR-AUTH-12).
- **AC1** Given the list of controller mappings, when compared with the security tests, then every protected endpoint is covered (checked by an automated test).

### NFR-SEC-10 — Injection safety
**Priority:** MVP
**Description:** All SQL uses bound parameters; search wildcards `%` and `_` typed by users are escaped; no SQL is built by string concatenation with user input.
- **AC1 (rejection)** Given `search=' OR 1=1 --`, when listing parties, then the response is 200 with an empty page and no error.
- **AC2** Given `search=100%`, when listing, then only parties containing the literal `100%` match.

### NFR-SEC-11 — Request rate limits
**Priority:** MVP
**Description:** In addition to the per-account login lock, a rate-limit filter allows at most 10 requests per minute per IP address on `/api/v1/auth/**` and at most 300 requests per minute per user (per IP when unauthenticated) on every other API path. Exceeding a limit returns 429 `RATE_LIMITED` with a `Retry-After` header.
- **AC1 (rejection)** Given a user who has sent 300 requests within one minute, when the 301st request arrives in that minute, then the response is 429 `RATE_LIMITED` with `Retry-After`.
- **AC2 (rejection)** Given normal use below the limit, when requests are made, then none is rejected.

## 3.3 Reliability and data integrity (REL)

### NFR-REL-01 — Invariants hold after any sequence
**Priority:** MVP
**Description:** After any sequence of operations, stock is never negative, cash and bank balances are never negative, `inventory_balances` equals the sum of movements, and every payment's allocations sum to at most its amount.
- **AC1** Given a randomized test of 10000 operations with a fixed seed, when finished, then zero invariant violations are reported.
- **AC2 (rejection)** Given the same test with the stock lock removed, when run, then at least one violation is detected (proof that the test can fail).

### NFR-REL-02 — One use case, one transaction
**Priority:** MVP
**Description:** Each use case that writes more than one row (receipt, delivery, production, outsourcing, payment, transfer, reversal) commits atomically.
- **AC1** Given fault injection after the last-but-one write of each such use case, when the request fails, then no partial rows exist.

### NFR-REL-03 — Availability
**Priority:** MVP
**Description:** Monthly availability of at least 99.0% (about 7 hours of downtime) excluding maintenance announced 24 hours in advance.
- **AC1** Given a month of uptime-probe data, when computed, then successful probes are at least 99.0%.

### NFR-REL-04 — Backups and restore
**Priority:** MVP
**Description:** A full compressed database backup runs nightly and is copied off the host; backups are kept 30 days. Recovery point objective is 24 hours; recovery time objective is 4 hours. A restore is tested before go-live and every quarter, and the restored database passes the reconciliation check of FR-INV-09.
- **AC1** Given last night's backup, when restored on a clean host, then the application starts and the reconciliation check reports 0 differences, within 4 hours.
- **AC2 (rejection)** Given a backup job that fails, when the schedule time plus one hour passes, then an alert is raised.

### NFR-REL-05 — Dependency failure isolation
**Priority:** MVP
**Description:** Failure of the notification provider or file storage never rolls back a business transaction and is reported with a problem body and stable code.
- **AC1** Given the notification provider is down, when a payment is recorded, then the payment succeeds.
- **AC2 (rejection)** Given file storage is unavailable, when a document is uploaded, then the response is a 5xx problem body with code `INTERNAL_ERROR` and no database row remains.

### NFR-REL-06 — Time handling
**Priority:** MVP
**Description:** All business logic reads time from the injected clock (Asia/Kolkata); timestamps are stored in UTC.
- **AC1** Given a fixed clock of 2026-10-01 23:30 Asia/Kolkata, when a purchase date defaults to today, then it is 2026-10-01.
- **AC2** Given the database server in another time zone, when a record is created, then `created_at` equals the UTC instant.

### NFR-REL-07 — Migration safety
**Priority:** MVP
**Description:** Schema changes only through Flyway; merged migrations are never edited; each migration is tested on an empty database and on data created by the previous version.
- **AC1** Given a new migration, when the build runs, then the application starts on an empty database with `ddl-auto=validate`.
- **AC2 (rejection)** Given a merged migration whose checksum changed, when the application starts, then Flyway refuses to start.

## 3.4 Observability and audit (OBS)

### NFR-OBS-01 — Structured request logs
**Priority:** MVP
**Description:** In production logs are JSON, one line per request with request id, method, path, status, duration and user id.
- **AC1** Given any request, when the log is parsed, then those six fields are present.

### NFR-OBS-02 — Request id
**Priority:** MVP
**Description:** The `X-Request-Id` header is accepted from the client or generated, returned in the response, and attached to every log line of that request.
- **AC1** Given a request with `X-Request-Id: abc-1`, when it completes, then the response header is `abc-1` and its log lines contain `abc-1`.

### NFR-OBS-03 — Health
**Priority:** MVP
**Description:** `/actuator/health` returns `UP` only when the database is reachable; component details are shown in dev and hidden in production.
- **AC1 (rejection)** Given the database is stopped, when health is requested, then the status is `DOWN` (503).
- **AC2** Given production, when health is requested, then the body shows no component details.

### NFR-OBS-04 — Metrics
**Priority:** MVP
**Description:** Metrics (HTTP latency, JVM, connection pool) are exposed in Prometheus format on a protected management endpoint.
- **AC1 (rejection)** Given no token, when the metrics endpoint is requested, then the response is 401.

### NFR-OBS-05 — Operational alerts
**Priority:** MVP
**Description:** An external probe checks health every minute and alerts after 3 consecutive failures; disk usage above 80% and a failed backup raise alerts.
- **AC1** Given the service is stopped, when 3 minutes pass, then an alert is sent.

### NFR-OBS-06 — Audit trail
**Priority:** MVP
**Description:** Every financial, inventory and master record has creation time, creator and version; reversals, adjustments and wastage entries also carry a reason and the acting user; nothing is physically deleted.
- **AC1** Given a reversal, when the record is read, then reason, user and time are present.
- **AC2 (rejection)** Given a request to reverse without a reason, when sent, then the response is 400 `VALIDATION_FAILED`.

## 3.5 Usability (USE)

These apply to the desktop client (Windows and Linux, see docs/06-UI-SPEC.md) and to the API as the client's contract. A mobile client is POST-MVP future scope.

### NFR-USE-01 — Fast daily entries
**Priority:** MVP
**Description:** Recording a customer payment, a work record or an expense needs at most 5 inputs, with the date defaulting to today and the account to the last one used.
- **AC1** Given the payment screen, when counted, then it asks for at most 5 inputs.

### NFR-USE-02 — The client never calculates business values
**Priority:** MVP
**Description:** Allocations, balances, outstanding, payables and stock are shown exactly as returned by the API.
- **AC1** Given a payment response, when displayed, then the client shows the backend allocation and does not recompute it.

### NFR-USE-03 — Readable numbers
**Priority:** MVP
**Description:** Money shows the rupee sign with Indian digit grouping; weights show `kg` with 3 decimals; key figures use a font size of at least 20 sp.
- **AC1** Given the amount 1250000.5, when displayed, then it reads `₹12,50,000.50`.

### NFR-USE-04 — Error messages
**Priority:** MVP
**Description:** The API `detail` is plain English of at most 200 characters with no stack traces or internal names; the client maps the stable `code` to a user-facing message. Tamil text is post-MVP.
- **AC1** Given a 409 `INSUFFICIENT_INVENTORY`, when displayed, then the user sees a clear message built from the `code`.
- **AC2 (rejection)** Given any 500 response, when inspected, then the body contains no stack trace, SQL or class names.

### NFR-USE-05 — Retry-safe submissions
**Priority:** MVP
**Description:** The client sends a new `Idempotency-Key` for each user action and reuses it when retrying; requests time out after 30 seconds.
- **AC1** Given a payment submitted over a failing network and retried, when it finally succeeds, then exactly one payment exists.

### NFR-USE-06 — Accessibility
**Priority:** MVP
**Description:** Text scales up to 200% without clipping; contrast between text and background is at least 4.5:1; click targets are at least 32 x 32 px; every action is reachable by keyboard with a visible focus indicator.
- **AC1** Given the largest system font scale, when a list screen is opened at 200% text scale in a 1366x768 window, then no text is cut off.
- **AC2** Given only a keyboard, when the user records a customer payment, then every field and the submit action are reachable with Tab and Enter.

## 3.6 Data (DATA)

### NFR-DATA-01 — Exact types
**Priority:** MVP
**Description:** Money is `NUMERIC(14,2)` and `BigDecimal` with HALF_UP; weight is `NUMERIC(12,3)` and `BigDecimal`; no `double` or `float` is used for money or weight; comparisons use `compareTo`.
- **AC1** Given the source tree, when searched by a build check, then no `double` or `float` field exists in domain or DTO classes for amounts or weights.
- **AC2** Given `2.0` and `2.00`, when compared by the code, then they are treated as equal.

### NFR-DATA-02 — No stored derived values
**Priority:** MVP
**Description:** Stock, balances, outstanding and payables are computed; the only stored projection is `inventory_balances`.
- **AC1** Given the schema, when a test lists numeric columns named like `*balance*`, `*outstanding*` or `*payable*`, then only `inventory_balances.quantity_kg` is found.

### NFR-DATA-03 — Retention
**Priority:** MVP
**Description:** Financial and inventory records are kept for at least 10 years; no automatic purge exists; parties and accounts are deactivated, never deleted.
- **AC1 (rejection)** Given any financial or inventory table, when a delete is attempted through the API, then no endpoint exists (405).

### NFR-DATA-04 — Go-live reconciliation
**Priority:** MVP
**Description:** After the opening data is entered, the totals for stock per product and location, each account balance, customer outstanding, supplier payables, outstanding loans and chit contributions equal the figures from the notebooks, signed by the owner; remaining differences are recorded as dated adjustments with reasons.
- **AC1** Given the owner's notebook totals, when compared with the system reports, then every difference is 0.00 or 0.000 or has a documented adjustment.
- **AC2 (rejection)** Given any undocumented difference, when go-live is reviewed, then go-live is blocked.

### NFR-DATA-05 — Referential integrity
**Priority:** MVP
**Description:** Every relationship has a foreign key and every foreign key column has an index, except documented polymorphic references (`reference_type`/`reference_id`); constraints use the naming convention `pk_`, `fk_`, `uq_`, `ck_`, `ix_`.
- **AC1** Given the schema, when a test lists foreign keys without an index, then the list is empty.
- **AC2 (rejection)** Given an insert with a non-existing supplier id, when executed, then the database rejects it.

### NFR-DATA-06 — Unique business numbers
**Priority:** MVP
**Description:** Business numbers (`PUR`, `RCT`, `RTN`, `MOV`, `ORD`, `PAY`, `BAT`, `JOB`, `EXP`, `TRF`, `LON`, `CHT`, `PTY`) are unique per prefix and year, enforced by the database, and gap-free on rollback.
- **AC1** Given 20 parallel creations of the same kind, when finished, then all numbers are distinct and consecutive.
- **AC2 (rejection)** Given a duplicate number inserted directly, when executed, then the database rejects it.

### NFR-DATA-07 — Audit retrieval and export
**Priority:** MVP
**Description:** The owner can retrieve and export records by period, transaction type and category at any time (FR-RPT-16).
- **AC1** Given a financial year, when exported, then all transaction types of that year are included.

## 3.7 Operations and deployment (OPS)

### NFR-OPS-01 — Deployable artifact
**Priority:** MVP
**Description:** CI builds one container image that runs as a non-root user; a docker compose stack runs the application, PostgreSQL and a TLS reverse proxy.
- **AC1** Given the image, when started with valid environment variables, then it becomes healthy within 60 s.
- **AC2 (rejection)** Given the container, when its user is inspected, then it is not `root`.

### NFR-OPS-02 — Configuration
**Priority:** MVP
**Description:** All environment-specific values come from environment variables; `.env.example` lists each variable with a comment and a safe example; a missing required variable fails start-up.
- **AC1** Given a fresh clone, when `.env.example` is copied and filled, then the application starts.
- **AC2 (rejection)** Given `DB_URL` missing in production, when the application starts, then it fails with a clear message.

### NFR-OPS-03 — Continuous integration
**Priority:** MVP
**Description:** Every pull request builds, runs all tests (including Testcontainers) and the dependency scan in at most 15 minutes; the main branch stays green.
- **AC1** Given a pull request, when CI runs, then it reports build, test and scan results.
- **AC2 (rejection)** Given a failing test, when CI runs, then the pull request is blocked.

### NFR-OPS-04 — Rollback
**Priority:** MVP
**Description:** The previous image can be redeployed within 15 minutes; migrations are forward-only and backward-compatible with the previous version for one release, otherwise rollback uses the pre-deployment backup.
- **AC1** Given a faulty release, when the previous image is started, then the service is healthy within 15 minutes.

### NFR-OPS-05 — Backup before change
**Priority:** MVP
**Description:** A backup is taken and verified before the opening data import and before every deployment that contains a migration.
- **AC1** Given a deployment with a migration, when it starts, then a backup newer than 1 hour exists.
- **AC2 (rejection)** Given no recent backup, when the deployment script runs, then it stops.

### NFR-OPS-06 — Runbook
**Priority:** MVP
**Description:** A runbook documents deploy, rollback, restore, JWT secret rotation, adding a user and unlocking an account, each as numbered commands.
- **AC1** Given the runbook, when a second person follows the restore steps on a clean host, then the system is restored without other help.

### NFR-OPS-07 — Release and parallel run
**Priority:** MVP
**Description:** The first production release is tagged `v1.0.0` with a changelog; the notebooks are kept in parallel for 2 to 4 weeks and the owner confirms the weekly totals match before retiring them.
- **AC1** Given each week of the parallel run, when totals are compared, then differences are explained or corrected.

## 3.8 Testing (TEST)

### NFR-TEST-01 — Real database
**Priority:** MVP
**Description:** Integration tests run against PostgreSQL 17 in Testcontainers; H2 or other in-memory databases are never used.
- **AC1** Given the test configuration, when searched, then no H2 dependency exists.

### NFR-TEST-02 — Coverage
**Priority:** MVP
**Description:** Line coverage of the `application` and `domain` packages is at least 80%; the build fails below it.
- **AC1** Given the coverage report, when below 80%, then the build fails.

### NFR-TEST-03 — Requirement traceability
**Priority:** MVP
**Description:** Every `MVP` requirement ID appears in the display name of at least one passing test, and a CI script fails when one is missing.
- **AC1** Given the traceability matrix, when generated from tests, then no MVP requirement has an empty test list.
- **AC2 (rejection)** Given a requirement with no test, when CI runs, then it fails and names the ID.

### NFR-TEST-04 — Rejections are tested
**Priority:** MVP
**Description:** Every rejection criterion has a test asserting the HTTP status and the `code`.
- **AC1** Given FR-PAY-03 AC2, when its test runs, then it asserts 409 and `PAYMENT_ALLOCATION_EXCEEDED`.

### NFR-TEST-05 — Concurrency tests
**Priority:** MVP
**Description:** Stock, cash, business numbers, payments, transfers and reversals each have a multi-thread test; each is shown to fail when its lock is removed.
- **AC1** Given the stock concurrency test with the lock disabled, when run, then it fails.

### NFR-TEST-06 — End-to-end scenarios
**Priority:** MVP
**Description:** API scenario tests cover the full loops: (a) login, supplier, purchase, partial receipts, stock, customer, order, production, delivery, payment, outstanding, dashboard; (b) outsourcing issue, receive, manufacturer payment; (c) expenses, transfer, loan, chit, cash position.
- **AC1** Given a clean database, when scenario (a) runs, then every step succeeds and the dashboard figures equal the expected values.
- **AC2** Given the three scenarios, when run together, then they complete in at most 3 minutes.

### NFR-TEST-07 — Migrations in the build
**Priority:** MVP
**Description:** Every build applies all migrations to an empty database with `ddl-auto=validate`.
- **AC1 (rejection)** Given an entity mapped to a missing column, when the build runs, then it fails.

### NFR-TEST-08 — Domain unit tests
**Priority:** MVP
**Description:** Entity invariants and every legal and illegal status transition have unit tests without Spring.
- **AC1** Given each state machine in DOMAIN_RULES §5, when the unit tests run, then each illegal transition throws `INVALID_STATE_TRANSITION`.

### NFR-TEST-09 — Independent tests
**Priority:** MVP
**Description:** Tests create their own data and do not depend on order or on data left by others.
- **AC1** Given the suite run in random order three times, when finished, then all runs pass.

---

# 4. Decisions made while writing

Items the sources left open or that conflicted. Each was resolved with the plan decisions, the domain rules, or the simplest option consistent with the sources. The owner-facing ones are also in `01-PRD.md` §8.

| # | Item | Decision | Reason |
|---|---|---|---|
| 1 | Lenders | Banks and private lenders are parties with the new role `LENDER` (FR-PARTY-02, FR-LOAN-01). | The schema design links loans to a party but the four existing roles do not fit a lender. Requires the role list and the party role check to include `LENDER`. |
| 2 | Vuda warp purchases | Purchases have a product type (`RAW_SILK` or `VUDA_WARP`); vuda warp is received into the `RAW_STOCK` location, which therefore holds all trading stock (FR-PROC-01, FR-PROC-17). | Vuda warp is bought and resold (domain rules) but the purchase table design has no product type. |
| 3 | Order status path | `CONFIRMED → READY` is allowed when no production is needed; `COMPLETED` is set explicitly after delivery and is independent of payment; delivery is all items at once (FR-ORD-10 to FR-ORD-13). | The state machine needs a way to deliver from stock; payment status is separate by rule. This extends DOMAIN_RULES §5 and needs a change-log entry. |
| 4 | Q2 mechanics | Obligation starts at confirmation; advances are applied automatically at confirmation; an order with allocations cannot be cancelled (FR-ORD-07, FR-ORD-09, FR-PAY-05). | Simplest rule that keeps allocations attached to existing order items. |
| 5 | Supplier advances | Supplier payments cannot exceed the total payable (FR-PAY-08). | The domain rules define an advance only for customers. Supplier advances are post-MVP. |
| 6 | Cheques, refunds, customer returns | Cheques count as cleared when recorded; pending and bounced states, refunds and customer returns are post-MVP (FR-PAY-25, FR-INV-17). | DOMAIN_RULES §5 has only `RECORDED → REVERSED` for payments; the roadmap has no refund or return tasks. |
| 7 | Reminder timing | The earlier "day-8, day-10" notes are treated as examples of configurable offsets; defaults are 3 days before the due date, on the due date, and daily when overdue; no penalty amount is computed (FR-NTF-01, FR-NTF-02, ASSUMPTION Q3). | Credit periods vary per supplier and timing must be configurable (rule 19). |
| 8 | Product transformation in movements | FR-PROD-03 states only the net stock effect: raw silk leaves internal WIP, finished warp of the batch's type enters finished stock. How a movement represents the change of product type is left to the data model. | A single product type per movement cannot describe raw silk becoming warp. |
| 9 | Discrepancy | A production discrepancy is stored on the batch and booked as a flagged `WASTAGE` movement (FR-PROD-03). | There is no separate movement type and discrepancy must be visible. |
| 10 | Return larger than returnable | Reported with `INSUFFICIENT_INVENTORY` and a message naming the returnable weight (FR-PROC-13). | No dedicated error code exists and codes are a stable contract. |
| 11 | Loan repayment larger than outstanding | Reported with `PAYMENT_ALLOCATION_EXCEEDED` (FR-LOAN-03). | The closest existing code ("amount exceeds outstanding"). |
| 12 | Loan closing | A loan is closed explicitly when principal is 0; interest can be paid until then (FR-LOAN-06). | Interest is separate from principal and may follow the last principal repayment. |
| 13 | Chit status and duration | `ACTIVE → MATURED` when the payout is recorded, then `CLOSED`; the chit records a duration in months (FR-CHIT-01 to FR-CHIT-06). | The domain rules list `MATURED` and remaining expected contributions need a duration. |
| 14 | Mistaken work records | Work records can be reversed while unallocated (FR-WORK-06). | History is immutable; a correction path is required. Needs a change-log entry in DOMAIN_RULES. |
| 15 | Expense categories | Fixed seeded list in the first release; configurable categories are post-MVP (FR-FIN-11, FR-FIN-19). | The roadmap does not include category management. |
| 16 | Stock adjustment | An adjustment cannot make a balance negative either (FR-INV-12). | The database check and the non-negotiable rule forbid negative stock; the "exception" in DOMAIN_RULES §4 is read as freedom from a source document, not from the check. |
| 17 | Extra inventory endpoints | Manual wastage, adjustments and opening balances are explicit endpoints (FR-INV-11 to FR-INV-13). | DOMAIN_RULES lists these movement types and the sources require recording them. |
| 18 | Opening obligations | Opening receivables and payables are stored as dated opening obligations that join oldest-first allocation (FR-PAY-22). | Roadmap task 8.7 asked for this to be designed; an obligation table keeps them out of orders and purchases. |
| 19 | Revenue recognition | The sales report recognises revenue at the delivery date; loans, transfers, chit payouts, opening balances and advances are excluded (FR-RPT-07). | Domain rules 31 and 32 and the order lifecycle. |
| 20 | Rate limiting | The login lock plus an in-memory per-IP limit on `/api/v1/auth/**` (10/min) and a per-user limit on other paths (300/min) are in the first release, returning 429 `RATE_LIMITED` (NFR-SEC-11). | Aligned with `09-SECURITY.md`; an in-memory filter needs no cache store or new dependency. |
| 21 | Weight precision | More than 3 decimals is rejected rather than rounded (FR-INV-15). | Silent rounding would hide data entry mistakes in a ledger. |
| 22 | Account number storage | Only the last 4 digits of a bank account number are stored (FR-FIN-01, NFR-SEC-06). | The design stores a masked number and secrets must not be logged. |
| 23 | Customer credit period | Not built; the first release shows the age of outstanding amounts (FR-PARTY-17). | Customers have no fixed deadline; the sources say configure only where required. |
| 24 | Closing an account | Allowed only at a zero balance (FR-FIN-16). | Keeps balances meaningful; history remains. |
| 25 | Client requirements | Only principles are defined for the client; they are written as NFR-USE, not as screens. The client is a desktop application (Windows and Linux); mobile is POST-MVP. | The sources define no screens; the screens are specified in docs/06-UI-SPEC.md. |
| 26 | Scope of sales bills | Generated bills are post-MVP; only attached documents are MVP (FR-DOC-06). | The later scope decision (Step 5) lists documents as attachments; the earlier requirements list "basic sales bills". The later decision wins. |
| 27 | Lock waits | A transaction waits at most 5 seconds for a row lock; on timeout the request fails with 409 `CONCURRENT_MODIFICATION` and can be retried (FR-INV-06, FR-FIN-10). | Prevents a request from hanging forever; reuses an existing stable code. Needs a handler for lock-timeout exceptions. |
| 28 | Invalid enum values | An unknown enum value in a query parameter or body is `MALFORMED_REQUEST`; constraint violations on valid types (blank, too long, out of range) are `VALIDATION_FAILED`. | DOMAIN_RULES §7 defines `MALFORMED_REQUEST` as "wrong types" and `VALIDATION_FAILED` as Bean Validation failures. |

# 5. Traceability matrix

One row per requirement. The column "Test names" is filled in as tests are written (test display names must contain the requirement ID, see NFR-TEST-03).

| Requirement | Title | Module / area | Priority | Test names |
|---|---|---|---|---|
| FR-AUTH-01 | Log in with username and password | Authentication | MVP | |
| FR-AUTH-02 | Access token content and lifetime | Authentication | MVP | |
| FR-AUTH-03 | Unauthenticated and expired requests | Authentication | MVP | |
| FR-AUTH-04 | Refresh with rotation | Authentication | MVP | |
| FR-AUTH-05 | Refresh-token reuse detection | Authentication | MVP | |
| FR-AUTH-06 | Log out | Authentication | MVP | |
| FR-AUTH-07 | Brute-force protection | Authentication | MVP | |
| FR-AUTH-08 | Password storage | Authentication | MVP | |
| FR-AUTH-09 | Permission-based authorization | Authentication | MVP | |
| FR-AUTH-10 | Bootstrap of the first owner | Authentication | MVP | |
| FR-AUTH-11 | Current user | Authentication | MVP | |
| FR-AUTH-12 | Authenticated endpoints only | Authentication | MVP | |
| FR-AUTH-13 | Audit identity on records | Authentication | MVP | |
| FR-AUTH-14 | User management | Authentication | POST-MVP | |
| FR-AUTH-15 | Change own password | Authentication | POST-MVP | |
| FR-PARTY-01 | Create a party | Parties | MVP | |
| FR-PARTY-02 | Roles of a party | Parties | MVP | |
| FR-PARTY-03 | Unique, gap-free party codes | Parties | MVP | |
| FR-PARTY-04 | Phone numbers | Parties | MVP | |
| FR-PARTY-05 | Optional address | Parties | MVP | |
| FR-PARTY-06 | View a party | Parties | MVP | |
| FR-PARTY-07 | List and search parties | Parties | MVP | |
| FR-PARTY-08 | Update a party | Parties | MVP | |
| FR-PARTY-09 | Add a role to an existing party | Parties | MVP | |
| FR-PARTY-10 | Deactivate and reactivate | Parties | MVP | |
| FR-PARTY-11 | Inactive parties cannot be used in new transactions | Parties | MVP | |
| FR-PARTY-12 | Role must match the operation | Parties | MVP | |
| FR-PARTY-13 | Supplier default credit days | Parties | MVP | |
| FR-PARTY-14 | Worker default rates | Parties | MVP | |
| FR-PARTY-15 | No hard delete | Parties | MVP | |
| FR-PARTY-16 | Lender role | Parties | MVP | |
| FR-PARTY-17 | Customer-specific credit period | Parties | POST-MVP | |
| FR-PROC-01 | Create a purchase | Procurement | MVP | |
| FR-PROC-02 | Purchase numbers | Procurement | MVP | |
| FR-PROC-03 | Purchase value is derived | Procurement | MVP | |
| FR-PROC-04 | Due date | Procurement | MVP | |
| FR-PROC-05 | Purchase price and weight are historical | Procurement | MVP | |
| FR-PROC-06 | View a purchase | Procurement | MVP | |
| FR-PROC-07 | List purchases | Procurement | MVP | |
| FR-PROC-08 | Record a material receipt | Procurement | MVP | |
| FR-PROC-09 | Receipt weights are consistent | Procurement | MVP | |
| FR-PROC-10 | Only accepted material enters stock | Procurement | MVP | |
| FR-PROC-11 | Purchase receiving status | Procurement | MVP | |
| FR-PROC-12 | Cancel a purchase | Procurement | MVP | |
| FR-PROC-13 | Return material to the supplier | Procurement | MVP | |
| FR-PROC-14 | Receipts and returns are never rewritten | Procurement | MVP | |
| FR-PROC-15 | Supplier payable per purchase (ASSUMPTION Q1) | Procurement | MVP | |
| FR-PROC-16 | Overdue purchases | Procurement | MVP | |
| FR-PROC-17 | Vuda warp purchases | Procurement | MVP | |
| FR-PROC-18 | Purchase history is preserved | Procurement | MVP | |
| FR-PROC-19 | Retry-safe receipts | Procurement | MVP | |
| FR-PROC-20 | Low-stock indication when buying | Procurement | POST-MVP | |
| FR-INV-01 | Stock changes only through movements | Inventory | MVP | |
| FR-INV-02 | Movement record | Inventory | MVP | |
| FR-INV-03 | Valid movement types | Inventory | MVP | |
| FR-INV-04 | Balances are a projection updated atomically | Inventory | MVP | |
| FR-INV-05 | Stock never goes negative | Inventory | MVP | |
| FR-INV-06 | Deadlock-free locking | Inventory | MVP | |
| FR-INV-07 | Stock summary | Inventory | MVP | |
| FR-INV-08 | Movement history | Inventory | MVP | |
| FR-INV-09 | Balance reconciliation check | Inventory | MVP | |
| FR-INV-10 | Vuda warp is trade-only | Inventory | MVP | |
| FR-INV-11 | Record wastage manually | Inventory | MVP | |
| FR-INV-12 | Stock adjustment (Owner only) | Inventory | MVP | |
| FR-INV-13 | Opening stock at go-live | Inventory | MVP | |
| FR-INV-14 | Material outside with manufacturers | Inventory | MVP | |
| FR-INV-15 | Weight precision | Inventory | MVP | |
| FR-INV-16 | Wastage and discrepancy visibility | Inventory | MVP | |
| FR-INV-17 | Customer returns | Inventory | POST-MVP | |
| FR-INV-18 | Low-stock threshold and lot costing | Inventory | POST-MVP | |
| FR-ORD-01 | Create an order | Orders | MVP | |
| FR-ORD-02 | Item amounts are derived | Orders | MVP | |
| FR-ORD-03 | Rates are historical | Orders | MVP | |
| FR-ORD-04 | View an order | Orders | MVP | |
| FR-ORD-05 | List and filter orders | Orders | MVP | |
| FR-ORD-06 | Edit an order before it is locked | Orders | MVP | |
| FR-ORD-07 | Confirm an order | Orders | MVP | |
| FR-ORD-08 | Order locking when work starts | Orders | MVP | |
| FR-ORD-09 | Cancel an order | Orders | MVP | |
| FR-ORD-10 | Mark an order ready | Orders | MVP | |
| FR-ORD-11 | Deliver an order | Orders | MVP | |
| FR-ORD-12 | No partial delivery of an item | Orders | MVP | |
| FR-ORD-13 | Complete an order | Orders | MVP | |
| FR-ORD-14 | Payment status is derived | Orders | MVP | |
| FR-ORD-15 | Direct sale of raw silk and vuda warp | Orders | MVP | |
| FR-ORD-16 | Pending and late orders | Orders | MVP | |
| FR-ORD-17 | Orders are never deleted | Orders | MVP | |
| FR-ORD-18 | Order numbers | Orders | MVP | |
| FR-ORD-19 | Per-item delivery, customer returns and final bill | Orders | POST-MVP | |
| FR-PAY-01 | Record a customer payment | Payments | MVP | |
| FR-PAY-02 | Oldest-first allocation | Payments | MVP | |
| FR-PAY-03 | Explicit allocations are validated | Payments | MVP | |
| FR-PAY-04 | Customer advance | Payments | MVP | |
| FR-PAY-05 | Advance is applied when an order is confirmed (ASSUMPTION Q2) | Payments | MVP | |
| FR-PAY-06 | Atomic settlement | Payments | MVP | |
| FR-PAY-07 | Idempotent payment requests | Payments | MVP | |
| FR-PAY-08 | Record a supplier payment | Payments | MVP | |
| FR-PAY-09 | Record a manufacturer payment | Payments | MVP | |
| FR-PAY-10 | Record a worker payment | Payments | MVP | |
| FR-PAY-11 | Party role must match the direction | Payments | MVP | |
| FR-PAY-12 | Method and account must be consistent | Payments | MVP | |
| FR-PAY-13 | Money precision | Payments | MVP | |
| FR-PAY-14 | View and list payments | Payments | MVP | |
| FR-PAY-15 | Reverse a payment | Payments | MVP | |
| FR-PAY-16 | Payments are immutable | Payments | MVP | |
| FR-PAY-17 | Customer outstanding (ASSUMPTION Q2) | Payments | MVP | |
| FR-PAY-18 | Customer outstanding ageing | Payments | MVP | |
| FR-PAY-19 | Supplier payables | Payments | MVP | |
| FR-PAY-20 | Manufacturer payable | Payments | MVP | |
| FR-PAY-21 | Worker payable | Payments | MVP | |
| FR-PAY-22 | Opening obligations at go-live | Payments | MVP | |
| FR-PAY-23 | Settlement history | Payments | MVP | |
| FR-PAY-24 | Concurrent payments cannot over-allocate | Payments | MVP | |
| FR-PAY-25 | Refunds, cheque clearance, supplier advances | Payments | POST-MVP | |
| FR-PROD-01 | Create a production batch | Production | MVP | |
| FR-PROD-02 | Start a batch (consume raw silk) | Production | MVP | |
| FR-PROD-03 | Complete a batch (reconciliation) | Production | MVP | |
| FR-PROD-04 | Discrepancy is explicit | Production | MVP | |
| FR-PROD-05 | Cancel a batch | Production | MVP | |
| FR-PROD-06 | View and list batches | Production | MVP | |
| FR-PROD-07 | Many-to-many with order items | Production | MVP | |
| FR-PROD-08 | Atomic start and completion | Production | MVP | |
| FR-PROD-09 | Concurrent starts cannot oversubscribe stock | Production | MVP | |
| FR-PROD-10 | Completed batches are immutable | Production | MVP | |
| FR-PROD-11 | Edit a planned batch | Production | MVP | |
| FR-PROD-12 | Production analytics | Production | POST-MVP | |
| FR-OUT-01 | Create an outsourcing job | Outsourcing | MVP | |
| FR-OUT-02 | Issue raw silk to the manufacturer | Outsourcing | MVP | |
| FR-OUT-03 | Receive finished warp | Outsourcing | MVP | |
| FR-OUT-04 | Job completion | Outsourcing | MVP | |
| FR-OUT-05 | Manufacturer payable is historical | Outsourcing | MVP | |
| FR-OUT-06 | Cancel a job | Outsourcing | MVP | |
| FR-OUT-07 | View and list jobs | Outsourcing | MVP | |
| FR-OUT-08 | The order link is optional | Outsourcing | MVP | |
| FR-OUT-09 | Atomic issue and receipt | Outsourcing | MVP | |
| FR-OUT-10 | Concurrent issues cannot oversubscribe stock | Outsourcing | MVP | |
| FR-OUT-11 | Receipts are immutable | Outsourcing | MVP | |
| FR-OUT-12 | Outsourcing cost analysis | Outsourcing | POST-MVP | |
| FR-WORK-01 | Record work | Workforce | MVP | |
| FR-WORK-02 | Only the relevant measure is allowed | Workforce | MVP | |
| FR-WORK-03 | Historical rate | Workforce | MVP | |
| FR-WORK-04 | Payment status is derived | Workforce | MVP | |
| FR-WORK-05 | List and filter work records | Workforce | MVP | |
| FR-WORK-06 | Reverse a mistaken record | Workforce | MVP | |
| FR-WORK-07 | Records are immutable | Workforce | MVP | |
| FR-WORK-08 | Link to a production batch | Workforce | MVP | |
| FR-WORK-09 | Permissions | Workforce | MVP | |
| FR-WORK-10 | Worker efficiency analysis | Workforce | POST-MVP | |
| FR-FIN-01 | Create an account | Finance | MVP | |
| FR-FIN-02 | Opening balance is a transaction | Finance | MVP | |
| FR-FIN-03 | Balances and available money are derived | Finance | MVP | |
| FR-FIN-04 | Transactions are written only by the system | Finance | MVP | |
| FR-FIN-05 | Transaction content | Finance | MVP | |
| FR-FIN-06 | Money never goes negative | Finance | MVP | |
| FR-FIN-07 | Transaction history | Finance | MVP | |
| FR-FIN-08 | Cash position | Finance | MVP | |
| FR-FIN-09 | Transfer between own accounts | Finance | MVP | |
| FR-FIN-10 | Transfers cannot deadlock | Finance | MVP | |
| FR-FIN-11 | Record an expense | Finance | MVP | |
| FR-FIN-12 | Personal drawings stay separate | Finance | MVP | |
| FR-FIN-13 | Expense list and summary | Finance | MVP | |
| FR-FIN-14 | Reverse an expense | Finance | MVP | |
| FR-FIN-15 | Balance adjustment with reason | Finance | MVP | |
| FR-FIN-16 | Close an account | Finance | MVP | |
| FR-FIN-17 | Reversal compensates, never deletes | Finance | MVP | |
| FR-FIN-18 | Retry-safe expenses and transfers | Finance | MVP | |
| FR-FIN-19 | Configurable expense categories | Finance | POST-MVP | |
| FR-FIN-20 | Bank integration and mismatch alerts | Finance | POST-MVP | |
| FR-LOAN-01 | Register a new loan | Loans | MVP | |
| FR-LOAN-02 | Register an existing loan at go-live | Loans | MVP | |
| FR-LOAN-03 | Repay principal | Loans | MVP | |
| FR-LOAN-04 | Pay interest | Loans | MVP | |
| FR-LOAN-05 | Outstanding principal is derived | Loans | MVP | |
| FR-LOAN-06 | Close a loan | Loans | MVP | |
| FR-LOAN-07 | Correct a mistaken loan payment | Loans | MVP | |
| FR-LOAN-08 | Loan accounting classification | Loans | MVP | |
| FR-LOAN-09 | Loans are never deleted | Loans | MVP | |
| FR-LOAN-10 | Interest calculation, reminders and repayment advice | Loans | POST-MVP | |
| FR-CHIT-01 | Create a chit | Chits | MVP | |
| FR-CHIT-02 | Record a contribution (cash only) | Chits | MVP | |
| FR-CHIT-03 | Record the payout (cash only) | Chits | MVP | |
| FR-CHIT-04 | Organiser payment | Chits | MVP | |
| FR-CHIT-05 | Chit summary is derived | Chits | MVP | |
| FR-CHIT-06 | Close a chit | Chits | MVP | |
| FR-CHIT-07 | Classification in reports | Chits | MVP | |
| FR-CHIT-08 | Existing chit at go-live | Chits | MVP | |
| FR-CHIT-09 | Correct a mistaken chit entry | Chits | MVP | |
| FR-CHIT-10 | Chit calculation and reminders | Chits | POST-MVP | |
| FR-RPT-01 | Owner dashboard | Reports | MVP | |
| FR-RPT-02 | Dashboard separates available money from obligations | Reports | MVP | |
| FR-RPT-03 | Customer outstanding report | Reports | MVP | |
| FR-RPT-04 | Customer statement | Reports | MVP | |
| FR-RPT-05 | Supplier payables report | Reports | MVP | |
| FR-RPT-06 | Purchase report | Reports | MVP | |
| FR-RPT-07 | Sales and orders report | Reports | MVP | |
| FR-RPT-08 | Expense report | Reports | MVP | |
| FR-RPT-09 | Inventory movement report | Reports | MVP | |
| FR-RPT-10 | Production and wastage report | Reports | MVP | |
| FR-RPT-11 | Outsourcing report | Reports | MVP | |
| FR-RPT-12 | Cash and bank report | Reports | MVP | |
| FR-RPT-13 | Worker payable and payroll report | Reports | MVP | |
| FR-RPT-14 | Loans and chits report | Reports | MVP | |
| FR-RPT-15 | Report consistency | Reports | MVP | |
| FR-RPT-16 | CSV export for audit | Reports | MVP | |
| FR-RPT-17 | Profit and loss and profitability | Reports | POST-MVP | |
| FR-NTF-01 | Notification configuration | Notifications | MVP | |
| FR-NTF-02 | Supplier due reminders | Notifications | MVP | |
| FR-NTF-03 | Notification lifecycle | Notifications | MVP | |
| FR-NTF-04 | Sending is a side effect after commit | Notifications | MVP | |
| FR-NTF-05 | View notifications in the app | Notifications | MVP | |
| FR-NTF-06 | Upcoming dues on the dashboard | Notifications | MVP | |
| FR-NTF-07 | Other alerts | Notifications | POST-MVP | |
| FR-NTF-08 | WhatsApp delivery | Notifications | POST-MVP | |
| FR-DOC-01 | Attach a document | Documents | MVP | |
| FR-DOC-02 | Safe storage | Documents | MVP | |
| FR-DOC-03 | List and download | Documents | MVP | |
| FR-DOC-04 | Documents are immutable | Documents | MVP | |
| FR-DOC-05 | Attaching does not change data | Documents | MVP | |
| FR-DOC-06 | Sales bills, WhatsApp sharing, OCR and GST | Documents | POST-MVP | |
| NFR-PERF-01 | Realistic test dataset | PERF | MVP | |
| NFR-PERF-02 | Read latency | PERF | MVP | |
| NFR-PERF-03 | Dashboard latency | PERF | MVP | |
| NFR-PERF-04 | Write latency | PERF | MVP | |
| NFR-PERF-05 | Concurrency | PERF | MVP | |
| NFR-PERF-06 | Reports and exports | PERF | MVP | |
| NFR-PERF-07 | No per-row queries | PERF | MVP | |
| NFR-PERF-08 | Start-up time | PERF | MVP | |
| NFR-SEC-01 | Transport security | SEC | MVP | |
| NFR-SEC-02 | Secrets | SEC | MVP | |
| NFR-SEC-03 | Input limits | SEC | MVP | |
| NFR-SEC-04 | Response headers | SEC | MVP | |
| NFR-SEC-05 | Cross-origin access | SEC | MVP | |
| NFR-SEC-06 | Sensitive data in logs and responses | SEC | MVP | |
| NFR-SEC-07 | Dependency vulnerabilities | SEC | MVP | |
| NFR-SEC-08 | Database privileges | SEC | MVP | |
| NFR-SEC-09 | Authorization is tested everywhere | SEC | MVP | |
| NFR-SEC-10 | Injection safety | SEC | MVP | |
| NFR-SEC-11 | Request rate limits | SEC | MVP | |
| NFR-REL-01 | Invariants hold after any sequence | REL | MVP | |
| NFR-REL-02 | One use case, one transaction | REL | MVP | |
| NFR-REL-03 | Availability | REL | MVP | |
| NFR-REL-04 | Backups and restore | REL | MVP | |
| NFR-REL-05 | Dependency failure isolation | REL | MVP | |
| NFR-REL-06 | Time handling | REL | MVP | |
| NFR-REL-07 | Migration safety | REL | MVP | |
| NFR-OBS-01 | Structured request logs | OBS | MVP | |
| NFR-OBS-02 | Request id | OBS | MVP | |
| NFR-OBS-03 | Health | OBS | MVP | |
| NFR-OBS-04 | Metrics | OBS | MVP | |
| NFR-OBS-05 | Operational alerts | OBS | MVP | |
| NFR-OBS-06 | Audit trail | OBS | MVP | |
| NFR-USE-01 | Fast daily entries | USE | MVP | |
| NFR-USE-02 | The client never calculates business values | USE | MVP | |
| NFR-USE-03 | Readable numbers | USE | MVP | |
| NFR-USE-04 | Error messages | USE | MVP | |
| NFR-USE-05 | Retry-safe submissions | USE | MVP | |
| NFR-USE-06 | Accessibility | USE | MVP | |
| NFR-DATA-01 | Exact types | DATA | MVP | |
| NFR-DATA-02 | No stored derived values | DATA | MVP | |
| NFR-DATA-03 | Retention | DATA | MVP | |
| NFR-DATA-04 | Go-live reconciliation | DATA | MVP | |
| NFR-DATA-05 | Referential integrity | DATA | MVP | |
| NFR-DATA-06 | Unique business numbers | DATA | MVP | |
| NFR-DATA-07 | Audit retrieval and export | DATA | MVP | |
| NFR-OPS-01 | Deployable artifact | OPS | MVP | |
| NFR-OPS-02 | Configuration | OPS | MVP | |
| NFR-OPS-03 | Continuous integration | OPS | MVP | |
| NFR-OPS-04 | Rollback | OPS | MVP | |
| NFR-OPS-05 | Backup before change | OPS | MVP | |
| NFR-OPS-06 | Runbook | OPS | MVP | |
| NFR-OPS-07 | Release and parallel run | OPS | MVP | |
| NFR-TEST-01 | Real database | TEST | MVP | |
| NFR-TEST-02 | Coverage | TEST | MVP | |
| NFR-TEST-03 | Requirement traceability | TEST | MVP | |
| NFR-TEST-04 | Rejections are tested | TEST | MVP | |
| NFR-TEST-05 | Concurrency tests | TEST | MVP | |
| NFR-TEST-06 | End-to-end scenarios | TEST | MVP | |
| NFR-TEST-07 | Migrations in the build | TEST | MVP | |
| NFR-TEST-08 | Domain unit tests | TEST | MVP | |
| NFR-TEST-09 | Independent tests | TEST | MVP | |
