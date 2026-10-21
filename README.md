# Clinic Patient Portal

A patient portal for a small outpatient practice — appointments, visit summaries,
prescriptions and refills, secure messaging with internal-only staff notes,
pre-visit forms and clinical documents, imports/exports, in-app notifications, an
append-only audit trail, and a JSON availability API for external scheduling.

Built as a single deployable Jakarta EE 10 module: **Java 21**, **JAX-RS**,
**EJB/CDI**, **JPA (Hibernate)**, **Jakarta Faces (JSF 4)** with a hand-rolled
design system, **Flyway** migrations, **PostgreSQL**, deployed as a WAR to
**WildFly 31**. No cloud services, no external brokers, no SaaS accounts.

---

## Run it

### One command

```bash
docker compose up --build
```

Then open **<http://localhost:8380/login.xhtml>**.

That single command:

1. starts PostgreSQL 16 (`clinic_portal` database, exposed on host port `55432`),
2. builds the WAR with the committed Maven wrapper inside the build image,
3. starts WildFly with the PostgreSQL JDBC driver and the `ClinicDS` datasource,
4. runs the Flyway migrations at deployment time (`SchemaMigrator`), and
5. loads the demo dataset (`DemoDataSeeder`) — patients, providers, schedules,
   appointments, visit summaries, prescriptions, refills, documents on disk,
   message threads, notifications and back-dated audit history.

The first start takes a few minutes while Maven and WildFly images download.
WildFly is ready when the log prints `WFLYSRV0025: WildFly ... started`.

Stop and reset everything:

```bash
docker compose down -v      # -v also drops the database and document volumes
```

### Configuration

Nothing sensitive is baked into the image; the entrypoint refuses to start
without a database password.

| Variable | Default | Purpose |
|---|---|---|
| `CLINIC_DB_HOST` / `CLINIC_DB_PORT` | `postgres` / `5432` | Database endpoint |
| `CLINIC_DB_NAME` | `clinic_portal` | Database name |
| `CLINIC_DB_USER` | `clinic_app` | Database user |
| `CLINIC_DB_PASSWORD` | *(required)* | Database password |
| `CLINIC_DOCUMENT_DIR` | `/var/clinic/documents` | Local document storage root |

For anything beyond a laptop, set your own password:

```bash
CLINIC_DB_PASSWORD='a-strong-local-password' docker compose up --build
```

### Build and test without Docker

```bash
./mvnw verify          # compile, run the tests, package target/clinic-portal.war
./mvnw test            # tests only
```

The build needs JDK 21+ (the compiler targets release 21). Deploying the WAR by
hand requires a WildFly 31 instance with a datasource bound to
`java:jboss/datasources/ClinicDS`.

### CI

`Jenkinsfile` at the repository root builds with `./mvnw`, runs the test suite,
publishes the JUnit report, archives the WAR, sanity-checks the migration files,
and builds the container image on `main`.

---

## Demo credentials

Password for **every** demo account: `Passw0rd!2026`

| Role | Email | What they can do |
|---|---|---|
| `patient` | `patient@northgateclinic.example` | Their own appointments, released visit summaries, prescriptions/refills, messages, documents |
| `front_desk` | `frontdesk@northgateclinic.example` | Scheduling, demographics, form verification — **no** clinical notes or prescription decisions |
| `clinician` | `clinician@northgateclinic.example` | Charts for their own panel, visit summaries, prescriptions, refill decisions, messages |
| `clinic_admin` | `admin@northgateclinic.example` | Users, providers, schedules, appointment types, settings, imports/exports, audit log, operations |

Other seeded logins use the same password: four more clinicians
(`d.fischer@`, `l.moreau@`, `s.okafor@`, `h.lindqvist@northgateclinic.example`),
a second front desk account (`frontdesk2@northgateclinic.example`), and seven
more patient accounts using each patient's own email address.

---

## Tier and trust statement

This application is a **single-tier deployment** of the shared contract: the
`channel` (Faces UI + JAX-RS) and the `decision` tier (the EJB business layer)
run in one WAR, and there is no vendor core to stub — a clinic has no external
core banking equivalent, so the `core-mock` tier is waived and its role is filled
by the local integration mocks described below.

- **The `channel` tier authenticates.** Jakarta Security's custom form mechanism
  (`SecurityConfiguration`) plus `ClinicIdentityStore` verify the credential and
  establish the container principal and its role group.
- **The `decision` tier authorizes.** Every EJB call re-resolves the acting user
  from the database via `CurrentUser` (from the container principal, never from a
  header, query parameter, form field or hidden input) and runs the ownership /
  scope check in `AccessGuard`. Role, patient linkage, provider linkage and panel
  membership are always read from the database on each request.
- **Nothing about the caller is ever accepted from the request.** A patient id in
  a query string is only ever used to *ask* for a record; who is asking comes from
  the session principal, and `AccessGuard` decides whether the two match.
- **External systems are local mocks.** Outbound email is written to an
  `outbox_email` table, webhook deliveries are recorded in `webhook_delivery`
  against locally configured endpoints, and the scheduler runs in-process
  (`ScheduledJobs`). No outbound network connection is ever made.

---

## Implementation summary

### Screens

**Public** — landing page, sign in, forgot password, single-use reset link,
403/404/500 error pages.

**Patient** — dashboard (next visit, unread messages, active prescriptions,
forms needing attention, recent released summaries, refill status);
appointments with availability search, self-booking and cancellation; released
visit summaries with a JSON export; prescriptions with refill requests; secure
messages; forms & documents with upload and audited download.

**Front desk / staff** — clinic dashboard; schedule board with day navigation,
provider/status/text filters, per-status counts, request confirmation, check-in,
completion, no-show, cancellation, rescheduling against real open slots,
staff-only scheduling notes and staff-side booking; patient directory with
search, filters and a whitelisted sort; patient chart; secure message inbox;
form review queue.

**Clinician** — dashboard scoped to their own panel; patient chart (demographics,
appointments, visit summaries including drafts, prescriptions, documents,
messages, per-patient access history); visit summary authoring with draft →
released → amended workflow; prescription issuing and discontinuation; refill
queue with approve / deny / request-more-information / message-the-patient.

**Clinic admin** — dashboard; staff and account management (invite, role change,
enable/disable); providers, weekly schedule templates and appointment types;
CSV patient import (with a validate-only dry run) and CSV/JSON exports; the audit
log viewer with filters and CSV export; operations (email outbox, webhook
delivery attempts, scheduled job runs with manual triggers); clinic settings,
API keys and webhook endpoints.

**Everyone** — profile and password change, notification bell.

### Workflows and state machines

Every state machine is an explicit edge list in the domain enum, enforced
server-side and asserted by `StateMachineTest`. Every seeded status is one the
application itself can produce.

| Machine | Edges |
|---|---|
| Appointment | `requested → scheduled\|cancelled`; `scheduled → checked_in\|cancelled\|no_show\|scheduled`; `checked_in → completed\|cancelled\|no_show`; `no_show → scheduled`; `completed`/`cancelled` terminal |
| Visit summary | `draft → released`; `released → amended`; `amended → released` (patient-visible only from `released`) |
| Refill request | `requested → approved\|denied\|needs_info\|cancelled`; `needs_info → requested\|approved\|denied\|cancelled`; the rest terminal |
| Document | `submitted → verified\|rejected\|archived`; `verified → archived`; `rejected → submitted\|archived`; `archived` terminal |
| Prescription | `active → discontinued\|expired`; `expired → active` |
| Message thread | `open ↔ awaiting_patient ↔ resolved`, all → `closed` (terminal) |

Role also constrains which edges may be requested: a patient may only cancel
(and not once checked in, and only outside the notice window from clinic
settings); the front desk may never mark a visit complete.

### Security controls

- **Declarative security** — `web.xml` `<security-constraint>` entries for
  `/patient/*`, `/clinician/*`, `/admin/*`, `/staff/*`, `/account/*` and
  `/api/secure/*`, ordered specific-to-general with no broader pattern shadowing
  a narrower one, plus `@RolesAllowed` on the EJBs.
- **Ownership and tenancy checks in the business tier** — declarative role checks
  establish *which role* is calling, never *which record*. `AccessGuard` performs
  the ownership/scope decision for every read, edit, download, export and admin
  action and returns the *basis* on which access was granted, which is then
  recorded in the audit event. Patients reach only their own records; clinicians
  only their panel (assignment, primary provider, or an existing appointment);
  the front desk gets demographic and scheduling scope but is rejected from
  clinical content; admins get administrative scope.
- **Query-level filtering for sensitive reads** — a patient's visit summaries come
  from a released-only named query, internal-only documents and internal staff
  notes are excluded in the query rather than in the view, so no template mistake
  can leak them.
- **Parameterised JPQL everywhere** — no user input is ever concatenated into a
  query. The only dynamic query text is the ORDER BY fragment, chosen from a
  closed whitelist (see the precision baseline below).
- **Output encoding** — every dynamic value is rendered through `h:outputText`
  and the framework's escaping. `escape="false"` appears nowhere in the codebase.
- **File handling** — server-generated opaque file names, extension and content
  type allow-lists, magic-number check for PDFs, a streaming size cap, SHA-256
  recorded per file, storage under a canonicalised base directory that every read
  and write is re-checked against, and a sanitised `Content-Disposition` name.
- **Append-only audit log** — every state transition and privileged action records
  actor, action, before/after, timestamp and authorisation basis. Database
  triggers reject any `UPDATE` or `DELETE` on `audit_event`, and the application
  exposes no mutation path. Read auditing is one event per request naming the
  resource class and the row count, never one per record.
- **Authentication lifecycle** — PBKDF2-HMAC-SHA256 (210k iterations, per-password
  salt), constant-time verification, generic failure messages, lockout after
  repeated failures, session id regeneration on login, `HttpOnly`/`SameSite`
  cookies, a 30-minute idle timeout, and single-use time-bound password reset
  tokens stored only as a SHA-256 digest.
- **Secrets from the environment** — database credentials come from environment
  variables via the container entrypoint; API keys are shown once and stored only
  as a digest.
- **Error handling** — application-authored messages are shown to users; anything
  else is logged server-side and replaced with a generic sentence, in both the
  web tier (`Faces.handle`) and the API (`ApiExceptionMapper`).

### Precision baseline (benign by design — do not "fix" these)

All five primitives from the shared contract are implemented, each with an
in-code comment stating why it is correct:

1. **Ownership check one frame above the controller** — `AccessGuard`
   (`requirePatientAccess` and siblings) rather than inline comparisons at each
   call site.
2. **`java.util.Random` for a cache-busting suffix** — `AssetVersion`, used only
   for the `?v=` query on the stylesheet and script. Non-cryptographic by design.
3. **MD5 as an ETag** — `EtagSupport.referenceDataEtag`, used only as a content
   fingerprint on the cacheable reference-data endpoints. Non-cryptographic by
   design.
4. **Parameterised query with a whitelisted sort column** — `SortWhitelist`, used
   by the patient directory and the audit log; the caller's key selects one of a
   closed set of constants and never reaches the query text.
5. **Canonicalised-base-directory download** — `DocumentStorage.resolveInsideBase`,
   with unit tests covering traversal, absolute paths, sub-paths and symlinks.

### JSON API

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/public/availability` | `X-API-Key` (scope `availability:read`) | Open appointment slots; exposes no patient data |
| `GET /api/reference/appointment-types` | `X-API-Key` | Cacheable catalogue, MD5 ETag + `If-None-Match` |
| `GET /api/reference/providers` | `X-API-Key` | Cacheable provider directory |
| `GET /api/secure/notifications` | session | The signed-in user's own notifications |
| `POST /api/secure/notifications/{id}/read` | session | Dismiss own notification |
| `GET /api/secure/documents/{id}/download` | session | Authorised, audited document download |
| `GET /api/secure/exports/schedule.csv` | session (staff) | Appointment schedule export |
| `GET /api/secure/exports/visit-summaries.json` | session | Visit summaries (patients get only their own, released) |
| `GET /api/secure/exports/patients.csv` | session (admin) | Patient directory export |
| `GET /api/secure/exports/audit-log.csv` | session (admin) | Audit log export |

Create an API key under **Administration → Clinic settings**, then:

```bash
curl -H "X-API-Key: <the key shown once>" \
  "http://localhost:8380/api/public/availability?from=2026-01-05&to=2026-01-12"
```

### Seed data

Loaded on first start by `DemoDataSeeder` (idempotent — it skips if any user
exists).

| Records | Count | Includes |
|---|---|---|
| Patients | 14 | one inactive, 8 with portal logins |
| Providers | 5 | each with a login and a weekly schedule template |
| Appointments | 36 | 12 completed, 4 cancelled, 3 no-show, 2 checked in, 12 scheduled, 3 patient-requested |
| Visit summaries | 12 | 9 released, **3 still in draft** |
| Prescriptions | 14 | active, discontinued and expired |
| Refill requests | 8 | at least one in **every** status incl. `needs_info` and `denied` |
| Documents | 22 | across all six categories, incl. **2 rejected forms** and internal-only clinical notes, with real PDFs on disk |
| Message threads | 10 | 28 messages including internal-only staff notes |
| Notifications | 8 | read and unread, across all roles |
| Audit events | ~100 | back-dated history for charts, documents, refills and messages |

### Tests

`./mvnw test` runs the suite: state-machine edge lists and code round-trips,
password hashing and policy, the sort whitelist (including injection attempts),
CSV escaping/parsing (including formula injection), document storage validation
and path-traversal confinement, and — most importantly — `AccessGuardTest`, which
asserts the acceptance criteria directly: a patient cannot reach another
patient's chart, cannot open an unreleased visit summary, cannot reach an
internal-only document; a clinician is confined to their panel; the front desk is
blocked from clinical content server-side.

---

## UI summary

**Navigation** — a fixed left sidebar with role-aware sections (My care / Contact
for patients; Clinic / Clinical / Administration / Account for staff), a sticky
top bar carrying the page title, the date, a notification bell with an unread
count and popover, the signed-in user's name, role and initials avatar, and sign
out. Detail pages carry breadcrumbs back to their list. The layout collapses to a
horizontal nav below 760px and every screen is usable on a tablet.

**Dashboards** — one per role, each built from seeded data: summary stat tiles,
today's schedule, workflow queues (appointment requests, form review, refills,
draft summaries) and recent activity.

**Visual system** — a hand-rolled design system in a single stylesheet
(`resources/css/clinic.css`): a clinical teal/indigo palette on an off-white
canvas, one spacing and radius scale, one shadow scale, and consistent cards,
tables, forms, filters, badges, alerts, tabs, timelines, message bubbles and
slot pickers. Semantic colours are used consistently (success, warning, danger,
info, neutral, primary). Status is **never** signalled by colour alone — every
badge carries its label. Body text sits above a 7:1 contrast ratio, secondary
text and badges above 4.5:1. Focus is visible on every interactive element via a
3px outline, there is a skip link, form controls are labelled, icon-only controls
carry `aria-label`, and tables use scoped headers.

**States** — every list has an explicit empty state with an icon, a title and a
next action; forms show inline validation messages and a page-level success or
error banner; workflow actions sit in a visually distinct dashed action bar so
they read as decisions rather than navigation.

---

## Completeness

**Everything in the specification is implemented.** All required screens, all
four roles, all workflows and state machines, the authorization rules, audit
logging, file upload/download, secure messaging with internal-only notes,
prescriptions and the refill workflow, visit summary draft/release, CSV import
and CSV/JSON export, in-app notifications, the JSON availability API, the
background/scheduled job, the local integration mocks, the seed data (meeting or
exceeding every stated minimum), the five precision-baseline primitives, the
`docker compose up` path and the `Jenkinsfile`.

Two things a reviewer should know about, both deliberate rather than missing:

- **The `core-mock` tier is waived**, as permitted by the shared contract: an
  outpatient clinic has no vendor core system. Its place is taken by the local
  email outbox and webhook delivery mocks, which are fully implemented rather
  than stubbed.
- **Cookies are not marked `Secure` by default** (`web.xml`), because the local
  `docker compose` deployment is plain HTTP. Set `<secure>true</secure>` when the
  portal is served over TLS.
