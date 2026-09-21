# OpsBridge

An internal incident-management tool: declare an incident against a service, move it through a
defined workflow, and watch SLA clocks while it runs. Think of a reservation system, but the thing
being tracked is an outage rather than a booking.

Everything below is generated from the code in this repository. Where a number or rule appears, it
is the one the code actually enforces.

## What it does

- **Incidents** — declare, triage, investigate, mitigate, resolve or cancel, under a state machine
  that rejects illegal jumps. Assignment and status changes use optimistic locking, so two people
  editing the same incident cannot silently overwrite each other.
- **SLA tracking** — response, mitigation and resolution targets are derived from an incident's
  priority and severity, then re-evaluated on read so a deadline that passes while nobody is
  looking still shows as breached.
- **Service catalog** — the services incidents are filed against, each carrying a live open-incident
  count and a health signal.
- **Dashboard** — open/unassigned counts, SLA breaches in the trailing week, MTTR and its 7-day
  trend, priority distribution, and a recent-incident feed.
- **Live updates** — a push channel over WebSocket keeps open screens current without polling.
- **Roles** — Viewer (read-only), Operator (incident workflow), Admin (also the service catalog).
- **Bilingual UI** — English and Japanese, with every user-facing string in i18next resources.

## Stack

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.5.16, Spring Security, Spring Data JPA, Flyway, PostgreSQL 16, Maven |
| Auth | JWT (jjwt 0.13.0), HMAC-SHA, BCrypt password hashing |
| Frontend | React 19, TypeScript, Vite 8, MobX 7, i18next, React Router 7 |
| Tests | JUnit 5 + Mockito + Spring MVC Test (backend), Vitest + Testing Library (frontend) |

## Prerequisites

- **Java 21** — `java -version` should report 21.x
- **Node 20+** and npm
- **Docker** — for the PostgreSQL container (the app itself runs on the host)

The Maven wrapper (`backend/mvnw`) is committed, so a separate Maven install is not required.

## Quickstart

**1. Configure the environment.** Copy the template and fill it in:

```bash
cp .env.example .env
```

`.env` is gitignored and is the only place secrets live. One value needs care:

> `JWT_SECRET` must be **at least 256 bits** (32+ characters). The signing library rejects anything
> shorter at startup with `WeakKeyException`, and the app will fail to boot rather than run with a
> weak key.

**2. Start PostgreSQL:**

```bash
docker compose up -d postgres
```

Flyway applies the schema (`V1`–`V7`) automatically on first backend start, including the dev
seed users — see [DEVELOPMENT.md](DEVELOPMENT.md) for the accounts you can log in with.

**3. Start the backend.** The variables in `.env` must be present in the process environment; the
app does not read the file itself:

```bash
cd backend
set -a && source ../.env && set +a
./mvnw spring-boot:run
```

It listens on `http://localhost:8080`. Check it with `curl localhost:8080/actuator/health`.

**4. Start the frontend:**

```bash
cd frontend
npm install
npm run dev
```

It serves `http://localhost:5173` and talks to the backend at `VITE_API_BASE_URL`.

## Running the tests

```bash
cd backend  && ./mvnw test     # 312 tests
cd frontend && npm run test    # 267 tests across 27 files
```

Additional frontend checks:

```bash
npm run lint      # oxlint
npx tsc -b        # type check
```

## Repository layout

```
backend/src/main/java/io/opsbridge/
  api/{request,response}/   DTOs at every controller boundary
  controller/               HTTP mapping and validation only
  domain/{entity,enums}/    JPA entities and the enums shared with the frontend
  repository/               persistence, including the custom aggregate queries
  security/{jwt,policy}/    JWT filter/provider and the named authorization policies
  service/{,mapper}/        business logic, the state machine, SLA math, entity->DTO mapping
  support/{error,pagination,audit,clock,realtime}/
backend/src/main/resources/db/migration/   Flyway migrations, additive only

frontend/src/
  api/       HTTP + WebSocket clients
  stores/    one MobX store per domain, composed in RootStore
  components/ pages/ hooks/ types/ i18n/ styles/
frontend/src/test/

design/      the approved design prototypes the UI is built against
```

## Further reading

- [ARCHITECTURE.md](ARCHITECTURE.md) — layers, a request traced end to end, and the design decisions
- [SECURITY.md](SECURITY.md) — the controls that are implemented, and the ones that are not
- [DEVELOPMENT.md](DEVELOPMENT.md) — local workflow, seed accounts, and the environment traps
- `OpsBridge_Project_Specification.md` — the full requirements
