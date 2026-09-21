# Architecture

How OpsBridge is put together, and why the parts that look unusual are that way. Every claim here
is traceable to a file; where a decision has a non-obvious reason, the reason is given rather than
just the rule.

## The shape of it

```
Browser (React + MobX)
   │  REST over JSON            ▲  WebSocket push
   ▼                            │
┌──────────────────────────────────────────────────┐
│ Filter chain    TraceIdFilter → JwtAuthFilter    │
│ Matchers        SecurityConfig                   │
├──────────────────────────────────────────────────┤
│ Controllers     HTTP mapping + validation only   │
├──────────────────────────────────────────────────┤
│ Services        @Transactional, state machine,   │
│                 SLA math, audit writes           │
├──────────────────────────────────────────────────┤
│ Repositories    persistence only                 │
└──────────────────────────────────────────────────┘
                     │
                PostgreSQL (schema owned by Flyway)
```

## Layer contract

**Controllers** map HTTP and validate. They hold no business logic and never touch a repository.
They translate a validated request DTO into a command record and hand it to a service.

**Services** own transaction boundaries, the incident state machine, SLA arithmetic, and the audit
trail. They speak in entities and commands, never in HTTP types.

**Repositories** are persistence only. The custom queries they carry (`countOpenIncidentsByPriority`,
`countOpenIncidentsByService`, `findSlaBreachEvaluationCandidates`) are aggregates, not decisions —
the interpretation happens a layer up.

**DTOs at every boundary.** A JPA entity never leaves a controller; `service/mapper/` converts.

**The frontend makes no authorization decisions.** Hiding a button is UX. Every mutating action is
enforced server-side as though the client check did not exist — see [SECURITY.md](SECURITY.md).

## One request, end to end

`PATCH /api/v1/incidents/{id}/status` — an Operator moving an incident to `MITIGATED`:

1. **`TraceIdFilter`** puts a trace id in the MDC so anything logged for this request, including an
   error response, can be correlated.
2. **`JwtAuthFilter`** validates the bearer token and builds an `OpsBridgePrincipal`. Any invalid
   token — expired, tampered, malformed — produces the same 401; the filter does not explain which.
3. **`SecurityConfig`** matches `PATCH /api/v1/incidents/**` and requires `OPERATOR` or `ADMIN`.
4. **`@PreAuthorize(Policies.OPERATOR_OR_ADMIN)`** on the controller method checks the same rule
   again. The duplication is deliberate: a future matcher edit cannot silently open the method.
5. **`IncidentController.transition`** validates the body (`expectedVersion` is mandatory) and
   builds a `TransitionIncidentCommand`. The actor comes from the token, never the body.
6. **`IncidentService.transition`** opens a transaction and:
   - loads the incident and compares `expectedVersion` **before** touching state, so a stale write
     is rejected without partial mutation;
   - asks **`IncidentStateMachine`** whether the jump is legal, rejecting with 422 if not;
   - stamps the SLA milestone this transition represents (first triage records the response time,
     `MITIGATED` stamps mitigation, `RESOLVED` stamps resolution and marks the SLA met or breached);
   - appends a row to `incident_activity` — actor, field, old value, new value, timestamp — inside
     the same transaction, so a change and its audit record commit or fail together;
   - publishes an `IncidentChangedEvent`.
7. **Hibernate's version check** on `UPDATE` catches a concurrent write that slipped past the
   explicit check. Both routes surface as one 409 `STALE_VERSION`.
8. **`IncidentChangedListener`** consumes the event **after commit** and hands it to
   `LiveEventBroadcaster`, which pushes to every authenticated socket. After-commit matters: a
   rolled-back transaction must not announce a change that did not happen.
9. **The browser** receives the frame, `LiveStore` routes it to `IncidentStore` and `DashboardStore`,
   and any open screen re-renders.

## The live channel

`/ws/events` is push-only. It is deliberately framework-free on the client (`api/liveClient.ts`) —
one channel with one message direction does not need a protocol library.

**The token is the first frame, not a query parameter.** The browser's `WebSocket` constructor
cannot set an `Authorization` header, and the usual workaround (`?access_token=…`) writes the token
into server access logs and browser history. Instead the socket opens unauthenticated, sends
`{type:"AUTH",token}`, and stays inert until the server accepts it. A sweeper closes any socket that
never authenticates within a deadline.

**A refused token closes with 4401 and the client stops.** Retrying with a token the server has
already rejected would never succeed and would hide the real problem behind a permanent
"connecting".

**Reconnection is exponential with jitter**, capped at 30s. Jitter stops every open browser from
returning in the same instant after a backend restart.

**The socket is an invalidation hint, never a source of truth.** Nothing is replayed for a client
that was disconnected, so on every *re*connection the affected stores re-read from REST, which is
authoritative. The worst case for a dropped event is staleness until the next reconnect — never a
permanently wrong screen.

**A pushed event never inserts a row.** `applyLiveIncidentUpdate` only updates rows already on
screen. Inserting would ignore the active filters, sort order and page boundary, putting a row
somewhere it does not belong. A genuinely new incident appears on the next fetch.

## Frontend composition

One MobX store per domain, composed in `RootStore`: `AuthStore`, `IncidentStore`, `ServiceStore`,
`DashboardStore`, `LiveStore`, `UiStore`. A store owns only its own domain.

`LiveStore` holds connection state and nothing else. It routes events to the stores that own the
data through their public methods, so store boundaries stay intact.

**`DashboardStore` keeps its own `recentIncidents`** rather than reading `IncidentStore.items`.
Both would use the same endpoint, but `IncidentStore`'s list state belongs to the Incidents screen —
fetching into it from the dashboard would clobber whatever filter the user had left set there.

**The live connection is owned by `ProtectedRoute`, not `AppShell`.** Incident detail renders
outside the shell (it has breadcrumb-only chrome, matching the prototype), so a shell-owned socket
would not exist on that screen at all, and navigating into it would tear the connection down.

**Enum values cross the wire raw.** The API sends `P1` and `INVESTIGATING`; the frontend maps them
to i18n labels. Translating server-side would bake a language into the API.

## Decisions worth knowing

**SLA state is re-evaluated on read.** A stored `sla_state` goes stale the moment a deadline passes
with nobody touching the incident. `SlaCalculator.evaluate` recomputes from timestamps whenever an
incident is read or counted, so a breach that happened overnight is visible immediately rather than
waiting for the next write. `SlaBreachMonitor` sweeps on an interval for the same reason: a breach
has no writer, so nothing else would notice it.

**Optimistic locking, never last-write-wins, for incidents.** Status and assignment carry
`@Version`; a stale write returns 409 with a clear body. The service catalog deliberately does
*not* have a version — concurrent catalog edits are last-write-wins, and the audit trail is what
makes a clobber reconstructable.

**The catalog's open-incident count is grouped by service *and* priority.** A bare total cannot
distinguish a P1 outage from a pile of P4s, and health has to. The query returns one row per
(service, priority) pair and the service layer folds them into a total plus the urgent subset. One
grouped query, not a count per row, because the catalog lists a whole page of services at once.

**Aggregate counts come from the server, not from a fetched page.** Deriving the catalog's counts
client-side from one page of incidents would report a service whose open work fell outside that
page as "Nominal / 0" — a wrong health signal in the column whose whole job is to be trusted.

**Mutation responses carry real counts.** `ServiceMapper.toResponse` takes the service *paired* with
its counts, and there is no Service-only overload. An earlier version defaulted the count to zero,
and every edited service came back over the wire looking quiet.

**Errors leave through one handler.** `GlobalExceptionHandler` produces a single shape — timestamp,
status, code, message, path, traceId — for every failure. Branches exist for validation, an
unconvertible query param, a `@PreAuthorize` denial and a concurrency race specifically because
each would otherwise fall through to the catch-all and read as a 500.

**Flyway migrations are additive.** An applied migration is never edited; a correction is a new
`V{n}__*.sql`.
