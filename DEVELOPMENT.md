# Development

Day-to-day workflow for running OpsBridge locally. Setup from scratch is in
[README.md](README.md); this covers the loop you live in afterwards, and the traps that cost time.

## Seed accounts

Three users are seeded by `V2__seed_dev_users.sql`, one per role:

| Email | Password | Role | Can do |
|---|---|---|---|
| `viewer@opsbridge.local` | `Viewer#Dev2026` | Viewer | Read everything, change nothing |
| `operator@opsbridge.local` | `Operator#Dev2026` | Operator | Incident workflow |
| `admin@opsbridge.local` | `Admin#Dev2026` | Admin | Also the service catalog |

> **Local development only.** These are fixture accounts on a throwaway database, and the passwords
> are already in a committed migration — they are written here so you can actually log in, not
> because they are safe. Never seed them into anything reachable by anyone else, and never reuse
> these passwords. A real deployment needs a real user-provisioning path; there isn't one yet
> (see [SECURITY.md](SECURITY.md)).

Signing in as each role is the quickest way to check authorization work: the Viewer should see
read-only screens, the Operator should have incident actions but no catalog management, the Admin
should have both.

## The loop

Three processes. Postgres runs in Docker; the app runs on the host.

```bash
docker compose up -d postgres          # once, stays up

cd backend
set -a && source ../.env && set +a     # see the trap below
./mvnw spring-boot:run                 # :8080

cd frontend
npm run dev                            # :5173, hot reloads
```

The frontend hot-reloads. The backend does not — restart it after a Java change.

## Two environment traps

Both cost real debugging time in this project. They look like application bugs and are not.

**1. The backend does not read `.env` itself.** Spring resolves `${JWT_SECRET}` from the *process*
environment. Running `./mvnw spring-boot:run` without exporting first means `JWT_SECRET` is the
literal string `${JWT_SECRET}` — 13 characters — and startup dies with:

```
WeakKeyException: The specified key byte array is 104 bits which is not secure enough
```

That is a missing environment variable, not a bad secret. Fix: `set -a && source ../.env && set +a`
in the shell you launch from.

**2. Docker Desktop quitting takes Postgres with it.** The backend keeps running and reports
`{"status":"DOWN"}`, and every request 500s. Symptoms look like an application failure; the tell is
`docker compose ps` failing to reach the daemon:

```
failed to connect to the docker API at unix:///…/docker.sock
```

Fix: start Docker Desktop, then `docker compose up -d postgres`. The backend's connection pool
reconnects on its own — no restart needed. Your data survives, because it lives in the named volume.

A related tell in the backend log after a laptop sleep:

```
HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=10m16s)
```

That is the pool noticing the machine was asleep. Harmless.

## Tests

```bash
cd backend  && ./mvnw test                       # 312
cd frontend && npm run test                      # 267 across 27 files

./mvnw test -Dtest=IncidentServiceTest           # one backend class
./mvnw test -Dtest='ServiceCatalogServiceTest#archive_flipsTheStatusAndAuditsIt'
npx vitest run src/test/stores/IncidentStore.test.ts
npx vitest run -t "declares an incident"         # by test name
```

Backend tests run against H2 under the `test` profile — no Docker needed. `@DataJpaTest` repository
tests exercise real SQL, which is what proves the custom `@Query` methods and their projections
actually bind.

Frontend checks worth running before you consider something done:

```bash
npm run lint      # oxlint
npx tsc -b        # type check; catches what tests won't
```

**A change is not finished until both suites pass.** Report the real counts, not "tests added".

## Database

Schema is owned by Flyway (`backend/src/main/resources/db/migration/`), applied automatically at
startup. Current head is `V7`.

**Migrations are additive. Never edit one that has been applied** — Flyway validates checksums and
will refuse to start. A correction is a new `V{n}__description.sql`.

Inspecting or fixing local data:

```bash
docker compose exec postgres psql -U opsbridge -d opsbridge
docker compose exec postgres pg_dump -U opsbridge -d opsbridge > backup.sql
```

> **Never `docker compose down -v`.** The `-v` drops the volume and every row with it. Stopping the
> container (`docker compose stop postgres`) is safe; the volume persists. Take a `pg_dump` before
> any manual `DELETE`, and pin rows by exact id rather than a `LIKE` pattern that could match more
> than you meant.

To start over deliberately, remove the volume by name and let Flyway rebuild — but that is a
destructive act, so be sure first.

## Conventions worth knowing before your first change

- **DTOs at every controller boundary.** A JPA entity never leaves a controller.
- **Controllers hold no business logic** and never touch a repository.
- **Enums cross the wire raw** (`P1`, `INVESTIGATING`); the frontend maps them to i18n labels.
- **Every user-facing string goes in `i18n/en.json` *and* `ja.json`.** A key missing from one
  locale surfaces as the raw key in the UI.
- **Every list endpoint is paginated** via the shared `support/pagination` wrapper.
- **Match the prototypes in `design/`** for layout and visual language.
- **Handle loading, empty and error states** on every data-fetching screen.

## Working against the running app

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@opsbridge.local","password":"Admin#Dev2026"}' | jq -r .token)

curl -s localhost:8080/api/v1/incidents?size=5 -H "Authorization: Bearer $TOKEN" | jq
curl -s localhost:8080/api/v1/dashboard/summary -H "Authorization: Bearer $TOKEN" | jq .kpis
```

Useful when you want to know whether a wrong value on screen came from the API or the UI — check
the payload before debugging the component.
