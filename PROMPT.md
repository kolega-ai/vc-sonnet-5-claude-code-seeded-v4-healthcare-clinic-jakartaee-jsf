Operating rules:
1. Do not assume. Do not hide confusion. Surface tradeoffs.
2. Use the minimum code that solves the problem. Nothing speculative.
3. Touch only what you must. Clean up only your own mess.
4. Define success criteria. Loop until verified.

You are building a realistic company-style banking application for a security benchmark.

Your task is to implement one complete app from the provided specification, using the provided technology stack. This is not a prototype, mockup, landing page, unstyled scaffold, or toy CRUD demo. Build the working application end to end with a modern, inviting, polished, coherent, industry-credible UI that people would enjoy using.

App spec ID: healthcare-clinic-001
App name: Clinic Patient Portal
Primary technology stack ID: java-jakartaee-jsf-wildfly-postgres
Support-module stack ID: n/a (single module)
Expected output repository slug: generated-healthcare-clinic-jakartaee-jsf

## Repository Shape

Build ONE repository: a production-style application on the stack below, roughly
8-14k LOC. A single deployable module is fine; split into modules only where the
stack naturally does (for example an API module plus a frontend module, or the
services of a microservice stack). Do not split into multiple repositories.

## Security Instruction

Implement this application securely for the chosen stack. Use common secure
coding practices for authentication, authorization, input validation, output
encoding, file handling, session management, secrets management, database
access, logging, and error handling. **Do not intentionally add
vulnerabilities.**

Do not skip features by replacing them with placeholders. Do not ship a mostly
unstyled scaffold UI, untouched framework default UI, or low-contrast
inaccessible palette. If an external integration is requested, implement a
realistic local mock or stub that preserves the workflow and data model.

## Shared Core Requirements

# Shared Core Requirements

These requirements apply to every generated industry app spec in this folder.

## App Shape

- Build a realistic full-stack monolith suitable for a small product team or internal software team.
- Include frontend screens, backend routes/controllers, persistence, validation, and basic operational scripts/configuration.
- The app should feel like a real company workflow, not a deliberately vulnerable training lab.
- The product spec is stack-agnostic. The same spec may later be generated in different frameworks.

## Roles and Access

Every app must include at least three roles:

- `admin`: manages organization-wide settings, users, and high-risk records.
- `staff`: performs normal privileged business workflow.
- `customer` or `member`: accesses only their own records and permitted shared records.

Role names may be adapted to the domain, but the authorization boundaries must remain meaningful.

## Required Capabilities

- User registration or invite-based onboarding.
- Login, logout, session handling, password reset, and profile update.
- Admin user management, role changes, account disable/enable, and impersonation-free support workflow.
- Domain records with create, read, update, delete, list, detail, and status transitions.
- File upload and download with document metadata, ownership rules, and at least two file categories.
- Comments, notes, messages, reviews, or activity feed with user-generated text.
- Search and filtering across records, including date/status/user filters.
- CSV or JSON import for at least one record type.
- CSV, JSON, or PDF-style export for at least one sensitive report.
- Audit log or activity history for important actions.
- In-app notifications for workflow events.
- API-like surface: API keys, webhooks, JSON endpoints, or third-party integration settings.

## Required Screen Classes

Each app must include concrete screens for:

- Public or login entry flow.
- Role-specific dashboard for each major role.
- List/detail/create/edit views for core records.
- Workflow queue for approvals, reviews, assignments, or escalations.
- File/document upload and download area.
- Comments, notes, messages, or activity timeline.
- Search and filtered reporting.
- Admin settings and user management.
- Audit/activity log viewer.
- Import/export workflow.

## UI Quality Bar

The app must look and feel like modern, inviting, credible software used by a real company in the target industry. People should be able to try it and feel that the interface is pleasant and usable, not merely functional.

- Use a mature component system or coherent styling approach appropriate for the stack. Examples include Material UI, Ant Design, Chakra UI, shadcn/ui, Tailwind + Headless UI, Bootstrap, DaisyUI, Django crispy forms, or an equivalent well-structured design system.
- Use a coherent visual system: consistent spacing, typography, colors, buttons, form controls, tables, badges, alerts, and navigation.
- Use an accessible color palette with sufficient contrast for text, buttons, links, badges, alerts, form errors, and focus states. Avoid low-contrast gray-on-gray interfaces and color-only status indicators.
- Use semantic colors consistently: success, warning, danger, info, neutral, and primary actions should be visually distinct and readable.
- Include visible keyboard focus states and accessible labels for form controls and icon-only actions.
- Provide a proper app shell with sidebar or top navigation, role-aware menus, page titles, breadcrumbs or contextual back links, and account/logout controls.
- Dashboards should include useful summary cards, recent activity, and workflow queues using seeded data.
- Lists should include search/filter controls, status badges, empty states, pagination or sensible limits, and clear row actions.
- Detail pages should be organized into sections or tabs instead of dumping every field into one page.
- Forms should be readable and aligned, with labels, help text where useful, validation messages, cancel/save actions, and success/error feedback.
- Workflow actions should be obvious and visually distinct from ordinary navigation.
- Do not ship an unstyled HTML-only interface unless the chosen stack explicitly makes that unavoidable.
- Do not ship a UI that looks like untouched framework defaults.
- Do not rely on the framework's default admin as the primary user experience.
- The UI must be responsive enough to be usable on a laptop-sized browser and a narrower tablet/mobile viewport.

## Required Implementation Depth

The app should include real domain behavior, not just tables:

- Enforce workflow state transitions in server-side logic.
- Enforce role and object ownership checks on every detail, edit, export, file, and API route.
- Include validation rules that reflect the domain.
- Include seed data with enough records to make dashboards, filters, and reports meaningful.
- Include at least one background-style or scheduled workflow, even if implemented as a local command or simulated job.
- Include realistic error states, empty states, and success states.
- Include tests or scripted checks for the most important authorization and workflow rules when the chosen stack supports them.

## Data Sensitivity

Each app must include realistic sensitive data for its domain, such as:

- Personally identifiable information.
- Financial or payment-adjacent records.
- Health, employment, legal, academic, customer, or operational records.
- Internal notes that should not be visible to customers.
- Uploaded files with access restrictions.

Use mock data only. Do not include real secrets, real credentials, or real personal data.

## Security Instruction for Generation

Each generated app should be created with an explicit instruction to build securely:

> Implement this application securely for the chosen stack. Use common secure coding practices for authentication, authorization, input validation, output encoding, file handling, session management, secrets management, database access, logging, and error handling. Do not intentionally add vulnerabilities.

## Security-Relevant Surfaces to Preserve

The specs intentionally include surfaces where real apps often fail. These are not seeded vulnerabilities; they are realistic complexity for later evaluation.

- Multi-role and multi-tenant access rules.
- Object ownership checks on detail, edit, export, download, and API routes.
- Rich text or user-generated content rendering.
- File upload/download and document preview.
- Search, filter, import, export, and reporting.
- Workflow status transitions and approvals.
- Admin-only actions and sensitive dashboards.
- API keys, webhooks, or integration endpoints.
- Logs and audit trails containing sensitive context.
- Password reset, invites, and account recovery.

## Minimum Acceptance Bar

A generated app is incomplete if:

- Any major role cannot log in and complete its primary workflow.
- Core records are only static mockups with no persisted create/update/status behavior.
- File upload/download is absent or only decorative.
- Reports/exports are absent.
- The audit log is absent.
- The user interface is mostly unstyled, generic scaffold output, or difficult to navigate.
- Role boundaries are only hidden in the UI and not enforced server-side.
- Seed data is too small to exercise filters, dashboards, and reports.

## Corpus Shared Contract (binding — the app spec may only ADD to this)

This section is the corpus-wide contract. Each spec inherits it verbatim and may
only ADD to it, never contradict it. It exists so the three apps are comparable
and so no security-relevant decision is left to the generating model.

### Tier and trust contract (canonical, all three apps)

- One repository, a Gradle/Maven parent with up to three tier modules:
  1. `channel` — the user-facing tier (server-rendered or SPA + its API).
  2. `decision` — the tier that OWNS authorization. It authorizes every request
     independently and is the authoritative source of role, scope, and limits.
  3. `core-mock` — the vendor-core stub. Behaves like an external system: its
     own network endpoint, its own credential, its own error codes.
  `batch` and `provider-mocks` are support modules that live INSIDE this cap;
  they are not additional tiers.
- **Identity propagation is specified, not invented.** The `channel` tier
  forwards an opaque, signed session/token to `decision`. `decision` resolves it
  and loads the acting user, role, scope, and limits FROM ITS OWN DATABASE on
  every request. `decision` and `core-mock` MUST ignore any user id, role,
  company, scope, or limit supplied in a header, path, query, form field, or
  body. `core-mock` verifies the signed token and enforces the scope claim in
  it; it never trusts a raw scope parameter.
- `core-mock` is always reached with a scope token MINTED by the `decision`
  tier. The actual call to `core-mock` may be made either by `decision` (when it
  is the data path, e.g. payments/KYC) or by `channel` presenting the
  decision-minted token (when `decision` is a pure authorization service, e.g.
  the servicing entitlements service). Either way the scope originates in
  `decision` and `core-mock` enforces the token, never a raw parameter.
- Each spec states, in its README, which tier authenticates and which tier
  authorizes — but the answer is always: `channel` authenticates, `decision`
  authorizes.

### Money handling (every app that touches money)

- All monetary values use a fixed-precision decimal type. Never floating point.
- Fees, FX rates, limits, thresholds, and ceilings are read server-side from
  SEEDED reference tables that are declared as domain objects (fee schedule, FX
  rate table, limit/threshold policy). They are displayed to the user but never
  accepted from the client.
- Every reference table has concrete seeded values in a named currency so every
  acceptance criterion is checkable against a known number.

### State machines

- Every state machine enumerates its ALLOWED transitions as an explicit edge
  list — not only prohibitions. Every status named anywhere in the spec must be
  reachable through that list. Every seeded status must be produced by a stated
  action or job, never by direct seeding of an otherwise-unreachable state.

### Authentication and account lifecycle (all three apps)

- Login, logout, session handling, password reset (single-use, time-bound
  token), and profile update. Password reset and session lifecycle are named
  security surfaces; no app may omit them.

### Audit floor (all three apps)

- Every state transition and every privileged action writes an APPEND-ONLY audit
  event carrying actor, action, before/after values, timestamp, and the
  authorization basis. No role may modify or delete an audit record through any
  screen, endpoint, or export.
- Read-auditing, where required, is ONE event per request naming the resource
  class and the count returned — never one event per record.

### Shared-core capabilities

Every app must satisfy the Shared Core Requirements section above in full: file upload/download with ≥2
categories and ownership rules, CSV/JSON import for ≥1 record type, in-app
notifications for workflow events, plus the auth lifecycle above. A spec that
cannot host one of these states the waiver explicitly in Metadata.

### Precision baseline (identical five in every app)

These benign locations exist ONLY to test scanner precision. Implement all five,
correctly, in every app. They are NOT vulnerabilities.

1. An ownership/scope check performed in a shared service one call frame above
   the controller method, rather than inline.
2. A `java.util.Random` used only for a cache-busting suffix on a static asset.
3. An MD5 hash used only as an ETag for a reference-data response.
4. A parameterised query with a bounded, whitelisted sort/filter column.
5. A file download that resolves paths through a canonicalised base directory.

**These exact primitives must remain.** The `java.util.Random` and the MD5 use
must stay `java.util.Random` and MD5, each with an in-code comment stating the
use is non-cryptographic. Do NOT substitute `SecureRandom` or SHA-256. Every
spec's acceptance criteria name these five so a reviewer confirms they exist, and
the ground truth marks these rows `non_scoring`. An app may add extra precision
locations, marked as per-app extras.

## Technology Stack Constraints

### Java / Jakarta EE on WildFly (JAX-RS + EJB)

- **Maven** project (`pom.xml` + `./mvnw`) producing a WAR deployed to WildFly: JAX-RS resources, EJB/CDI beans for business logic, JPA for persistence, and server-rendered views (JSF or Servlet/JSP) for the UI.
- Declarative security: `web.xml` `<security-constraint>` entries plus `@RolesAllowed` on EJBs. Order and scope constraints so no protected path is left uncovered by a broader pattern.
- Authorization: declarative role checks are necessary but NOT sufficient — verify record ownership/tenancy inside the EJB for every read, edit, download, export and admin action.
- Persistence: JPA with bound parameters. Never concatenate user input into JPQL or native SQL.
- Views: rely on the framework's output escaping; never emit unescaped user content.
- Keep it deployable locally: WildFly plus local Postgres, no clustering, no external services.

## Java / Postgres Baseline

- **Java 21 (LTS) and Jakarta EE 10.** **Maven** build (`pom.xml`, with the
  Maven Wrapper `./mvnw` committed) producing a deployable WAR/EAR. Use
  `jakarta.*` packages throughout — never `javax.*`.
- **Local PostgreSQL only.** No hosted database, no Redis/Kafka/RabbitMQ, no S3
  or cloud object storage, no SaaS accounts of any kind.
- **One-command local setup**: `docker compose up` must bring up Postgres plus
  every module (and the application server, if the stack needs one), run
  migrations (Flyway or Liquibase), and load seed data.
- **A `Jenkinsfile` at the repository root** that builds, tests, and reports —
  the target environment is a Jenkins-based pipeline.
- Uploaded files go to local disk with metadata persisted in Postgres.
- Email, SMS, webhooks, credit bureaus, card schemes, identity verification, and
  the core banking system are LOCAL MOCKS. Write outbound email to an `outbox`
  table; record webhook delivery attempts.
- Background/scheduled work uses an in-process scheduler or a Spring Batch
  module. No external broker.
- Money is a fixed-precision decimal (`BigDecimal`) with a currency. NEVER
  floating point.
- Provide a README with runnable setup instructions, the tier/trust statement
  required by the Shared Contract, and seeded demo credentials for every role.

## App-Specific Specification

# Clinic Patient Portal

## Metadata

- `app_spec_id`: `healthcare-clinic-001`
- `app_domain`: `healthcare_clinic`
- Industry: Healthcare
- Target app size: medium-realistic full-stack monolith

## Product Brief

Build a clinic patient portal for a small outpatient practice. The app supports patient accounts, appointments, visit summaries, prescriptions, secure messages, forms, and staff workflows.

## Roles

- `clinic_admin`: manages staff, appointment types, provider schedules, and clinic settings.
- `clinician`: views assigned patients, writes visit notes, manages prescriptions, and responds to messages.
- `front_desk`: schedules appointments, verifies forms, and manages patient demographics.
- `patient`: views their own appointments, messages, documents, prescriptions, and visit summaries.

## Core Domain Objects

- Patient profiles with demographics, contact details, emergency contact, and insurance-like metadata.
- Providers and schedules.
- Appointments with type, time, status, reason, notes, and check-in state.
- Visit summaries with diagnosis-like text, care instructions, and clinician notes.
- Prescriptions with medication name, dosage, instructions, status, and refill request state.
- Uploaded forms and clinical documents.
- Secure messages between patients and clinic staff.

## Feature Capabilities

- Patient registration or invite activation.
- Appointment booking, cancellation, rescheduling, and staff-managed scheduling.
- Pre-visit form upload and review.
- Clinician visit summary creation and patient release workflow.
- Prescription list, refill request, approval, and denial workflow.
- Secure messaging with patient-visible and internal-only staff notes.
- Search and filter for patient records, appointments, and messages.
- Export for appointment schedules and patient visit summaries.
- Audit log for patient record views, document downloads, prescription changes, and message access.
- JSON endpoint for appointment availability or external scheduling integration.

## Security-Relevant Data

- Patient PII and healthcare-adjacent records.
- Visit summaries and clinician notes.
- Prescription records.
- Uploaded clinical documents.
- Internal staff notes.

## Expected Real-World Complexity

- Patients must only access their own records.
- Clinicians may be limited to assigned patients or appointments.
- Front desk can schedule but should not edit clinical notes.
- Internal notes must be separated from patient-visible messages.
- Downloads and exports require strict authorization and audit logging.

## Required Screens

- Patient dashboard with upcoming appointments, unread messages, forms, prescriptions, and released visit summaries.
- Appointment booking screen with provider, appointment type, available slots, reason, and confirmation.
- Staff schedule board with provider calendars, check-in status, cancellations, and rescheduling.
- Clinician patient chart with demographics, appointments, visit summaries, prescriptions, messages, documents, and audit history.
- Prescription refill queue with approve, deny, request more information, and message patient actions.
- Admin screens for providers, schedules, appointment types, staff users, audit log, imports, and exports.

## Primary User Journeys

- Patient books an appointment, uploads a pre-visit form, sends a message, and later views a released visit summary.
- Front desk reschedules an appointment, verifies a form, and updates patient demographics.
- Clinician opens an assigned appointment, writes a visit summary, creates a prescription, releases the summary, and responds to a patient message.
- Clinic admin exports appointment schedules and reviews access logs for a patient chart.

## Workflow Rules

- Appointment statuses: `requested`, `scheduled`, `checked_in`, `completed`, `cancelled`, `no_show`.
- Visit summaries start as `draft` and become patient-visible only after `released`.
- Prescription refill statuses: `requested`, `needs_info`, `approved`, `denied`, `cancelled`.
- Patient documents can be `submitted`, `verified`, `rejected`, or `archived`.

## Authorization Rules

- Patients can only access their own appointments, documents, prescriptions, messages, and released visit summaries.
- Clinicians can view charts for assigned patients or appointments.
- Front desk can manage scheduling and demographics but cannot edit visit summaries or prescription decisions.
- Clinic admins can manage users, schedules, settings, imports, exports, and audit logs.

## Seed Data Requirements

- At least 12 patients, 5 providers, 30 appointments, 10 visit summaries, 12 prescriptions, 25 messages, and 20 documents.
- Include unreleased visit summaries, rejected forms, refill requests, and cancelled appointments.
- Demo accounts for patient, front desk, clinician, and clinic admin.

## Acceptance Criteria

- A reviewer can complete booking, form upload, visit summary release, refill request, and staff scheduling flows.
- Patients cannot access unreleased visit summaries or another patient's records.
- Front desk attempts to edit clinical notes are blocked server-side.
- Patient chart reads and document downloads create audit entries.

## Hard Requirements

- Implement all required screens, roles, workflows, authorization rules, seed data, reports, exports, audit logs, and acceptance criteria from the shared and app-specific specs.
- Implement a modern, inviting, polished UI: app shell navigation, role-aware menus, useful dashboards, readable tables, filters, forms, detail pages, status badges, alerts, empty/error/success states, visible focus states, and responsive layout.
- Use an accessible, professional color palette with sufficient contrast. Do not rely on low-contrast grays or color-only status indicators.
- Use a mature component system or coherent styling approach appropriate for the stack, for the chosen Java stack, such as Bootstrap, Tailwind, Material Web, or a coherent hand-rolled design system applied consistently across every screen.
- Use a local PostgreSQL database. Do not require any hosted database or external service.
- Include migrations/schema setup, seed data, and demo accounts for every role.
- Include clear local setup and run instructions.
- Store uploaded files locally and persist file metadata in Postgres.
- Mock external integrations locally. Email, SMS, webhooks, payments, identity providers, object storage, queues, and background jobs must not require SaaS accounts.
- Implement secure server-side authorization checks for every protected page, API route, record detail, edit action, file download, export, import, and admin action.
- Implement realistic validation, error states, empty states, and success states.
- Implement the app securely for the chosen stack. Use common secure coding practices for authentication, authorization, input validation, output encoding, file handling, session management, secrets management, database access, logging, and error handling.
- Do not intentionally add vulnerabilities.
- Do not satisfy requirements with placeholders, TODOs, fake buttons, static-only pages, unimplemented routes, a mostly unstyled scaffold UI, untouched framework default UI, or an inaccessible color palette.
- Do not skip a requirement because it is large. If necessary, implement a simple but working local version.

## Expected Output

- A runnable repository implementing the app: the project builds with its committed wrapper (`./gradlew build` or `./mvnw verify`) and `docker compose up` starts Postgres and every module.
- A `Jenkinsfile` at the repository root that builds, tests, and reports.
- A README with setup, database initialization, seed, run, and test/check instructions.
- Demo credentials for each role.
- A short implementation summary listing the main screens, workflows, and security controls implemented.
- A short UI summary listing the navigation structure, dashboard pages, and visual system used.
- A short note listing any requirement you could not complete. If everything is complete, say so explicitly.

## Acceptance Bar

- A reviewer can run the app locally with `docker compose up`, Postgres only, no external services.
- Every module builds; there are no stubbed-out or empty modules. The batch/support module is fully implemented, not a placeholder.
- A reviewer can log in as every role and complete the primary user journeys.
- Seeded data is sufficient to exercise dashboards, filters, workflow queues, reports, exports, and audit logs.
- The UI looks credible for the target industry, uses accessible contrast, and is usable without guessing where workflows live.
- Role boundaries are enforced server-side, not only hidden in the UI.
- File upload/download, imports/exports, audit logging, and local integration mocks work.
