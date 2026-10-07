# 07 - Tasks

Ordered work plan for the SmartSilk (Nexora) backend and desktop client, from the current state of the `reference` branch to go-live.
Every task is sized for 30-90 minutes, names the files it touches, the tests that prove it, the requirement IDs it covers
(`02-REQUIREMENTS.md`) and the tasks it depends on. Test names here are identical to the traceability table in `08-TESTING.md`.

## How to read this file

- **Path roots.** File paths are relative to `Nexora-backend/` unless they start with `../` (repository root).
  `{M}` = `src/main/java/com/nexora`, `{T}` = `src/test/java/com/nexora`, `{R}` = `src/main/resources`,
  `{DB}` = `src/main/resources/db/migration`, `{DM}` = `../nexora-desktop/src/main/kotlin/com/nexora/desktop`,
  `{DT}` = `../nexora-desktop/src/test/kotlin/com/nexora/desktop` (desktop client, milestone M9).
- **Tests.** `Class#method` is a JUnit test. Its `@DisplayName` starts with the requirement IDs it proves, followed by the method
  name in words (rule NFR-TEST-03), for example `@DisplayName("FR-PAY-02 oldest first 20k 30k 40k pay 45k settles a then b25k")`.
  `check:` entries are commands or operational checks whose expected result is stated; they are run at the milestone gate.
- **Done means:** the listed tests exist and pass, `./gradlew build` is green (for M9: `cd nexora-desktop && ./gradlew check`), the task checkbox is ticked and one plain commit
  describes the change in normal words (for example "Add purchase receipt endpoint"). Commit messages carry no task IDs.
- **Order.** Tasks inside a milestone are in dependency order. A milestone starts only after the previous gate passed.
- **Decisions.** Business defaults come from the Q1-Q8 assumptions and the reconciliation decisions R1-R9; each is isolated in the
  class named in `04-DATA-MODEL.md` / `03-ARCHITECTURE.md` so the owner's answer changes one place.

## M0 - Foundation (done)

Work already committed on the branch. Listed so the history is complete; the tests exist and pass.

### [x] T0.01 - Base package `com.nexora` and main class `NexoraApplication`

- **Goal:** Lower-case package root that every module lives under.
- **Files:** `{M}/NexoraApplication.java`
- **Tests:**
  - `NexoraApplicationTests#contextLoads` - display name "context loads"
- **Requirements:** none (supporting task)
- **Depends on:** none

### [x] T0.02 - YAML configuration with `dev`, `test`, `prod` profiles

- **Goal:** Environment variables with dev defaults; prod fails fast without them; UTC JDBC time zone.
- **Files:** `{R}/application.yml`, `{R}/application-dev.yml`, `{R}/application-test.yml`, `{R}/application-prod.yml`
- **Tests:**
  - `NexoraApplicationTests#contextLoads` - display name "context loads"
- **Requirements:** none (supporting task)
- **Depends on:** T0.01

### [x] T0.03 - `.env.example` and local PostgreSQL via docker compose

- **Goal:** Documented variables; local database on port 5431.
- **Files:** `.env.example`, `docker-compose.yml`
- **Tests:**
  - `NexoraApplicationTests#contextLoads` - display name "context loads"
- **Requirements:** none (supporting task)
- **Depends on:** T0.02

### [x] T0.04 - `AuditableEntity` and JPA auditing

- **Goal:** UUID id, created/updated timestamps, creator and `@Version` on every entity.
- **Files:** `{M}/shared/domain/AuditableEntity.java`, `{M}/shared/config/JpaAuditingConfig.java`
- **Tests:**
  - `NexoraApplicationTests#contextLoads` - display name "context loads"
- **Requirements:** none (supporting task)
- **Depends on:** T0.01

### [x] T0.05 - `ErrorCode`, `BusinessException`, `GlobalExceptionHandler`

- **Goal:** RFC 9457 problem body with a stable `code` for every error.
- **Files:** `{M}/shared/error/ErrorCode.java`, `{M}/shared/error/BusinessException.java`, `{M}/shared/error/GlobalExceptionHandler.java`
- **Tests:**
  - `GlobalExceptionHandlerTest#handleBusiness_buildsProblemDetailWithCodeStatusAndTimestamp` - display name "NFR-USE-04 handle business builds problem detail with code status and timestamp"
  - `GlobalExceptionHandlerTest#handleGeneric_doesNotLeakExceptionMessage` - display name "NFR-USE-04 handle generic does not leak exception message"
- **Requirements:** NFR-USE-04
- **Depends on:** T0.01

### [x] T0.06 - `Clock` bean, `PageResponse`, `Money`, `Weight`

- **Goal:** One time source (Asia/Kolkata); stable page shape; exact money (scale 2) and weight (scale 3) with HALF_UP.
- **Files:** `{M}/shared/config/ClockConfig.java`, `{M}/shared/api/PageResponse.java`, `{M}/shared/domain/Money.java`, `{M}/shared/domain/Weight.java`
- **Tests:**
  - `MoneyTest#round_whenExactlyHalf_roundsUp` - display name "NFR-DATA-01 round when exactly half rounds up"
  - `MoneyTest#round_whenBelowHalf_roundsDown` - display name "NFR-DATA-01 round when below half rounds down"
  - `MoneyTest#of_whenWholeNumber_hasScaleTwo` - display name "NFR-DATA-01 of when whole number has scale two"
  - `MoneyTest#add_decimalValues_isExactUnlikeDouble` - display name "NFR-DATA-01 add decimal values is exact unlike double"
  - `MoneyTest#equals_differentScale_isFalseButCompareToIsZero` - display name "FR-PAY-13 equals different scale is false but compare to is zero"
  - `MoneyTest#isPositive_zeroAndNegative_returnFalse` - display name "FR-PAY-13 is positive zero and negative return false"
  - `WeightTest#of_whenOneDecimal_hasScaleThree` - display name "FR-INV-15 of when one decimal has scale three"
  - `WeightTest#round_whenExactlyHalf_roundsUp` - display name "FR-INV-15 round when exactly half rounds up"
  - `WeightTest#isPositive_zero_returnsFalse` - display name "FR-INV-15 is positive zero returns false"
  - `PageResponseTest#from_springPage_copiesAllPagingFields` - display name "from spring page copies all paging fields"
- **Requirements:** NFR-DATA-01, FR-PAY-13, FR-INV-15
- **Depends on:** T0.01

### [x] T0.07 - Business number generator (`V4`) with row lock

- **Goal:** `PREFIX-YYYY-NNNN`, unique and gap-free per year under concurrency and rollback.
- **Files:** `{DB}/V4__create_business_number_sequences.sql`, `{M}/shared/numbering/BusinessNumberGenerator.java`, `{M}/shared/numbering/BusinessNumberSequence.java`, `{M}/shared/numbering/BusinessNumberSequenceRepository.java`
- **Tests:**
  - `BusinessNumberGeneratorTest#next_firstCall_returnsNumberOneWithPrefixAndYear` - display name "NFR-DATA-06 next first call returns number one with prefix and year"
  - `BusinessNumberGeneratorTest#next_calledRepeatedly_incrementsByOne` - display name "NFR-DATA-06 next called repeatedly increments by one"
  - `BusinessNumberGeneratorTest#next_differentPrefixes_haveIndependentCounters` - display name "NFR-DATA-06 next different prefixes have independent counters"
  - `BusinessNumberGeneratorTest#next_whenTransactionRollsBack_numberIsNotLost` - display name "NFR-DATA-06 next when transaction rolls back number is not lost"
  - `BusinessNumberGeneratorTest#next_outsideTransaction_isRejected` - display name "NFR-REL-02 next outside transaction is rejected"
  - `BusinessNumberGeneratorTest#next_invalidPrefix_isRejected` - display name "next invalid prefix is rejected"
  - `BusinessNumberGeneratorTest#next_withManyParallelRequests_neverReturnsDuplicates` - display name "NFR-DATA-06 NFR-TEST-05 next with many parallel requests never returns duplicates"
- **Requirements:** NFR-DATA-06, NFR-REL-02, NFR-TEST-05
- **Depends on:** T0.04

### [x] T0.08 - springdoc OpenAPI, enabled only in `dev`

- **Goal:** Swagger UI for local testing; not exposed in production.
- **Files:** `{M}/shared/config/OpenApiConfig.java`, `{R}/application.yml`, `{R}/application-dev.yml`
- **Tests:**
  - `OpenApiDevProfileTest#apiDocs_inDevProfile_returnsOurTitle` - display name "FR-AUTH-12 api docs in dev profile returns our title"
  - `OpenApiProdProfileTest#apiDocs_inProdProfile_areNotExposed` - display name "FR-AUTH-12 api docs in prod profile are not exposed"
  - `OpenApiProdProfileTest#swaggerUi_inProdProfile_isNotExposed` - display name "FR-AUTH-12 swagger ui in prod profile is not exposed"
- **Requirements:** FR-AUTH-12
- **Depends on:** T0.02

### [x] T0.09 - Testcontainers PostgreSQL base class

- **Goal:** One shared PostgreSQL 17 container for every integration test.
- **Files:** `{T}/support/AbstractIntegrationTest.java`, `build.gradle`
- **Tests:**
  - `NexoraApplicationTests#contextLoads` - display name "NFR-TEST-01 context loads"
- **Requirements:** NFR-TEST-01
- **Depends on:** T0.01

### [x] T0.10 - GitHub Actions CI running `./gradlew build`

- **Goal:** Every pull request builds and runs all tests.
- **Files:** `../.github/workflows/ci.yml`
- **Tests:**
  - check: `CI job `build` green on a pull request`
- **Requirements:** none (supporting task)
- **Depends on:** T0.09

### [x] T0.11 - `.editorconfig`

- **Goal:** Consistent indentation and line endings.
- **Files:** `../.editorconfig`
- **Tests:**
  - check: ``git diff --check` reports nothing`
- **Requirements:** none (supporting task)
- **Depends on:** none

## M1 - Identity and parties

Log in securely and manage customers, suppliers, manufacturers, workers and lenders. Starts from the uncommitted security work in the working tree (error codes, `shared/security`, revised `V3`, new `V5`).

### [ ] T1.01 - Finish the shared security and error foundation

- **Goal:** Wire `nexora.security.*` properties (JWT secret from `JWT_SECRET`, dev default only in `application-dev.yml`), the extended exception handler and the JSON 401/403 writers; `ErrorCode` gains `METHOD_NOT_ALLOWED` (405), `UNSUPPORTED_MEDIA_TYPE` (415), `DUPLICATE_RESOURCE` (409) and `RATE_LIMITED` (429); app starts and existing tests pass.
- **Files:** `{R}/application.yml`, `{R}/application-dev.yml`, `.env.example`, `{M}/shared/security/SecurityConfig.java`, `{M}/shared/security/JwtConfig.java`, `{M}/shared/security/SecurityProperties.java`, `{M}/shared/security/ProblemJsonWriter.java`, `{M}/shared/security/SecurityErrorHandlers.java`, `{M}/shared/error/GlobalExceptionHandler.java`, `{M}/shared/error/ErrorCode.java`, `{T}/support/AbstractApiTest.java`, `{T}/support/AuthTokens.java`, `{T}/shared/security/SecurityPropertiesTest.java`, `{T}/shared/error/ErrorCodeTest.java`, `{T}/shared/error/GlobalExceptionHandlerApiTest.java`
- **Tests:**
  - `SecurityPropertiesTest#shortJwtSecret_failsStartup` - display name "NFR-SEC-02 short jwt secret fails startup"
  - `ErrorCodeTest#rateLimited_mapsTo429` - display name "NFR-SEC-11 rate limited maps to429"
  - `GlobalExceptionHandlerApiTest#unknownPath_returns404ProblemWithCode` - display name "NFR-USE-04 unknown path returns404 problem with code"
  - `GlobalExceptionHandlerApiTest#wrongMethod_returns405MethodNotAllowed` - display name "NFR-USE-04 wrong method returns405 method not allowed"
  - `GlobalExceptionHandlerApiTest#malformedJson_returns400MalformedRequest` - display name "NFR-USE-04 malformed json returns400 malformed request"
- **Requirements:** NFR-SEC-02, NFR-SEC-11, NFR-USE-04
- **Depends on:** T0.05, T0.09

### [ ] T1.02 - Identity schema and entities (`V3` revised with `LENDER`, `V5`)

- **Goal:** Users, roles, permissions, refresh tokens mapped; `V3` is edited in place (it is unmerged) so `ck_party_roles_role` includes `LENDER`; reset the local database with `docker compose down -v`; `ddl-auto=validate` passes on an empty database.
- **Files:** `{DB}/V3__initial_foundation_tables.sql`, `{DB}/V5__create_identity_tables.sql`, `{M}/identity/domain/User.java`, `{M}/identity/domain/Role.java`, `{M}/identity/domain/RefreshToken.java`, `{M}/identity/infrastructure/UserRepository.java`, `{M}/identity/infrastructure/RoleRepository.java`, `{M}/identity/infrastructure/RefreshTokenRepository.java`, `{M}/shared/security/Permission.java`, `{T}/shared/persistence/MigrationIntegrationTest.java`, `{T}/identity/application/PermissionCatalogIntegrationTest.java`, `{T}/identity/domain/UserTest.java`
- **Tests:**
  - `MigrationIntegrationTest#allMigrations_onEmptyDatabase_validateAgainstEntities` - display name "NFR-TEST-07 NFR-REL-07 all migrations on empty database validate against entities"
  - `MigrationIntegrationTest#partyRolesCheck_acceptsLender` - display name "FR-PARTY-16 party roles check accepts lender"
  - `PermissionCatalogIntegrationTest#permissionEnum_matchesPermissionsTable` - display name "FR-AUTH-09 permission enum matches permissions table"
  - `UserTest#recordFailedLogin_fifthFailure_locksAccountFor15Minutes` - display name "FR-AUTH-07 NFR-TEST-08 record failed login fifth failure locks account for15 minutes"
  - `UserTest#isLocked_afterLockExpiry_returnsFalse` - display name "FR-AUTH-07 is locked after lock expiry returns false"
- **Requirements:** NFR-TEST-07, NFR-REL-07, FR-PARTY-16, FR-AUTH-09, FR-AUTH-07, NFR-TEST-08
- **Depends on:** T1.01

### [ ] T1.03 - Login and access token

- **Goal:** `POST /api/v1/auth/login` returns a signed HS256 JWT (15 min, issuer `nexora`, permissions claim) and an opaque refresh token; identical 401 for unknown, wrong-password and inactive users.
- **Files:** `{M}/identity/application/AuthService.java`, `{M}/identity/application/AccessTokenService.java`, `{M}/identity/application/RefreshTokenHasher.java`, `{M}/identity/api/AuthController.java`, `{M}/identity/api/dto/LoginRequest.java`, `{M}/identity/api/dto/TokenResponse.java`, `{T}/identity/api/AuthApiTest.java`, `{T}/identity/application/AccessTokenServiceTest.java`, `{T}/identity/application/PasswordStorageIntegrationTest.java`
- **Tests:**
  - `AuthApiTest#login_withValidCredentials_returnsTokenPair` - display name "FR-AUTH-01 login with valid credentials returns token pair"
  - `AuthApiTest#login_withWrongPassword_returns401InvalidCredentials` - display name "FR-AUTH-01 NFR-TEST-04 login with wrong password returns401 invalid credentials"
  - `AuthApiTest#login_withUnknownUser_returnsSameMessageAsWrongPassword` - display name "FR-AUTH-01 login with unknown user returns same message as wrong password"
  - `AuthApiTest#login_inactiveUser_returns401InvalidCredentials` - display name "FR-AUTH-01 login inactive user returns401 invalid credentials"
  - `AccessTokenServiceTest#issue_containsIssuerSubjectPermissionsAnd15MinuteExpiry` - display name "FR-AUTH-02 issue contains issuer subject permissions and15 minute expiry"
  - `AuthApiTest#login_responseNeverContainsPasswordHash` - display name "FR-AUTH-08 login response never contains password hash"
  - `PasswordStorageIntegrationTest#storedHash_usesBcryptPrefix` - display name "FR-AUTH-08 stored hash uses bcrypt prefix"
- **Requirements:** FR-AUTH-01, NFR-TEST-04, FR-AUTH-02, FR-AUTH-08
- **Depends on:** T1.02

### [ ] T1.04 - Brute-force lockout

- **Goal:** 5 consecutive failures lock the account for 15 minutes; failures are counted under a row lock and survive the rejected request (no rollback).
- **Files:** `{M}/identity/application/AuthService.java`, `{M}/identity/domain/User.java`, `{M}/identity/infrastructure/UserRepository.java`, `{T}/identity/api/AuthApiTest.java`, `{T}/identity/application/LoginLockoutConcurrencyTest.java`
- **Tests:**
  - `AuthApiTest#login_afterFiveFailures_rejectsCorrectPasswordFor15Minutes` - display name "FR-AUTH-07 login after five failures rejects correct password for15 minutes"
  - `LoginLockoutConcurrencyTest#parallelWrongPasswords_countEveryFailure` - display name "FR-AUTH-07 parallel wrong passwords count every failure"
- **Requirements:** FR-AUTH-07
- **Depends on:** T1.03

### [ ] T1.05 - Refresh rotation, reuse detection, logout

- **Goal:** `/auth/refresh` rotates the token; a rotated token revokes all of the user's tokens; `/auth/logout` is idempotent (204).
- **Files:** `{M}/identity/application/AuthService.java`, `{M}/identity/api/AuthController.java`, `{M}/identity/api/dto/RefreshRequest.java`, `{T}/identity/api/AuthApiTest.java`
- **Tests:**
  - `AuthApiTest#refresh_withValidToken_rotatesAndOldTokenStopsWorking` - display name "FR-AUTH-04 refresh with valid token rotates and old token stops working"
  - `AuthApiTest#refresh_withExpiredToken_returns401TokenExpired` - display name "FR-AUTH-04 refresh with expired token returns401 token expired"
  - `AuthApiTest#refresh_withUnknownToken_returns401Unauthenticated` - display name "FR-AUTH-04 refresh with unknown token returns401 unauthenticated"
  - `AuthApiTest#refresh_withRotatedToken_revokesAllTokensOfUser` - display name "FR-AUTH-05 refresh with rotated token revokes all tokens of user"
  - `AuthApiTest#logout_revokesToken_andIsIdempotent` - display name "FR-AUTH-06 logout revokes token and is idempotent"
- **Requirements:** FR-AUTH-04, FR-AUTH-05, FR-AUTH-06
- **Depends on:** T1.04

### [ ] T1.06 - JSON 401/403 and public endpoints

- **Goal:** Missing, malformed or expired token returns problem+json 401 (`UNAUTHENTICATED`/`TOKEN_EXPIRED`); missing permission returns 403 `ACCESS_DENIED`; only the documented paths are public.
- **Files:** `{M}/shared/security/SecurityErrorHandlers.java`, `{M}/shared/security/SecurityConfig.java`, `{T}/shared/security/SecurityErrorApiTest.java`
- **Tests:**
  - `SecurityErrorApiTest#protectedEndpoint_withoutToken_returns401ProblemJson` - display name "FR-AUTH-03 protected endpoint without token returns401 problem json"
  - `SecurityErrorApiTest#protectedEndpoint_withExpiredToken_returns401TokenExpired` - display name "FR-AUTH-03 protected endpoint with expired token returns401 token expired"
  - `SecurityErrorApiTest#publicEndpoints_withoutToken_areReachable` - display name "FR-AUTH-12 public endpoints without token are reachable"
  - `SecurityErrorApiTest#staffUser_withoutPermission_returns403AccessDenied` - display name "FR-AUTH-09 staff user without permission returns403 access denied"
- **Requirements:** FR-AUTH-03, FR-AUTH-12, FR-AUTH-09
- **Depends on:** T1.05

### [ ] T1.07 - Owner bootstrap and `GET /auth/me`

- **Goal:** First OWNER created from `BOOTSTRAP_OWNER_*` only when `users` is empty; `/auth/me` returns roles and permissions; user management and password change are not exposed in the first release.
- **Files:** `{M}/identity/application/OwnerBootstrap.java`, `{M}/identity/api/AuthController.java`, `{M}/identity/api/dto/MeResponse.java`, `{R}/application-dev.yml`, `.env.example`, `{T}/identity/application/OwnerBootstrapIntegrationTest.java`, `{T}/identity/api/AuthApiTest.java`
- **Tests:**
  - `OwnerBootstrapIntegrationTest#emptyUsersTable_withEnvironmentVariables_createsOwner` - display name "FR-AUTH-10 empty users table with environment variables creates owner"
  - `OwnerBootstrapIntegrationTest#existingUsers_skipsBootstrap` - display name "FR-AUTH-10 existing users skips bootstrap"
  - `AuthApiTest#me_returnsCallerRolesAndPermissions` - display name "FR-AUTH-11 me returns caller roles and permissions"
  - `AuthApiTest#userManagementEndpoints_areNotExposedInFirstRelease` - display name "FR-AUTH-14 user management endpoints are not exposed in first release"
  - `AuthApiTest#changePasswordEndpoint_isNotExposedInFirstRelease` - display name "FR-AUTH-15 change password endpoint is not exposed in first release"
- **Requirements:** FR-AUTH-10, FR-AUTH-11, FR-AUTH-14, FR-AUTH-15
- **Depends on:** T1.06

### [ ] T1.08 - Party domain model

- **Goal:** `Party` aggregate with roles (incl. `LENDER`), phones (exactly one primary), address, supplier credit days and worker rates; status ACTIVE/INACTIVE through named methods.
- **Files:** `{M}/parties/domain/Party.java`, `{M}/parties/domain/PartyRole.java`, `{M}/parties/domain/PartyStatus.java`, `{M}/parties/domain/PartyPhoneNumber.java`, `{M}/parties/domain/InvalidPartyRoleException.java`, `{M}/parties/domain/PartyInactiveException.java`, `{T}/parties/domain/PartyTest.java`
- **Tests:**
  - `PartyTest#create_withoutRole_isRejected` - display name "FR-PARTY-01 create without role is rejected"
  - `PartyTest#create_withSeveralRoles_keepsAll` - display name "FR-PARTY-02 FR-PARTY-16 create with several roles keeps all"
  - `PartyTest#replacePhones_withTwoPrimaries_isRejected` - display name "FR-PARTY-04 replace phones with two primaries is rejected"
  - `PartyTest#replacePhones_withoutPrimary_makesFirstPrimary` - display name "FR-PARTY-04 replace phones without primary makes first primary"
  - `PartyTest#deactivate_whenInactive_throwsInvalidStateTransition` - display name "FR-PARTY-10 NFR-TEST-08 deactivate when inactive throws invalid state transition"
  - `PartyTest#supplierCreditDays_withoutSupplierRole_isRejected` - display name "FR-PARTY-13 supplier credit days without supplier role is rejected"
  - `PartyTest#workerRates_withoutWorkerRole_isRejected` - display name "FR-PARTY-14 worker rates without worker role is rejected"
  - `PartyTest#addRole_toExistingParty_keepsExistingRoles` - display name "FR-PARTY-09 add role to existing party keeps existing roles"
- **Requirements:** FR-PARTY-01, FR-PARTY-02, FR-PARTY-16, FR-PARTY-04, FR-PARTY-10, NFR-TEST-08, FR-PARTY-13, FR-PARTY-14, FR-PARTY-09
- **Depends on:** T1.02

### [ ] T1.09 - Create and view a party

- **Goal:** `POST /api/v1/parties` (201 + `Location`, code `PTY-YYYY-NNNN`) and `GET /api/v1/parties/{id}`; `created_by` from the token.
- **Files:** `{M}/parties/application/PartyService.java`, `{M}/parties/application/PartyRef.java`, `{M}/parties/infrastructure/PartyRepository.java`, `{M}/parties/api/PartyController.java`, `{M}/parties/api/dto/CreatePartyRequest.java`, `{M}/parties/api/dto/PhoneRequest.java`, `{M}/parties/api/dto/PartyResponse.java`, `{M}/parties/api/PartyMapper.java`, `{T}/support/TestDataFactory.java`, `{T}/support/DatabaseCleaner.java`, `{T}/parties/api/PartyApiTest.java`, `{T}/shared/persistence/AuditingIntegrationTest.java`
- **Tests:**
  - `PartyApiTest#create_withValidRequest_returns201WithPartyCode` - display name "FR-PARTY-01 FR-PARTY-03 create with valid request returns201 with party code"
  - `PartyApiTest#create_withBlankName_returns400ValidationFailed` - display name "FR-PARTY-01 NFR-TEST-04 create with blank name returns400 validation failed"
  - `PartyApiTest#create_withoutAddress_isAccepted` - display name "FR-PARTY-05 create without address is accepted"
  - `PartyApiTest#create_withTooLongCity_returns400ValidationFailed` - display name "FR-PARTY-05 NFR-SEC-03 create with too long city returns400 validation failed"
  - `PartyApiTest#get_returnsRolesPhonesDefaultsAndAuditFields` - display name "FR-PARTY-06 get returns roles phones defaults and audit fields"
  - `PartyApiTest#get_unknownId_returns404ResourceNotFound` - display name "FR-PARTY-06 get unknown id returns404 resource not found"
  - `AuditingIntegrationTest#createdRecord_storesCreatorFromTokenAndVersion` - display name "FR-AUTH-13 NFR-OBS-06 created record stores creator from token and version"
- **Requirements:** FR-PARTY-01, FR-PARTY-03, NFR-TEST-04, FR-PARTY-05, NFR-SEC-03, FR-PARTY-06, FR-AUTH-13, NFR-OBS-06
- **Depends on:** T1.08, T1.06

### [ ] T1.10 - List and search parties

- **Goal:** `GET /api/v1/parties?role&status&search&page&size&sort` with escaped LIKE wildcards, page size capped at 100, no N+1.
- **Files:** `{M}/parties/application/PartyService.java`, `{M}/parties/infrastructure/PartySpecifications.java`, `{M}/parties/api/dto/PartySummaryResponse.java`, `{R}/application.yml`, `{T}/parties/api/PartyApiTest.java`, `{T}/parties/application/PartyListQueryCountIntegrationTest.java`
- **Tests:**
  - `PartyApiTest#list_filteredByRoleStatusAndSearch_returnsPage` - display name "FR-PARTY-07 list filtered by role status and search returns page"
  - `PartyApiTest#list_searchWithPercentSign_isEscaped` - display name "FR-PARTY-07 NFR-SEC-10 list search with percent sign is escaped"
  - `PartyApiTest#list_pageSizeAbove100_isCapped` - display name "NFR-SEC-03 list page size above100 is capped"
  - `PartyApiTest#list_unknownSortProperty_returns400ValidationFailed` - display name "FR-PARTY-07 list unknown sort property returns400 validation failed"
  - `PartyListQueryCountIntegrationTest#list_statementCount_doesNotGrowWithPageSize` - display name "NFR-PERF-07 list statement count does not grow with page size"
- **Requirements:** FR-PARTY-07, NFR-SEC-10, NFR-SEC-03, NFR-PERF-07
- **Depends on:** T1.09

### [ ] T1.11 - Update, add role, activate/deactivate

- **Goal:** `PATCH /api/v1/parties/{id}` (phones fully replaced, code unchanged), add roles, `POST .../deactivate` and `.../activate`; no delete route.
- **Files:** `{M}/parties/application/PartyService.java`, `{M}/parties/api/PartyController.java`, `{M}/parties/api/dto/UpdatePartyRequest.java`, `{T}/parties/api/PartyApiTest.java`
- **Tests:**
  - `PartyApiTest#patch_replacesPhonesAndKeepsPartyCode` - display name "FR-PARTY-08 patch replaces phones and keeps party code"
  - `PartyApiTest#patch_addRole_keepsExistingRoles` - display name "FR-PARTY-09 patch add role keeps existing roles"
  - `PartyApiTest#deactivate_thenActivate_switchesStatus` - display name "FR-PARTY-10 deactivate then activate switches status"
  - `PartyApiTest#delete_isNotSupported_returns405` - display name "FR-PARTY-15 delete is not supported returns405"
- **Requirements:** FR-PARTY-08, FR-PARTY-09, FR-PARTY-10, FR-PARTY-15
- **Depends on:** T1.10

### [ ] T1.12 - `PartyService.requireActiveWithRole` contract

- **Goal:** The single check other modules call: 404 unknown, 422 `INVALID_PARTY_ROLE`, 422 `PARTY_INACTIVE`; returns `PartyRef`.
- **Files:** `{M}/parties/application/PartyService.java`, `{M}/parties/application/PartyRef.java`, `{T}/parties/application/PartyServiceIntegrationTest.java`
- **Tests:**
  - `PartyServiceIntegrationTest#requireActiveWithRole_inactiveParty_throwsPartyInactive` - display name "FR-PARTY-11 require active with role inactive party throws party inactive"
  - `PartyServiceIntegrationTest#requireActiveWithRole_missingRole_throwsInvalidPartyRole` - display name "FR-PARTY-12 require active with role missing role throws invalid party role"
  - `PartyServiceIntegrationTest#requireActiveWithRole_lenderRole_isAccepted` - display name "FR-PARTY-16 require active with role lender role is accepted"
- **Requirements:** FR-PARTY-11, FR-PARTY-12, FR-PARTY-16
- **Depends on:** T1.11

### [ ] T1.13 - Authorization coverage guard

- **Goal:** Reflection test over every registered endpoint: 401 without token, 403 for a STAFF user lacking the permission; every `@PreAuthorize` names an existing `Permission`.
- **Files:** `{T}/shared/security/EndpointSecurityCoverageTest.java`, `{T}/shared/security/PreAuthorizePermissionTest.java`
- **Tests:**
  - `EndpointSecurityCoverageTest#everyEndpoint_rejectsMissingTokenAndMissingPermission` - display name "NFR-SEC-09 FR-AUTH-09 every endpoint rejects missing token and missing permission"
  - `PreAuthorizePermissionTest#everyPreAuthorize_namesExistingPermission` - display name "FR-AUTH-09 every pre authorize names existing permission"
- **Requirements:** NFR-SEC-09, FR-AUTH-09
- **Depends on:** T1.12

### M1 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests '*Auth*' --tests '*Party*' --tests '*Security*'` | all selected tests pass |
| 3 | `./gradlew bootRun & curl -fsS -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"owner","password":"<dev password>"}'` | HTTP 200 with `accessToken`, `refreshToken`, `expiresIn: 900` |
| 4 | `curl -s -o /dev/null -w "%{http_code}" localhost:8080/api/v1/parties` | `401` |

## M2 - Procurement and inventory

Purchase, partial and full receipts, returns and a raw-silk stock that can never go negative. The inventory ledger and its locking are the riskiest code in the system.

### [ ] T2.01 - Inventory schema (`V6`) and movement rules

- **Goal:** `inventory_balances` and `material_movements` (incl. `to_product_type`); enums `ProductType`, `StockLocation`, `MovementType`; pure rule class validating (type, from, to, product) pairs.
- **Files:** `{DB}/V6__create_inventory_tables.sql`, `{M}/inventory/domain/ProductType.java`, `{M}/inventory/domain/StockLocation.java`, `{M}/inventory/domain/MovementType.java`, `{M}/inventory/domain/MaterialMovement.java`, `{M}/inventory/domain/InventoryBalance.java`, `{M}/inventory/domain/MovementRules.java`, `{M}/inventory/infrastructure/MaterialMovementRepository.java`, `{M}/inventory/infrastructure/InventoryBalanceRepository.java`, `{T}/inventory/domain/MovementRulesTest.java`
- **Tests:**
  - `MovementRulesTest#legalPairs_matchMovementTypeTable` - display name "FR-INV-03 legal pairs match movement type table"
  - `MovementRulesTest#vudaWarp_inConsumptionIssueOrOutput_isRejected` - display name "FR-INV-10 vuda warp in consumption issue or output is rejected"
  - `MovementRulesTest#quantityWithFourDecimals_isRejected` - display name "FR-INV-15 quantity with four decimals is rejected"
  - `MovementRulesTest#customerReturnPair_isDefinedForLaterUse` - display name "FR-INV-17 customer return pair is defined for later use"
- **Requirements:** FR-INV-03, FR-INV-10, FR-INV-15, FR-INV-17
- **Depends on:** T1.13

### [ ] T2.02 - `InventoryService.recordMovement` with row locks

- **Goal:** The only writer of stock: insert-if-absent + `PESSIMISTIC_WRITE` on balance rows in (product, location) order, check, insert movement (`MOV-` number), update balances; `MANDATORY` transaction.
- **Files:** `{M}/inventory/application/InventoryService.java`, `{M}/inventory/application/MovementCommand.java`, `{M}/inventory/application/MovementResult.java`, `{M}/inventory/domain/InsufficientInventoryException.java`, `{M}/inventory/infrastructure/InventoryBalanceRepository.java`, `{T}/inventory/application/InventoryServiceIntegrationTest.java`
- **Tests:**
  - `InventoryServiceIntegrationTest#recordMovement_writesMovementAndBalanceInOneTransaction` - display name "FR-INV-01 FR-INV-02 FR-INV-04 record movement writes movement and balance in one transaction"
  - `InventoryServiceIntegrationTest#recordMovement_moreThanAvailable_throwsInsufficientInventory` - display name "FR-INV-05 NFR-REL-01 record movement more than available throws insufficient inventory"
  - `InventoryServiceIntegrationTest#recordMovement_outsideTransaction_isRejected` - display name "NFR-REL-02 record movement outside transaction is rejected"
  - `InventoryServiceIntegrationTest#recordMovement_conversionToWarp_updatesWipUnderTargetProduct` - display name "FR-INV-03 record movement conversion to warp updates wip under target product"
- **Requirements:** FR-INV-01, FR-INV-02, FR-INV-04, FR-INV-05, NFR-REL-01, NFR-REL-02, FR-INV-03
- **Depends on:** T2.01

### [ ] T2.03 - Stock concurrency tests

- **Goal:** Prove the lock: stock 100 kg, parallel 80 and 70 -> exactly one 409; opposite two-balance movements finish without deadlock; each fails when the lock is removed.
- **Files:** `{T}/inventory/application/InventoryConcurrencyTest.java`, `{T}/support/ConcurrentRunner.java`
- **Tests:**
  - `InventoryConcurrencyTest#parallel80And70From100_exactlyOneSucceeds` - display name "FR-INV-05 NFR-TEST-05 parallel80 and70 from100 exactly one succeeds"
  - `InventoryConcurrencyTest#oppositeTwoBalanceMovements_doNotDeadlock` - display name "FR-INV-06 opposite two balance movements do not deadlock"
- **Requirements:** FR-INV-05, NFR-TEST-05, FR-INV-06
- **Depends on:** T2.02

### [ ] T2.04 - Stock read endpoints

- **Goal:** `GET /api/v1/inventory/summary` (balances, totals, cumulative wastage) and `GET /api/v1/inventory/movements` (filters, page); no client route writes movements.
- **Files:** `{M}/inventory/api/InventoryController.java`, `{M}/inventory/api/dto/StockSummaryResponse.java`, `{M}/inventory/api/dto/MovementResponse.java`, `{M}/inventory/application/InventoryQueryService.java`, `{T}/inventory/api/InventoryApiTest.java`
- **Tests:**
  - `InventoryApiTest#summary_returnsBalancesTotalsAndCumulativeWastage` - display name "FR-INV-07 FR-INV-16 summary returns balances totals and cumulative wastage"
  - `InventoryApiTest#movements_filteredByProductLocationAndDate_returnsPage` - display name "FR-INV-08 movements filtered by product location and date returns page"
  - `InventoryApiTest#postMovement_routeDoesNotExist` - display name "FR-INV-01 post movement route does not exist"
  - `InventoryApiTest#summary_exposesRawSilkAvailableForPurchaseScreen` - display name "FR-PROC-20 summary exposes raw silk available for purchase screen"
  - `InventoryApiTest#summary_hasNoThresholdOrLotFieldsInFirstRelease` - display name "FR-INV-18 summary has no threshold or lot fields in first release"
- **Requirements:** FR-INV-07, FR-INV-16, FR-INV-08, FR-INV-01, FR-PROC-20, FR-INV-18
- **Depends on:** T2.02

### [ ] T2.05 - Manual wastage, adjustments, opening stock

- **Goal:** `POST /inventory/wastage`, `POST /inventory/adjustments` (`INVENTORY_ADJUST`, reason required, never below zero), opening balances once per product and location.
- **Files:** `{M}/inventory/api/InventoryController.java`, `{M}/inventory/api/dto/RecordWastageRequest.java`, `{M}/inventory/api/dto/StockAdjustmentRequest.java`, `{M}/inventory/api/dto/OpeningStockRequest.java`, `{M}/inventory/application/InventoryAdjustmentService.java`, `{T}/inventory/api/InventoryApiTest.java`
- **Tests:**
  - `InventoryApiTest#wastage_recordsMovementWithReason` - display name "FR-INV-11 wastage records movement with reason"
  - `InventoryApiTest#adjustment_withoutPermission_returns403` - display name "FR-INV-12 adjustment without permission returns403"
  - `InventoryApiTest#adjustment_belowZero_returns409InsufficientInventory` - display name "FR-INV-12 NFR-REL-01 adjustment below zero returns409 insufficient inventory"
  - `InventoryApiTest#openingBalance_recordedOncePerProductAndLocation` - display name "FR-INV-13 opening balance recorded once per product and location"
- **Requirements:** FR-INV-11, FR-INV-12, NFR-REL-01, FR-INV-13
- **Depends on:** T2.04

### [ ] T2.06 - Balance reconciliation check

- **Goal:** Query comparing every `inventory_balances` row with the sum of movements; used by tests and by a daily job that logs a difference.
- **Files:** `{M}/inventory/application/InventoryReconciliationService.java`, `{M}/inventory/application/InventoryReconciliationJob.java`, `{T}/inventory/application/InventoryReconciliationIntegrationTest.java`
- **Tests:**
  - `InventoryReconciliationIntegrationTest#balances_equalSumOfMovements` - display name "FR-INV-09 NFR-REL-01 balances equal sum of movements"
- **Requirements:** FR-INV-09, NFR-REL-01
- **Depends on:** T2.05

### [ ] T2.07 - Procurement schema (`V7`) and `Purchase` aggregate

- **Goal:** `purchases` (with `product_type`), `material_receipts`, `supplier_returns`; state machine `ORDERED -> PARTIALLY_RECEIVED -> FULLY_RECEIVED`, `CANCELLED`; derived value and due date.
- **Files:** `{DB}/V7__create_procurement_tables.sql`, `{M}/procurement/domain/Purchase.java`, `{M}/procurement/domain/PurchaseStatus.java`, `{M}/procurement/domain/MaterialReceipt.java`, `{M}/procurement/domain/SupplierReturn.java`, `{M}/procurement/domain/PaymentType.java`, `{M}/procurement/domain/PurchaseReceiptExceededException.java`, `{M}/procurement/infrastructure/PurchaseRepository.java`, `{T}/procurement/domain/PurchaseTest.java`
- **Tests:**
  - `PurchaseTest#recordReceipt_beyondOrdered_throwsPurchaseReceiptExceeded` - display name "FR-PROC-08 record receipt beyond ordered throws purchase receipt exceeded"
  - `PurchaseTest#recordReceipt_acceptedPlusRejectedAboveReceived_isRejected` - display name "FR-PROC-09 record receipt accepted plus rejected above received is rejected"
  - `PurchaseTest#recordReceipt_statusMovesPartialThenFull` - display name "FR-PROC-11 NFR-TEST-08 record receipt status moves partial then full"
  - `PurchaseTest#cancel_afterReceipt_throwsInvalidStateTransition` - display name "FR-PROC-12 NFR-TEST-08 cancel after receipt throws invalid state transition"
  - `PurchaseTest#dueDate_isPurchaseDatePlusCreditDays` - display name "FR-PROC-04 due date is purchase date plus credit days"
  - `PurchaseTest#orderedValue_isRoundedHalfUp` - display name "FR-PROC-03 ordered value is rounded half up"
- **Requirements:** FR-PROC-08, FR-PROC-09, FR-PROC-11, NFR-TEST-08, FR-PROC-12, FR-PROC-04, FR-PROC-03
- **Depends on:** T2.02

### [ ] T2.08 - Create, view and list purchases

- **Goal:** `POST /api/v1/purchases` (supplier role checked, `PUR-` number, credit days prefilled from the supplier), `GET /{id}` with derived weights, `GET` list with filters; rate and weight immutable; no delete.
- **Files:** `{M}/procurement/application/PurchaseService.java`, `{M}/procurement/api/PurchaseController.java`, `{M}/procurement/api/dto/CreatePurchaseRequest.java`, `{M}/procurement/api/dto/UpdatePurchaseRequest.java`, `{M}/procurement/api/dto/PurchaseResponse.java`, `{M}/procurement/api/dto/PurchaseSummaryResponse.java`, `{T}/procurement/api/PurchaseApiTest.java`, `{T}/procurement/application/PurchaseNumberConcurrencyTest.java`
- **Tests:**
  - `PurchaseApiTest#create_withValidRequest_returns201WithPurchaseNumber` - display name "FR-PROC-01 FR-PROC-02 create with valid request returns201 with purchase number"
  - `PurchaseApiTest#create_withNonSupplier_returns422InvalidPartyRole` - display name "FR-PROC-01 FR-PARTY-12 create with non supplier returns422 invalid party role"
  - `PurchaseApiTest#create_responseValueComputedByBackend` - display name "FR-PROC-03 create response value computed by backend"
  - `PurchaseApiTest#create_cashPurchase_dueDateEqualsPurchaseDate` - display name "FR-PROC-04 create cash purchase due date equals purchase date"
  - `PurchaseApiTest#patch_rateOrWeight_isRejected` - display name "FR-PROC-05 patch rate or weight is rejected"
  - `PurchaseApiTest#get_returnsReceiptsReturnsAndDerivedWeights` - display name "FR-PROC-06 get returns receipts returns and derived weights"
  - `PurchaseApiTest#list_filteredBySupplierStatusAndDateRange` - display name "FR-PROC-07 list filtered by supplier status and date range"
  - `PurchaseApiTest#delete_isNotSupported` - display name "FR-PROC-18 delete is not supported"
  - `PurchaseNumberConcurrencyTest#parallelCreates_produceUniqueGapFreeNumbers` - display name "FR-PROC-02 NFR-DATA-06 NFR-TEST-05 parallel creates produce unique gap free numbers"
- **Requirements:** FR-PROC-01, FR-PROC-02, FR-PARTY-12, FR-PROC-03, FR-PROC-04, FR-PROC-05, FR-PROC-06, FR-PROC-07, FR-PROC-18, NFR-DATA-06, NFR-TEST-05
- **Depends on:** T2.07, T1.12

### [ ] T2.09 - Record a material receipt

- **Goal:** `POST /api/v1/purchases/{id}/receipts` in one transaction: receipt (`RCT-`), `RECEIPT` movement of the accepted kg only (raw silk and vuda into `RAW_STOCK`), purchase status.
- **Files:** `{M}/procurement/application/PurchaseReceivingService.java`, `{M}/procurement/api/PurchaseController.java`, `{M}/procurement/api/dto/RecordReceiptRequest.java`, `{M}/procurement/api/dto/ReceiptResponse.java`, `{T}/procurement/api/PurchaseReceiptApiTest.java`
- **Tests:**
  - `PurchaseReceiptApiTest#receive_acceptedWeightOnly_entersRawStock` - display name "FR-PROC-08 FR-PROC-10 receive accepted weight only enters raw stock"
  - `PurchaseReceiptApiTest#receive_beyondOrdered_returns409PurchaseReceiptExceeded` - display name "FR-PROC-08 NFR-TEST-04 receive beyond ordered returns409 purchase receipt exceeded"
  - `PurchaseReceiptApiTest#receive_vudaWarp_entersRawStockAsVuda` - display name "FR-PROC-17 receive vuda warp enters raw stock as vuda"
  - `PurchaseReceiptApiTest#receive_250Ordered100Received_thenPlus80Accepted_thenPlus100Rejected` - display name "FR-PROC-11 receive 250 ordered100 received then plus80 accepted then plus100 rejected"
- **Requirements:** FR-PROC-08, FR-PROC-10, NFR-TEST-04, FR-PROC-17, FR-PROC-11
- **Depends on:** T2.08

### [ ] T2.10 - Supplier returns and purchase cancel

- **Goal:** `POST .../returns` creates a `PURCHASE_RETURN` movement (`RTN-`), never rewrites receipts, cannot exceed available stock; `POST .../cancel` only before any receipt or allocation.
- **Files:** `{M}/procurement/application/PurchaseReceivingService.java`, `{M}/procurement/api/PurchaseController.java`, `{M}/procurement/api/dto/RecordReturnRequest.java`, `{T}/procurement/api/SupplierReturnApiTest.java`, `{T}/procurement/api/PurchaseApiTest.java`
- **Tests:**
  - `SupplierReturnApiTest#return_createsPurchaseReturnMovement_receiptUnchanged` - display name "FR-PROC-13 FR-PROC-14 return creates purchase return movement receipt unchanged"
  - `SupplierReturnApiTest#return_moreThanAvailable_returns409InsufficientInventory` - display name "FR-PROC-13 return more than available returns409 insufficient inventory"
  - `PurchaseApiTest#cancel_withoutReceipts_setsCancelled` - display name "FR-PROC-12 cancel without receipts sets cancelled"
- **Requirements:** FR-PROC-13, FR-PROC-14, FR-PROC-12
- **Depends on:** T2.09

### [ ] T2.11 - Module boundary guard

- **Goal:** Build fails when a module imports another module's `domain` or `infrastructure` (ADR-025).
- **Files:** `{T}/architecture/ModuleBoundaryTest.java`
- **Tests:**
  - `ModuleBoundaryTest#modules_onlyUseOtherModulesApplicationLayer` - display name "FR-INV-01 modules only use other modules application layer"
- **Requirements:** FR-INV-01
- **Depends on:** T2.10

### M2 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests '*ConcurrencyTest'` | all concurrency tests pass |
| 3 | Mutation check: comment out `@Lock(PESSIMISTIC_WRITE)` in `InventoryBalanceRepository`, run `./gradlew test --tests InventoryConcurrencyTest`, restore | the test FAILS with the lock removed and PASSES after restoring it |
| 4 | `./gradlew test --tests InventoryReconciliationIntegrationTest` | pass: every balance equals the sum of its movements |

## M3 - Orders and payments (MVP vertical slice)

Orders, the money ledger, oldest-first allocation, idempotent payments, reversals and outstanding. Ends with the full MVP loop running through the API.

### [ ] T3.01 - Orders schema (`V8`) and `CustomerOrder` aggregate

- **Goal:** `customer_orders`, `order_items`; amounts rounded HALF_UP; state machine incl. `CONFIRMED -> READY` and explicit `COMPLETED`; lock from `IN_PROGRESS`.
- **Files:** `{DB}/V8__create_order_tables.sql`, `{M}/orders/domain/CustomerOrder.java`, `{M}/orders/domain/OrderItem.java`, `{M}/orders/domain/OrderStatus.java`, `{M}/orders/domain/OrderLockedException.java`, `{M}/orders/infrastructure/CustomerOrderRepository.java`, `{T}/orders/domain/CustomerOrderTest.java`
- **Tests:**
  - `CustomerOrderTest#itemAmount_isRoundedHalfUpToPaise` - display name "FR-ORD-02 item amount is rounded half up to paise"
  - `CustomerOrderTest#transitions_followStateMachine` - display name "FR-ORD-07 FR-ORD-10 FR-ORD-13 NFR-TEST-08 transitions follow state machine"
  - `CustomerOrderTest#illegalTransition_throwsInvalidStateTransition` - display name "NFR-TEST-08 illegal transition throws invalid state transition"
  - `CustomerOrderTest#edit_afterLock_throwsOrderLocked` - display name "FR-ORD-08 edit after lock throws order locked"
- **Requirements:** FR-ORD-02, FR-ORD-07, FR-ORD-10, FR-ORD-13, NFR-TEST-08, FR-ORD-08
- **Depends on:** T2.11

### [ ] T3.02 - Create, view and list orders

- **Goal:** `POST /api/v1/orders` (`ORD-` number, customer role), `GET /{id}`, `GET` list with `pending` and `late`; rates stored on items; no delete.
- **Files:** `{M}/orders/application/OrderService.java`, `{M}/orders/api/OrderController.java`, `{M}/orders/api/dto/CreateOrderRequest.java`, `{M}/orders/api/dto/OrderItemRequest.java`, `{M}/orders/api/dto/OrderResponse.java`, `{M}/orders/api/dto/OrderSummaryResponse.java`, `{T}/orders/api/OrderApiTest.java`
- **Tests:**
  - `OrderApiTest#create_withItems_returns201WithOrderNumberAndAmounts` - display name "FR-ORD-01 FR-ORD-02 FR-ORD-18 create with items returns201 with order number and amounts"
  - `OrderApiTest#create_forNonCustomer_returns422InvalidPartyRole` - display name "FR-ORD-01 create for non customer returns422 invalid party role"
  - `OrderApiTest#create_expectedDateBeforeOrderDate_returns400` - display name "FR-ORD-01 create expected date before order date returns400"
  - `OrderApiTest#rate_isStoredOnItemAndNeverRecalculated` - display name "FR-ORD-03 rate is stored on item and never recalculated"
  - `OrderApiTest#get_returnsItemsPaymentStatusAndOutstanding` - display name "FR-ORD-04 NFR-USE-02 get returns items payment status and outstanding"
  - `OrderApiTest#list_filteredByCustomerStatusAndPending` - display name "FR-ORD-05 list filtered by customer status and pending"
  - `OrderApiTest#list_lateFlag_whenExpectedDatePassed` - display name "FR-ORD-16 list late flag when expected date passed"
  - `OrderApiTest#delete_isNotSupported` - display name "FR-ORD-17 delete is not supported"
- **Requirements:** FR-ORD-01, FR-ORD-02, FR-ORD-18, FR-ORD-03, FR-ORD-04, NFR-USE-02, FR-ORD-05, FR-ORD-16, FR-ORD-17
- **Depends on:** T3.01

### [ ] T3.03 - Edit, confirm, cancel, ready, complete

- **Goal:** `PATCH` while `PLACED`/`CONFIRMED` and unlocked; `confirm`, `cancel` (refused when allocations exist), `ready`, `complete` through named entity methods.
- **Files:** `{M}/orders/application/OrderService.java`, `{M}/orders/api/OrderController.java`, `{M}/orders/api/dto/UpdateOrderRequest.java`, `{T}/orders/api/OrderApiTest.java`
- **Tests:**
  - `OrderApiTest#patch_placedOrder_recalculatesAmounts` - display name "FR-ORD-06 patch placed order recalculates amounts"
  - `OrderApiTest#patch_lockedOrder_returns409OrderLocked` - display name "FR-ORD-06 FR-ORD-08 patch locked order returns409 order locked"
  - `OrderApiTest#confirm_placedOrder_becomesObligation` - display name "FR-ORD-07 confirm placed order becomes obligation"
  - `OrderApiTest#cancel_withAllocations_returns409InvalidStateTransition` - display name "FR-ORD-09 cancel with allocations returns409 invalid state transition"
  - `OrderApiTest#ready_fromConfirmed_whenFilledFromStock` - display name "FR-ORD-10 ready from confirmed when filled from stock"
  - `OrderApiTest#complete_afterDelivery_independentOfPayment` - display name "FR-ORD-13 complete after delivery independent of payment"
- **Requirements:** FR-ORD-06, FR-ORD-08, FR-ORD-07, FR-ORD-09, FR-ORD-10, FR-ORD-13
- **Depends on:** T3.02

### [ ] T3.04 - Deliver an order

- **Goal:** `POST /orders/{id}/deliver`: all items together; `DELIVERY` from `FINISHED_STOCK` for warp, `DIRECT_SALE` from `RAW_STOCK` for raw silk and vuda; all or nothing.
- **Files:** `{M}/orders/application/OrderDeliveryService.java`, `{M}/orders/api/OrderController.java`, `{T}/orders/api/OrderDeliveryApiTest.java`
- **Tests:**
  - `OrderDeliveryApiTest#deliver_warpItems_createDeliveryMovementsFromFinishedStock` - display name "FR-ORD-11 deliver warp items create delivery movements from finished stock"
  - `OrderDeliveryApiTest#deliver_rawSilkAndVuda_createDirectSaleFromRawStock` - display name "FR-ORD-11 FR-ORD-15 deliver raw silk and vuda create direct sale from raw stock"
  - `OrderDeliveryApiTest#deliver_allItemsTogether_noPartialItem` - display name "FR-ORD-12 deliver all items together no partial item"
  - `OrderDeliveryApiTest#deliver_insufficientStock_returns409AndNothingChanges` - display name "FR-ORD-11 NFR-REL-02 deliver insufficient stock returns409 and nothing changes"
  - `OrderDeliveryApiTest#partialItemDelivery_isNotOfferedInFirstRelease` - display name "FR-ORD-19 partial item delivery is not offered in first release"
- **Requirements:** FR-ORD-11, FR-ORD-15, FR-ORD-12, NFR-REL-02, FR-ORD-19
- **Depends on:** T3.03

### [ ] T3.05 - Finance schema (`V9`), accounts and `FinanceService`

- **Goal:** Accounts (CASH/BANK, last 4 digits only), opening balance as a transaction, the money ledger with the one-reference CHECK and the type/direction/reference table of `04` section 7.7 (17 types incl. `ADJUSTMENT` without reference and `CHIT_ORGANISER_PAYMENT` with `chit_id`); `recordTransaction` locks the account and refuses negative cash or bank.
- **Files:** `{DB}/V9__create_finance_tables.sql`, `{M}/finance/domain/FinancialAccount.java`, `{M}/finance/domain/FinancialTransaction.java`, `{M}/finance/domain/TransactionType.java`, `{M}/finance/domain/Direction.java`, `{M}/finance/domain/InsufficientFundsException.java`, `{M}/finance/application/FinanceService.java`, `{M}/finance/application/TransactionCommand.java`, `{M}/finance/application/FinancialAccountService.java`, `{M}/finance/infrastructure/FinancialAccountRepository.java`, `{M}/finance/infrastructure/FinancialTransactionRepository.java`, `{M}/finance/api/FinancialAccountController.java`, `{M}/finance/api/FinancialTransactionController.java`, `{M}/finance/api/dto/CreateAccountRequest.java`, `{M}/finance/api/dto/AccountResponse.java`, `{M}/finance/api/dto/TransactionResponse.java`, `{T}/finance/application/FinanceServiceIntegrationTest.java`, `{T}/finance/api/FinancialAccountApiTest.java`, `{T}/finance/api/FinancialTransactionApiTest.java`, `{T}/finance/application/CashConcurrencyTest.java`
- **Tests:**
  - `FinanceServiceIntegrationTest#recordTransaction_outAboveBalance_throwsInsufficientFunds` - display name "FR-FIN-06 record transaction out above balance throws insufficient funds"
  - `FinanceServiceIntegrationTest#transaction_requiresExactlyOneReferenceOrNone` - display name "FR-FIN-05 transaction requires exactly one reference or none"
  - `FinanceServiceIntegrationTest#typeDirectionReferenceRules_matchTypeTable` - display name "FR-FIN-05 FR-FIN-15 FR-CHIT-04 type direction reference rules match type table"
  - `FinancialAccountApiTest#create_bankAccount_storesLastFourDigitsOnly` - display name "FR-FIN-01 NFR-SEC-06 create bank account stores last four digits only"
  - `FinancialAccountApiTest#openingBalance_isTransactionNotColumn` - display name "FR-FIN-02 opening balance is transaction not column"
  - `FinancialAccountApiTest#list_balancesDerivedFromTransactions` - display name "FR-FIN-03 list balances derived from transactions"
  - `FinancialAccountApiTest#postTransaction_routeDoesNotExist` - display name "FR-FIN-04 post transaction route does not exist"
  - `FinancialTransactionApiTest#list_filteredByAccountTypeAndDate` - display name "FR-FIN-07 list filtered by account type and date"
  - `CashConcurrencyTest#parallelCashOuts_neverDriveBalanceNegative` - display name "FR-FIN-06 NFR-TEST-05 parallel cash outs never drive balance negative"
- **Requirements:** FR-FIN-06, FR-FIN-05, FR-FIN-15, FR-CHIT-04, FR-FIN-01, NFR-SEC-06, FR-FIN-02, FR-FIN-03, FR-FIN-04, FR-FIN-07, NFR-TEST-05
- **Depends on:** T3.04

### [ ] T3.06 - Allocation schema (`V10`), idempotency keys and audit log

- **Goal:** `opening_obligations`, customer/supplier allocations, `idempotency_keys` and `audit_log`; `IdempotencyService` stores the response in the use-case transaction, replays it, 409 `DUPLICATE_REQUEST` on a different body; `AuditService.record` writes `audit_log` in the caller's transaction (`AuditAction` holds every action of `04` section 12.4, incl. `ACCOUNT_ADJUSTED`, `LOAN_PAYMENT_REVERSED`, `CHIT_ENTRY_REVERSED`) and the stock adjustment and wastage use cases (T2.05) start writing audit entries.
- **Files:** `{DB}/V10__create_payment_allocation_tables.sql`, `{M}/shared/idempotency/IdempotencyService.java`, `{M}/shared/idempotency/IdempotencyKey.java`, `{M}/shared/idempotency/IdempotencyKeyRepository.java`, `{M}/shared/idempotency/Idempotent.java`, `{M}/shared/idempotency/IdempotencyInterceptor.java`, `{M}/shared/audit/AuditService.java`, `{M}/shared/audit/AuditEntry.java`, `{M}/shared/audit/AuditAction.java`, `{M}/shared/audit/AuditEntryRepository.java`, `{M}/inventory/application/InventoryAdjustmentService.java`, `{T}/shared/idempotency/IdempotencyIntegrationTest.java`, `{T}/shared/idempotency/IdempotencyConcurrencyTest.java`, `{T}/shared/audit/AuditLogIntegrationTest.java`
- **Tests:**
  - `IdempotencyIntegrationTest#replay_sameKeySameBody_returnsStoredResponse` - display name "FR-PAY-07 replay same key same body returns stored response"
  - `IdempotencyIntegrationTest#sameKeyDifferentBody_returns409DuplicateRequest` - display name "FR-PAY-07 same key different body returns409 duplicate request"
  - `IdempotencyIntegrationTest#requiredRoute_withoutKey_returns400ValidationFailed` - display name "FR-PAY-07 required route without key returns400 validation failed"
  - `IdempotencyConcurrencyTest#parallelDuplicates_createOnePayment` - display name "FR-PAY-07 NFR-USE-05 parallel duplicates create one payment"
  - `AuditLogIntegrationTest#auditService_writesEntryInCallersTransaction` - display name "NFR-OBS-06 audit service writes entry in callers transaction"
  - `AuditLogIntegrationTest#stockAdjustmentAndWastage_writeAuditEntryWithReason` - display name "NFR-OBS-06 FR-INV-12 stock adjustment and wastage write audit entry with reason"
- **Requirements:** FR-PAY-07, NFR-USE-05, NFR-OBS-06, FR-INV-12
- **Depends on:** T3.05

### [ ] T3.07 - Customer payment with oldest-first allocation

- **Goal:** `POST /api/v1/payments` direction `IN`: party role, method/account consistency, allocation oldest-first, excess as advance, payment + allocations + transaction atomic (`PAY-` number).
- **Files:** `{M}/finance/domain/Payment.java`, `{M}/finance/domain/CustomerPaymentAllocation.java`, `{M}/finance/domain/AllocationPlanner.java`, `{M}/finance/application/PaymentService.java`, `{M}/finance/infrastructure/PaymentRepository.java`, `{M}/finance/infrastructure/ObligationQueryRepository.java`, `{M}/finance/api/PaymentController.java`, `{M}/finance/api/dto/RecordPaymentRequest.java`, `{M}/finance/api/dto/PaymentResponse.java`, `{T}/finance/domain/CustomerAllocationTest.java`, `{T}/finance/api/PaymentApiTest.java`
- **Tests:**
  - `CustomerAllocationTest#oldestFirst_20k30k40kPay45k_settlesAThenB25k` - display name "FR-PAY-02 oldest first 20k30k40k pay45k settles athen b25k"
  - `PaymentApiTest#customerPayment_allocatesOldestFirstAndRecordsTransaction` - display name "FR-PAY-01 FR-PAY-06 customer payment allocates oldest first and records transaction"
  - `PaymentApiTest#customerPayment_excess_becomesAdvance` - display name "FR-PAY-04 customer payment excess becomes advance"
  - `PaymentApiTest#amountWithThreeDecimals_returns400` - display name "FR-PAY-13 amount with three decimals returns400"
  - `PaymentApiTest#cashMethod_withBankAccount_returns422InvalidPaymentMethod` - display name "FR-PAY-12 cash method with bank account returns422 invalid payment method"
  - `PaymentApiTest#directionIn_forSupplier_returns422InvalidPartyRole` - display name "FR-PAY-11 direction in for supplier returns422 invalid party role"
  - `PaymentApiTest#record_withoutDate_defaultsToToday` - display name "NFR-USE-01 record without date defaults to today"
  - `PaymentApiTest#refundDirection_isNotOfferedInFirstRelease` - display name "FR-PAY-25 refund direction is not offered in first release"
- **Requirements:** FR-PAY-02, FR-PAY-01, FR-PAY-06, FR-PAY-04, FR-PAY-13, FR-PAY-12, FR-PAY-11, NFR-USE-01, FR-PAY-25
- **Depends on:** T3.06

### [ ] T3.08 - Explicit allocations and advance on confirmation

- **Goal:** Client-supplied allocations re-validated (owner, outstanding, sum); confirming an order applies the customer's advance oldest-first in the same transaction.
- **Files:** `{M}/finance/application/PaymentService.java`, `{M}/finance/application/AdvanceAllocationService.java`, `{M}/orders/application/OrderService.java`, `{T}/finance/api/PaymentApiTest.java`
- **Tests:**
  - `PaymentApiTest#explicitAllocation_aboveOutstanding_returns409AllocationExceeded` - display name "FR-PAY-03 explicit allocation above outstanding returns409 allocation exceeded"
  - `PaymentApiTest#explicitAllocation_otherCustomersItem_returns422InvalidAllocation` - display name "FR-PAY-03 explicit allocation other customers item returns422 invalid allocation"
  - `PaymentApiTest#confirmOrder_appliesAvailableAdvanceOldestFirst` - display name "FR-PAY-05 confirm order applies available advance oldest first"
- **Requirements:** FR-PAY-03, FR-PAY-05
- **Depends on:** T3.07

### [ ] T3.09 - Supplier payments and payables

- **Goal:** Direction `OUT` to a supplier allocated oldest purchase first; payable = (accepted - returned) x rate - allocations (Q1); overdue by due date.
- **Files:** `{M}/finance/domain/SupplierPaymentAllocation.java`, `{M}/finance/application/PaymentService.java`, `{M}/procurement/application/SupplierPayableQuery.java`, `{M}/procurement/api/SupplierPayableController.java`, `{M}/procurement/api/dto/SupplierPayableResponse.java`, `{T}/finance/api/SupplierPaymentApiTest.java`, `{T}/procurement/api/SupplierPayableApiTest.java`
- **Tests:**
  - `SupplierPaymentApiTest#supplierPayment_allocatesOldestPurchaseFirst` - display name "FR-PAY-08 supplier payment allocates oldest purchase first"
  - `SupplierPayableApiTest#payable_isAcceptedMinusReturnedTimesRateMinusAllocations` - display name "FR-PROC-15 FR-PAY-19 payable is accepted minus returned times rate minus allocations"
  - `SupplierPayableApiTest#purchase_pastDueWithPayable_isOverdue` - display name "FR-PROC-16 purchase past due with payable is overdue"
- **Requirements:** FR-PAY-08, FR-PROC-15, FR-PAY-19, FR-PROC-16
- **Depends on:** T3.08

### [ ] T3.10 - Customer outstanding and payment status

- **Goal:** `GET /api/v1/customers/{id}/outstanding` (confirmed..completed orders minus allocations, advance, ageing buckets); order and item payment status derived.
- **Files:** `{M}/finance/application/CustomerOutstandingQuery.java`, `{M}/finance/api/CustomerOutstandingController.java`, `{M}/finance/api/dto/OutstandingResponse.java`, `{T}/finance/api/CustomerOutstandingApiTest.java`, `{T}/orders/api/OrderApiTest.java`
- **Tests:**
  - `CustomerOutstandingApiTest#outstanding_confirmedOrdersMinusAllocations` - display name "FR-PAY-17 outstanding confirmed orders minus allocations"
  - `CustomerOutstandingApiTest#outstanding_groupsByAgeBuckets` - display name "FR-PAY-18 outstanding groups by age buckets"
  - `CustomerOutstandingApiTest#outstanding_showsAgeWithoutCreditPeriodAlerts` - display name "FR-PARTY-17 outstanding shows age without credit period alerts"
  - `OrderApiTest#paymentStatus_derivedFromAllocations` - display name "FR-ORD-14 payment status derived from allocations"
- **Requirements:** FR-PAY-17, FR-PAY-18, FR-PARTY-17, FR-ORD-14
- **Depends on:** T3.09

### [ ] T3.11 - Payment views and reversal

- **Goal:** `GET /payments/{id}`, list, `/allocations`; `POST /payments/{id}/reverse` (`PAYMENT_REVERSE`, reason): `REVERSED`, compensating `REVERSAL` transaction, allocations released; no update or delete.
- **Files:** `{M}/finance/application/PaymentReversalService.java`, `{M}/finance/api/PaymentController.java`, `{M}/finance/api/dto/ReversePaymentRequest.java`, `{M}/finance/api/dto/AllocationResponse.java`, `{T}/finance/api/PaymentApiTest.java`, `{T}/finance/api/PaymentReversalApiTest.java`, `{T}/finance/application/PaymentReversalConcurrencyTest.java`
- **Tests:**
  - `PaymentApiTest#get_returnsAllocationsAndTransactionId` - display name "FR-PAY-14 get returns allocations and transaction id"
  - `PaymentApiTest#allocations_listedAfterReversalAsReleased` - display name "FR-PAY-23 allocations listed after reversal as released"
  - `PaymentReversalApiTest#reverse_marksReversedAndAddsCompensatingTransaction` - display name "FR-PAY-15 FR-FIN-17 reverse marks reversed and adds compensating transaction"
  - `PaymentReversalApiTest#reverse_withoutPermission_returns403` - display name "FR-PAY-15 reverse without permission returns403"
  - `PaymentReversalApiTest#reverse_writesAuditEntryWithReason` - display name "NFR-OBS-06 reverse writes audit entry with reason"
  - `PaymentApiTest#putOrDelete_isNotSupported` - display name "FR-PAY-16 put or delete is not supported"
  - `PaymentReversalConcurrencyTest#parallelReversals_reverseOnce` - display name "FR-PAY-15 NFR-TEST-05 parallel reversals reverse once"
- **Requirements:** FR-PAY-14, FR-PAY-23, FR-PAY-15, FR-FIN-17, NFR-OBS-06, FR-PAY-16, NFR-TEST-05
- **Depends on:** T3.10

### [ ] T3.12 - Payment concurrency and idempotent routes so far

- **Goal:** Obligation rows locked in ascending id order so parallel payments cannot over-allocate; `@Idempotent` on receipts, returns, deliver, payments and payment reverse (5 of the 18 routes of `03` section 7.2), each replay returning the stored response.
- **Files:** `{M}/finance/application/PaymentService.java`, `{M}/procurement/api/PurchaseController.java`, `{M}/orders/api/OrderController.java`, `{M}/finance/api/PaymentController.java`, `{T}/finance/application/PaymentConcurrencyTest.java`, `{T}/procurement/api/PurchaseReceiptApiTest.java`, `{T}/procurement/api/SupplierReturnApiTest.java`, `{T}/orders/api/OrderDeliveryApiTest.java`, `{T}/finance/api/PaymentApiTest.java`, `{T}/finance/api/PaymentReversalApiTest.java`
- **Tests:**
  - `PaymentConcurrencyTest#parallelPayments_neverOverAllocate` - display name "FR-PAY-24 NFR-TEST-05 parallel payments never over allocate"
  - `PurchaseReceiptApiTest#receive_replayWithSameKey_createsOneReceipt` - display name "FR-PROC-19 receive replay with same key creates one receipt"
  - `SupplierReturnApiTest#return_replayWithSameKey_createsOneReturn` - display name "FR-PROC-19 return replay with same key creates one return"
  - `OrderDeliveryApiTest#deliver_replayWithSameKey_deliversOnce` - display name "FR-PAY-07 deliver replay with same key delivers once"
  - `PaymentApiTest#record_replayWithSameKey_returnsStoredResponse` - display name "FR-PAY-07 NFR-USE-05 record replay with same key returns stored response"
  - `PaymentReversalApiTest#reverse_replayWithSameKey_returnsStoredResponse` - display name "FR-PAY-07 reverse replay with same key returns stored response"
- **Requirements:** FR-PAY-24, NFR-TEST-05, FR-PROC-19, FR-PAY-07, NFR-USE-05
- **Depends on:** T3.11

### [ ] T3.13 - Opening obligations

- **Goal:** Dated opening receivables and payables (permission `PAYMENT_CREATE`) that payments allocate to oldest-first like orders and purchases.
- **Files:** `{M}/finance/domain/OpeningObligation.java`, `{M}/finance/application/OpeningObligationService.java`, `{M}/finance/api/OpeningObligationController.java`, `{M}/finance/api/dto/OpeningObligationRequest.java`, `{T}/finance/api/OpeningObligationApiTest.java`
- **Tests:**
  - `OpeningObligationApiTest#openingReceivable_isAllocatableOldestFirst` - display name "FR-PAY-22 opening receivable is allocatable oldest first"
- **Requirements:** FR-PAY-22
- **Depends on:** T3.12

### [ ] T3.14 - MVP scenario

- **Goal:** One API test running login -> supplier -> purchase -> partial receipts -> stock -> customer -> order -> confirm -> deliver -> payment -> outstanding.
- **Files:** `{T}/scenario/MvpScenarioTest.java`
- **Tests:**
  - `MvpScenarioTest#loginSupplierPurchaseReceiveOrderDeliverPayOutstanding` - display name "NFR-TEST-06 login supplier purchase receive order deliver pay outstanding"
- **Requirements:** NFR-TEST-06
- **Depends on:** T3.13

### M3 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests MvpScenarioTest` | pass |
| 3 | `./gradlew test --tests '*ConcurrencyTest'` | all pass |
| 4 | Mutation check: remove the account lock in `FinanceService` and the obligation lock in `PaymentService` one at a time, run `CashConcurrencyTest` / `PaymentConcurrencyTest`, restore | each test FAILS without its lock and PASSES with it |

## M4 - Production, outsourcing and workforce

In-house batches, job work with manufacturers, worker records and the matching payments, reusing the inventory and payment engines.

### [ ] T4.01 - Production schema (`V11`) and `ProductionBatch`

- **Goal:** Batches with order-item links; `PLANNED -> IN_PROGRESS -> COMPLETED`, `CANCELLED` only from `PLANNED`; reconciliation `input = output + wastage + discrepancy`.
- **Files:** `{DB}/V11__create_production_tables.sql`, `{M}/production/domain/ProductionBatch.java`, `{M}/production/domain/ProductionOrderItem.java`, `{M}/production/domain/BatchStatus.java`, `{M}/production/domain/ProductionNotReconciledException.java`, `{M}/production/infrastructure/ProductionBatchRepository.java`, `{T}/production/domain/ProductionBatchTest.java`
- **Tests:**
  - `ProductionBatchTest#complete_inputNotReconciled_throwsProductionNotReconciled` - display name "FR-PROD-03 FR-PROD-04 complete input not reconciled throws production not reconciled"
  - `ProductionBatchTest#cancel_afterStart_throwsInvalidStateTransition` - display name "FR-PROD-05 NFR-TEST-08 cancel after start throws invalid state transition"
  - `ProductionBatchTest#completed_cannotBeEdited` - display name "FR-PROD-10 completed cannot be edited"
- **Requirements:** FR-PROD-03, FR-PROD-04, FR-PROD-05, NFR-TEST-08, FR-PROD-10
- **Depends on:** T3.14

### [ ] T4.02 - Create, view, list and edit batches

- **Goal:** `POST /api/v1/production-batches` (`BAT-`), `GET`, list, `PATCH` while planned; vuda refused.
- **Files:** `{M}/production/application/ProductionService.java`, `{M}/production/api/ProductionBatchController.java`, `{M}/production/api/dto/CreateBatchRequest.java`, `{M}/production/api/dto/UpdateBatchRequest.java`, `{M}/production/api/dto/BatchResponse.java`, `{T}/production/api/ProductionBatchApiTest.java`
- **Tests:**
  - `ProductionBatchApiTest#create_withOrderLinks_returnsPlannedBatch` - display name "FR-PROD-01 FR-PROD-07 create with order links returns planned batch"
  - `ProductionBatchApiTest#create_vudaWarp_returns422InvalidProductForOperation` - display name "FR-INV-10 create vuda warp returns422 invalid product for operation"
  - `ProductionBatchApiTest#get_returnsLinksWeightsAndWorkRecords` - display name "FR-PROD-06 get returns links weights and work records"
  - `ProductionBatchApiTest#patch_plannedBatch_changesInputAndLinks` - display name "FR-PROD-11 patch planned batch changes input and links"
- **Requirements:** FR-PROD-01, FR-PROD-07, FR-INV-10, FR-PROD-06, FR-PROD-11
- **Depends on:** T4.01

### [ ] T4.03 - Start a batch

- **Goal:** `POST .../start`: `CONSUMPTION` of raw silk into `INTERNAL_WIP` (as the target warp) and `OrderService.lockForProduction` in one transaction.
- **Files:** `{M}/production/application/ProductionService.java`, `{M}/orders/application/OrderService.java`, `{T}/production/api/ProductionBatchApiTest.java`, `{T}/production/application/ProductionConcurrencyTest.java`
- **Tests:**
  - `ProductionBatchApiTest#start_consumesRawSilkAndLocksOrders` - display name "FR-PROD-02 FR-ORD-08 start consumes raw silk and locks orders"
  - `ProductionBatchApiTest#start_insufficientRawSilk_returns409AndNoStatusChange` - display name "FR-PROD-08 start insufficient raw silk returns409 and no status change"
  - `ProductionConcurrencyTest#parallelStarts_cannotOversubscribeRawSilk` - display name "FR-PROD-09 NFR-TEST-05 parallel starts cannot oversubscribe raw silk"
- **Requirements:** FR-PROD-02, FR-ORD-08, FR-PROD-08, FR-PROD-09, NFR-TEST-05
- **Depends on:** T4.02

### [ ] T4.04 - Complete and cancel a batch

- **Goal:** `POST .../complete`: `PRODUCTION_OUTPUT` and `WASTAGE` movements with an explicit discrepancy; `POST .../cancel` from `PLANNED`.
- **Files:** `{M}/production/application/ProductionService.java`, `{M}/production/api/ProductionBatchController.java`, `{M}/production/api/dto/CompleteBatchRequest.java`, `{T}/production/api/ProductionBatchApiTest.java`
- **Tests:**
  - `ProductionBatchApiTest#complete_100In94Out4Waste2Discrepancy_succeeds` - display name "FR-PROD-03 FR-PROD-08 complete 100 in94 out4 waste2 discrepancy succeeds"
  - `ProductionBatchApiTest#complete_100In94Out10Waste_returns422NotReconciled` - display name "FR-PROD-03 FR-PROD-04 complete 100 in94 out10 waste returns422 not reconciled"
  - `ProductionBatchApiTest#cancel_planned_setsCancelled` - display name "FR-PROD-05 cancel planned sets cancelled"
- **Requirements:** FR-PROD-03, FR-PROD-08, FR-PROD-04, FR-PROD-05
- **Depends on:** T4.03

### [ ] T4.05 - Outsourcing schema (`V12`) and `OutsourcingJob`

- **Goal:** Jobs with optional order links and manufacturer allocations; `CREATED -> MATERIAL_ISSUED -> PARTIALLY_RECEIVED -> COMPLETED`, `CANCELLED` from `CREATED`.
- **Files:** `{DB}/V12__create_outsourcing_tables.sql`, `{M}/outsourcing/domain/OutsourcingJob.java`, `{M}/outsourcing/domain/OutsourcingOrderItem.java`, `{M}/outsourcing/domain/JobStatus.java`, `{M}/outsourcing/domain/OutsourceReceiptExceededException.java`, `{M}/outsourcing/infrastructure/OutsourcingJobRepository.java`, `{T}/outsourcing/domain/OutsourcingJobTest.java`
- **Tests:**
  - `OutsourcingJobTest#receive_beyondIssued_throwsOutsourceReceiptExceeded` - display name "FR-OUT-03 receive beyond issued throws outsource receipt exceeded"
  - `OutsourcingJobTest#receivedPlusWastageEqualsIssued_completesJob` - display name "FR-OUT-04 NFR-TEST-08 received plus wastage equals issued completes job"
  - `OutsourcingJobTest#cancel_afterIssue_throwsInvalidStateTransition` - display name "FR-OUT-06 cancel after issue throws invalid state transition"
- **Requirements:** FR-OUT-03, FR-OUT-04, NFR-TEST-08, FR-OUT-06
- **Depends on:** T4.04

### [ ] T4.06 - Create and issue a job

- **Goal:** `POST /api/v1/outsourcing-jobs` (`JOB-`, manufacturer role, order optional), `POST .../issue`: `OUTSOURCE_ISSUE` to `EXTERNAL_WIP` and lock of linked orders.
- **Files:** `{M}/outsourcing/application/OutsourcingService.java`, `{M}/outsourcing/api/OutsourcingJobController.java`, `{M}/outsourcing/api/dto/CreateJobRequest.java`, `{M}/outsourcing/api/dto/JobResponse.java`, `{T}/outsourcing/api/OutsourcingJobApiTest.java`, `{T}/outsourcing/application/OutsourcingConcurrencyTest.java`
- **Tests:**
  - `OutsourcingJobApiTest#create_withoutOrder_isAccepted` - display name "FR-OUT-01 FR-OUT-08 create without order is accepted"
  - `OutsourcingJobApiTest#create_nonManufacturer_returns422InvalidPartyRole` - display name "FR-OUT-01 create non manufacturer returns422 invalid party role"
  - `OutsourcingJobApiTest#issue_movesRawSilkToExternalWipAndLocksLinkedOrder` - display name "FR-OUT-02 FR-OUT-08 FR-OUT-09 issue moves raw silk to external wip and locks linked order"
  - `OutsourcingConcurrencyTest#parallelIssues_cannotOversubscribeRawSilk` - display name "FR-OUT-10 NFR-TEST-05 parallel issues cannot oversubscribe raw silk"
- **Requirements:** FR-OUT-01, FR-OUT-08, FR-OUT-02, FR-OUT-09, FR-OUT-10, NFR-TEST-05
- **Depends on:** T4.05

### [ ] T4.07 - Receive, view, cancel and external WIP

- **Goal:** `POST .../receive` (`OUTSOURCE_RECEIPT` + wastage), job views, `cancel`, `GET /api/v1/inventory/external-wip`.
- **Files:** `{M}/outsourcing/application/OutsourcingService.java`, `{M}/outsourcing/api/OutsourcingJobController.java`, `{M}/outsourcing/api/dto/ReceiveJobRequest.java`, `{M}/inventory/api/InventoryController.java`, `{M}/inventory/api/dto/ExternalWipResponse.java`, `{T}/outsourcing/api/OutsourcingJobApiTest.java`, `{T}/inventory/api/InventoryApiTest.java`
- **Tests:**
  - `OutsourcingJobApiTest#receive_movesWarpToFinishedStockWithWastage` - display name "FR-OUT-03 FR-OUT-09 receive moves warp to finished stock with wastage"
  - `OutsourcingJobApiTest#receipts_cannotBeEditedOrDeleted` - display name "FR-OUT-11 receipts cannot be edited or deleted"
  - `OutsourcingJobApiTest#receive_replayWithSameKey_receivesOnce` - display name "FR-OUT-09 FR-PAY-07 receive replay with same key receives once"
  - `OutsourcingJobApiTest#get_returnsIssuedReceivedRemainingAndPayable` - display name "FR-OUT-07 get returns issued received remaining and payable"
  - `OutsourcingJobApiTest#cancel_created_setsCancelled` - display name "FR-OUT-06 cancel created sets cancelled"
  - `InventoryApiTest#externalWip_perManufacturerAndJob` - display name "FR-INV-14 external wip per manufacturer and job"
- **Requirements:** FR-OUT-03, FR-OUT-09, FR-OUT-11, FR-PAY-07, FR-OUT-07, FR-OUT-06, FR-INV-14
- **Depends on:** T4.06

### [ ] T4.08 - Manufacturer payable and payments

- **Goal:** `GET /api/v1/manufacturers/{id}/payable` = received kg x historical rate - allocations; direction `OUT` payments allocated oldest job first.
- **Files:** `{M}/finance/domain/ManufacturerPaymentAllocation.java`, `{M}/finance/application/PaymentService.java`, `{M}/outsourcing/application/ManufacturerPayableQuery.java`, `{M}/outsourcing/api/ManufacturerPayableController.java`, `{T}/finance/api/ManufacturerPaymentApiTest.java`
- **Tests:**
  - `ManufacturerPaymentApiTest#payment_allocatesOldestJobFirst` - display name "FR-PAY-09 payment allocates oldest job first"
  - `ManufacturerPaymentApiTest#payable_isReceivedKgTimesHistoricalRate` - display name "FR-OUT-05 FR-PAY-20 payable is received kg times historical rate"
- **Requirements:** FR-PAY-09, FR-OUT-05, FR-PAY-20
- **Depends on:** T4.07

### [ ] T4.09 - Workforce schema (`V13`) and work records

- **Goal:** `POST /api/v1/work-records`: rolling = hours only, warping = kg only, rate copied from the worker default or the request, optional batch link, `WORK_RECORD_MANAGE`.
- **Files:** `{DB}/V13__create_workforce_tables.sql`, `{M}/workforce/domain/WorkRecord.java`, `{M}/workforce/domain/WorkType.java`, `{M}/workforce/application/WorkRecordService.java`, `{M}/workforce/infrastructure/WorkRecordRepository.java`, `{M}/workforce/api/WorkRecordController.java`, `{M}/workforce/api/dto/RecordWorkRequest.java`, `{M}/workforce/api/dto/WorkRecordResponse.java`, `{T}/workforce/domain/WorkRecordTest.java`, `{T}/workforce/api/WorkRecordApiTest.java`
- **Tests:**
  - `WorkRecordTest#rolling_requiresHoursOnly` - display name "FR-WORK-02 rolling requires hours only"
  - `WorkRecordTest#warping_requiresQuantityOnly` - display name "FR-WORK-02 warping requires quantity only"
  - `WorkRecordApiTest#record_copiesRateFromWorkerDefaults` - display name "FR-WORK-01 FR-WORK-03 FR-PARTY-14 record copies rate from worker defaults"
  - `WorkRecordApiTest#record_nonWorker_returns422InvalidPartyRole` - display name "FR-WORK-01 record non worker returns422 invalid party role"
  - `WorkRecordApiTest#record_linkedToProductionBatch` - display name "FR-WORK-08 record linked to production batch"
  - `WorkRecordApiTest#record_withoutPermission_returns403` - display name "FR-WORK-09 record without permission returns403"
- **Requirements:** FR-WORK-02, FR-WORK-01, FR-WORK-03, FR-PARTY-14, FR-WORK-08, FR-WORK-09
- **Depends on:** T4.08

### [ ] T4.10 - Work-record list, reversal and worker payments

- **Goal:** List with filters, reversal without active allocations, no edit/delete; worker payments oldest work first; derived status; payable per payroll week.
- **Files:** `{M}/workforce/application/WorkRecordService.java`, `{M}/workforce/api/WorkRecordController.java`, `{M}/workforce/application/WorkerPayableQuery.java`, `{M}/workforce/api/WorkerPayableController.java`, `{M}/finance/domain/WorkerPaymentAllocation.java`, `{M}/finance/application/PaymentService.java`, `{T}/workforce/api/WorkRecordApiTest.java`, `{T}/finance/api/WorkerPaymentApiTest.java`
- **Tests:**
  - `WorkRecordApiTest#list_filteredByWorkerTypeAndDate` - display name "FR-WORK-05 list filtered by worker type and date"
  - `WorkRecordApiTest#reverse_withAllocations_isRejected` - display name "FR-WORK-06 reverse with allocations is rejected"
  - `WorkRecordApiTest#putOrDelete_isNotSupported` - display name "FR-WORK-07 put or delete is not supported"
  - `WorkerPaymentApiTest#payment_allocatesOldestWorkFirst` - display name "FR-PAY-10 payment allocates oldest work first"
  - `WorkerPaymentApiTest#status_derivedFromAllocations` - display name "FR-WORK-04 status derived from allocations"
  - `WorkerPaymentApiTest#payable_forPayrollWeek` - display name "FR-PAY-21 payable for payroll week"
- **Requirements:** FR-WORK-05, FR-WORK-06, FR-WORK-07, FR-PAY-10, FR-WORK-04, FR-PAY-21
- **Depends on:** T4.09

### [ ] T4.11 - Production and outsourcing scenarios

- **Goal:** API scenarios for the in-house loop and the job-work loop.
- **Files:** `{T}/scenario/ProductionScenarioTest.java`, `{T}/scenario/OutsourcingScenarioTest.java`
- **Tests:**
  - `ProductionScenarioTest#purchaseProduceDeliverPay` - display name "NFR-TEST-06 purchase produce deliver pay"
  - `OutsourcingScenarioTest#issueReceiveDeliverPayManufacturer` - display name "NFR-TEST-06 issue receive deliver pay manufacturer"
- **Requirements:** NFR-TEST-06
- **Depends on:** T4.10

### M4 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests '*ScenarioTest'` | all scenarios pass |
| 3 | Mutation check: remove the balance lock, run `ProductionConcurrencyTest` and `OutsourcingConcurrencyTest`, restore | both FAIL without the lock and PASS with it |

## M5 - Finance completion

Transfers, expenses (incl. personal drawings), account adjustments and closing, cash position, loans and chits. All money still moves only through `FinanceService`.

### [ ] T5.01 - Transfers between own accounts

- **Goal:** `POST /api/v1/finance/transfers` (`TRF-`, idempotent): one OUT + one IN, not revenue; both accounts locked in ascending id order.
- **Files:** `{M}/finance/domain/AccountTransfer.java`, `{M}/finance/application/TransferService.java`, `{M}/finance/api/TransferController.java`, `{M}/finance/api/dto/TransferRequest.java`, `{M}/finance/api/dto/TransferResponse.java`, `{T}/finance/api/TransferApiTest.java`, `{T}/finance/application/TransferConcurrencyTest.java`
- **Tests:**
  - `TransferApiTest#transfer_createsOutAndInTransactionsNotRevenue` - display name "FR-FIN-09 transfer creates out and in transactions not revenue"
  - `TransferApiTest#transfer_sameAccount_returns400` - display name "FR-FIN-09 transfer same account returns400"
  - `TransferApiTest#transfer_aboveSourceBalance_returns409InsufficientFunds` - display name "FR-FIN-09 FR-FIN-06 transfer above source balance returns409 insufficient funds"
  - `TransferApiTest#transfer_replayWithSameKey_createsOneTransfer` - display name "FR-FIN-18 transfer replay with same key creates one transfer"
  - `TransferConcurrencyTest#oppositeTransfers_doNotDeadlock` - display name "FR-FIN-10 NFR-TEST-05 opposite transfers do not deadlock"
- **Requirements:** FR-FIN-09, FR-FIN-06, FR-FIN-18, FR-FIN-10, NFR-TEST-05
- **Depends on:** T4.11

### [ ] T5.02 - Expenses and personal drawings

- **Goal:** `POST /api/v1/expenses` (`EXP-`, idempotent), list, summary, `reverse`; personal drawings excluded from business totals; fixed category list.
- **Files:** `{M}/finance/domain/Expense.java`, `{M}/finance/domain/ExpenseCategory.java`, `{M}/finance/domain/ExpenseKind.java`, `{M}/finance/application/ExpenseService.java`, `{M}/finance/infrastructure/ExpenseRepository.java`, `{M}/finance/api/ExpenseController.java`, `{M}/finance/api/dto/RecordExpenseRequest.java`, `{M}/finance/api/dto/ExpenseResponse.java`, `{T}/finance/api/ExpenseApiTest.java`
- **Tests:**
  - `ExpenseApiTest#record_createsOutTransaction` - display name "FR-FIN-11 record creates out transaction"
  - `ExpenseApiTest#personalDrawing_excludedFromBusinessTotals` - display name "FR-FIN-12 personal drawing excluded from business totals"
  - `ExpenseApiTest#list_andSummaryByCategoryAndKind` - display name "FR-FIN-13 list and summary by category and kind"
  - `ExpenseApiTest#reverse_addsCompensatingInTransaction` - display name "FR-FIN-14 FR-FIN-17 reverse adds compensating in transaction"
  - `ExpenseApiTest#record_replayWithSameKey_createsOneExpense` - display name "FR-FIN-18 record replay with same key creates one expense"
  - `ExpenseApiTest#reverse_replayWithSameKey_returnsStoredResponse` - display name "FR-FIN-18 reverse replay with same key returns stored response"
  - `ExpenseApiTest#unknownCategory_returns400MalformedRequest` - display name "FR-FIN-19 unknown category returns400 malformed request"
- **Requirements:** FR-FIN-11, FR-FIN-12, FR-FIN-13, FR-FIN-14, FR-FIN-17, FR-FIN-18, FR-FIN-19
- **Depends on:** T5.01

### [ ] T5.03 - Account adjustments, closing and cash position

- **Goal:** `POST /finance/accounts/{id}/adjustments` (idempotent): an `ADJUSTMENT` transaction `IN` or `OUT` with no reference, `FINANCE_MANAGE`, reason required, never below zero, audited `ACCOUNT_ADJUSTED`; `POST .../close` only at 0.00; `GET /api/v1/finance/cash-position`.
- **Files:** `{M}/finance/application/FinancialAccountService.java`, `{M}/finance/api/FinancialAccountController.java`, `{M}/finance/api/dto/AccountAdjustmentRequest.java`, `{M}/finance/application/CashPositionQuery.java`, `{M}/finance/api/CashPositionController.java`, `{T}/finance/api/FinancialAccountApiTest.java`, `{T}/finance/api/CashPositionApiTest.java`, `{T}/finance/api/FinancialTransactionApiTest.java`
- **Tests:**
  - `FinancialAccountApiTest#adjustment_requiresReasonAndPermission` - display name "FR-FIN-15 adjustment requires reason and permission"
  - `FinancialAccountApiTest#adjustment_writesAdjustmentTransactionAndAccountAdjustedAudit` - display name "FR-FIN-15 NFR-OBS-06 adjustment writes adjustment transaction and account adjusted audit"
  - `FinancialAccountApiTest#adjustmentDecreaseBelowZero_returns409InsufficientFunds` - display name "FR-FIN-15 FR-FIN-06 adjustment decrease below zero returns409 insufficient funds"
  - `FinancialAccountApiTest#adjustment_replayWithSameKey_returnsStoredResponse` - display name "FR-FIN-18 adjustment replay with same key returns stored response"
  - `FinancialAccountApiTest#close_withNonZeroBalance_isRejected` - display name "FR-FIN-16 close with non zero balance is rejected"
  - `CashPositionApiTest#cashPosition_cashBanksAndTotal` - display name "FR-FIN-08 cash position cash banks and total"
  - `FinancialTransactionApiTest#manualEntryOnly_noStatementImportRoute` - display name "FR-FIN-20 manual entry only no statement import route"
- **Requirements:** FR-FIN-15, NFR-OBS-06, FR-FIN-06, FR-FIN-18, FR-FIN-16, FR-FIN-08, FR-FIN-20
- **Depends on:** T5.02

### [ ] T5.04 - Loans

- **Goal:** Loans from `LENDER` parties (`LON-`, `interest_notes`): received, principal repayment (not above outstanding), interest (expense), close at 0, reverse a payment (audited `LOAN_PAYMENT_REVERSED`); `POST /loans/existing` stores `repaid_before_go_live` without any cash transaction; the four money routes are idempotent.
- **Files:** `{M}/loans/domain/Loan.java`, `{M}/loans/domain/LoanPayment.java`, `{M}/loans/application/LoanService.java`, `{M}/loans/infrastructure/LoanRepository.java`, `{M}/loans/api/LoanController.java`, `{M}/loans/api/dto/CreateLoanRequest.java`, `{M}/loans/api/dto/ExistingLoanRequest.java`, `{M}/loans/api/dto/LoanPaymentRequest.java`, `{M}/loans/api/dto/LoanResponse.java`, `{T}/loans/api/LoanApiTest.java`
- **Tests:**
  - `LoanApiTest#create_recordsLoanReceivedInTransaction` - display name "FR-LOAN-01 create records loan received in transaction"
  - `LoanApiTest#create_storesInterestNotes` - display name "FR-LOAN-01 create stores interest notes"
  - `LoanApiTest#create_forNonLender_returns422InvalidPartyRole` - display name "FR-LOAN-01 FR-PARTY-16 create for non lender returns422 invalid party role"
  - `LoanApiTest#registerExisting_storesRepaidBeforeGoLiveWithoutTransaction` - display name "FR-LOAN-02 register existing stores repaid before go live without transaction"
  - `LoanApiTest#registerExisting_outstandingAbovePrincipal_returns400` - display name "FR-LOAN-02 register existing outstanding above principal returns400"
  - `LoanApiTest#principalRepayment_aboveOutstanding_isRejected` - display name "FR-LOAN-03 principal repayment above outstanding is rejected"
  - `LoanApiTest#interestPayment_isExpenseNotPrincipal` - display name "FR-LOAN-04 FR-LOAN-08 interest payment is expense not principal"
  - `LoanApiTest#outstanding_subtractsRepaidBeforeGoLiveAndRepayments` - display name "FR-LOAN-05 outstanding subtracts repaid before go live and repayments"
  - `LoanApiTest#close_withOutstanding_isRejected` - display name "FR-LOAN-06 close with outstanding is rejected"
  - `LoanApiTest#reversePayment_addsCompensatingTransactionAndAuditEntry` - display name "FR-LOAN-07 NFR-OBS-06 reverse payment adds compensating transaction and audit entry"
  - `LoanApiTest#moneyWrites_replayWithSameKey_returnStoredResponse` - display name "FR-FIN-18 FR-PAY-07 money writes replay with same key return stored response"
  - `LoanApiTest#putOrDelete_isNotSupported` - display name "FR-LOAN-09 put or delete is not supported"
  - `LoanApiTest#interestIsEnteredManually` - display name "FR-LOAN-10 interest is entered manually"
- **Requirements:** FR-LOAN-01, FR-PARTY-16, FR-LOAN-02, FR-LOAN-03, FR-LOAN-04, FR-LOAN-08, FR-LOAN-05, FR-LOAN-06, FR-LOAN-07, NFR-OBS-06, FR-FIN-18, FR-PAY-07, FR-LOAN-09, FR-LOAN-10
- **Depends on:** T5.03

### [ ] T5.05 - Chits

- **Goal:** Chits (`CHT-`, `prior_contributions_count`/`_amount` for chits running at go-live, no cash transaction for them): contributions and payout cash only, organiser payments as `CHIT_ORGANISER_PAYMENT`, derived summary, close, reverse an entry (audited `CHIT_ENTRY_REVERSED`; reversing the payout returns `MATURED -> ACTIVE`); the four money routes are idempotent.
- **Files:** `{M}/chits/domain/Chit.java`, `{M}/chits/domain/ChitEntry.java`, `{M}/chits/application/ChitService.java`, `{M}/chits/infrastructure/ChitRepository.java`, `{M}/chits/domain/ChitStatus.java`, `{M}/chits/api/ChitController.java`, `{M}/chits/api/dto/CreateChitRequest.java`, `{M}/chits/api/dto/ChitEntryRequest.java`, `{M}/chits/api/dto/ChitResponse.java`, `{T}/chits/api/ChitApiTest.java`, `{T}/chits/domain/ChitTest.java`
- **Tests:**
  - `ChitApiTest#create_returnsActiveChit` - display name "FR-CHIT-01 create returns active chit"
  - `ChitApiTest#contribution_nonCash_returns422InvalidPaymentMethod` - display name "FR-CHIT-02 contribution non cash returns422 invalid payment method"
  - `ChitApiTest#payout_cashOnly_recordsInTransaction` - display name "FR-CHIT-03 payout cash only records in transaction"
  - `ChitApiTest#organiserPayment_recordedSeparately` - display name "FR-CHIT-04 organiser payment recorded separately"
  - `ChitApiTest#organiserPayment_createsChitOrganiserPaymentOutTransaction` - display name "FR-CHIT-04 organiser payment creates chit organiser payment out transaction"
  - `ChitApiTest#summary_derivedFromEntries` - display name "FR-CHIT-05 summary derived from entries"
  - `ChitApiTest#close_fromActiveOrMatured` - display name "FR-CHIT-06 close from active or matured"
  - `ChitApiTest#create_withPriorContributions_countsThemWithoutCashTransaction` - display name "FR-CHIT-08 create with prior contributions counts them without cash transaction"
  - `ChitApiTest#priorContributionsCountAboveDuration_returns400` - display name "FR-CHIT-08 prior contributions count above duration returns400"
  - `ChitApiTest#reverseEntry_addsCompensatingTransactionAndAuditEntry` - display name "FR-CHIT-09 NFR-OBS-06 reverse entry adds compensating transaction and audit entry"
  - `ChitApiTest#reversePayout_returnsChitToActive` - display name "FR-CHIT-09 reverse payout returns chit to active"
  - `ChitTest#payoutReversal_movesMaturedBackToActiveOnly` - display name "FR-CHIT-09 NFR-TEST-08 payout reversal moves matured back to active only"
  - `ChitApiTest#moneyWrites_replayWithSameKey_returnStoredResponse` - display name "FR-PAY-07 money writes replay with same key return stored response"
  - `ChitApiTest#amountsAreEnteredWithoutBiddingCalculation` - display name "FR-CHIT-10 amounts are entered without bidding calculation"
- **Requirements:** FR-CHIT-01, FR-CHIT-02, FR-CHIT-03, FR-CHIT-04, FR-CHIT-05, FR-CHIT-06, FR-CHIT-08, FR-CHIT-09, NFR-OBS-06, NFR-TEST-08, FR-PAY-07, FR-CHIT-10
- **Depends on:** T5.04

### [ ] T5.06 - Finance scenario and invariant property test

- **Goal:** Scenario: expenses, transfer, loan, chit, reversal, cash position; random sequences of operations never break an invariant; a guard test that the `@Idempotent` routes are exactly the 18 of `03` section 7.2.
- **Files:** `{T}/scenario/FinanceScenarioTest.java`, `{T}/scenario/InvariantPropertyTest.java`, `{T}/architecture/IdempotencyRouteCoverageTest.java`
- **Tests:**
  - `FinanceScenarioTest#transferExpenseLoanChitReversalCashPosition` - display name "NFR-TEST-06 transfer expense loan chit reversal cash position"
  - `IdempotencyRouteCoverageTest#exactlyTheEighteenListedRoutes_requireIdempotencyKey` - display name "FR-PAY-07 FR-PROC-19 FR-FIN-18 exactly the eighteen listed routes require idempotency key"
  - `InvariantPropertyTest#randomOperationSequences_keepAllInvariants` - display name "NFR-REL-01 random operation sequences keep all invariants"
- **Requirements:** NFR-TEST-06, FR-PAY-07, FR-PROC-19, FR-FIN-18, NFR-REL-01
- **Depends on:** T5.05

### M5 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests TransferConcurrencyTest --tests InvariantPropertyTest` | pass; no deadlock reported by PostgreSQL |
| 3 | Mutation check: lock transfer accounts in request order instead of ascending id, run `TransferConcurrencyTest`, restore | the test FAILS (deadlock or timeout) and PASSES after restoring |

## M6 - Reports, dashboard, notifications and documents

Read-only SQL projections for the owner, due-date reminders sent after commit, and bill uploads.

### [ ] T6.01 - Owner dashboard

- **Goal:** `GET /api/v1/dashboard/summary` in one call; obligations shown separately from available money; upcoming and overdue supplier dues computed live. Adds the final dashboard assertions to `MvpScenarioTest` and `ProductionScenarioTest`.
- **Files:** `{M}/reporting/application/DashboardQuery.java`, `{M}/reporting/api/DashboardController.java`, `{M}/reporting/api/dto/DashboardResponse.java`, `{T}/scenario/MvpScenarioTest.java`, `{T}/scenario/ProductionScenarioTest.java`, `{T}/reporting/api/DashboardApiTest.java`
- **Tests:**
  - `DashboardApiTest#summary_returnsCashReceivablesPayablesStockAndDues` - display name "FR-RPT-01 FR-NTF-06 summary returns cash receivables payables stock and dues"
  - `DashboardApiTest#obligations_neverAddedToAvailableMoney` - display name "FR-RPT-02 obligations never added to available money"
- **Requirements:** FR-RPT-01, FR-NTF-06, FR-RPT-02
- **Depends on:** T5.06

### [ ] T6.02 - Customer reports

- **Goal:** Customer outstanding report and customer statement (opening, debits, credits, closing).
- **Files:** `{M}/reporting/application/CustomerReportQuery.java`, `{M}/reporting/api/CustomerReportController.java`, `{T}/reporting/api/CustomerOutstandingReportApiTest.java`, `{T}/reporting/api/CustomerStatementApiTest.java`
- **Tests:**
  - `CustomerOutstandingReportApiTest#report_sortedByOutstanding` - display name "FR-RPT-03 report sorted by outstanding"
  - `CustomerStatementApiTest#statement_openingDebitsCreditsClosing` - display name "FR-RPT-04 statement opening debits credits closing"
- **Requirements:** FR-RPT-03, FR-RPT-04
- **Depends on:** T6.01

### [ ] T6.03 - Supplier and purchase reports

- **Goal:** Supplier payables with ageing buckets; purchases grouped by supplier and product type.
- **Files:** `{M}/reporting/application/SupplierReportQuery.java`, `{M}/reporting/api/SupplierReportController.java`, `{T}/reporting/api/SupplierPayablesReportApiTest.java`, `{T}/reporting/api/PurchaseReportApiTest.java`
- **Tests:**
  - `SupplierPayablesReportApiTest#report_withAgeingBuckets` - display name "FR-RPT-05 report with ageing buckets"
  - `PurchaseReportApiTest#report_groupedBySupplierAndProduct` - display name "FR-RPT-06 report grouped by supplier and product"
- **Requirements:** FR-RPT-05, FR-RPT-06
- **Depends on:** T6.02

### [ ] T6.04 - Sales and expense reports

- **Goal:** Sales per period/customer/product (revenue at delivery); expenses by category, kind and month with loan interest; no profit figures yet.
- **Files:** `{M}/reporting/application/SalesReportQuery.java`, `{M}/reporting/application/ExpenseReportQuery.java`, `{M}/reporting/api/SalesReportController.java`, `{M}/reporting/api/ExpenseReportController.java`, `{T}/reporting/api/SalesReportApiTest.java`, `{T}/reporting/api/ExpenseReportApiTest.java`
- **Tests:**
  - `SalesReportApiTest#report_revenueRecognisedAtDelivery` - display name "FR-RPT-07 report revenue recognised at delivery"
  - `ExpenseReportApiTest#report_byCategoryKindAndMonthWithInterest` - display name "FR-RPT-08 report by category kind and month with interest"
  - `SalesReportApiTest#report_hasNoProfitFiguresInFirstRelease` - display name "FR-RPT-17 report has no profit figures in first release"
- **Requirements:** FR-RPT-07, FR-RPT-08, FR-RPT-17
- **Depends on:** T6.03

### [ ] T6.05 - Inventory, production and outsourcing reports

- **Goal:** Opening/movements/closing per product; per-batch input/output/wastage/discrepancy with wastage %; per-manufacturer job report.
- **Files:** `{M}/reporting/application/OperationsReportQuery.java`, `{M}/reporting/api/OperationsReportController.java`, `{T}/reporting/api/InventoryMovementReportApiTest.java`, `{T}/reporting/api/ProductionReportApiTest.java`, `{T}/reporting/api/OutsourcingReportApiTest.java`
- **Tests:**
  - `InventoryMovementReportApiTest#report_openingMovementsClosing` - display name "FR-RPT-09 report opening movements closing"
  - `ProductionReportApiTest#report_showsWastagePercentagePerBatch` - display name "FR-RPT-10 FR-PROD-12 report shows wastage percentage per batch"
  - `OutsourcingReportApiTest#report_perManufacturerAndJob` - display name "FR-RPT-11 FR-OUT-12 report per manufacturer and job"
- **Requirements:** FR-RPT-09, FR-RPT-10, FR-PROD-12, FR-RPT-11, FR-OUT-12
- **Depends on:** T6.04

### [ ] T6.06 - Cash/bank, worker, loans and chits reports

- **Goal:** Per-account opening/in/out/closing; worker payable split rolling/warping; loans and chits with chit flows classed as investment.
- **Files:** `{M}/reporting/application/FinanceReportQuery.java`, `{M}/reporting/api/FinanceReportController.java`, `{T}/reporting/api/CashBankReportApiTest.java`, `{T}/reporting/api/WorkerPayableReportApiTest.java`, `{T}/reporting/api/LoansAndChitsReportApiTest.java`
- **Tests:**
  - `CashBankReportApiTest#report_openingInOutClosingPerAccount` - display name "FR-RPT-12 report opening in out closing per account"
  - `WorkerPayableReportApiTest#report_splitsRollingAndWarping` - display name "FR-RPT-13 FR-WORK-10 report splits rolling and warping"
  - `LoansAndChitsReportApiTest#report_loansAndChits` - display name "FR-RPT-14 report loans and chits"
  - `LoansAndChitsReportApiTest#chitMovements_areInvestmentNotExpense` - display name "FR-CHIT-07 chit movements are investment not expense"
- **Requirements:** FR-RPT-12, FR-RPT-13, FR-WORK-10, FR-RPT-14, FR-CHIT-07
- **Depends on:** T6.05

### [ ] T6.07 - Report consistency and CSV export

- **Goal:** Report totals equal the detail endpoints; `format=csv` on reports and main lists.
- **Files:** `{M}/reporting/api/CsvExporter.java`, `{M}/reporting/api/CsvMessageConverter.java`, `{T}/reporting/application/ReportConsistencyIntegrationTest.java`, `{T}/reporting/api/CsvExportApiTest.java`
- **Tests:**
  - `ReportConsistencyIntegrationTest#reportTotals_equalDetailEndpointTotals` - display name "FR-RPT-15 report totals equal detail endpoint totals"
  - `CsvExportApiTest#export_returnsCsvWithHeaders` - display name "FR-RPT-16 NFR-DATA-07 export returns csv with headers"
- **Requirements:** FR-RPT-15, FR-RPT-16, NFR-DATA-07
- **Depends on:** T6.06

### [ ] T6.08 - Notifications schema (`V14`), configuration and daily job

- **Goal:** `notification_configurations` (`days_before_due` default 3, `overdue_repeat_days` default 1), `GET/PUT /api/v1/notifications/configurations`, 08:00 IST job creating notifications with `reminder_kind` `DUE_SOON` / `DUE_TODAY` / `OVERDUE` and a unique `dedupe_key` (`SUPPLIER_DUE:<purchaseId>:<kind>`, overdue repeats add the date), resolved when paid.
- **Files:** `{DB}/V14__create_documents_and_notifications_tables.sql`, `{M}/notifications/domain/Notification.java`, `{M}/notifications/domain/NotificationConfiguration.java`, `{M}/notifications/application/SupplierDueReminderJob.java`, `{M}/notifications/application/NotificationService.java`, `{M}/notifications/infrastructure/NotificationRepository.java`, `{M}/notifications/api/NotificationConfigurationController.java`, `{T}/notifications/api/NotificationConfigurationApiTest.java`, `{T}/notifications/application/SupplierDueReminderJobIntegrationTest.java`, `{T}/notifications/domain/NotificationLifecycleTest.java`
- **Tests:**
  - `NotificationConfigurationApiTest#put_updatesDaysBeforeDue` - display name "FR-NTF-01 put updates days before due"
  - `SupplierDueReminderJobIntegrationTest#job_createsDueSoonDueTodayAndOverdueReminderKinds` - display name "FR-NTF-02 job creates due soon due today and overdue reminder kinds"
  - `SupplierDueReminderJobIntegrationTest#jobRunTwiceSameDay_createsNoDuplicateByDedupeKey` - display name "FR-NTF-02 job run twice same day creates no duplicate by dedupe key"
  - `SupplierDueReminderJobIntegrationTest#overdueReminder_repeatsEveryOverdueRepeatDays` - display name "FR-NTF-01 FR-NTF-02 overdue reminder repeats every overdue repeat days"
  - `NotificationLifecycleTest#paidPurchase_resolvesPendingNotification` - display name "FR-NTF-03 paid purchase resolves pending notification"
- **Requirements:** FR-NTF-01, FR-NTF-02, FR-NTF-03
- **Depends on:** T6.07

### [ ] T6.09 - Notification dispatch and list

- **Goal:** `NotificationProvider` (log implementation) called after commit with retry; provider failure never rolls back; `GET /api/v1/notifications`.
- **Files:** `{M}/notifications/application/NotificationProvider.java`, `{M}/notifications/infrastructure/LoggingNotificationProvider.java`, `{M}/notifications/application/NotificationDispatcher.java`, `{M}/notifications/api/NotificationController.java`, `{T}/notifications/application/NotificationDispatchIntegrationTest.java`, `{T}/notifications/api/NotificationApiTest.java`
- **Tests:**
  - `NotificationDispatchIntegrationTest#providerFailure_doesNotRollBackBusinessTransaction` - display name "FR-NTF-04 NFR-REL-05 provider failure does not roll back business transaction"
  - `NotificationApiTest#list_filteredByStatusAndType` - display name "FR-NTF-05 list filtered by status and type"
  - `NotificationDispatchIntegrationTest#onlySupplierDueTypeIsScheduledInFirstRelease` - display name "FR-NTF-07 only supplier due type is scheduled in first release"
  - `NotificationDispatchIntegrationTest#loggingProviderIsTheOnlyProvider` - display name "FR-NTF-08 logging provider is the only provider"
- **Requirements:** FR-NTF-04, NFR-REL-05, FR-NTF-05, FR-NTF-07, FR-NTF-08
- **Depends on:** T6.08

### [ ] T6.10 - Documents

- **Goal:** `POST /api/v1/documents` (multipart, max 10 MB, generated storage id, sanitised name, optional `supersedesDocumentId` for a corrected file), list, download; never overwritten or deleted; no effect on referenced records.
- **Files:** `{M}/documents/domain/Document.java`, `{M}/documents/application/DocumentService.java`, `{M}/documents/application/FileStorage.java`, `{M}/documents/infrastructure/LocalFileStorage.java`, `{M}/documents/infrastructure/DocumentRepository.java`, `{M}/documents/api/DocumentController.java`, `{R}/application.yml`, `{T}/documents/api/DocumentApiTest.java`
- **Tests:**
  - `DocumentApiTest#upload_storesUnderGeneratedId` - display name "FR-DOC-01 FR-DOC-02 upload stores under generated id"
  - `DocumentApiTest#upload_pathTraversalName_isSanitised` - display name "FR-DOC-02 upload path traversal name is sanitised"
  - `DocumentApiTest#upload_over10Mb_isRejected` - display name "NFR-SEC-03 upload over10 mb is rejected"
  - `DocumentApiTest#list_andDownloadContent` - display name "FR-DOC-03 list and download content"
  - `DocumentApiTest#overwriteOrDelete_isNotSupported` - display name "FR-DOC-04 overwrite or delete is not supported"
  - `DocumentApiTest#upload_supersedingDocument_keepsBothListed` - display name "FR-DOC-04 upload superseding document keeps both listed"
  - `DocumentApiTest#supersedesUnknownDocument_returns404` - display name "FR-DOC-04 supersedes unknown document returns404"
  - `DocumentApiTest#upload_doesNotChangeReferencedRecord` - display name "FR-DOC-05 upload does not change referenced record"
  - `DocumentApiTest#salesBillGeneration_isNotOfferedInFirstRelease` - display name "FR-DOC-06 sales bill generation is not offered in first release"
- **Requirements:** FR-DOC-01, FR-DOC-02, NFR-SEC-03, FR-DOC-03, FR-DOC-04, FR-DOC-05, FR-DOC-06
- **Depends on:** T6.09

### M6 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL; 0 test failures |
| 2 | `./gradlew test --tests ReportConsistencyIntegrationTest --tests DashboardApiTest` | pass |

## M7 - Hardening

Coverage and audit gates, security controls, audit log and immutability triggers, observability, performance, failure drills, API contract freeze.

### [ ] T7.01 - Coverage gate and requirement traceability

- **Goal:** JaCoCo 0.80 line rule on `application`/`domain`; JUnit test that every MVP requirement ID appears in a display name; every `ErrorCode` asserted somewhere; display names added to the Milestone 0 tests; random test order run.
- **Files:** `build.gradle`, `{T}/architecture/RequirementTraceabilityTest.java`, `{T}/architecture/ErrorCodeCoverageTest.java`, `{T}/architecture/NoInMemoryDatabaseTest.java`, `src/test/resources/junit-platform.properties`
- **Tests:**
  - check: `./gradlew jacocoTestCoverageVerification` - proves NFR-TEST-02
  - `RequirementTraceabilityTest#everyMvpRequirementId_appearsInATestDisplayName` - display name "NFR-TEST-03 every mvp requirement id appears in atest display name"
  - `ErrorCodeCoverageTest#everyErrorCode_isAssertedByAtLeastOneTest` - display name "NFR-TEST-04 every error code is asserted by at least one test"
  - `NoInMemoryDatabaseTest#testClasspath_hasNoH2OrHsqldb` - display name "NFR-TEST-01 test classpath has no h2 or hsqldb"
  - check: `./gradlew test -PrandomOrder` - proves NFR-TEST-09
- **Requirements:** NFR-TEST-02, NFR-TEST-03, NFR-TEST-04, NFR-TEST-01, NFR-TEST-09
- **Depends on:** T6.10

### [ ] T7.02 - Dependency audit

- **Goal:** OWASP dependency-check plugin 13.0.0, `failBuildOnCVSS = 7.0`, suppressions file with reasons and expiry.
- **Files:** `build.gradle`, `config/dependency-check-suppressions.xml`
- **Tests:**
  - check: `./gradlew dependencyCheckAnalyze` - proves NFR-SEC-07
- **Requirements:** NFR-SEC-07
- **Depends on:** T7.01

### [ ] T7.03 - Headers, CORS, request limits, rate limit

- **Goal:** nosniff/DENY/no-referrer/no-store headers, no CORS, 1 MB JSON limit; rate-limit filter: 10 requests/minute per IP on `/api/v1/auth/**`, 300/minute per user (per IP when unauthenticated) elsewhere, 429 `RATE_LIMITED` with `Retry-After`.
- **Files:** `{M}/shared/security/SecurityConfig.java`, `{M}/shared/security/RateLimitFilter.java`, `{M}/shared/error/ErrorCode.java`, `{R}/application.yml`, `{T}/shared/security/SecurityHeadersApiTest.java`, `{T}/shared/security/RequestLimitApiTest.java`, `{T}/shared/security/RateLimitApiTest.java`
- **Tests:**
  - `SecurityHeadersApiTest#responses_carryNosniffFrameDenyNoStore` - display name "NFR-SEC-04 responses carry nosniff frame deny no store"
  - `SecurityHeadersApiTest#corsPreflight_isRejected` - display name "NFR-SEC-05 cors preflight is rejected"
  - `RequestLimitApiTest#jsonBodyOver1Mb_isRejected` - display name "NFR-SEC-03 json body over1 mb is rejected"
  - `RateLimitApiTest#perUser_over300RequestsPerMinute_returns429RateLimited` - display name "NFR-SEC-11 per user over300 requests per minute returns429 rate limited"
  - `RateLimitApiTest#authEndpoints_over10RequestsPerMinutePerIp_return429WithRetryAfter` - display name "NFR-SEC-11 auth endpoints over10 requests per minute per ip return429 with retry after"
  - `RateLimitApiTest#normalUseBelowLimit_isNeverRejected` - display name "NFR-SEC-11 normal use below limit is never rejected"
- **Requirements:** NFR-SEC-04, NFR-SEC-05, NFR-SEC-03, NFR-SEC-11
- **Depends on:** T7.02

### [ ] T7.04 - Sensitive data, SQL safety and database roles

- **Goal:** No password/token/full account number in logs or responses; no native SQL built from input; app DB role cannot update or delete ledger rows.
- **Files:** `{T}/shared/security/SensitiveDataLogTest.java`, `{T}/architecture/SqlSafetyTest.java`, `../deploy/postgres/init/01-roles.sql`, `{T}/shared/persistence/DatabaseRoleIntegrationTest.java`
- **Tests:**
  - `SensitiveDataLogTest#loginAndPayment_logsContainNoPasswordTokenOrAccountNumber` - display name "NFR-SEC-06 FR-AUTH-08 login and payment logs contain no password token or account number"
  - `SqlSafetyTest#noNativeQueryConcatenatesInput` - display name "NFR-SEC-10 no native query concatenates input"
  - `DatabaseRoleIntegrationTest#appRole_cannotUpdateOrDeleteLedgerRows` - display name "NFR-SEC-08 app role cannot update or delete ledger rows"
- **Requirements:** NFR-SEC-06, FR-AUTH-08, NFR-SEC-10, NFR-SEC-08
- **Depends on:** T7.03

### [ ] T7.05 - Immutability triggers (`V15`)

- **Goal:** Function `prevent_row_modification()` and its `UPDATE`/`DELETE` triggers on every append-only table, including `audit_log` (`04-DATA-MODEL.md` section 14); `DatabaseCleaner` keeps using `TRUNCATE`.
- **Files:** `{DB}/V15__create_immutability_triggers.sql`, `{T}/support/DatabaseCleaner.java`, `{T}/shared/persistence/ImmutabilityTriggerIntegrationTest.java`
- **Tests:**
  - `ImmutabilityTriggerIntegrationTest#updateOrDeleteOfLedgerRow_isRejectedByTrigger` - display name "FR-PROC-14 FR-OUT-11 NFR-OBS-06 update or delete of ledger row is rejected by trigger"
- **Requirements:** FR-PROC-14, FR-OUT-11, NFR-OBS-06
- **Depends on:** T7.04

### [ ] T7.06 - Observability

- **Goal:** JSON request logs with request id and user, `X-Request-Id` echo/generation, health UP only with DB (details hidden in prod), Prometheus on management port 8081.
- **Files:** `{M}/shared/observability/RequestIdFilter.java`, `{M}/shared/observability/RequestLoggingFilter.java`, `{R}/application.yml`, `{R}/application-prod.yml`, `build.gradle`, `{T}/shared/observability/RequestIdFilterApiTest.java`, `{T}/shared/observability/StructuredLogTest.java`, `{T}/shared/observability/HealthApiTest.java`, `{T}/shared/observability/MetricsApiTest.java`
- **Tests:**
  - `RequestIdFilterApiTest#requestId_isEchoedAndGeneratedWhenMissing` - display name "NFR-OBS-02 request id is echoed and generated when missing"
  - `StructuredLogTest#requestLogLine_hasIdMethodPathStatusDurationUser` - display name "NFR-OBS-01 request log line has id method path status duration user"
  - `HealthApiTest#health_isUpOnlyWhenDatabaseReachable` - display name "NFR-OBS-03 health is up only when database reachable"
  - `HealthApiTest#prodProfile_hidesHealthDetails` - display name "NFR-OBS-03 prod profile hides health details"
  - `MetricsApiTest#prometheusEndpoint_servedOnManagementPortOnly` - display name "NFR-OBS-04 prometheus endpoint served on management port only"
- **Requirements:** NFR-OBS-02, NFR-OBS-01, NFR-OBS-03, NFR-OBS-04
- **Depends on:** T7.05

### [ ] T7.07 - Static safety checks

- **Goal:** Source scans: no `Instant.now()`/`LocalDate.now()` without the clock, no `double`/`float` for money or weight, no derived columns, every FK indexed, no purge job or delete endpoint, migrations on previous-release data.
- **Files:** `{T}/architecture/ClockUsageTest.java`, `{T}/architecture/ExactTypesTest.java`, `{T}/shared/persistence/DerivedValuesTest.java`, `{T}/shared/persistence/ForeignKeyIndexIntegrationTest.java`, `{T}/architecture/RetentionTest.java`, `{T}/shared/persistence/MigrationIntegrationTest.java`
- **Tests:**
  - `ClockUsageTest#businessCodeNeverCallsNowWithoutClock` - display name "NFR-REL-06 business code never calls now without clock"
  - `ExactTypesTest#noDoubleOrFloatInDomainOrApplication` - display name "NFR-DATA-01 no double or float in domain or application"
  - `DerivedValuesTest#noDerivedColumnsInSchema` - display name "NFR-DATA-02 no derived columns in schema"
  - `ForeignKeyIndexIntegrationTest#everyForeignKeyColumn_hasIndex` - display name "NFR-DATA-05 every foreign key column has index"
  - `RetentionTest#noScheduledPurgeAndNoDeleteEndpoints` - display name "NFR-DATA-03 no scheduled purge and no delete endpoints"
  - `MigrationIntegrationTest#migrationsApplyOnDataFromPreviousRelease` - display name "NFR-REL-07 migrations apply on data from previous release"
- **Requirements:** NFR-REL-06, NFR-DATA-01, NFR-DATA-02, NFR-DATA-05, NFR-DATA-03, NFR-REL-07
- **Depends on:** T7.06

### [ ] T7.08 - Reference dataset and performance tests

- **Goal:** Generator for 5 years (>= 100000 movements and transactions); p95 budgets for reads, dashboard, writes, reports, CSV; 20-client mixed load; startup time. Tagged `performance`.
- **Files:** `{T}/performance/ReferenceDataGenerator.java`, `{T}/performance/PerformanceSmokeTest.java`, `{T}/performance/StartupTimeTest.java`, `build.gradle`
- **Tests:**
  - `PerformanceSmokeTest#referenceDataset_generatedWithConsistentBalances` - display name "NFR-PERF-01 reference dataset generated with consistent balances"
  - `PerformanceSmokeTest#reads_withinP95Of300ms` - display name "NFR-PERF-02 reads within p95 of300ms"
  - `PerformanceSmokeTest#dashboard_withinP95Of500ms` - display name "NFR-PERF-03 dashboard within p95 of500ms"
  - `PerformanceSmokeTest#writes_withinP95Of500ms` - display name "NFR-PERF-04 writes within p95 of500ms"
  - `PerformanceSmokeTest#twentyClientsMixedLoad_noUnexpectedFailures` - display name "NFR-PERF-05 twenty clients mixed load no unexpected failures"
  - `PerformanceSmokeTest#yearReportAndCsvExport_withinBudgets` - display name "NFR-PERF-06 year report and csv export within budgets"
  - `StartupTimeTest#emptyDatabase_migratesAndStartsWithin60Seconds` - display name "NFR-PERF-08 empty database migrates and starts within60 seconds"
- **Requirements:** NFR-PERF-01, NFR-PERF-02, NFR-PERF-03, NFR-PERF-04, NFR-PERF-05, NFR-PERF-06, NFR-PERF-08
- **Depends on:** T7.07

### [ ] T7.09 - Failure drills and error-message contract

- **Goal:** Database restart mid-request fails cleanly and a retry succeeds; every problem `detail` is plain text up to 200 characters without internal names.
- **Files:** `{T}/shared/persistence/FailureDrillIntegrationTest.java`, `{T}/shared/error/ErrorMessageContractTest.java`
- **Tests:**
  - `FailureDrillIntegrationTest#databaseRestartMidRequest_failsCleanlyAndRetrySucceeds` - display name "NFR-REL-05 database restart mid request fails cleanly and retry succeeds"
  - `ErrorMessageContractTest#everyProblemDetail_isShortPlainTextWithoutInternalNames` - display name "NFR-USE-04 every problem detail is short plain text without internal names"
- **Requirements:** NFR-REL-05, NFR-USE-04
- **Depends on:** T7.08

### [ ] T7.10 - API contract freeze

- **Goal:** Export `openapi.json` into the repository and fail the build when the live spec differs without the file being updated.
- **Files:** `openapi.json`, `{T}/shared/config/OpenApiExportTest.java`
- **Tests:**
  - `OpenApiExportTest#exportedSpec_matchesCommittedOpenapiJson` - display name "exported spec matches committed openapi json"
- **Requirements:** none (supporting task)
- **Depends on:** T7.09

### M7 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `./gradlew build` | BUILD SUCCESSFUL including `jacocoTestCoverageVerification` (line coverage >= 80% on application/domain) |
| 2 | `./gradlew dependencyCheckAnalyze` | no dependency with CVSS >= 7.0 |
| 3 | `./gradlew test -Pperformance --tests '*Performance*' --tests StartupTimeTest` | all budgets met |
| 4 | `./gradlew test -PrandomOrder` | 0 failures in random class and method order |

## M8 - Deployment and go-live

Container image, production compose with HTTPS, CD with approval, backups with a tested restore, monitoring, go-live import, runbook and release.

### [ ] T8.01 - Dockerfile

- **Goal:** Multi-stage build, JRE 21 only, non-root user, layered jar, `HEALTHCHECK` on liveness.
- **Files:** `Dockerfile`, `.dockerignore`
- **Tests:**
  - check: `docker build -t nexora:local . && docker run --rm --entrypoint id nexora:local -u` - proves NFR-OPS-01
- **Requirements:** NFR-OPS-01
- **Depends on:** T7.10

### [ ] T8.02 - Production compose and Caddy

- **Goal:** `deploy/docker-compose.prod.yml` (app, postgres on a private network, Caddy with TLS, HSTS, HTTP->HTTPS redirect).
- **Files:** `../deploy/docker-compose.prod.yml`, `../deploy/Caddyfile`, `../deploy/.env.prod.example`
- **Tests:**
  - check: `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod.example config` - proves NFR-OPS-01
  - check: `curl -sI http://<host>/actuator/health shows 308 to https and https response carries Strict-Transport-Security` - proves NFR-SEC-01
- **Requirements:** NFR-OPS-01, NFR-SEC-01
- **Depends on:** T8.01

### [ ] T8.03 - Configuration fail-fast

- **Goal:** Every environment variable documented in `.env.example`/`.env.prod.example`; a missing required variable stops the prod profile at startup.
- **Files:** `.env.example`, `../deploy/.env.prod.example`, `{R}/application-prod.yml`, `{T}/shared/config/ConfigurationFailFastTest.java`
- **Tests:**
  - `ConfigurationFailFastTest#missingRequiredVariable_inProd_failsStartup` - display name "NFR-OPS-02 NFR-SEC-02 missing required variable in prod fails startup"
- **Requirements:** NFR-OPS-02, NFR-SEC-02
- **Depends on:** T8.02

### [ ] T8.04 - CI extension and deploy workflow

- **Goal:** CI jobs build, audit, compose-check, image (GHCR); `deploy.yml` with manual approval; `deploy.sh` with pre-deploy backup, smoke test and auto-rollback.
- **Files:** `../.github/workflows/ci.yml`, `../.github/workflows/deploy.yml`, `../deploy/scripts/deploy.sh`
- **Tests:**
  - check: `CI on a pull request: all jobs green within 15 minutes` - proves NFR-OPS-03
  - check: `deploy/scripts/deploy.sh <previous-tag> on staging restores the previous version within 15 minutes` - proves NFR-OPS-04
- **Requirements:** NFR-OPS-03, NFR-OPS-04
- **Depends on:** T8.03

### [ ] T8.05 - Backups and restore drill

- **Goal:** Nightly encrypted `pg_dump` off-site, 30-day retention, pre-deploy backup, monthly restore rehearsal with reconciliation queries.
- **Files:** `../deploy/scripts/backup.sh`, `../deploy/scripts/restore-drill.sh`
- **Tests:**
  - check: `deploy/scripts/backup.sh --label drill && deploy/scripts/restore-drill.sh exits 0 and reconciliation totals match` - proves NFR-REL-04, NFR-OPS-05
- **Requirements:** NFR-REL-04, NFR-OPS-05
- **Depends on:** T8.04

### [ ] T8.06 - Monitoring and alerts

- **Goal:** External probe every minute (alert after 3 failures), disk > 80% and failed backup alerts, host check restarting the app.
- **Files:** `../deploy/scripts/host-check.sh`
- **Tests:**
  - check: `stop the app container: alert fires within 3 minutes; host-check restarts it` - proves NFR-OBS-05
  - check: `uptime probe report for the first month shows >= 99.0% availability` - proves NFR-REL-03
- **Requirements:** NFR-OBS-05, NFR-REL-03
- **Depends on:** T8.05

### [ ] T8.07 - Go-live data import

- **Goal:** Import opening stock, account balances, receivables, payables, loans and chits through the normal endpoints; reconciliation totals signed off by the owner.
- **Files:** `../deploy/import/README.md`, `{T}/scenario/GoLiveImportIntegrationTest.java`
- **Tests:**
  - `GoLiveImportIntegrationTest#openingDataTotals_matchReconciliationQueries` - display name "NFR-DATA-04 FR-INV-13 FR-PAY-22 opening data totals match reconciliation queries"
- **Requirements:** NFR-DATA-04, FR-INV-13, FR-PAY-22
- **Depends on:** T8.06

### [ ] T8.08 - Runbook and README

- **Goal:** `docs/RUNBOOK.md` (deploy, rollback, restore, JWT secret rotation, add a user, unlock an account) and `README.md` (setup, run, test, deploy).
- **Files:** `../docs/RUNBOOK.md`, `../README.md`, `{T}/architecture/RunbookCompletenessTest.java`
- **Tests:**
  - `RunbookCompletenessTest#runbook_hasEveryRequiredProcedure` - display name "NFR-OPS-06 runbook has every required procedure"
- **Requirements:** NFR-OPS-06
- **Depends on:** T8.07

### [ ] T8.09 - Release v1.0.0 and parallel run

- **Goal:** `CHANGELOG.md`, release `v1.0.0`, 2-4 weeks parallel with the notebooks, weekly reconciliation confirmed by the owner.
- **Files:** `../CHANGELOG.md`
- **Tests:**
  - check: ``git tag -l v1.0.0` prints the tag and the weekly reconciliation sheet is signed for every parallel-run week` - proves NFR-OPS-07
- **Requirements:** NFR-OPS-07
- **Depends on:** T8.08

### M8 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `docker compose -f deploy/docker-compose.prod.yml up -d && curl -fsS https://<host>/actuator/health` | `{"status":"UP"}` |
| 2 | `deploy/scripts/restore-drill.sh` | exit code 0; restored totals equal production totals |
| 3 | `./gradlew build && ./gradlew dependencyCheckAnalyze` | both succeed on the release commit |

## M9 - Desktop client

> **Ownership (`TEAM_SPLIT.md`):** the client is owned by Developer B and is built as Kotlin Multiplatform (ADR-036).
> Developer B follows `ROADMAP_DEV_B.md`, which orders these client tasks into B's milestones and adapts paths to `nexora-client/`.


Kotlin + Compose Multiplatform Desktop client for Windows and Linux (ADR-035, `06-UI-SPEC.md`), a separate Gradle build in `nexora-desktop/` at the repository root. Layers: Compose UI -> ViewModel (StateFlow) -> Repository -> Ktor API client. Screens follow the module order of `06` section 4. The client never computes business values.

### [ ] T9.01 - Desktop project setup

- **Goal:** Gradle build with the pinned versions of `06` section 5.3 (Kotlin 2.4.20, Compose Multiplatform 1.12.1, Ktor 3.6.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, Koin 4.2.2, lifecycle-viewmodel-compose 2.11.0, java-keyring 1.0.4, JUnit Jupiter 6.0.3, JDK 21); a 1280x720-minimum window; Koin start.
- **Files:** `../nexora-desktop/settings.gradle.kts`, `../nexora-desktop/build.gradle.kts`, `../nexora-desktop/gradle/libs.versions.toml`, `{DM}/Main.kt`, `{DM}/di/AppModule.kt`, `{DT}/di/AppModuleTest.kt`
- **Tests:**
  - `AppModuleTest#koinModules_resolveEveryDefinition` - display name "koin modules resolve every definition"
- **Requirements:** none (supporting task)
- **Depends on:** T8.09

### [ ] T9.02 - BigDecimal serializer and Indian formatting

- **Goal:** `KSerializer<BigDecimal>` that reads the JSON number literal text and never passes through `Double`; formatters for `₹12,50,000.50`, `1,250.500 kg`, `dd-MM-yyyy` and Asia/Kolkata timestamps.
- **Files:** `{DM}/api/BigDecimalSerializer.kt`, `{DM}/util/IndianFormat.kt`, `{DM}/util/Dates.kt`, `{DT}/api/BigDecimalSerializerTest.kt`, `{DT}/util/IndianFormatTest.kt`
- **Tests:**
  - `BigDecimalSerializerTest#roundTrip_12500000_50_staysExact` - display name "NFR-USE-02 NFR-DATA-01 round trip 12500000 50 stays exact"
  - `IndianFormatTest#money_usesRupeeSignAndIndianGrouping` - display name "NFR-USE-03 money uses rupee sign and indian grouping"
  - `IndianFormatTest#weight_showsThreeDecimalsAndKg` - display name "NFR-USE-03 weight shows three decimals and kg"
- **Requirements:** NFR-USE-02, NFR-DATA-01, NFR-USE-03
- **Depends on:** T9.01

### [ ] T9.03 - API client and error mapping

- **Goal:** Ktor CIO client (30 s timeout, JSON with `ignoreUnknownKeys`), ProblemDetail parsed to `ApiError(code, detail, fieldErrors)`, the code -> message table of `06` section 9 as resource strings.
- **Files:** `{DM}/api/ApiClient.kt`, `{DM}/api/ApiError.kt`, `{DM}/repository/ApiResult.kt`, `{DM}/ui/components/ErrorMessages.kt`, `../nexora-desktop/src/main/resources/strings.properties`, `{DT}/api/ApiClientTest.kt`, `{DT}/ui/components/ErrorMessagesTest.kt`
- **Tests:**
  - `ApiClientTest#problemDetail_isMappedToApiErrorWithFieldErrors` - display name "NFR-USE-04 problem detail is mapped to api error with field errors"
  - `ApiClientTest#requestTimeout_is30Seconds` - display name "NFR-USE-05 request timeout is30 seconds"
  - `ErrorMessagesTest#everyServerErrorCode_hasUserMessage` - display name "NFR-USE-04 every server error code has user message"
- **Requirements:** NFR-USE-04, NFR-USE-05
- **Depends on:** T9.02

### [ ] T9.04 - Settings store and server setup (S-AUTH-01)

- **Goal:** Settings JSON file per OS user; server address must be `https://` (`http://` only for localhost); health and info check; an API major-version mismatch blocks login.
- **Files:** `{DM}/util/SettingsStore.kt`, `{DM}/ui/auth/ServerSetupScreen.kt`, `{DM}/viewmodel/auth/ServerSetupViewModel.kt`, `{DT}/viewmodel/auth/ServerSetupViewModelTest.kt`
- **Tests:**
  - `ServerSetupViewModelTest#httpAddress_onlyAllowedForLocalhost` - display name "NFR-SEC-01 http address only allowed for localhost"
  - `ServerSetupViewModelTest#apiMajorVersionMismatch_blocksLogin` - display name "api major version mismatch blocks login"
- **Requirements:** NFR-SEC-01
- **Depends on:** T9.03

### [ ] T9.05 - Login, credential store, refresh and retry (S-AUTH-02, S-AUTH-03)

- **Goal:** Access token in memory only; refresh token in the OS credential store via java-keyring; on 401 one shared refresh, then one retry; a failed refresh opens the session-expired dialog and keeps unsaved input; logout deletes local tokens.
- **Files:** `{DM}/api/AuthPlugin.kt`, `{DM}/util/CredentialStore.kt`, `{DM}/repository/AuthRepository.kt`, `{DM}/viewmodel/auth/LoginViewModel.kt`, `{DM}/viewmodel/auth/SessionViewModel.kt`, `{DM}/ui/auth/LoginScreen.kt`, `{DM}/ui/auth/SessionExpiredDialog.kt`, `{DT}/repository/AuthRepositoryTest.kt`, `{DT}/viewmodel/auth/LoginViewModelTest.kt`, `{DT}/util/CredentialStoreTest.kt`, `{DT}/ui/auth/LoginUiTest.kt`
- **Tests:**
  - `AuthRepositoryTest#expiredAccessToken_refreshesOnceAndRetries` - display name "FR-AUTH-04 expired access token refreshes once and retries"
  - `AuthRepositoryTest#concurrentRequests_shareOneRefresh` - display name "FR-AUTH-04 concurrent requests share one refresh"
  - `AuthRepositoryTest#refreshFailure_clearsStoredTokenAndAsksForLogin` - display name "FR-AUTH-03 refresh failure clears stored token and asks for login"
  - `LoginViewModelTest#invalidCredentials_showsGenericMessage` - display name "FR-AUTH-01 FR-AUTH-07 invalid credentials shows generic message"
  - `CredentialStoreTest#onlyRefreshTokenIsStored` - display name "NFR-SEC-06 only refresh token is stored"
  - `LoginUiTest#login_withValidCredentials_opensLandingScreen` - display name "FR-AUTH-01 FR-AUTH-11 login with valid credentials opens landing screen"
- **Requirements:** FR-AUTH-04, FR-AUTH-03, FR-AUTH-01, FR-AUTH-07, NFR-SEC-06, FR-AUTH-11
- **Depends on:** T9.04

### [ ] T9.06 - Application shell and navigation

- **Goal:** Sidebar entries by permission (`/auth/me`), top bar (server status dot, bell, user, log out), global shortcuts of `06` section 1.1, window state remembered, landing screen by permission.
- **Files:** `{DM}/ui/shell/AppShell.kt`, `{DM}/ui/shell/Sidebar.kt`, `{DM}/ui/shell/TopBar.kt`, `{DM}/ui/shell/Shortcuts.kt`, `{DM}/viewmodel/shell/NavigationViewModel.kt`, `{DT}/viewmodel/shell/NavigationViewModelTest.kt`, `{DT}/ui/shell/ShellUiTest.kt`
- **Tests:**
  - `NavigationViewModelTest#entriesWithoutPermission_areHidden` - display name "FR-AUTH-09 entries without permission are hidden"
  - `NavigationViewModelTest#staffWithoutReportView_landsOnInventory` - display name "FR-AUTH-09 staff without report view lands on inventory"
  - `ShellUiTest#globalShortcuts_openRecordForms` - display name "NFR-USE-01 global shortcuts open record forms"
- **Requirements:** FR-AUTH-09, NFR-USE-01
- **Depends on:** T9.05

### [ ] T9.07 - Reusable components and accessibility

- **Goal:** `DataTable`, `MoneyField`, `WeightField`, `DateField`, `PartyPicker`, `EntityPicker`, `StatusChip`, `ConfirmDialog`, `ErrorBanner`, `EmptyState`, `FieldError`, `SubmitButton` (keeps the idempotency key for retries), `ResultPanel`; text scale 100-200 %; focus outline and contrast rules of `06` section 3.8.
- **Files:** `{DM}/ui/components/DataTable.kt`, `{DM}/ui/components/MoneyField.kt`, `{DM}/ui/components/WeightField.kt`, `{DM}/ui/components/DateField.kt`, `{DM}/ui/components/PartyPicker.kt`, `{DM}/ui/components/EntityPicker.kt`, `{DM}/ui/components/StatusChip.kt`, `{DM}/ui/components/ConfirmDialog.kt`, `{DM}/ui/components/ErrorBanner.kt`, `{DM}/ui/components/EmptyState.kt`, `{DM}/ui/components/FieldError.kt`, `{DM}/ui/components/SubmitButton.kt`, `{DM}/ui/components/ResultPanel.kt`, `{DM}/ui/theme/Theme.kt`, `{DM}/util/IdempotencyKeys.kt`, `{DT}/ui/components/MoneyFieldTest.kt`, `{DT}/ui/components/WeightFieldTest.kt`, `{DT}/ui/components/SubmitControllerTest.kt`, `{DT}/ui/components/StatusChipTest.kt`, `{DT}/ui/components/AccessibilityUiTest.kt`
- **Tests:**
  - `MoneyFieldTest#thirdDecimal_isRejectedWhileTyping` - display name "FR-PAY-13 third decimal is rejected while typing"
  - `WeightFieldTest#fourthDecimal_isRejectedWhileTyping` - display name "FR-INV-15 fourth decimal is rejected while typing"
  - `SubmitControllerTest#retryAfterTimeout_reusesIdempotencyKey` - display name "NFR-USE-05 FR-PAY-07 retry after timeout reuses idempotency key"
  - `StatusChipTest#statusShownAsTextNotColourOnly` - display name "NFR-USE-06 status shown as text not colour only"
  - `AccessibilityUiTest#textScale200_noLabelIsClipped` - display name "NFR-USE-06 text scale200 no label is clipped"
  - `AccessibilityUiTest#iconOnlyButtons_haveAccessibleNames` - display name "NFR-USE-06 icon only buttons have accessible names"
  - `AccessibilityUiTest#themeColours_meetContrast4_5To1` - display name "NFR-USE-06 theme colours meet contrast4 5 to1"
- **Requirements:** FR-PAY-13, FR-INV-15, NFR-USE-05, FR-PAY-07, NFR-USE-06
- **Depends on:** T9.06

### [ ] T9.08 - Dashboard (S-DASH-01)

- **Goal:** Three bands from `GET /dashboard/summary` (money available kept separate from obligations), dues and recent-transaction panels, 5-minute auto refresh.
- **Files:** `{DM}/ui/dashboard/DashboardScreen.kt`, `{DM}/viewmodel/dashboard/DashboardViewModel.kt`, `{DM}/repository/DashboardRepository.kt`, `{DT}/viewmodel/dashboard/DashboardViewModelTest.kt`, `{DT}/ui/dashboard/DashboardUiTest.kt`
- **Tests:**
  - `DashboardViewModelTest#showsApiFiguresWithoutComputing` - display name "FR-RPT-01 FR-RPT-02 NFR-USE-02 shows api figures without computing"
  - `DashboardUiTest#keyFigures_atLeast20sp` - display name "NFR-USE-03 key figures at least20sp"
- **Requirements:** FR-RPT-01, FR-RPT-02, NFR-USE-02, NFR-USE-03
- **Depends on:** T9.07

### [ ] T9.09 - Parties (S-PTY-01..03)

- **Goal:** List with debounced search, create/edit form (roles, phones, defaults), detail with role tabs (outstanding, statement, payables, loans, activity), activate/deactivate.
- **Files:** `{DM}/ui/parties/PartyListScreen.kt`, `{DM}/ui/parties/PartyFormPanel.kt`, `{DM}/ui/parties/PartyDetailScreen.kt`, `{DM}/viewmodel/parties/PartyListViewModel.kt`, `{DM}/viewmodel/parties/PartyFormViewModel.kt`, `{DM}/viewmodel/parties/PartyDetailViewModel.kt`, `{DM}/repository/PartyRepository.kt`, `{DT}/viewmodel/parties/PartyListViewModelTest.kt`, `{DT}/viewmodel/parties/PartyFormViewModelTest.kt`, `{DT}/viewmodel/parties/PartyDetailViewModelTest.kt`
- **Tests:**
  - `PartyListViewModelTest#search_debounced300msAndCancelsStaleRequests` - display name "FR-PARTY-07 search debounced300ms and cancels stale requests"
  - `PartyFormViewModelTest#fieldErrors_shownUnderMatchingFields` - display name "FR-PARTY-01 NFR-USE-04 field errors shown under matching fields"
  - `PartyDetailViewModelTest#tabsShownPerRole` - display name "FR-PARTY-06 tabs shown per role"
- **Requirements:** FR-PARTY-07, FR-PARTY-01, NFR-USE-04, FR-PARTY-06
- **Depends on:** T9.08

### [ ] T9.10 - Purchases, receipts and returns (S-PUR-01..06)

- **Goal:** Purchase list, new purchase, detail with status-dependent actions, receive-silk and return dialogs (idempotent), supplier payables overview.
- **Files:** `{DM}/ui/purchases/PurchaseListScreen.kt`, `{DM}/ui/purchases/NewPurchasePanel.kt`, `{DM}/ui/purchases/PurchaseDetailScreen.kt`, `{DM}/ui/purchases/ReceiveSilkDialog.kt`, `{DM}/ui/purchases/ReturnDialog.kt`, `{DM}/ui/purchases/SupplierPayablesScreen.kt`, `{DM}/viewmodel/purchases/PurchaseListViewModel.kt`, `{DM}/viewmodel/purchases/PurchaseFormViewModel.kt`, `{DM}/viewmodel/purchases/PurchaseDetailViewModel.kt`, `{DM}/viewmodel/purchases/ReceiveSilkViewModel.kt`, `{DM}/viewmodel/purchases/ReturnDialogViewModel.kt`, `{DM}/repository/PurchaseRepository.kt`, `{DT}/viewmodel/purchases/PurchaseFormViewModelTest.kt`, `{DT}/viewmodel/purchases/PurchaseDetailViewModelTest.kt`, `{DT}/viewmodel/purchases/ReceiveSilkViewModelTest.kt`, `{DT}/ui/purchases/ReceiveSilkUiTest.kt`, `{DT}/viewmodel/purchases/ReturnDialogViewModelTest.kt`
- **Tests:**
  - `PurchaseFormViewModelTest#cashPurchase_forcesCreditDaysToZero` - display name "FR-PROC-04 cash purchase forces credit days to zero"
  - `PurchaseDetailViewModelTest#actionsEnabledByStatus` - display name "FR-PROC-12 actions enabled by status"
  - `ReceiveSilkViewModelTest#receiptExceeded_showsErrorAtReceivedField` - display name "FR-PROC-08 receipt exceeded shows error at received field"
  - `ReceiveSilkUiTest#receiveSilk_keyboardOnlyFlow` - display name "FR-PROC-08 FR-PROC-19 receive silk keyboard only flow"
  - `ReturnDialogViewModelTest#insufficientInventory_showsMessage` - display name "FR-PROC-13 insufficient inventory shows message"
- **Requirements:** FR-PROC-04, FR-PROC-12, FR-PROC-08, FR-PROC-19, FR-PROC-13
- **Depends on:** T9.09

### [ ] T9.11 - Inventory (S-INV-01..05)

- **Goal:** Stock summary tiles and table, read-only movement history, material with manufacturers, wastage and adjustment dialogs.
- **Files:** `{DM}/ui/inventory/StockSummaryScreen.kt`, `{DM}/ui/inventory/MovementHistoryScreen.kt`, `{DM}/ui/inventory/ExternalWipScreen.kt`, `{DM}/ui/inventory/RecordWastageDialog.kt`, `{DM}/ui/inventory/StockAdjustmentDialog.kt`, `{DM}/viewmodel/inventory/StockSummaryViewModel.kt`, `{DM}/viewmodel/inventory/MovementHistoryViewModel.kt`, `{DM}/viewmodel/inventory/StockAdjustmentViewModel.kt`, `{DM}/repository/InventoryRepository.kt`, `{DT}/viewmodel/inventory/StockSummaryViewModelTest.kt`, `{DT}/viewmodel/inventory/MovementHistoryViewModelTest.kt`, `{DT}/viewmodel/inventory/StockAdjustmentViewModelTest.kt`
- **Tests:**
  - `StockSummaryViewModelTest#balancesShownAsReturned` - display name "FR-INV-07 NFR-USE-02 balances shown as returned"
  - `MovementHistoryViewModelTest#offersNoCreateAction` - display name "FR-INV-01 offers no create action"
  - `StockAdjustmentViewModelTest#reasonIsRequired` - display name "FR-INV-12 reason is required"
- **Requirements:** FR-INV-07, NFR-USE-02, FR-INV-01, FR-INV-12
- **Depends on:** T9.10

### [ ] T9.12 - Orders (S-ORD-01..03)

- **Goal:** Order list with pending/late chips, create/edit form with item rows, detail showing only the legal next actions with confirmations.
- **Files:** `{DM}/ui/orders/OrderListScreen.kt`, `{DM}/ui/orders/OrderFormPanel.kt`, `{DM}/ui/orders/OrderDetailScreen.kt`, `{DM}/viewmodel/orders/OrderListViewModel.kt`, `{DM}/viewmodel/orders/OrderFormViewModel.kt`, `{DM}/viewmodel/orders/OrderDetailViewModel.kt`, `{DM}/repository/OrderRepository.kt`, `{DT}/viewmodel/orders/OrderFormViewModelTest.kt`, `{DT}/viewmodel/orders/OrderDetailViewModelTest.kt`
- **Tests:**
  - `OrderFormViewModelTest#amountShownOnlyFromApiResponse` - display name "FR-ORD-02 NFR-USE-02 amount shown only from api response"
  - `OrderDetailViewModelTest#onlyLegalNextActionsAreShown` - display name "FR-ORD-07 FR-ORD-11 only legal next actions are shown"
  - `OrderDetailViewModelTest#lockedOrder_formIsReadOnly` - display name "FR-ORD-08 locked order form is read only"
- **Requirements:** FR-ORD-02, NFR-USE-02, FR-ORD-07, FR-ORD-11, FR-ORD-08
- **Depends on:** T9.11

### [ ] T9.13 - Payments (S-PAY-01..03)

- **Goal:** Payment list, record payment (default path 5 inputs, accounts filtered by method, optional manual allocation, server result view), payment detail with reversal.
- **Files:** `{DM}/ui/payments/PaymentListScreen.kt`, `{DM}/ui/payments/RecordPaymentDialog.kt`, `{DM}/ui/payments/PaymentDetailScreen.kt`, `{DM}/viewmodel/payments/PaymentListViewModel.kt`, `{DM}/viewmodel/payments/RecordPaymentViewModel.kt`, `{DM}/viewmodel/payments/PaymentDetailViewModel.kt`, `{DM}/repository/PaymentRepository.kt`, `{DT}/viewmodel/payments/RecordPaymentViewModelTest.kt`, `{DT}/ui/payments/RecordPaymentUiTest.kt`, `{DT}/viewmodel/payments/PaymentDetailViewModelTest.kt`
- **Tests:**
  - `RecordPaymentViewModelTest#defaultPath_needsAtMostFiveInputs` - display name "NFR-USE-01 default path needs at most five inputs"
  - `RecordPaymentViewModelTest#cashMethod_listsOnlyCashAccounts` - display name "FR-PAY-12 cash method lists only cash accounts"
  - `RecordPaymentViewModelTest#manualAllocationAboveOutstanding_isBlocked` - display name "FR-PAY-03 manual allocation above outstanding is blocked"
  - `RecordPaymentUiTest#recordPayment_showsServerAllocations` - display name "FR-PAY-02 NFR-USE-02 record payment shows server allocations"
  - `PaymentDetailViewModelTest#reverse_requiresReason` - display name "FR-PAY-15 reverse requires reason"
- **Requirements:** NFR-USE-01, FR-PAY-12, FR-PAY-03, FR-PAY-02, NFR-USE-02, FR-PAY-15
- **Depends on:** T9.12

### [ ] T9.14 - Money (S-FIN-01..06)

- **Goal:** Accounts and cash position, new account (last 4 digits only), account and all transactions (read only), transfer and balance-adjustment dialogs, close only at zero.
- **Files:** `{DM}/ui/money/AccountsScreen.kt`, `{DM}/ui/money/NewAccountPanel.kt`, `{DM}/ui/money/AccountTransactionsScreen.kt`, `{DM}/ui/money/TransactionsScreen.kt`, `{DM}/ui/money/TransferDialog.kt`, `{DM}/ui/money/BalanceAdjustmentDialog.kt`, `{DM}/viewmodel/money/AccountsViewModel.kt`, `{DM}/viewmodel/money/NewAccountViewModel.kt`, `{DM}/viewmodel/money/TransferViewModel.kt`, `{DM}/repository/FinanceRepository.kt`, `{DT}/viewmodel/money/NewAccountViewModelTest.kt`, `{DT}/viewmodel/money/TransferViewModelTest.kt`, `{DT}/viewmodel/money/AccountsViewModelTest.kt`
- **Tests:**
  - `NewAccountViewModelTest#bankAccount_acceptsOnlyLastFourDigits` - display name "FR-FIN-01 NFR-SEC-06 bank account accepts only last four digits"
  - `TransferViewModelTest#sameAccount_isBlocked` - display name "FR-FIN-09 same account is blocked"
  - `AccountsViewModelTest#closeEnabledOnlyAtZeroBalance` - display name "FR-FIN-16 close enabled only at zero balance"
- **Requirements:** FR-FIN-01, NFR-SEC-06, FR-FIN-09, FR-FIN-16
- **Depends on:** T9.13

### [ ] T9.15 - Expenses, loans and chits (S-EXP, S-LON, S-CHT)

- **Goal:** Expenses with summary and reversal, record expense (5 inputs), loans list/new/detail, chits list/new/detail with cash-only account selectors.
- **Files:** `{DM}/ui/expenses/ExpensesScreen.kt`, `{DM}/ui/expenses/RecordExpensePanel.kt`, `{DM}/ui/loans/LoansScreen.kt`, `{DM}/ui/loans/NewLoanPanel.kt`, `{DM}/ui/loans/LoanDetailScreen.kt`, `{DM}/ui/chits/ChitsScreen.kt`, `{DM}/ui/chits/NewChitPanel.kt`, `{DM}/ui/chits/ChitDetailScreen.kt`, `{DM}/viewmodel/expenses/ExpensesViewModel.kt`, `{DM}/viewmodel/expenses/RecordExpenseViewModel.kt`, `{DM}/viewmodel/loans/LoanDetailViewModel.kt`, `{DM}/viewmodel/chits/ChitDetailViewModel.kt`, `{DM}/repository/ExpenseRepository.kt`, `{DM}/repository/LoanRepository.kt`, `{DM}/repository/ChitRepository.kt`, `{DT}/viewmodel/expenses/RecordExpenseViewModelTest.kt`, `{DT}/viewmodel/expenses/ExpensesViewModelTest.kt`, `{DT}/viewmodel/loans/LoanDetailViewModelTest.kt`, `{DT}/viewmodel/chits/ChitDetailViewModelTest.kt`
- **Tests:**
  - `RecordExpenseViewModelTest#defaultPath_needsAtMostFiveInputs` - display name "NFR-USE-01 FR-FIN-11 default path needs at most five inputs"
  - `ExpensesViewModelTest#personalDrawingsShownSeparately` - display name "FR-FIN-12 personal drawings shown separately"
  - `LoanDetailViewModelTest#repaymentAboveOutstanding_isBlocked` - display name "FR-LOAN-03 repayment above outstanding is blocked"
  - `ChitDetailViewModelTest#accountSelector_listsCashAccountsOnly` - display name "FR-CHIT-02 account selector lists cash accounts only"
- **Requirements:** NFR-USE-01, FR-FIN-11, FR-FIN-12, FR-LOAN-03, FR-CHIT-02
- **Depends on:** T9.14

### [ ] T9.16 - Production, outsourcing and work (S-PRD, S-OUT, S-WRK)

- **Goal:** Batch list/form/detail (start, complete with the input-consistency check, cancel), job list/new/detail (issue, receive), work records with the weekly default filter and the record-another flow.
- **Files:** `{DM}/ui/production/BatchListScreen.kt`, `{DM}/ui/production/BatchFormPanel.kt`, `{DM}/ui/production/BatchDetailScreen.kt`, `{DM}/ui/production/CompleteBatchDialog.kt`, `{DM}/ui/outsourcing/JobListScreen.kt`, `{DM}/ui/outsourcing/NewJobPanel.kt`, `{DM}/ui/outsourcing/JobDetailScreen.kt`, `{DM}/ui/outsourcing/ReceiveWarpDialog.kt`, `{DM}/ui/work/WorkRecordsScreen.kt`, `{DM}/ui/work/RecordWorkPanel.kt`, `{DM}/viewmodel/production/BatchDetailViewModel.kt`, `{DM}/viewmodel/production/CompleteBatchViewModel.kt`, `{DM}/viewmodel/outsourcing/ReceiveWarpViewModel.kt`, `{DM}/viewmodel/work/RecordWorkViewModel.kt`, `{DM}/repository/ProductionRepository.kt`, `{DM}/repository/OutsourcingRepository.kt`, `{DM}/repository/WorkRepository.kt`, `{DT}/viewmodel/production/CompleteBatchViewModelTest.kt`, `{DT}/viewmodel/production/BatchDetailViewModelTest.kt`, `{DT}/viewmodel/outsourcing/ReceiveWarpViewModelTest.kt`, `{DT}/viewmodel/work/RecordWorkViewModelTest.kt`, `{DT}/ui/work/RecordWorkUiTest.kt`
- **Tests:**
  - `CompleteBatchViewModelTest#saveEnabledOnlyWhenOutputWastageDiscrepancyEqualInput` - display name "FR-PROD-03 save enabled only when output wastage discrepancy equal input"
  - `BatchDetailViewModelTest#startRequiresConfirmation` - display name "FR-PROD-02 start requires confirmation"
  - `ReceiveWarpViewModelTest#receiveAboveStillOutside_showsError` - display name "FR-OUT-03 receive above still outside shows error"
  - `RecordWorkViewModelTest#rollingShowsHoursWarpingShowsKg` - display name "FR-WORK-02 NFR-USE-01 rolling shows hours warping shows kg"
  - `RecordWorkUiTest#recordWork_keepsWorkerForNextEntry` - display name "FR-WORK-01 record work keeps worker for next entry"
- **Requirements:** FR-PROD-03, FR-PROD-02, FR-OUT-03, FR-WORK-02, NFR-USE-01, FR-WORK-01
- **Depends on:** T9.15

### [ ] T9.17 - Reports, notifications and documents (S-RPT, S-NTF, S-DOC)

- **Goal:** Generic report viewer with CSV export through the native save dialog, notification bell and list, reminder settings, documents list with open/save, attach dialog (10 MB checked before upload).
- **Files:** `{DM}/ui/reports/ReportsHomeScreen.kt`, `{DM}/ui/reports/ReportViewerScreen.kt`, `{DM}/ui/notifications/NotificationsScreen.kt`, `{DM}/ui/notifications/ReminderSettingsPanel.kt`, `{DM}/ui/documents/DocumentsScreen.kt`, `{DM}/ui/documents/AttachDocumentDialog.kt`, `{DM}/viewmodel/reports/ReportViewerViewModel.kt`, `{DM}/viewmodel/notifications/NotificationBellViewModel.kt`, `{DM}/viewmodel/documents/AttachDocumentViewModel.kt`, `{DM}/repository/ReportRepository.kt`, `{DM}/repository/NotificationRepository.kt`, `{DM}/repository/DocumentRepository.kt`, `{DT}/viewmodel/reports/ReportViewerViewModelTest.kt`, `{DT}/viewmodel/notifications/NotificationBellViewModelTest.kt`, `{DT}/viewmodel/documents/AttachDocumentViewModelTest.kt`
- **Tests:**
  - `ReportViewerViewModelTest#exportCsv_usesCurrentFilters` - display name "FR-RPT-16 export csv uses current filters"
  - `NotificationBellViewModelTest#countsPendingNotifications` - display name "FR-NTF-05 counts pending notifications"
  - `AttachDocumentViewModelTest#fileOver10Mb_isRejectedBeforeUpload` - display name "FR-DOC-01 NFR-SEC-03 file over10 mb is rejected before upload"
- **Requirements:** FR-RPT-16, FR-NTF-05, FR-DOC-01, NFR-SEC-03
- **Depends on:** T9.16

### [ ] T9.18 - Offline states and settings

- **Goal:** Connect failure or 30 s timeout: red status dot, "Cannot reach the server" banner with Retry, last data kept with "Last updated", form input never cleared; settings screen with client, server and API versions.
- **Files:** `{DM}/viewmodel/shell/OfflineStateViewModel.kt`, `{DM}/ui/settings/SettingsScreen.kt`, `{DT}/viewmodel/shell/OfflineStateViewModelTest.kt`
- **Tests:**
  - `OfflineStateViewModelTest#connectFailure_keepsLastDataAndShowsRetry` - display name "NFR-USE-04 NFR-USE-05 connect failure keeps last data and shows retry"
- **Requirements:** NFR-USE-04, NFR-USE-05
- **Depends on:** T9.17

### [ ] T9.19 - Installers (MSI, DEB)

- **Goal:** Compose `nativeDistributions` with `Msi` and `Deb`, package name `SmartSilk`, version from Gradle, bundled JRE 21, Windows start-menu entry and desktop shortcut.
- **Files:** `../nexora-desktop/build.gradle.kts`, `../nexora-desktop/packaging/icon.ico`, `../nexora-desktop/packaging/icon.png`
- **Tests:**
  - check: `cd nexora-desktop && ./gradlew packageDistributionForCurrentOS writes build/compose/binaries/main/deb/*.deb on Linux and build/compose/binaries/main/msi/*.msi on Windows`
- **Requirements:** none (supporting task)
- **Depends on:** T9.18

### [ ] T9.20 - Desktop CI matrix

- **Goal:** Workflow running `./gradlew check packageDistributionForCurrentOS` in `nexora-desktop/` on `ubuntu-latest` (UI tests under `xvfb-run`) and `windows-latest`, uploading the DEB and the MSI.
- **Files:** `../.github/workflows/desktop.yml`
- **Tests:**
  - check: `desktop workflow green on ubuntu-latest and windows-latest with both installers attached`
- **Requirements:** none (supporting task)
- **Depends on:** T9.19

### M9 quality gate

Run from `Nexora-backend/` (deployment commands from the repository root). The milestone is finished only when every row holds.

| # | Command / check | Expected result |
|---|---|---|
| 1 | `cd nexora-desktop && ./gradlew check packageDistributionForCurrentOS` | exit 0 on Linux (DEB) and on Windows (MSI); all view-model, repository and UI tests pass |
| 2 | `desktop CI workflow` | green on `ubuntu-latest` and `windows-latest` |
| 3 | `install the DEB/MSI, connect to the staging server, log in, record a payment, receive silk, record work` | each flow completes and shows the server result; no business value is computed on the client |

## Summary

| Milestone | Tasks |
|---|---|
| M0 | 11 |
| M1 | 13 |
| M2 | 11 |
| M3 | 14 |
| M4 | 11 |
| M5 | 6 |
| M6 | 10 |
| M7 | 10 |
| M8 | 9 |
| M9 | 20 |
