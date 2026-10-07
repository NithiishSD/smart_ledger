# 06 — UI Specification: SmartSilk Desktop Client

> **Client structure (ADR-036, `TEAM_SPLIT.md` §6):** the client is a Kotlin Multiplatform project in `nexora-client/`
> (`commonMain` + `desktopMain`; desktop target only for now, Android later). Screens, behaviour and versions in this document still apply; read section 5.1's
> `nexora-desktop/` layout as `nexora-client/` with shared code in `commonMain`. Build order: `ROADMAP_DEV_B.md`.

| | |
|---|---|
| Client | Desktop application, Kotlin + Compose Multiplatform (Desktop/JVM) |
| Platforms | Windows 10/11 x64 (MSI installer), Linux Ubuntu 22.04+ x64 (DEB installer) |
| Server | Central backend at `https://<server>/api/v1` (see `10-DEPLOYMENT.md`) |
| Mobile | Android client is POST-MVP future scope; screens and view-models below are written so they can later be shared with an Android target |
| Requirements | `02-REQUIREMENTS.md` (FR/NFR IDs), API contract `05-API-SPEC.md` (same paths as below) |

---

## 1. Principles

| # | Principle | Measurable rule |
|---|---|---|
| P1 | The owner is not technical | Every screen title, label and button uses business words from the notebook ("Receive silk", "Money received"), never table or code names. No screen shows a UUID; business numbers (`PUR-2026-0012`) are shown instead. |
| P2 | Keyboard-first data entry | Every form is completable without a mouse: logical Tab order top-to-bottom, left-to-right; `Enter` submits when focus is not in a multi-line field; `Esc` cancels a dialog or form (with an unsaved-changes confirmation when a field was edited); `Ctrl+S` saves the current form. |
| P3 | Fast daily entries (NFR-USE-01) | Record payment, record work and record expense need at most 5 user inputs; the date defaults to today (Asia/Kolkata) and the account defaults to the last account used on this computer. |
| P4 | Big readable numbers (NFR-USE-03) | Key figures (dashboard tiles, balances, outstanding, stock) use at least 20 sp text; table cells at least 14 sp. |
| P5 | The client never computes business values (NFR-USE-02) | Totals, item amounts, balances, outstanding, payables, allocations, stock, due dates and statuses are displayed exactly as returned by the API. The client only formats them. While a form is being filled it may show "calculated on save" next to derived fields; the real value appears after the API response. |
| P6 | Clear errors (NFR-USE-04) | Errors are shown from the stable `code` using the table in section 9; the API `detail` is shown underneath in smaller text; field errors (`errors[]`) appear under the matching field. |
| P7 | English first | All text is English in the first release; every string lives in one resource file so Tamil can be added POST-MVP without code changes. |
| P8 | Permission-aware UI | After login the client reads `GET /api/v1/auth/me` (FR-AUTH-11). Navigation entries and action buttons whose permission is missing are hidden; the API remains the authority (a 403 still shows the `ACCESS_DENIED` message). |
| P9 | Irreversible actions confirm | Reversals, cancellations, deactivations, closing accounts/loans/chits, delivery and batch start/complete show a confirmation dialog that names the record and its effect; the confirm button is not the default button (Enter does not confirm). |

### 1.1 Global keyboard shortcuts (the six most frequent actions + navigation)
| Shortcut | Action | Opens |
|---|---|---|
| `Ctrl+Shift+P` | Record payment (money in or out) | S-PAY-02 |
| `Ctrl+Shift+R` | Receive silk against a purchase | purchase picker -> S-PUR-04 |
| `Ctrl+Shift+E` | Record expense | S-EXP-02 |
| `Ctrl+Shift+W` | Record work | S-WRK-02 |
| `Ctrl+Shift+O` | New customer order | S-ORD-02 |
| `Ctrl+Shift+U` | New purchase | S-PUR-02 |
| `Ctrl+N` | New record on the current list screen | the module's create form |
| `Ctrl+F` | Focus the search box of the current list | — |
| `F5` | Reload the current screen from the server | — |
| `Alt+1` .. `Alt+9` | Jump to the first nine navigation entries | — |
| `Ctrl+L` | Log out (with confirmation) | S-AUTH-02 |

Shortcuts whose permission is missing do nothing and show a 3-second hint "Not allowed for your account".

---

## 2. Window layout

```
+--------------------------------------------------------------------------------------+
| SmartSilk   [server: online ●]                 [bell 3]   Owner (owner)  [Log out]   |  top bar 48 px
+------------+-------------------------------------------------------------------------+
| Dashboard  |  Screen title                         [search........] [filters] [+ New]|
| Parties    |  ---------------------------------------------------------------------- |
| Purchases  |  list pane (table, paging)               |  detail pane (selected row)   |
| Inventory  |                                          |                               |
| Orders     |                                          |                               |
| Payments   |                                          |                               |
| Production |                                          |                               |
| Outsourcing|                                          |                               |
| Work       |                                          |                               |
| Money      |                                          |                               |
| Expenses   |                                          |                               |
| Loans      |                                          |                               |
| Chits      |                                          |                               |
| Reports    |                                          |                               |
| Documents  |                                          |                               |
| Settings   |                                          |                               |
+------------+-------------------------------------------------------------------------+
  sidebar 200 px (collapsible to a 64 px icon rail)
```

| Element | Behaviour |
|---|---|
| Minimum window | 1280 x 720; the window cannot be resized smaller. Designed for 1366 x 768 and larger. |
| Sidebar | 200 px with icon + label; collapses to a 64 px icon rail (tooltips show labels) when the window is narrower than 1366 px or the user toggles it (`Ctrl+B`). The state is remembered. |
| Top bar | Server status dot (green = last request succeeded, amber = request in progress > 2 s, red = offline/unreachable), notification bell with the count of `PENDING` notifications (S-NTF-01), user display name, Log out. |
| Content area | List screens use a split view: list pane (minimum 640 px) and detail pane (minimum 420 px). Below 1366 px width the detail pane opens as a full-content view with a Back button (`Alt+Left`). |
| Forms | Create/edit forms open as a right-side panel 560 px wide over the list (list remains visible); short actions (receipt, return, transfer, reversal reason) open as modal dialogs 480 px wide. |
| Window state | Size, position and maximised state are restored at the next start. |
| Landing screen | Dashboard when the user has `REPORT_VIEW`; otherwise Inventory summary (STAFF). |

---

## 3. Cross-cutting flows

### 3.1 First run: server setup (S-AUTH-01)
1. When no server URL is stored, the app opens S-AUTH-01.
2. The user enters the server address (for example `https://nexora.example.com`). The client requires `https://` (an `http://` address is accepted only for `localhost` and shows a warning banner "Development server").
3. "Test connection" calls `GET /actuator/health`; success shows the server version from `GET /actuator/info` and enables "Continue".
4. The client checks the API major version: if the server's major version differs from the client's, it shows "This app version (x) does not match the server (y). Install the matching version." and blocks login.
5. The URL is stored in the user's application settings file (not in the credential store).

### 3.2 Login and session (FR-AUTH-01..07)
| Step | Behaviour |
|---|---|
| Login | `POST /api/v1/auth/login` with username and password. On success the access token is kept **in memory only**; the refresh token is written to the OS credential store through java-keyring (Windows Credential Manager; Linux Secret Service). Then `GET /api/v1/auth/me` loads permissions. |
| Start-up with a stored refresh token | The client calls `POST /api/v1/auth/refresh`; on success the user lands on the landing screen without typing the password; on failure the stored token is deleted and S-AUTH-02 opens. |
| Credential store unavailable | (Linux without a Secret Service) the refresh token is not stored; the user logs in at each start. A one-line notice explains this on the login screen. |
| Every API request | `Authorization: Bearer <access token>`. Timeout 30 s (NFR-USE-05). |
| 401 `TOKEN_EXPIRED` or `UNAUTHENTICATED` | The client calls `POST /auth/refresh` once (requests that arrive meanwhile wait for the same refresh), stores the rotated refresh token, and retries the original request once. If refresh fails, all screens close their forms (unsaved input is kept in memory), S-AUTH-03 (session expired dialog) asks for the password, and after login the user returns to the same screen with the input restored. |
| Logout | `POST /api/v1/auth/logout` with the refresh token, then the token is deleted from the credential store and memory; S-AUTH-02 opens. Logout works offline (local deletion only). |
| Locked account | `INVALID_CREDENTIALS` after 5 failures shows the generic message; the client never reveals whether the account is locked (FR-AUTH-07). |

### 3.3 Retry-safe submissions (NFR-USE-05, FR-PAY-07, FR-PROC-19, FR-FIN-18)
- Every create/action submit that the API marks idempotent (payments, receipts, returns, expenses, transfers, reversals, loan/chit entries) gets a new `Idempotency-Key` (UUID v4) when the user presses Save.
- The key is reused for automatic and manual retries of the same submit (timeout, connection reset, 5xx). It is discarded only after a 2xx or a 4xx response, or when the user edits the form.
- While a submit is in flight the Save button shows a spinner and is disabled; a second press does nothing.
- `409 DUPLICATE_REQUEST` (same key, different body) shows "This entry was already saved with different values. Reload and check before entering again."

### 3.4 Offline and timeouts
- No offline writes in the MVP. When a request fails to connect or times out after 30 s, the screen shows the error banner "Cannot reach the server" with "Retry" (`F5`) and the top-bar dot turns red.
- Read screens keep the last loaded data visible with a grey "Last updated hh:mm" label when a reload fails.
- Form input is never cleared by a failed submit.

### 3.5 Lists and search
| Aspect | Rule |
|---|---|
| Paging | Server-side pages; page size selector 20 (default) / 50 / 100 (API maximum 100). Footer shows "Showing 21–40 of 312" and First/Previous/Next/Last. `PgDn`/`PgUp` change page when the table has focus. |
| Sorting | Clicking a sortable column header sends `sort=<field>,asc|desc`; only fields listed as sortable in `05-API-SPEC.md` are clickable. Default sort follows the API default (newest first). |
| Search | Search-as-you-type with a 300 ms debounce after the last keystroke; minimum 2 characters; requests superseded by newer input are cancelled. |
| Filters | Shown as a filter row (chips for status, date range picker, party picker). Active filters are listed above the table with an "x" to remove each; the filter state is kept while navigating within the session. |
| Row actions | `Enter` on a selected row opens its detail; context menu (right-click or `Shift+F10`) lists the row's allowed actions. |
| Empty list | Illustration-free text: "No purchases yet" + primary button "New purchase" (if permitted); with filters: "No results for these filters" + "Clear filters". |

### 3.6 Formatting
| Value | Display | On the wire |
|---|---|---|
| Money | `₹` + Indian digit grouping + 2 decimals: `₹12,50,000.50` (NFR-USE-03); negative values in red with a leading minus | JSON number with 2 decimals, parsed to `BigDecimal` without passing through `Double` |
| Weight | 3 decimals + ` kg`: `1,250.500 kg` (Indian grouping for the integer part) | JSON number with 3 decimals, parsed to `BigDecimal` |
| Rates | `₹850.00 /kg`, `₹120.00 /hour` | number, 2 decimals |
| Dates | `dd-MM-yyyy` (`08-10-2026`); date inputs accept `dd-MM-yyyy`, `ddMMyyyy` and `t` (today) | ISO `yyyy-MM-dd` |
| Timestamps | `dd-MM-yyyy HH:mm` in Asia/Kolkata | ISO-8601 UTC |
| Status | Coloured chip with business wording (section 8) | enum name |
Input fields for money and weight accept digits and one decimal point, reject more decimals than the scale (2 for money, 3 for weight) as the user types, and ignore grouping commas pasted from elsewhere.

### 3.7 Export
Reports and the main lists offer "Export CSV" (FR-RPT-16): the client calls the same endpoint with `format=csv` and the current filters, then opens a native Save dialog (default file name `<report>-<from>-<to>.csv`). Exports above 50,000 rows are refused by the API; the client shows the API message. Printing and PDF bills are POST-MVP (FR-DOC-06); the MVP prints nothing.

### 3.8 Accessibility (NFR-USE-06)
- Every control is reachable and operable by keyboard; focus is always visible (2 px outline in the accent colour, contrast at least 3:1 against its background).
- Text contrast is at least 4.5:1 for body text and 3:1 for text of 24 sp and larger.
- A "Text size" setting scales all text from 100% to 200% in steps of 25%; at 200% no label is clipped (layouts wrap or scroll).
- Interactive targets are at least 32 x 32 px (desktop pointer) and table rows at least 40 px high.
- Every icon-only button has an accessible name (Compose `contentDescription` / semantics) read by screen readers (Narrator, Orca).
- Status is never conveyed by colour alone: chips always include text.

---

## 4. Screens by module

Each screen lists: purpose · entry points · layout · fields (label | control | client validation | default) · actions (button -> API) · states. Client validation mirrors the API (`05-API-SPEC.md`) so the user sees mistakes before submitting; the API remains the authority.
Common states, used unless a screen says otherwise:
- **Loading:** skeleton rows in tables, a spinner in the detail pane; forms disable Save while submitting.
- **Error:** error banner at the top of the content area with the message from section 9 and "Retry" for read failures.
- **Success:** after a create, a 4-second toast "Saved PUR-2026-0012" and the new record opens in the detail pane.

### 4.1 Authentication and settings

**S-AUTH-01 Server setup** — described in 3.1. Fields: Server address (text, required, must start with `https://` or `http://localhost`). Actions: Test connection (`GET /actuator/health`, `GET /actuator/info`), Continue. States: error "Server not reachable" / "Not a SmartSilk server" (health response without the expected `info.app.name`).

**S-AUTH-02 Login** (FR-AUTH-01, FR-AUTH-07)
| Field | Control | Validation | Default |
|---|---|---|---|
| Username | text | required, 1–100 chars | last username used on this computer |
| Password | password (masked, toggle show) | required, 1–200 chars | empty |
Actions: Log in (`POST /api/v1/auth/login`) then `GET /api/v1/auth/me`; link "Change server" -> S-AUTH-01. States: error `INVALID_CREDENTIALS` ("Wrong username or password"), `RATE_LIMITED` ("Too many attempts. Wait a few minutes and try again."), offline banner. Caps Lock on shows a warning under the password field.

**S-AUTH-03 Session expired dialog** — modal; shows the username (read-only) and a password field; "Log in" re-authenticates and restores the previous screen and unsaved input (3.2). "Log out" discards.

**S-SET-01 Settings and about**
Sections: Server (address, Change -> S-AUTH-01), Display (text size 100–200%, sidebar collapsed by default), Defaults (default account per entry type, read from the accounts list), About (client version, server version from `/actuator/info`, API major version, licence notices), Log out. All settings are stored per OS user in the application settings file. Change password and user management are POST-MVP (FR-AUTH-14, FR-AUTH-15) and not shown.

### 4.2 Dashboard

**S-DASH-01 Owner dashboard** (FR-RPT-01, FR-RPT-02, FR-NTF-06) — `GET /api/v1/dashboard/summary`, permission `REPORT_VIEW`.
Layout (three bands, each tile clickable to its detail screen):
1. **Money available** — Cash, each bank account, Total available (largest tile). Visually separated from obligations (FR-RPT-02): obligations are never added to or subtracted from this band.
2. **Obligations** — Customer receivables (and advance total), Supplier payables (overdue total in red), Manufacturer payable, Worker payable, Loan principal outstanding, Active chit monthly commitments.
3. **Stock and work** — Raw silk available, Silk in internal WIP, Silk with manufacturers, Finished warp (silk / kora), Vuda warp stock; pending orders by status; production batches planned / in progress; outsourcing jobs not completed.
Side panels: "Supplier dues in the next 7 days and overdue" (supplier, purchase number, due date, payable, overdue days; click -> S-PUR-03) and "Recent transactions" (10 rows; click -> S-FIN-04 filtered).
States: loading skeleton tiles; error banner with Retry; the `asOf` time is shown top-right ("As of 08-10-2026 10:42"); auto-refresh every 5 minutes while the dashboard is visible, plus `F5`.

### 4.3 Parties

**S-PTY-01 Party list** (FR-PARTY-07) — `GET /api/v1/parties?role=&status=&search=&page=&size=&sort=`.
Columns: Code, Name, Roles (chips), Primary phone, City, Status. Filters: role (Customer, Supplier, Manufacturer, Worker, Lender), status (Active default / Inactive / All), search (name, code or phone; 300 ms debounce). Actions: New party (`PARTY_MANAGE`) -> S-PTY-02. Empty: "No parties yet".

**S-PTY-02 Party form (create / edit)** (FR-PARTY-01, -02, -04, -05, -08, -09, -13, -14, -16)
| Field | Control | Validation (client) | Default |
|---|---|---|---|
| Name | text | required, 1–200 chars | — |
| Roles | checkbox group: Customer, Supplier, Manufacturer, Worker, Lender | at least one; on edit, existing roles are checked and disabled (roles cannot be removed, FR-PARTY-09) | — |
| Phone numbers | repeatable row: number (text, 1–30 chars, digits, spaces, `+`, `-`), label (text, 1–50, suggestions Mobile / WhatsApp / Office), Primary (radio) | exactly one primary when any number exists; the first row is primary when none is chosen | one empty row |
| Address line | text | ≤ 255 | — |
| Area | text | ≤ 155 | — |
| City | text | ≤ 100 | — |
| District | text | ≤ 100 | — |
| State | text | ≤ 50 | `Tamil Nadu` |
| Pincode | text | ≤ 20 | — |
| Default credit days | integer field, shown only when Supplier is checked | ≥ 0 | — |
| Default rolling rate (₹/hour) | money field, shown only when Worker is checked | ≥ 0.00, 2 decimals | — |
| Default warping rate (₹/kg) | money field, shown only when Worker is checked | ≥ 0.00, 2 decimals | — |
| Note | multi-line text | ≤ 1000 | — |
Actions: Save -> create `POST /api/v1/parties` / edit `PATCH /api/v1/parties/{id}` (phone list is sent as a full replacement). Errors: `VALIDATION_FAILED` (field errors), `CONCURRENT_MODIFICATION` ("Someone changed this party. Reload to see the latest version." + Reload button keeping the user's input in a side note).

**S-PTY-03 Party detail** (FR-PARTY-06, -10, FR-PAY-17..21, FR-RPT-04)
Header: name, code, roles, status chip, phones, address. Buttons: Edit (`PARTY_MANAGE`), Deactivate / Activate (`POST /api/v1/parties/{id}/deactivate|activate`, confirmation: "Deactivated parties cannot be used in new entries. History stays visible.").
Tabs shown per role:
| Tab | Role | Content | API |
|---|---|---|---|
| Outstanding | Customer | total outstanding, advance, ageing buckets 0–30 / 31–60 / 61–90 / > 90 days, per-order breakdown (order no, date, amount, allocated, outstanding, age) | `GET /api/v1/customers/{id}/outstanding` |
| Statement | Customer | date range (default current financial year Apr–Mar), opening balance, debits, credits, running balance, closing balance; Export CSV | `GET /api/v1/customers/{id}/statement?from&to` |
| Payables | Supplier | per purchase: number, date, due date, payable, overdue days; totals | `GET /api/v1/suppliers/{id}/payables` |
| Payable | Manufacturer | per job: number, received kg, rate, payable, allocated; total | `GET /api/v1/manufacturers/{id}/payable` |
| Payable | Worker | date range (default current week Mon–Sun), work value, paid, pending | `GET /api/v1/workers/{id}/payable` |
| Loans | Lender | loans of this lender | `GET /api/v1/loans?partyId={id}` |
| Activity | all | links to purchases / orders / payments / jobs / work records filtered by this party | list endpoints with `supplierId` / `customerId` / `partyId` / `manufacturerId` / `workerId` |
Each money tab has the button "Record payment" (`PAYMENT_CREATE`) that opens S-PAY-02 pre-filled with this party and the direction.
States: `RESOURCE_NOT_FOUND` -> "This party no longer exists" + Back.

### 4.4 Purchases, receipts and returns

**S-PUR-01 Purchase list** (FR-PROC-07, -16) — `GET /api/v1/purchases?supplierId=&status=&productType=&from=&to=`.
Columns: Number, Date, Supplier, Product, Ordered kg, Received kg, Remaining kg, Value, Payable, Due date, Status chip, Overdue chip (with days). Filters: supplier picker, status (Ordered, Partially received, Fully received, Cancelled), product (Raw silk, Vuda warp), date range. Actions: New purchase (`PURCHASE_CREATE`), Export CSV.

**S-PUR-02 New purchase** (FR-PROC-01, -04, -17)
| Field | Control | Validation | Default |
|---|---|---|---|
| Supplier | party picker (role Supplier, active only) | required | — |
| Product | segmented: Raw silk / Vuda warp | required | Raw silk |
| Purchase date | date | required, not after today | today |
| Ordered weight (kg) | weight field | required, > 0.000, 3 decimals | — |
| Rate (₹/kg) | money field | required, ≥ 0.00 | — |
| Payment type | segmented: Credit / Cash | required | Credit |
| Credit days | integer | ≥ 0; forced to 0 and disabled for Cash | supplier's default credit days |
| Supplier bill number | text | ≤ 50 | — |
| Notes | multi-line | ≤ 1000 | — |
Read-only line under the form: "Value and due date are calculated on save". Action: Save -> `POST /api/v1/purchases`. Errors: `INVALID_PARTY_ROLE`, `PARTY_INACTIVE` (shown at the supplier field), `VALIDATION_FAILED`.

**S-PUR-03 Purchase detail** (FR-PROC-05, -06, -11, -12, -15, -16, FR-PAY-23, FR-DOC-03)
Header: number, status chip, supplier (link), product, date, ordered kg, rate, value, due date, payable, overdue days. Sections: Receipts table (number, date, received, accepted, rejected kg), Returns table (number, date, kg, reason), Payments allocated (payment no, date, amount), Documents (S-DOC-02). Buttons:
| Button | Permission | Enabled when | API |
|---|---|---|---|
| Receive silk | `PURCHASE_CREATE` | status Ordered / Partially received | S-PUR-04 |
| Return to supplier | `PURCHASE_CREATE` | accepted − returned > 0 | S-PUR-05 |
| Edit notes / bill no | `PURCHASE_CREATE` | always | `PATCH /api/v1/purchases/{id}` (only `supplierBillNumber`, `notes`) |
| Cancel purchase | `PURCHASE_CREATE` | nothing received and nothing allocated | `POST /api/v1/purchases/{id}/cancel` (confirmation) |
| Pay supplier | `PAYMENT_CREATE` | payable > 0 | S-PAY-02 pre-filled |
| Attach document | `DOCUMENT_MANAGE` | always | S-DOC-02 |
Errors: `INVALID_STATE_TRANSITION` on cancel ("This purchase already has receipts or payments and cannot be cancelled").

**S-PUR-04 Receive silk dialog** (FR-PROC-08, -09, -10, -19) — idempotent submit.
| Field | Control | Validation | Default |
|---|---|---|---|
| Receipt date | date | required, not after today, not before purchase date | today |
| Received weight (kg) | weight | required, > 0, ≤ remaining kg shown above the field | remaining kg |
| Accepted (kg) | weight | ≥ 0, accepted + rejected ≤ received | = received |
| Rejected (kg) | weight | ≥ 0 | 0.000 |
| Notes | text | ≤ 1000 | — |
Shows "Remaining to receive: 70.000 kg" (from the purchase response). Action: Save -> `POST /api/v1/purchases/{id}/receipts`. Errors: `PURCHASE_RECEIPT_EXCEEDED` (at the received field), `INVALID_STATE_TRANSITION`, `CONCURRENT_MODIFICATION` (reload the purchase and keep the dialog values).

**S-PUR-05 Return to supplier dialog** (FR-PROC-13) — idempotent.
Fields: Return date (date, required, default today), Weight kg (> 0, ≤ returnable kg shown), Reason (text, required, 1–500). Action: `POST /api/v1/purchases/{id}/returns`. Errors: `INSUFFICIENT_INVENTORY` ("Stock is lower than the return weight"), `VALIDATION_FAILED`.

**S-PUR-06 Supplier payables overview** (FR-PAY-19) — `GET /api/v1/payables/suppliers`. Totals: total payable, total overdue, due within 7 days. Table per supplier with expandable purchases; "Pay" button per supplier opens S-PAY-02. Permission `PAYMENT_VIEW`.

### 4.5 Inventory

**S-INV-01 Stock summary** (FR-INV-07, -14, -16) — `GET /api/v1/inventory/summary`, `INVENTORY_VIEW`.
Tiles: Raw silk available, Silk in internal WIP, Silk with manufacturers, Finished silk warp, Finished kora warp, Vuda warp. Table: product x location balances (internal locations only) and cumulative wastage per product. Click a cell -> S-INV-02 filtered by product and location. Buttons: Record wastage (S-INV-04), Adjust stock (S-INV-05) — both `INVENTORY_ADJUST`; "With manufacturers" -> S-INV-03.

**S-INV-02 Movement history** (FR-INV-08) — `GET /api/v1/inventory/movements?productType=&location=&movementType=&referenceType=&referenceId=&from=&to=`.
Columns: Number, Date, Type (business wording), Product (and "-> to product" when different), From, To, Weight kg, Reference (link to purchase/order/batch/job), Notes, Recorded by. Read-only: there is no create button for movements (FR-INV-01). Export CSV.

**S-INV-03 Material with manufacturers** (FR-INV-14) — `GET /api/v1/inventory/external-wip`. Grouped by manufacturer, then job: issued, received, wastage/difference, still outside (kg). Click a job -> S-OUT-03.

**S-INV-04 Record wastage dialog** (FR-INV-11) — idempotent.
Fields: Product (select), Source location (Raw stock / Internal WIP / External WIP; only combinations allowed for the product), Weight kg (> 0), Date (default today), Reason (required, 1–500). Action: `POST /api/v1/inventory/wastage`. Errors: `INSUFFICIENT_INVENTORY`, `INVALID_PRODUCT_FOR_OPERATION`.

**S-INV-05 Stock adjustment dialog** (FR-INV-12) — idempotent; warning text "Use only after a physical count. Stock can never go below zero."
Fields: Product, From location, To location (at least one internal; the two differ), Weight kg (> 0), Date (default today), Reason (required, 1–500). Action: `POST /api/v1/inventory/adjustments`. Errors: `INSUFFICIENT_INVENTORY`, `VALIDATION_FAILED`.

### 4.6 Orders

**S-ORD-01 Order list** (FR-ORD-05, -14, -16) — `GET /api/v1/orders?customerId=&status=&paymentStatus=&productType=&from=&to=&pending=`.
Columns: Number, Date, Customer, Items (count + first product), Total, Status chip, Payment chip (Unpaid / Partly paid / Paid), Expected date, Late chip. Quick filter chips: Pending (default on), Late, All. Actions: New order (`ORDER_MANAGE`), Export CSV.

**S-ORD-02 Order form (create / edit)** (FR-ORD-01, -02, -06, -15)
Header fields: Customer (party picker, role Customer, active, required), Order date (date, required, default today), Expected delivery date (date, optional, not before order date), Notes (≤ 1000).
Items table (at least one row; `Ctrl+Enter` adds a row, `Ctrl+Delete` removes the focused row):
| Column | Control | Validation | Default |
|---|---|---|---|
| Product | select: Silk warp, Kora warp, Raw silk, Vuda warp | required | Silk warp |
| Required weight (kg) | weight | > 0 | — |
| Rate (₹/kg) | money | ≥ 0.00 | rate of the customer's previous item of the same product, if the list endpoint returned one in this session; otherwise empty |
| Warp specification | text | ≤ 500 | — |
| Amount | read-only | — | "calculated on save" |
Total is shown only from the API response. Actions: Save -> `POST /api/v1/orders` or `PATCH /api/v1/orders/{id}`. Edit is offered only while status is Placed or Confirmed, not locked and without allocations; otherwise the form is read-only with the reason. Errors: `ORDER_LOCKED`, `INVALID_PARTY_ROLE`, `PARTY_INACTIVE`, `VALIDATION_FAILED`, `CONCURRENT_MODIFICATION`.

**S-ORD-03 Order detail** (FR-ORD-04, -07..-13, -16)
Header: number, customer, dates, status chip, payment chip, late chip, locked badge ("In work — cannot be edited"). Items table: product, required kg, rate, amount, allocated, outstanding, payment chip. Sections: linked production batches and outsourcing jobs (links), payments allocated, documents.
Status action bar (only legal next steps are shown; all need `ORDER_MANAGE`; each has a confirmation):
| Button | From status | API | Confirmation text |
|---|---|---|---|
| Confirm order | Placed | `POST /api/v1/orders/{id}/confirm` | "Confirmed orders count as money owed by the customer." |
| Mark ready | Confirmed, In progress | `POST /api/v1/orders/{id}/ready` | "All items are weighed and ready for delivery." |
| Deliver | Ready | `POST /api/v1/orders/{id}/deliver` (body: delivery date, default today) | "Stock will be reduced for every item. This cannot be undone." |
| Complete | Delivered | `POST /api/v1/orders/{id}/complete` | "Close this order. Payments can still be recorded." |
| Cancel order | Placed, Confirmed (not locked, no allocations) | `POST /api/v1/orders/{id}/cancel` | "The order is kept as cancelled." |
| Record payment | any except Cancelled | S-PAY-02 pre-filled (`PAYMENT_CREATE`) | — |
Errors: `INVALID_STATE_TRANSITION` (e.g. cancel with allocations: "Reverse the payments of this order first"), `ORDER_LOCKED`, `INSUFFICIENT_INVENTORY` on deliver ("Not enough finished stock for <product>"), `CONCURRENT_MODIFICATION`.

### 4.7 Payments

**S-PAY-01 Payment list** (FR-PAY-14) — `GET /api/v1/payments?partyId=&direction=&method=&accountId=&status=&from=&to=`.
Columns: Number, Date, Party, Direction (Received / Paid), Method, Account, Amount, Allocated, Unallocated (advance), Status chip (Recorded / Reversed). Actions: Record payment (`PAYMENT_CREATE`), Export CSV.

**S-PAY-02 Record payment** (FR-PAY-01..-04, -07..-13, NFR-USE-01) — idempotent; ≤ 5 inputs in the default path (party, amount, method, account, date).
| Field | Control | Validation | Default |
|---|---|---|---|
| Direction | segmented: Money received (customer) / Money paid (supplier, manufacturer, worker) | required | Money received |
| Party | party picker filtered by the roles allowed for the direction | required, active | pre-filled when opened from a party/order/purchase |
| Amount (₹) | money | required, > 0.00 | — |
| Method | select: Cash, UPI, Bank transfer, Cheque | required | last used on this computer |
| Account | select of active accounts; Cash method lists only Cash accounts, other methods only Bank accounts (FR-PAY-12) | required | last used account matching the method |
| Payment date | date | required, not after today | today |
| Notes | text | ≤ 500 | — |
| Allocate manually | toggle (off by default) | — | off |
When the party is selected, the panel shows the party's open items from the API (customer: `GET /customers/{id}/outstanding`; supplier: `GET /suppliers/{id}/payables`; manufacturer / worker payable endpoints) with the outstanding of each, oldest first. With "Allocate manually" on, each open item gets an amount field (≥ 0, ≤ that item's outstanding); the client checks that the entered amounts do not exceed the payment amount but does not compute what the server will allocate. With the toggle off the text reads "Allocated oldest-first by the server on save".
After a successful save the dialog switches to a result view: payment number, allocations returned by the API (item, amount), unallocated advance, and buttons "Done" / "Record another".
Errors: `PAYMENT_ALLOCATION_EXCEEDED`, `INVALID_PAYMENT_ALLOCATION`, `INVALID_PARTY_ROLE`, `PARTY_INACTIVE`, `INVALID_PAYMENT_METHOD`, `INSUFFICIENT_FUNDS` (money paid from an account without enough balance), `DUPLICATE_REQUEST`, `VALIDATION_FAILED`.

**S-PAY-03 Payment detail** (FR-PAY-14, -15, -23)
Header: number, party, direction, method, account, date, amount, status chip, notes. Allocation table (obligation, amount, released flag after reversal). Buttons: Reverse payment (`PAYMENT_REVERSE`, only when Recorded) opens a dialog with Reason (required, 1–500) and the confirmation "A compensating entry is added and the allocations are released. The original stays visible." -> `POST /api/v1/payments/{id}/reverse` (idempotent). Attach document (`DOCUMENT_MANAGE`).

### 4.8 Money: accounts, transactions, transfers

**S-FIN-01 Accounts and cash position** (FR-FIN-03, -08) — `GET /api/v1/finance/accounts`, `GET /api/v1/finance/cash-position`, `FINANCE_VIEW`.
Top: Physical cash, each bank balance, Total available. Table: Name, Type, Bank, Last 4 digits, Balance, Status. Row actions (`FINANCE_MANAGE`): Transactions (S-FIN-03), Adjust balance (S-FIN-06), Close account (`POST /api/v1/finance/accounts/{id}/close`, enabled only when the balance shown is ₹0.00; confirmation). Buttons: New account (S-FIN-02), Transfer (S-FIN-05).

**S-FIN-02 New account** (FR-FIN-01, -02)
| Field | Control | Validation | Default |
|---|---|---|---|
| Name | text | required, 1–100, unique (server enforces) | — |
| Type | segmented: Cash / Bank | required | Bank |
| Bank name | text, Bank only | required for Bank, ≤ 100 | — |
| Last 4 digits of account number | text, Bank only | exactly 4 digits | — |
| Opening balance (₹) | money | ≥ 0.00 | 0.00 |
| Opening date | date | required when opening balance > 0 | today |
The client never asks for the full account number. Action: `POST /api/v1/finance/accounts`. Errors: `DUPLICATE_RESOURCE` ("An account with this name exists").

**S-FIN-03 Account transactions** (FR-FIN-07) — `GET /api/v1/finance/accounts/{id}/transactions`: Date, Type, Direction, Amount, Running balance, Reference (link), Description. Date range filter; Export CSV.

**S-FIN-04 All transactions** (FR-FIN-07) — `GET /api/v1/finance/transactions?accountId=&type=&direction=&from=&to=&referenceType=&referenceId=`. Read-only (no create button, FR-FIN-04).

**S-FIN-05 Transfer dialog** (FR-FIN-09, -10, -18) — idempotent.
Fields: From account (active), To account (active, different from From), Amount (> 0.00), Date (default today), Notes (≤ 500). The From account's current balance (from S-FIN-01 data) is shown as information only. Action: `POST /api/v1/finance/transfers`. Errors: `INSUFFICIENT_FUNDS`, `VALIDATION_FAILED` (same account).

**S-FIN-06 Balance adjustment dialog** (FR-FIN-15) — idempotent; warning "Use only after counting cash or checking the bank statement."
Fields: Direction (Increase / Decrease), Amount (> 0.00), Date (default today), Reason (required, 1–500). Action: `POST /api/v1/finance/accounts/{id}/adjustments`. Errors: `INSUFFICIENT_FUNDS`.

### 4.9 Expenses

**S-EXP-01 Expenses** (FR-FIN-12, -13, -14) — `GET /api/v1/expenses?category=&kind=&recurrence=&accountId=&status=&from=&to=` and `GET /api/v1/expenses/summary`, `EXPENSE_CREATE`.
Top summary: totals per category for the selected month and per month for the selected year, with business and personal drawing totals shown separately. Table: Number, Date, Category, Kind (Business / Personal), Recurrence, Amount, Account, Description, Status. Row action: Reverse (Recorded only; reason required; confirmation) -> `POST /api/v1/expenses/{id}/reverse`. Buttons: Record expense, Export CSV.

**S-EXP-02 Record expense** (FR-FIN-11, NFR-USE-01) — idempotent; default path 5 inputs (category, amount, account, date, description).
| Field | Control | Validation | Default |
|---|---|---|---|
| Category | select: Transport, Electricity, Rent, Worker, Maintenance, Packing, Other | required | last used |
| Amount (₹) | money | > 0.00 | — |
| Account | select (active) | required | last used |
| Method | select limited by account type (Cash for Cash accounts; UPI / Bank transfer / Cheque for Bank) | required | Cash for Cash accounts, last used otherwise |
| Expense date | date | required, not after today | today |
| Description | text | required, 1–500 | — |
| Kind | segmented: Business / Personal drawing | required | Business |
| Recurrence | segmented: One time / Monthly | required | One time |
| Attach receipt | file chooser (optional, after save) | PDF/JPEG/PNG ≤ 10 MB | — |
Action: `POST /api/v1/expenses`, then (if a file was chosen) `POST /api/v1/documents` with reference type `EXPENSE`. Errors: `INSUFFICIENT_FUNDS`, `INVALID_PAYMENT_METHOD`.

### 4.10 Loans

**S-LON-01 Loans** (FR-LOAN-05) — `GET /api/v1/loans`: Number, Lender, Type (Bank / Private), Start date, Principal, Outstanding principal, Interest paid, Status. Button: New loan (`LOAN_MANAGE`).

**S-LON-02 New loan** (FR-LOAN-01) — idempotent.
Fields: Lender (party picker, role Lender, active, required), Loan type (Bank / Private), Principal (> 0.00), Start date (default today), Receiving account (required), Method (consistent with the account), Annual interest rate % (optional, 0–100, 2 decimals, informational), Interest notes (≤ 500). Action: `POST /api/v1/loans`. Errors: `INVALID_PARTY_ROLE`, `PARTY_INACTIVE`, `INVALID_PAYMENT_METHOD`.
Loans that already existed at go-live are entered by the go-live import (FR-LOAN-02, `10-DEPLOYMENT.md` §12), not through this screen.

**S-LON-03 Loan detail** (FR-LOAN-03..-07)
Header: lender, type, principal, outstanding principal, interest paid, status. History table: date, kind (Principal repayment / Interest), amount, account, status. Buttons (`LOAN_MANAGE`):
| Button | Dialog fields | API |
|---|---|---|
| Repay principal | amount (> 0, ≤ outstanding shown), account, method, date | `POST /api/v1/loans/{id}/principal-repayments` |
| Pay interest | amount (> 0), account, method, date, period note (≤ 200) | `POST /api/v1/loans/{id}/interest-payments` |
| Reverse (per history row) | reason (required) + confirmation | `POST /api/v1/loans/{id}/payments/{paymentId}/reverse` |
| Close loan | confirmation; enabled when outstanding = ₹0.00 | `POST /api/v1/loans/{id}/close` |
Errors: `INSUFFICIENT_FUNDS`, `VALIDATION_FAILED` (repayment above outstanding), `INVALID_STATE_TRANSITION`.

### 4.11 Chits

**S-CHT-01 Chits** (FR-CHIT-05) — `GET /api/v1/chits`: Number, Name, Total, Monthly amount, Duration, Contributions made, Remaining contributions, Payout received, Status. Button: New chit (`CHIT_MANAGE`).

**S-CHT-02 New chit** (FR-CHIT-01): Name (1–100), Total amount (> 0), Standard monthly amount (> 0), Duration months (integer > 0), Start date (default today), Notes (≤ 1000). Action: `POST /api/v1/chits`. Existing chits at go-live come from the import (FR-CHIT-08).

**S-CHT-03 Chit detail** (FR-CHIT-02..-06, -09)
Header: summary figures from `GET /api/v1/chits/{id}`. History table (date, kind, amount, status). Buttons (`CHIT_MANAGE`); every account selector lists **Cash accounts only** (cash-only rule):
| Button | Fields | API |
|---|---|---|
| Record contribution | amount (> 0, default the standard monthly amount), date, cash account | `POST /api/v1/chits/{id}/contributions` |
| Record payout | amount (> 0), date, cash account; confirmation "The chit becomes Matured" | `POST /api/v1/chits/{id}/payout` |
| Organiser payment | amount (> 0), date, cash account | `POST /api/v1/chits/{id}/organiser-payments` |
| Reverse entry | reason (required) + confirmation | `POST /api/v1/chits/{id}/entries/{entryId}/reverse` |
| Close chit | confirmation | `POST /api/v1/chits/{id}/close` |
Errors: `INVALID_PAYMENT_METHOD` (non-cash account), `INSUFFICIENT_FUNDS`, `INVALID_STATE_TRANSITION`.

### 4.12 Production

**S-PRD-01 Production batches** (FR-PROD-06) — `GET /api/v1/production-batches?status=&productType=&orderId=&from=&to=`: Number, Date, Warp type, Input kg, Output kg, Wastage kg, Discrepancy kg, Linked orders, Status. Button: New batch (`PRODUCTION_MANAGE`).

**S-PRD-02 Batch form (create / edit planned)** (FR-PROD-01, -07, -11)
Fields: Warp type (Silk warp / Kora warp), Production date (default today), Input raw silk (kg, > 0; "Raw silk available: x kg" shown from the inventory summary as information), Notes (≤ 1000). Order links table: order item picker (items of Confirmed orders of the same warp type) + allocated kg (> 0); add/remove rows. Action: `POST /api/v1/production-batches` / `PATCH /api/v1/production-batches/{id}` (Planned only).

**S-PRD-03 Batch detail** (FR-PROD-02..-05, -08, -10)
Header: number, warp type, status chip, input, output, wastage, discrepancy; links; work records of the batch (from `GET /api/v1/work-records?productionBatchId=`). Buttons (`PRODUCTION_MANAGE`):
| Button | From | Dialog | API |
|---|---|---|---|
| Start production | Planned | confirmation "x kg raw silk moves to production. Linked orders become locked." | `POST /api/v1/production-batches/{id}/start` |
| Complete | In progress | Output kg (≥ 0), Wastage kg (≥ 0), Discrepancy kg (≥ 0), Notes. The dialog shows "Input x kg = output + wastage + discrepancy" and enables Save only when the three entered values add up exactly to the input (an input check, not a business calculation; the API re-checks). | `POST /api/v1/production-batches/{id}/complete` |
| Cancel | Planned | confirmation | `POST /api/v1/production-batches/{id}/cancel` |
| Record work for this batch | In progress, Completed | opens S-WRK-02 with the batch pre-selected | — |
Errors: `INSUFFICIENT_INVENTORY` on start, `PRODUCTION_NOT_RECONCILED` on complete, `INVALID_STATE_TRANSITION`, `INVALID_PRODUCT_FOR_OPERATION`.

### 4.13 Outsourcing

**S-OUT-01 Outsourcing jobs** (FR-OUT-07) — `GET /api/v1/outsourcing-jobs?manufacturerId=&status=&orderId=&from=&to=`: Number, Manufacturer, Warp type, Issued kg, Received kg, Wastage kg, Still outside kg, Rate, Payable, Status. Button: New job (`OUTSOURCING_MANAGE`).

**S-OUT-02 New job** (FR-OUT-01, -08): Manufacturer (party picker, role Manufacturer, active), Warp type expected (Silk / Kora), Raw silk to issue (kg > 0), Rate (₹/kg ≥ 0.00), optional order item links with allocated kg, Notes. Action: `POST /api/v1/outsourcing-jobs`.

**S-OUT-03 Job detail** (FR-OUT-02..-06)
Header figures from the API; receipts table (date, received kg, wastage kg, notes). Buttons (`OUTSOURCING_MANAGE`):
| Button | From | Fields | API |
|---|---|---|---|
| Issue silk | Created | Issue date (default today); confirmation "x kg raw silk leaves the stock to <manufacturer>" | `POST /api/v1/outsourcing-jobs/{id}/issue` |
| Receive warp | Material issued, Partially received | Receipt date, Received kg (> 0), Wastage/difference kg (≥ 0), Notes; "Still outside: x kg" shown | `POST /api/v1/outsourcing-jobs/{id}/receive` (idempotent) |
| Cancel | Created | confirmation | `POST /api/v1/outsourcing-jobs/{id}/cancel` |
| Pay manufacturer | payable > 0 | S-PAY-02 pre-filled | — |
Errors: `INSUFFICIENT_INVENTORY`, `OUTSOURCE_RECEIPT_EXCEEDED`, `INVALID_STATE_TRANSITION`.

### 4.14 Work records

**S-WRK-01 Work records** (FR-WORK-04, -05, -06) — `GET /api/v1/work-records?workerId=&workType=&status=&from=&to=&productionBatchId=`, `WORK_RECORD_MANAGE`.
Default filter: current week (Mon–Sun) — the payroll week. Columns: Date, Worker, Type (Rolling / Warping), Hours or kg, Rate, Amount, Batch, Payment chip, Status. Row action: Reverse (Recorded and no active allocation; reason; confirmation) -> `POST /api/v1/work-records/{id}/reverse`. Buttons: Record work, "Pay worker" (opens S-PAY-02 for the selected worker).

**S-WRK-02 Record work** (FR-WORK-01..-03, -08, NFR-USE-01) — default path 4 inputs (worker, type, hours/kg, date).
| Field | Control | Validation | Default |
|---|---|---|---|
| Worker | party picker (role Worker, active) | required | last used |
| Work type | segmented: Rolling / Warping | required | last used for this worker |
| Hours (Rolling) | decimal, 2 decimals | > 0; only shown for Rolling | — |
| Quantity kg (Warping) | weight | > 0; only shown for Warping | — |
| Rate | money, optional | ≥ 0.00 | worker's default rate for the type (from the party record); empty means "use the worker's default" |
| Work date | date | not after today | today |
| Production batch | batch picker (In progress / Completed) | optional | pre-filled from S-PRD-03 |
| Notes | text | ≤ 500 | — |
After save the form stays open with the worker kept and the measure field cleared ("Record another" flow for weekly entry). Action: `POST /api/v1/work-records`. Errors: `INVALID_PARTY_ROLE`, `PARTY_INACTIVE`, `VALIDATION_FAILED` (hours sent for warping).

### 4.15 Reports

**S-RPT-01 Reports home** — a list of the report cards below (permission `REPORT_VIEW`). Each card opens S-RPT-02 with that report.

**S-RPT-02 Report viewer** (FR-RPT-03..-16) — one generic screen: filter bar (date range default = current month, plus report-specific filters), totals band, table, "Export CSV" (`format=csv`, 3.7), `F5` reload. States: empty "No data for this period"; error banner.
| Report | API | Filters | Columns / figures |
|---|---|---|---|
| Customer outstanding | `GET /api/v1/reports/customer-outstanding` | minimum amount | customer, outstanding, advance, unpaid orders, oldest unpaid age (sortable by outstanding) |
| Supplier payables | `GET /api/v1/reports/supplier-payables` | supplier | grouped per supplier: purchase, payable, due date, overdue days, bucket (not due / 1–7 / 8–15 / > 15) |
| Purchases | `GET /api/v1/reports/purchases` | period, supplier, product | ordered, received, accepted, rejected, returned kg, value |
| Sales and orders | `GET /api/v1/reports/sales` | period, customer, product | value placed, value delivered, kg delivered, outstanding |
| Expenses | `GET /api/v1/reports/expenses` | period, category, kind | per category / kind / month, loan interest cost |
| Inventory movements | `GET /api/v1/reports/inventory-movements` | period, product | opening, movements by type, closing per location |
| Production and wastage | `GET /api/v1/reports/production` | period | per batch input, output, wastage, discrepancy, wastage % |
| Outsourcing | `GET /api/v1/reports/outsourcing` | period, manufacturer | issued, received, wastage, outside, payable, paid, pending |
| Cash and bank | `GET /api/v1/reports/cash-bank` | period, account | opening, in/out per type, closing |
| Worker payable / payroll | `GET /api/v1/reports/worker-payable` | date range (default current week), worker | earned, paid, pending; rolling vs warping |
| Loans and chits | `GET /api/v1/reports/loans-and-chits` | — | loan principal, outstanding, interest paid; chit contributions, remaining, payout |
| Customer statement | `GET /api/v1/customers/{id}/statement?from&to` | customer (required), period | opening, debits, credits, running balance, closing |

### 4.16 Notifications

**S-NTF-01 Notifications** (FR-NTF-03, -05) — `GET /api/v1/notifications?status=&type=&from=&to=`, `REPORT_VIEW`. Opened from the top-bar bell (count of `PENDING`, refreshed every 5 minutes) or the sidebar. Columns: Date, Type (Due soon / Due today / Overdue), Supplier, Purchase (link), Message, Status chip. Read-only list in the MVP (WhatsApp delivery is POST-MVP).

**S-NTF-02 Reminder settings** (FR-NTF-01) — `GET /api/v1/notifications/configurations` (`FINANCE_VIEW`), `PUT /api/v1/notifications/configurations/{type}` (`FINANCE_MANAGE`). Per type (MVP: Supplier due): Enabled (switch), Days before due (integer 0–30, default 3), Repeat every N days while overdue (integer ≥ 1, default 1), Priority (Low / Normal / High). Save -> PUT. Shown under Settings.

### 4.17 Documents

**S-DOC-01 Documents** (FR-DOC-03) — `GET /api/v1/documents?referenceType=&referenceId=&documentType=&from=&to=`, `DOCUMENT_MANAGE`. Columns: Uploaded at, Type, File name, Size, Reference (link), Uploaded by. Row actions: Open (downloads `GET /api/v1/documents/{id}/content` to the OS temp folder and opens it with the default application), Save as (native save dialog).

**S-DOC-02 Attach document dialog** (FR-DOC-01, -02) — opened from purchase, order, expense and payment detail screens (reference pre-filled) or from S-DOC-01 (reference type None).
Fields: File (native file chooser and drag-and-drop onto the dialog; allowed extensions .pdf, .jpg, .jpeg, .png; ≤ 10 MB checked before upload), Document type (Purchase bill, Sales bill, Bank statement, Expense receipt, Other; default from the reference type). Action: `POST /api/v1/documents` (multipart) with an upload progress bar. Errors: `VALIDATION_FAILED` (type or size), `UNSUPPORTED_MEDIA_TYPE`.

### 4.18 Go-live data
Opening stock, opening customer/supplier/manufacturer/worker obligations, existing loans and chits are loaded by the one-off import runner described in `10-DEPLOYMENT.md` §12 (CSV files, dry run, reconciliation and sign-off). The desktop client has no import screen. After go-live, opening obligations appear in the party Outstanding / Payables tabs and in reports exactly like other obligations; opening balances of accounts created later are entered in S-FIN-02.

---

## 5. Client technical notes

### 5.1 Module layout (`nexora-desktop/`, a separate Gradle build at the repository root)
```
nexora-desktop/
├── build.gradle.kts            Compose Multiplatform desktop + nativeDistributions (MSI, DEB)
└── src/
    ├── main/kotlin/com/nexora/desktop/
    │   ├── Main.kt              window, theme, navigation host, Koin start
    │   ├── ui/<module>/         one package per module: screens and dialogs (Composable functions only)
    │   ├── ui/components/       reusable components (section 6)
    │   ├── viewmodel/<module>/  one ViewModel per screen, exposing StateFlow<UiState>
    │   ├── repository/          one repository per API area, returns Result types, maps errors to ApiError(code, detail, fieldErrors)
    │   ├── api/                 Ktor HttpClient setup, auth plugin (bearer + refresh), DTOs (@Serializable), BigDecimal serializer
    │   ├── di/                  Koin modules
    │   └── util/                formatting (Indian grouping), dates, idempotency keys, settings store, credential store
    └── test/kotlin/...          unit tests (view-models, repositories with Ktor MockEngine, formatters) and UI tests
```

### 5.2 Patterns
- **State:** each screen has a `UiState` data class (loading, data, error, form fields, submitting) held in a `MutableStateFlow` inside its ViewModel; Composables collect it with `collectAsState()` and send user intents to ViewModel functions. ViewModels survive navigation within the session (scoped to the navigation entry).
- **API client:** Ktor client with the CIO engine, `ContentNegotiation` + kotlinx.serialization JSON (`ignoreUnknownKeys = true`, `explicitNulls = false`), request timeout 30 s, `Auth` bearer provider for token refresh (3.2), a default `Accept: application/json` header and `Idempotency-Key` header where required.
- **Money and weight:** a custom `KSerializer<BigDecimal>` reads the JSON number token as its literal text (`JsonPrimitive.content`) and builds `BigDecimal(text)`; it writes `JsonUnquotedLiteral(value.toPlainString())`. No value passes through `Double` or `Float`.
- **Errors:** non-2xx responses are parsed as ProblemDetail (`code`, `detail`, `errors[]`); unknown codes fall back to "Something went wrong (code). Please try again or contact support."
- **DI:** Koin modules per layer (api, repository, viewmodel); no reflection-based scanning.
- **Credential store:** `com.github.javakeyring:java-keyring`, service name `SmartSilk`, account = server host + username. Only the refresh token is stored there.
- **Settings:** a small JSON file in the OS application-data folder (`%APPDATA%\SmartSilk\settings.json`, `~/.config/smartsilk/settings.json`): server URL, last username, window state, text size, last-used defaults. No tokens or passwords.
- **Packaging:** Compose `nativeDistributions` with target formats `Msi` and `Deb`, package name `SmartSilk`, vendor and version from the Gradle version, bundled JRE (jpackage), desktop shortcut and start-menu entry on Windows. MSI is built on a Windows CI runner and DEB on Linux (`10-DEPLOYMENT.md`).

### 5.3 Pinned versions (verified on Maven Central / release pages on 2026-10-08)
| Library | Coordinates | Version |
|---|---|---|
| Kotlin (JVM + Compose compiler plugin) | `org.jetbrains.kotlin` | 2.4.20 |
| Compose Multiplatform (Gradle plugin, desktop) | `org.jetbrains.compose` | 1.12.1 (released 2026-09-22) |
| Lifecycle ViewModel for Compose Multiplatform | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 |
| Ktor client (core, CIO, content-negotiation, serialization-kotlinx-json, auth, mock) | `io.ktor` | 3.6.0 |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 |
| kotlinx.coroutines (core, swing, test) | `org.jetbrains.kotlinx` | 1.11.0 |
| Koin (core, compose) | `io.insert-koin` | 4.2.2 |
| java-keyring | `com.github.javakeyring:java-keyring` | 1.0.4 |
| JUnit Jupiter | `org.junit.jupiter:junit-jupiter` | 6.0.3 (same line as the backend's Spring Boot 4.1.1 test stack) |
| JDK for build and bundled runtime | Eclipse Temurin | 21 |

### 5.4 Client tests (detail in `08-TESTING.md`)
ViewModel unit tests (state transitions, validation, idempotency-key reuse on retry), repository tests against Ktor `MockEngine` (error mapping, token refresh once, BigDecimal round-trip `12500000.50` exact), formatter tests (`₹12,50,000.50`, `1,250.500 kg`), and Compose desktop UI tests (`compose.uiTest`) for login, record payment, receive silk and record work.

---

## 6. Reusable components

| Component | Behaviour |
|---|---|
| `DataTable` | Server-side paging (20/50/100), sortable headers limited to allowed fields, keyboard row navigation (arrows, Enter, PgUp/PgDn), skeleton loading rows, empty and error slots, column widths remembered per screen, CSV export button hook. |
| `MoneyField` | Text field with `₹` prefix, accepts digits and one decimal point, max 2 decimals, max 12 integer digits (`NUMERIC(14,2)`), shows Indian grouping when not focused, exposes `BigDecimal?`. |
| `WeightField` | Same as MoneyField with ` kg` suffix, max 3 decimals, max 9 integer digits (`NUMERIC(12,3)`). |
| `DateField` | Text entry `dd-MM-yyyy` with calendar popup (`Alt+Down`), `t` = today, optional min/max date, exposes `LocalDate?`. |
| `PartyPicker` | Search-as-you-type (300 ms, ≥ 2 chars) on `GET /api/v1/parties?search=&role=&status=ACTIVE`, shows code, name, primary phone; arrow keys + Enter to pick; "New party" shortcut when permitted. |
| `EntityPicker` | Same pattern for purchases, orders/order items, batches, accounts. |
| `StatusChip` | Text + colour per status (section 8); never colour only. |
| `ConfirmDialog` | Title, record name, effect text, optional required reason field (1–500), Cancel is the default button. |
| `ErrorBanner` | Message from the code table, API detail in small text, Retry action for reads, dismissible. |
| `EmptyState` | Message + optional primary action. |
| `FieldError` | Red text under a field from `errors[]` with the same field name. |
| `SubmitButton` | Spinner while submitting, disabled during the request, carries the idempotency key for retries. |
| `ResultPanel` | Shows what the server did after a submit (number, allocations, new status). |

---

## 7. Navigation map

| Sidebar entry | Screens | Required permission to show |
|---|---|---|
| Dashboard | S-DASH-01 | `REPORT_VIEW` |
| Parties | S-PTY-01..03 | `PARTY_VIEW` |
| Purchases | S-PUR-01..06 | `PURCHASE_VIEW` |
| Inventory | S-INV-01..05 | `INVENTORY_VIEW` |
| Orders | S-ORD-01..03 | `ORDER_VIEW` |
| Payments | S-PAY-01..03, S-PUR-06 | `PAYMENT_VIEW` |
| Production | S-PRD-01..03 | `PRODUCTION_VIEW` |
| Outsourcing | S-OUT-01..03 | `OUTSOURCING_MANAGE` |
| Work | S-WRK-01..02 | `WORK_RECORD_MANAGE` |
| Money | S-FIN-01..06 | `FINANCE_VIEW` |
| Expenses | S-EXP-01..02 | `EXPENSE_CREATE` |
| Loans | S-LON-01..03 | `LOAN_MANAGE` |
| Chits | S-CHT-01..03 | `CHIT_MANAGE` |
| Reports | S-RPT-01..02 | `REPORT_VIEW` |
| Documents | S-DOC-01..02 | `DOCUMENT_MANAGE` |
| Notifications (bell) | S-NTF-01 | `REPORT_VIEW` |
| Settings | S-SET-01, S-NTF-02 (`FINANCE_MANAGE`) | always |

---

## 8. Status wording and colours

| Enum | Shown as | Colour |
|---|---|---|
| Party `ACTIVE` / `INACTIVE` | Active / Inactive | green / grey |
| Purchase `ORDERED`, `PARTIALLY_RECEIVED`, `FULLY_RECEIVED`, `CANCELLED` | Ordered, Partly received, Received, Cancelled | blue, amber, green, grey |
| Payment status `UNPAID`, `PARTIALLY_PAID`, `PAID`; flag overdue | Unpaid, Partly paid, Paid; Overdue (n days) | red, amber, green; red outline |
| Order `PLACED`, `CONFIRMED`, `IN_PROGRESS`, `READY`, `DELIVERED`, `COMPLETED`, `CANCELLED` | Placed, Confirmed, In work, Ready, Delivered, Completed, Cancelled | grey, blue, purple, teal, green, dark green, grey |
| Batch `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` | Planned, In production, Completed, Cancelled | blue, purple, green, grey |
| Job `CREATED`, `MATERIAL_ISSUED`, `PARTIALLY_RECEIVED`, `COMPLETED`, `CANCELLED` | Created, Silk issued, Partly received, Completed, Cancelled | blue, purple, amber, green, grey |
| Payment / expense / work record `RECORDED`, `REVERSED` | Recorded, Reversed | green, grey with strike-through amount |
| Account `ACTIVE`, `CLOSED` | Active, Closed | green, grey |
| Loan `ACTIVE`, `CLOSED`; Chit `ACTIVE`, `MATURED`, `CLOSED` | Active, Closed; Active, Matured, Closed | green, grey; green, teal, grey |
| Notification `PENDING`, `SENT`, `RESOLVED`, `FAILED` | Pending, Sent, Resolved, Failed | amber, blue, green, red |
All colours meet 4.5:1 contrast for the chip text against the chip background in light theme (the MVP ships a light theme only).

---

## 9. Error code -> message table (client resource strings)

| Code | Message shown to the user |
|---|---|
| `VALIDATION_FAILED` | "Please correct the highlighted fields." (field errors shown under each field) |
| `MALFORMED_REQUEST` | "The app sent something the server could not read. Update the app or contact support." |
| `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | "This action is not supported by the server version. Update the app." |
| `UNAUTHENTICATED` | "Please log in again." |
| `TOKEN_EXPIRED` | handled silently by refresh (3.2); if refresh fails: "Your session ended. Please log in again." |
| `INVALID_CREDENTIALS` | "Wrong username or password." |
| `RATE_LIMITED` | "Too many attempts. Wait a few minutes and try again." |
| `ACCESS_DENIED` | "Your account is not allowed to do this." |
| `RESOURCE_NOT_FOUND` | "This record no longer exists. It may have been changed elsewhere." |
| `DUPLICATE_BUSINESS_NUMBER`, `DUPLICATE_RESOURCE` | "A record with the same name or number already exists." |
| `DUPLICATE_REQUEST` | "This entry was already saved with different values. Reload and check before entering again." |
| `CONCURRENT_MODIFICATION` | "Someone else changed this record just now. Reload to see the latest version." |
| `INVALID_STATE_TRANSITION` | "This action is not possible in the current status." + API detail |
| `ORDER_LOCKED` | "This order is already in production or outsourcing and cannot be changed." |
| `INSUFFICIENT_INVENTORY` | "Not enough stock for this." + API detail (available kg) |
| `INSUFFICIENT_FUNDS` | "Not enough money in this account." + API detail |
| `PURCHASE_RECEIPT_EXCEEDED` | "This is more than the remaining weight of the purchase." |
| `OUTSOURCE_RECEIPT_EXCEEDED` | "This is more than the silk still with the manufacturer." |
| `PAYMENT_ALLOCATION_EXCEEDED` | "An amount is more than what is still owed, or more than the payment." |
| `INVALID_PAYMENT_ALLOCATION` | "One of the selected items does not belong to this party or is already settled." |
| `PRODUCTION_NOT_RECONCILED` | "Output, wastage and discrepancy must add up to the input weight." |
| `INVALID_PARTY_ROLE` | "This party does not have the right role for this entry." |
| `PARTY_INACTIVE` | "This party is inactive. Activate it first." |
| `INVALID_PRODUCT_FOR_OPERATION` | "This product cannot be used here (vuda warp is trade-only)." |
| `INVALID_PAYMENT_METHOD` | "This payment method does not match the account (chits are cash only)." |
| `INTERNAL_ERROR` | "Something went wrong on the server. Your data was not changed. Try again; if it repeats, contact support." |
| network failure / timeout | "Cannot reach the server. Check the internet connection and press Retry." |

---

## 10. Screen index

| Screen | Name | Module | Endpoints | FR / NFR IDs |
|---|---|---|---|---|
| S-AUTH-01 | Server setup | Auth | `GET /actuator/health`, `GET /actuator/info` | NFR-USE-04 |
| S-AUTH-02 | Login | Auth | `POST /auth/login`, `GET /auth/me` | FR-AUTH-01, -07, -11 |
| S-AUTH-03 | Session expired | Auth | `POST /auth/login`, `POST /auth/refresh` | FR-AUTH-03, -04 |
| S-SET-01 | Settings and about | Settings | `POST /auth/logout`, `GET /actuator/info` | FR-AUTH-06 |
| S-DASH-01 | Owner dashboard | Reports | `GET /dashboard/summary` | FR-RPT-01, -02, FR-NTF-06 |
| S-PTY-01 | Party list | Parties | `GET /parties` | FR-PARTY-07 |
| S-PTY-02 | Party form | Parties | `POST /parties`, `PATCH /parties/{id}` | FR-PARTY-01, -02, -04, -05, -08, -09, -13, -14, -16 |
| S-PTY-03 | Party detail | Parties | `GET /parties/{id}`, `POST /parties/{id}/deactivate`, `/activate`, `GET /customers/{id}/outstanding`, `GET /customers/{id}/statement`, `GET /suppliers/{id}/payables`, `GET /manufacturers/{id}/payable`, `GET /workers/{id}/payable` | FR-PARTY-06, -10, FR-PAY-17..-21, FR-RPT-04 |
| S-PUR-01 | Purchase list | Purchases | `GET /purchases` | FR-PROC-07, -16 |
| S-PUR-02 | New purchase | Purchases | `POST /purchases` | FR-PROC-01, -04, -17 |
| S-PUR-03 | Purchase detail | Purchases | `GET /purchases/{id}`, `PATCH /purchases/{id}`, `POST /purchases/{id}/cancel` | FR-PROC-05, -06, -11, -12, -15 |
| S-PUR-04 | Receive silk | Purchases | `POST /purchases/{id}/receipts` | FR-PROC-08, -09, -10, -19 |
| S-PUR-05 | Return to supplier | Purchases | `POST /purchases/{id}/returns` | FR-PROC-13 |
| S-PUR-06 | Supplier payables | Payments | `GET /payables/suppliers` | FR-PAY-19 |
| S-INV-01 | Stock summary | Inventory | `GET /inventory/summary` | FR-INV-07, -16 |
| S-INV-02 | Movement history | Inventory | `GET /inventory/movements` | FR-INV-01, -08 |
| S-INV-03 | With manufacturers | Inventory | `GET /inventory/external-wip` | FR-INV-14 |
| S-INV-04 | Record wastage | Inventory | `POST /inventory/wastage` | FR-INV-11 |
| S-INV-05 | Stock adjustment | Inventory | `POST /inventory/adjustments` | FR-INV-12 |
| S-ORD-01 | Order list | Orders | `GET /orders` | FR-ORD-05, -14, -16 |
| S-ORD-02 | Order form | Orders | `POST /orders`, `PATCH /orders/{id}` | FR-ORD-01, -02, -06, -15 |
| S-ORD-03 | Order detail | Orders | `GET /orders/{id}`, `POST /orders/{id}/confirm`, `/ready`, `/deliver`, `/complete`, `/cancel` | FR-ORD-04, -07..-13 |
| S-PAY-01 | Payment list | Payments | `GET /payments` | FR-PAY-14 |
| S-PAY-02 | Record payment | Payments | `POST /payments` + outstanding/payable endpoints | FR-PAY-01..-04, -07..-13, NFR-USE-01, -05 |
| S-PAY-03 | Payment detail | Payments | `GET /payments/{id}`, `GET /payments/{id}/allocations`, `POST /payments/{id}/reverse` | FR-PAY-14, -15, -23 |
| S-FIN-01 | Accounts and cash position | Money | `GET /finance/accounts`, `GET /finance/cash-position`, `POST /finance/accounts/{id}/close` | FR-FIN-03, -08, -16 |
| S-FIN-02 | New account | Money | `POST /finance/accounts` | FR-FIN-01, -02 |
| S-FIN-03 | Account transactions | Money | `GET /finance/accounts/{id}/transactions` | FR-FIN-07 |
| S-FIN-04 | All transactions | Money | `GET /finance/transactions` | FR-FIN-04, -07 |
| S-FIN-05 | Transfer | Money | `POST /finance/transfers` | FR-FIN-09, -10, -18 |
| S-FIN-06 | Balance adjustment | Money | `POST /finance/accounts/{id}/adjustments` | FR-FIN-15 |
| S-EXP-01 | Expenses | Expenses | `GET /expenses`, `GET /expenses/summary`, `POST /expenses/{id}/reverse` | FR-FIN-12, -13, -14 |
| S-EXP-02 | Record expense | Expenses | `POST /expenses`, `POST /documents` | FR-FIN-11, -18, NFR-USE-01 |
| S-LON-01 | Loans | Loans | `GET /loans` | FR-LOAN-05 |
| S-LON-02 | New loan | Loans | `POST /loans` | FR-LOAN-01 |
| S-LON-03 | Loan detail | Loans | `GET /loans/{id}`, `POST /loans/{id}/principal-repayments`, `/interest-payments`, `/payments/{paymentId}/reverse`, `/close` | FR-LOAN-03..-07 |
| S-CHT-01 | Chits | Chits | `GET /chits` | FR-CHIT-05 |
| S-CHT-02 | New chit | Chits | `POST /chits` | FR-CHIT-01 |
| S-CHT-03 | Chit detail | Chits | `GET /chits/{id}`, `POST /chits/{id}/contributions`, `/payout`, `/organiser-payments`, `/entries/{entryId}/reverse`, `/close` | FR-CHIT-02..-06, -09 |
| S-PRD-01 | Production batches | Production | `GET /production-batches` | FR-PROD-06 |
| S-PRD-02 | Batch form | Production | `POST /production-batches`, `PATCH /production-batches/{id}` | FR-PROD-01, -07, -11 |
| S-PRD-03 | Batch detail | Production | `GET /production-batches/{id}`, `POST .../start`, `.../complete`, `.../cancel`, `GET /work-records` | FR-PROD-02..-05, -08, -10 |
| S-OUT-01 | Outsourcing jobs | Outsourcing | `GET /outsourcing-jobs` | FR-OUT-07 |
| S-OUT-02 | New job | Outsourcing | `POST /outsourcing-jobs` | FR-OUT-01, -08 |
| S-OUT-03 | Job detail | Outsourcing | `GET /outsourcing-jobs/{id}`, `POST .../issue`, `.../receive`, `.../cancel` | FR-OUT-02..-06 |
| S-WRK-01 | Work records | Work | `GET /work-records`, `POST /work-records/{id}/reverse` | FR-WORK-04, -05, -06 |
| S-WRK-02 | Record work | Work | `POST /work-records` | FR-WORK-01..-03, -08, NFR-USE-01 |
| S-RPT-01 | Reports home | Reports | — | FR-RPT-15 |
| S-RPT-02 | Report viewer | Reports | `GET /reports/*`, `GET /customers/{id}/statement`, `format=csv` | FR-RPT-03..-14, -16 |
| S-NTF-01 | Notifications | Notifications | `GET /notifications` | FR-NTF-03, -05 |
| S-NTF-02 | Reminder settings | Notifications | `GET /notifications/configurations`, `PUT /notifications/configurations/{type}` | FR-NTF-01 |
| S-DOC-01 | Documents | Documents | `GET /documents`, `GET /documents/{id}/content` | FR-DOC-03 |
| S-DOC-02 | Attach document | Documents | `POST /documents` | FR-DOC-01, -02 |
All paths are relative to `/api/v1` except the actuator endpoints. 53 screens and dialogs in total.

---

## 11. Decisions made while writing

| # | Decision | Reason |
|---|---|---|
| 1 | No go-live import screen; opening stock/obligations/loans/chits come only from the import runner (`10-DEPLOYMENT.md` §12). Account opening balance stays in S-FIN-02. | The requirements give opening data no endpoints except account creation; the deployment plan defines a one-off runner with reconciliation and sign-off. |
| 2 | No printing in the MVP; CSV export only. | FR-RPT-16 requires CSV; printed/PDF bills are POST-MVP (FR-DOC-06). |
| 3 | Payment allocation preview is informational (open items from the API); with manual allocation off, the client shows "allocated oldest-first by the server" and then the server's result. | NFR-USE-02: the client never computes allocations. |
| 4 | The batch-completion dialog enables Save only when output + wastage + discrepancy equals the input. | It is an input-consistency check equal to the API rule (PRODUCTION_NOT_RECONCILED); the API still validates. |
| 5 | Targets are 32 x 32 px minimum instead of the 48 dp in NFR-USE-06. | 48 dp is a touch guideline; on desktop with a pointer and keyboard, 32 px plus full keyboard access meets the same intent. NFR-USE-06's text-scaling and contrast rules are kept unchanged. |
| 6 | Shortcuts use `Ctrl+Shift+<letter>` for the six frequent entries. | Avoids clashes with common single-modifier shortcuts (`Ctrl+P` print, `Ctrl+S` save, `Ctrl+F` find). |
| 7 | Dashboard is the landing screen only with `REPORT_VIEW`; STAFF lands on Inventory summary. | The permission matrix (`09-SECURITY.md` §3) gives the dashboard to `REPORT_VIEW` only. |
| 8 | Reminder settings live under Settings and require `FINANCE_MANAGE`. | `09-SECURITY.md` §3 places notification configuration under `FINANCE_MANAGE`. |
| 9 | Light theme only in the MVP. | No source asks for a dark theme; one theme halves contrast testing. |
| 10 | JUnit Jupiter 6.0.3 for the client tests. | It is the version the backend's Spring Boot 4.1.1 stack already uses; the newer 6.1.x line can be adopted with the backend. |
| 11 | `http://` server addresses are accepted only for `localhost` (development). | Tokens must never travel unencrypted over a network (`09-SECURITY.md`). |
| 12 | Default ranges: statement = current financial year (April–March); payroll views = current week Monday–Sunday; reports = current month. | Indian financial year and the weekly payroll described in the business analysis. |
