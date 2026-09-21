# Security

The controls **actually implemented** in this repository, each traceable to the code that enforces
it — followed by an explicit list of what is *not* implemented. A security document that overstates
its coverage is worse than none, so the second list matters as much as the first.

OpsBridge is an internal tool. The threat model assumed here is authenticated colleagues with
different roles, not anonymous internet traffic.

## Authentication

**JWT bearer tokens.** `AuthService.login` verifies credentials and `JwtTokenProvider` issues an
HMAC-SHA token carrying the user id as subject plus `role`, `email` and `name` claims. Lifetime
comes from `JWT_EXPIRATION_MS`.

**The signing key must be strong.** `Keys.hmacShaKeyFor` rejects anything under 256 bits, so a weak
`JWT_SECRET` fails at startup rather than running with breakable signatures.

**Passwords are BCrypt hashed** (`BCryptPasswordEncoder`). No plaintext password is stored,
compared, or logged. `UserMapper` has no field for the hash, so it cannot leave through an API
response.

**Every invalid token fails the same way.** Expired, tampered signature, malformed, or claims that
no longer parse all produce one 401 — `JwtAuthFilter` does not report which, so a probe learns
nothing about why a token was rejected.

**Login failures are indistinguishable.** An unknown email, a wrong password and an inactive account
all return the same `INVALID_CREDENTIALS` — the response does not reveal whether an address exists.

**Sessions are stateless.** `SessionCreationPolicy.STATELESS`; there is no server-side session to
fixate, and `httpBasic` and `formLogin` are both disabled.

## Authorization

**Two independent checks on every mutating endpoint**, per the project rules: a Spring Security
request matcher in `SecurityConfig` *and* a method-level `@PreAuthorize`. The duplication is the
point — editing one cannot silently open the other. Policies are named constants in
`security/policy/Policies` rather than strings scattered across annotations.

| Area | Viewer | Operator | Admin |
|---|---|---|---|
| Read incidents, services, users, dashboard | ✅ | ✅ | ✅ |
| Declare / update / transition / assign / escalate incidents | ❌ | ✅ | ✅ |
| Create / edit / archive services | ❌ | ❌ | ✅ |

Derived from the matchers in `SecurityConfig` and the `@PreAuthorize` annotations on each method.
Operators are deliberately excluded from catalog management.

**Unauthenticated is 401, not 403.** Spring's default returns 403 for an anonymous request to a
protected path, which conflates "who are you" with "you may not". `RestSecurityResponses` makes the
distinction explicit.

**A `@PreAuthorize` denial is a 403, not a 500.** That denial is thrown by the method interceptor
and so bypasses the filter chain's handler; `GlobalExceptionHandler` catches it and returns the
same shape and code the filter-level denial uses, so callers see one contract.

**The frontend makes no authorization decisions.** Hidden buttons are UX only. The test suites
assert both halves separately: that controls are hidden for the wrong role, *and* that the endpoint
rejects that role independently.

## Data integrity

**Optimistic locking on incident mutations.** Status and assignment carry `@Version` and require an
`expectedVersion` in the request. A stale write returns **409 `STALE_VERSION`** with a clear body
and is never silently applied. The version is checked before any state is touched, and Hibernate's
own version check catches a true concurrent race that slipped past it — both surface as the same
409, so a client has one conflict contract to handle. `IncidentConcurrencyTest` exercises the real
race with concurrent transactions.

**Every workflow mutation is audited.** Status changes, assignment, escalation, incident edits and
service create/edit each append to `incident_activity` — actor, event type, field, old value, new
value, timestamp — written inside the same transaction as the change, so a change and its audit
record commit or roll back together. An edit touching several fields writes one row per field.

**The actor always comes from the token.** Reporter and actor ids are taken from the authenticated
principal, never from the request body, so neither can be spoofed by a caller.

**Illegal state transitions are refused.** `IncidentStateMachine` rejects any jump outside the
specified workflow with 422, including emergency shortcuts like `OPEN → RESOLVED`.

## Transport and configuration

**CORS is an explicit allow-list** from `CORS_ALLOWED_ORIGINS`. No wildcard.

**Secrets are environment variables only** — DB credentials, `JWT_SECRET`, CORS origins. `.env` is
gitignored; `.env.example` documents the keys with placeholder values. Nothing secret is committed.

**CSRF protection is disabled, deliberately.** The API is stateless and the token is sent in an
`Authorization` header, not a cookie, so it is not attached automatically by the browser and the
CSRF class of attack does not apply. This would need revisiting if tokens ever moved to cookies.

**No secrets in logs.** Tokens and passwords are never logged. The unexpected-error handler returns
a fixed message and logs the detail server-side, so an internal exception cannot leak a connection
string or credential to a caller.

**Errors are uniform.** One shape (timestamp, status, code, message, path, traceId) for every
failure, so responses do not leak stack traces or implementation detail.

## WebSocket channel

**The token is the first frame, never a URL parameter.** `?access_token=…` would write the token
into server access logs and browser history. The socket opens unauthenticated, must send
`{type:"AUTH",token}`, and receives nothing until the server accepts it.

**Unauthenticated sockets are swept closed** after `REALTIME_AUTH_DEADLINE_MS`, so an idle
unauthenticated connection cannot sit open indefinitely.

**A refused token closes with 4401** and the client stops retrying rather than hammering the server
with a credential already known to be bad.

**Nothing is broadcast before authentication.** `LiveEventBroadcaster` only sends to sessions that
have completed the handshake.

## Not implemented

Known gaps, listed so nobody assumes otherwise:

- **No refresh tokens or revocation.** A token is valid until it expires; there is no deny-list, so
  logging out clears the client only. Shorten `JWT_EXPIRATION_MS` if that matters.
- **No rate limiting or account lockout.** Login is not throttled and repeated failures do not lock
  an account, so the API is open to online password guessing. This is the most significant gap for
  any deployment beyond a trusted network.
- **No password policy, rotation, or reset flow.** Users exist only via seed migration.
- **No TLS termination in the app.** It serves plain HTTP and expects a proxy to terminate TLS.
  Without one, bearer tokens travel in clear text.
- **No audit of reads.** Only mutations are recorded; who *viewed* an incident is not tracked.
- **No per-service or per-team authorization.** Any Operator can act on any incident; roles are
  global, not scoped.
- **No CSP, HSTS, or related response headers** beyond Spring Security's defaults.
- **No dependency or container vulnerability scanning** in the build.
