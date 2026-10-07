# SmartSilk (Nexora) — Product Requirements Document

**Version:** 1.0 · **Status:** Approved for implementation · **Related:** `02-REQUIREMENTS.md` (numbered requirements), `DOMAIN_RULES.md` (rules), `DECISIONS.md` (architecture decisions), `ROADMAP.md` (delivery plan).

## 1. Summary

SmartSilk (codebase name **Nexora**) is a business-visibility system for one family-run **silk-yarn trading and warping** business. It records purchases, material receipts, stock movements, customer orders, in-house production, outsourcing to other manufacturers, worker work, payments with allocation, expenses, loans, chits and cash and bank accounts. From those records it **derives** stock, work in progress, receivables, payables and the cash position and shows them to the owner on one dashboard.

It replaces ledger notebooks and memory with one connected, correct set of records. It is **not** a full accounting or ERP system; the business's auditor continues to do formal accounting from the bills and statements the business supplies.

The system will be used for real money and real stock. Correctness is more important than speed of delivery.

## 2. Business context

- **What the business does.** It buys raw silk yarn from suppliers (often on short credit), sells some of it directly, converts the rest into **warp** (kora or silk warp) either in-house (rolling, then warping) or by giving the silk to other manufacturers (job work), and sells the warp to customers by weight. It also trades **vuda warp**, which is bought and resold without production.
- **How money moves.** Customers have no fixed payment deadline and pay in advances, instalments or late, often against older orders. Suppliers have credit periods that vary and short. Workers are paid weekly (rolling by the hour, warping by the kilogram). Outsourced manufacturers are paid monthly by the kilogram of warp returned. The business also pays expenses, takes personal drawings, contributes to chits (cash only) and sometimes takes short-term loans (bank or private) with interest.
- **How it is run today.** The owner records everything in physical ledgers (customers, workers, job workers, expenses, chits) and keeps much of the supplier picture in memory. Orders arrive by phone and WhatsApp. Bills are prepared on paper, photographed and sent by WhatsApp.
- **Size.** One business, one owner as the only user at go-live, a small number of suppliers, tens of customers, a few workers. The reference workload for design is in `02-REQUIREMENTS.md` §3.

## 3. Problem statement

| # | Problem today | Consequence |
|---|---|---|
| P1 | Records are spread over many physical ledgers | The owner must combine them mentally to see the business position |
| P2 | Customers pay irregularly and against old orders | "How much does this customer owe, and which orders are settled?" is hard to answer |
| P3 | Supplier credit periods vary and penalties may apply | Due dates can be missed |
| P4 | Raw silk, work in progress, finished warp and material with job workers are not reconciled | Material loss and stock at external manufacturers are invisible |
| P5 | Business money, personal drawings, chit contributions and loans are mixed | Profitability and cash planning are unreliable |
| P6 | Cash and several bank accounts are tracked separately | No single "money available now" figure |
| P7 | Bills are prepared and shared by hand | Repetitive manual work |
| P8 | Knowledge sits in one person's head and notebooks | Nobody else can reconstruct the position |

## 4. Users and roles

| User | Role in the system | Access |
|---|---|---|
| **Owner** (business owner) | Does all operational and financial recording; reads the dashboard and reports. The only user at go-live. | Role `OWNER`: every permission |
| **Staff** (reserved) | A read-only role is seeded so access control can grow; no staff accounts are created in the first release. | Role `STAFF`: view parties, inventory, orders, production |
| **External parties** (no login) | Customers, suppliers, manufacturers (job workers), workers, lenders, chit organisers, the auditor | Represented as records, never as users |

A *user* (someone who logs in) is deliberately separate from a *party* (an external person or business).

## 5. Goals and success criteria

| # | Goal | Measurable success criterion |
|---|---|---|
| G1 | Give the owner one trustworthy picture of the business | The dashboard shows cash, each bank balance, receivables, payables, stock by stage, material with manufacturers, pending orders and upcoming supplier dues from one call that answers in 500 ms or less (95th percentile) on five years of data |
| G2 | Replace the notebooks for daily work | The owner completes the 17 daily tasks listed in §7.4 in the system, with no notebook entry, during a parallel run of 2 to 4 weeks |
| G3 | Never show impossible numbers | Stock and cash balances are never negative and balances always equal the sum of their records: 0 violations in a randomized test of 10000 operations and in the daily reconciliation check |
| G4 | Make settlement unambiguous | Customer outstanding is available per customer and per order within 300 ms, and every payment shows exactly how it was allocated |
| G5 | Prevent missed supplier payments | A reminder exists for every unpaid supplier purchase 3 days before its due date (configurable), on the due date and daily when overdue |
| G6 | Make material loss visible | Every production batch and outsourcing job shows input, output, wastage and discrepancy; wastage is reported by source |
| G7 | Keep history auditable | No financial or inventory record is deleted or edited in place; every correction is a visible reversal or adjustment with a reason and the acting user |
| G8 | Be safe to run | Nightly off-site backups, a restore tested before go-live and quarterly (recovery within 4 hours, at most 24 hours of data lost), and monthly availability of at least 99.0% |
| G9 | Start from the real position | At go-live every stock figure, account balance, customer outstanding, supplier payable, loan and chit equals the owner's notebook total (differences 0 or documented) |

## 6. Scope

### 6.1 In the first release (MVP)

- **Client:** a desktop application for Windows 10/11 and Linux (Ubuntu 22.04+), installed on the shop computer(s) and connected over HTTPS to one central server that hosts the API and the database (docs/06-UI-SPEC.md, ADR-035).

- **Access:** login for the owner, short-lived tokens with refresh, permission-based authorization, audit identity on every record.
- **Parties:** customers, suppliers, manufacturers, workers and lenders with several roles per party, phone numbers, supplier credit terms and worker default rates.
- **Procurement:** purchases of raw silk and vuda warp, partial receipts with accepted and rejected weight, returns to the supplier, due dates, supplier payable and overdue status.
- **Inventory:** a ledger of material movements, current stock by stage (raw, in rolling/warping, with manufacturers, finished warp), no negative stock, manual wastage, owner adjustments, opening stock.
- **Orders:** customer orders with items (raw silk, silk warp, kora warp, vuda warp), status, locking once work starts, delivery that moves stock, direct sale of raw silk and vuda warp through the same order flow.
- **Production and outsourcing:** in-house batches with reconciliation (input = output + wastage + discrepancy); outsourcing jobs with issue and partial receipts, material still outside, manufacturer payable.
- **Workforce:** work records for rolling (hours) and warping (kilograms) with the rate fixed at the time of work.
- **Payments:** customer, supplier, manufacturer and worker payments with oldest-first or explicit allocation, advances, reversal, retry safety, outstanding and payable views, opening receivables and payables.
- **Finance:** cash and bank accounts, opening balances, expenses and personal drawings, transfers, balance adjustments, available money, loans (principal and interest separately), chits (cash only), transaction history.
- **Visibility:** the owner dashboard, reports (customer outstanding and statement, supplier payables, purchases, sales, expenses, inventory movements, production and wastage, outsourcing, cash and bank, worker payable, loans and chits) and CSV export for audit.
- **Reminders:** supplier due reminders with a daily scheduled job.
- **Documents:** attach bills, statements and receipts to records; download them.
- **Operations:** container deployment with HTTPS, CI, backups with tested restore, runbook, go-live import and reconciliation.

### 6.2 After the first release (POST-MVP)

- **Mobile client** (Android), reusing the desktop client's screens and view-models.

User management and more roles; customer-specific credit periods; refunds, customer returns, pending and bounced cheques, supplier advances; configurable expense categories; generated sales bills, sharing by WhatsApp, supplier-bill extraction (OCR) and GST fields; low-stock and other alerts, loan and chit reminders, WhatsApp notifications; interest and chit calculations; profit and loss and profitability analysis; production and worker efficiency analytics; bank integration; Tamil language in the client; voice entry and advisory features that always require the owner's confirmation and never change financial records by themselves.

### 6.3 Non-goals

The first release will **not** include: microservices, Kafka, Kubernetes, a cache server, multiple databases, multi-company or multi-currency support, a full double-entry accounting engine or ERP permissions, automatic GST filing, automatic purchasing, automatic payments, automatic daily silk-price prediction, detailed warp dimension modelling (length, width, thread count), lot-level costing (FIFO) or lot traceability, and forecasting. Anything not listed in §6.1 is out of scope until it is requested as a requirement change.

## 7. Key business flows

### 7.1 Material flow

```text
Supplier ──purchase──▶ Material receipt (accepted / rejected kg)
                              │ accepted kg
                              ▼
                        RAW STOCK (raw silk, vuda warp)
        ┌─────────────────────┼──────────────────────┐
        ▼                     ▼                      ▼
  Direct sale          In-house production     Outsourcing job
  (via an order)       (batch: consume)        (issue to manufacturer)
        │                     │                      │
        │              INTERNAL WIP              EXTERNAL WIP
        │                     │ complete             │ receive
        │                     ▼                      ▼
        │               FINISHED STOCK (silk warp, kora warp)
        │                     │ deliver
        └──────────▶ CUSTOMER ◀┘
   Losses leave WIP/stock as WASTAGE (and a visible DISCREPANCY); returns to suppliers leave as RETURNED.
```

### 7.2 Sales and settlement flow

```text
Customer order (PLACED) ─confirm─▶ CONFIRMED ─(production / outsourcing starts)─▶ IN_PROGRESS (locked)
      ─ready─▶ READY ─deliver (stock moves)─▶ DELIVERED ─complete─▶ COMPLETED
Customer payment ─▶ allocated oldest-first to confirmed order items; any excess is a customer advance
Order payment status (UNPAID / PARTIALLY_PAID / PAID) is derived, never typed in.
```

### 7.3 Money flow

```text
Customer receipts ───────────────▶ ┐
Loan received ───────────────────▶ │
Chit payout ─────────────────────▶ │  CASH / BANK accounts  (balances derived, never negative)
Transfers between own accounts ◀─▶ │
                                    ▼
Supplier, manufacturer and worker payments, expenses, personal drawings,
chit contributions, loan principal and interest ◀── every movement is a financial transaction
```

Revenue is only the value of delivered sales. Loans, transfers, chit money, opening balances and advances are never revenue; loan principal and chit contributions are never expenses; personal drawings are reported separately from business expenses.

### 7.4 What the owner must be able to do without notebooks (acceptance for the first release)

1. Record a purchase. 2. Record a full or partial receipt. 3. Return poor-quality material. 4. See raw silk available. 5. Create a customer order. 6. Produce warp in-house. 7. Send silk to an outside manufacturer. 8. Record warp received from the manufacturer. 9. See silk currently outside. 10. Record a customer payment. 11. See customer outstanding. 12. Record a supplier payment. 13. See supplier payable and what is due. 14. Record expenses and drawings. 15. See cash and each bank balance. 16. See pending orders and commitments. 17. Review recent activity from one dashboard.

## 8. Assumptions

### 8.1 Business assumptions for the owner to confirm (Q1 to Q8)

These were open questions. A default was chosen so that building can proceed; each is isolated in one place in the design and is easy to change.

| # | Question | Default used | Status |
|---|---|---|---|
| Q1 | Is supplier payable based on ordered or accepted kg, and how do returns reduce it? | Payable = **accepted kg × the purchase rate**; rejected kg are not paid; a return reduces the payable at the original rate. | ASSUMPTION - confirm with owner |
| Q2 | When does a customer obligation start? | At order **confirmation**. Payments before confirmation are held as an advance and applied when the order is confirmed. An order with allocations cannot be cancelled until the payment is reversed. | ASSUMPTION - confirm with owner |
| Q3 | Supplier overdue penalty rule | Reminders only (3 days before due date by default, on the due date, daily when overdue). **No penalty amount is computed**; overdue days are shown. | ASSUMPTION - confirm with owner |
| Q4 | Can a bank account go negative (overdraft)? | **No.** Neither cash nor bank accounts can go negative. | ASSUMPTION - confirm with owner |
| Q5 | Rounding of rupee amounts | Keep **paise**: item amount = weight × rate rounded to 2 decimals (half up); totals are sums of rounded item amounts. | ASSUMPTION - confirm with owner |
| Q6 | Chit bidding and payout calculation | Not modelled. Contributions, payouts and organiser payments are **amounts entered by the owner**, cash only. | ASSUMPTION - confirm with owner |
| Q7 | Direct sale of raw silk | Goes through a normal **customer order** with items of type raw silk or vuda warp; no separate sale entity. | ASSUMPTION - confirm with owner |
| Q8 | Who logs in at go-live? | **Only the owner.** A read-only staff role exists but no staff accounts or user-management screens. | ASSUMPTION - confirm with owner |

### 8.2 Other assumptions

- One business (single tenant), one currency (rupees), one time zone (Asia/Kolkata for business dates; timestamps stored in UTC).
- Weight in kilograms is the only quantity that matters for stock, orders and production; warp dimensions are out of scope.
- The owner enters prices manually; the system never predicts prices.
- Cheques are recorded when they are cleared (pending and bounced cheques are post-MVP).
- Notebooks cannot be converted automatically; the opening position is entered by hand and reconciled with the owner (see goal G9).
- A supplier's credit period is chosen per purchase, defaulting from the supplier.

## 9. Constraints and risks

| Item | Detail |
|---|---|
| Architecture | One modular monolith (Java 21, Spring Boot 4, PostgreSQL 17); no distributed components (ADR-001) |
| Team and time | One developer; the first release must be small enough to finish and use; scope is frozen to §6.1 |
| Correctness | A wrong balance or stock figure is worse than a missing feature (rules in `DOMAIN_RULES.md`) |
| Hosting | Container on a small VPS with HTTPS, backups off the host (ADR-023) |
| Risk: financial correctness | Mitigated by ledgers, locking, idempotency keys, and tests for every rule |
| Risk: notebook data is incomplete or inconsistent | Mitigated by opening-balance entry, reconciliation with sign-off and a 2 to 4 week parallel run |
| Risk: the owner stops using it | Mitigated by at most 5 inputs for daily entries, one dashboard, and big readable numbers |
| Risk: unconfirmed business rules | Mitigated by §8.1 defaults isolated in one place each |
| Risk: external services fail | Notification and file storage failures never roll back a transaction |

## 10. Release plan

The delivery plan in `ROADMAP.md` builds vertical slices in this order: foundation, identity and parties, procurement and inventory, orders and payments (the MVP loop), production and outsourcing, finance completion, dashboard and reports, hardening, deployment and go-live. The desktop client starts after the orders-and-payments slice. Each phase ends with working, tested endpoints and a merge to the main branch.

## 11. Glossary

| Term | Meaning |
|---|---|
| Raw silk | Silk yarn bought from suppliers; measured in kg |
| Warp | Processed silk sold to customers; silk warp and kora (normal) warp |
| Vuda warp | A warp product that is bought and resold, never produced or outsourced |
| Rolling / warping | The two in-house production steps; rolling is paid per hour, warping per kilogram |
| WIP | Work in progress: internal (in-house batch) or external (with a manufacturer) |
| Job work / outsourcing | Giving raw silk to another manufacturer who returns warp |
| Party | An external person or business; may hold several roles |
| User | A person who logs in; not the same as a party |
| Material movement | A record of a stock change; stock is the sum of movements |
| Financial transaction | A record of a money change; account balance is the sum of transactions |
| Allocation | How a payment is split across obligations |
| Advance | Money received from a customer that is not yet allocated to an obligation |
| Outstanding / payable | Amount still owed by a customer / to a supplier, manufacturer or worker; always derived |
| Reversal | A compensating record that corrects a mistake without editing history |
| Personal drawing | Business money taken for personal use; reported separately from expenses |
| Chit | A chit-fund investment: monthly cash contributions, one lump-sum payout |
| Business number | Human-readable id such as `PUR-2026-0012` |
| Idempotency key | A client-supplied key that makes a retried request safe |
