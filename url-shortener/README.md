# URL Shortener

Spring Boot API plus an Angular front end. Paste a long URL, get a short one, and see how many
times each link has been followed.

Two screens:

- **`/`** — the shortener and its analytics dashboard.
- **`/governance`** — a control room for the SDLC orchestration engine that models the delivery of
  this service as a six-module pipeline. See [Orchestration engine](#orchestration-engine).

## Layout

```
backend/    Spring Boot 3.3 / Java 21 / H2 (in-memory)
frontend/   Angular 22, standalone components and signals
```

## Running it

Two terminals. Backend first:

```
cd backend
mvn spring-boot:run          # http://localhost:8080
```

The backend uses Redis at `localhost:6379` to cache short-code redirects. Start Redis locally for
the cache, or run without it: Redis failures fall back to H2 so the application remains usable.
Cached entries expire with the link, and click counts are still written to H2 for every redirect.

Then the front end:

```
cd frontend
npm install                  # first time only
npm start                    # http://localhost:4200
```

The dev server proxies `/api` to `localhost:8080` (see `frontend/proxy.conf.json`), so the browser
makes same-origin calls and no CORS setup is needed in development.

Data lives in an in-memory H2 database, so **links are gone when the backend restarts**. Inspect
the tables while it runs at http://localhost:8080/h2-console using the JDBC URL from
`application.properties`, user `sa`, and an empty password.

## Tests

```
cd backend  && mvn test      # 67 tests: JUnit 5 + MockMvc
cd frontend && npm test      # 34 tests: vitest + HttpTestingController
```

## API

| Method   | Path                          | Purpose                                     |
| -------- | ----------------------------- | ------------------------------------------- |
| `POST`   | `/api/v1/shorten`             | Create a short link (`201`)                 |
| `GET`    | `/api/v1/analytics`           | List links, newest first (`?page=&size=`)   |
| `GET`    | `/api/v1/analytics/summary`   | Totals across every link                    |
| `GET`    | `/api/v1/urls/{code}`         | Link details and click count                |
| `DELETE` | `/api/v1/urls/{code}`         | Delete a link (`204`)                       |
| `GET`    | `/{code}`                     | Redirect to the target (`302`) and count it |

Create a link:

```
curl -X POST http://localhost:8080/api/v1/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/very/long/path","customAlias":"demo","expiresInDays":30}'
```

```json
{
  "shortCode": "demo",
  "shortUrl": "http://localhost:8080/demo",
  "longUrl": "https://example.com/a/very/long/path",
  "createdAt": "2026-09-16T16:22:50.655037Z",
  "expiresAt": "2026-10-16T16:22:50.655037Z",
  "clickCount": 0
}
```

`customAlias` and `expiresInDays` are optional. Without an alias the service generates a random
7-character base62 code; without an expiry the link never expires.

Errors come back as JSON with the matching status:

```json
{ "status": 409, "error": "Conflict", "message": "Alias demo is already taken" }
```

`400` invalid URL or field, `404` unknown code, `409` alias taken, `410` link expired,
`429` rate limited (with a `Retry-After` header).

## How it works

- **Codes are random, not sequential.** `Base62Encoder` encodes a `SecureRandom` value padded to a
  fixed width, so codes cannot be walked to enumerate everyone's links. A collision is detected by
  the unique index and retried (`app.max-code-attempts`).
- **Only `http` and `https` targets are accepted.** The stored URL is echoed into a `Location`
  header, so allowing `javascript:` or `data:` would make every short link a script-injection
  vector.
- **Clicks are counted with an UPDATE, not a read-modify-write**, so concurrent redirects do not
  lose increments.
- **Redirect targets are cached in Redis** with the same lifetime as the link. Cache failures fall
  back to H2, and cache hits still execute the click-count update.
- **Redirects use `302` with `Cache-Control: no-store`** so browsers keep coming back through the
  service and clicks keep being counted.
- **Rate limiting is a per-IP token bucket** over `/api/**` only; redirects are never limited.

## Orchestration engine

The `/governance` screen drives a stateful workflow engine that models delivering this service as
six pipeline modules. The routing is a DAG rather than a list:

```
Requirements -> Architecture -+-> Implementation --+
                              |                    +-> [barrier] Testing -> Release Readiness
                              +-> Documentation ---+                          (human approval)
```

Implementation and Documentation are dispatched together onto a worker pool, so they run
concurrently; Testing has two incoming edges and is held by an explicit synchronization barrier
until both channels arrive. Governance controls:

- **Bounded retries** — three attempts per module, then the run gives up.
- **Automated rollback** — on the third failure the engine halts anything still in flight and
  restores the checkpoint taken before the current stage. The offending module stays `FAILED` so the
  grid still shows where it broke; a manual step resumes from the restored checkpoint.
- **Human approval** — Release Readiness parks at `AWAITING_APPROVAL` until a verification key is
  posted. The key is never written to the ledger, only the fact that one was accepted and by whom.
- **Lineage** — every dispatch, transition, retry, barrier wait and rollback is appended to a
  bounded, sequence-numbered ledger that survives a reset.

| Method | Path                                    | Purpose                                     |
| ------ | --------------------------------------- | ------------------------------------------- |
| `GET`  | `/api/v1/orchestration/state`           | Whole graph, barrier, gate and telemetry    |
| `GET`  | `/api/v1/orchestration/lineage`         | Ledger records, oldest first (`?limit=`)    |
| `POST` | `/api/v1/orchestration/start`           | Start a run (`409` if one is in progress)   |
| `POST` | `/api/v1/orchestration/step`            | Manual step; resumes after a rollback       |
| `POST` | `/api/v1/orchestration/failures/{mod}`  | Arm an anomaly (`?persistent=true`)         |
| `POST` | `/api/v1/orchestration/approve`         | Process the verification key                |
| `POST` | `/api/v1/orchestration/reset`           | Return every module to `PENDING`            |

Module work is simulated: `app.orchestration.module-latency-ms` stands in for real delivery effort,
since there is nothing to actually compile here. State lives in the one JVM, so a second replica
would run its own independent pipeline.

To watch a rollback: **Start run**, pick a module, press **Force rollback**, then
**Resume from checkpoint**.

## Configuration

All keys live in `backend/src/main/resources/application.properties`.

| Key                                | Default                 | Notes                                    |
| ---------------------------------- | ----------------------- | ---------------------------------------- |
| `spring.data.redis.host`           | `localhost`             | Redis host for redirect caching          |
| `spring.data.redis.port`           | `6379`                  | Redis port for redirect caching          |
| `app.base-url`                     | `http://localhost:8080` | Prefix for the returned `shortUrl`       |
| `app.code-length`                  | `7`                     | Generated code width (4-10)              |
| `app.max-code-attempts`            | `5`                     | Retries on a code collision              |
| `app.allowed-origins`              | `http://localhost:4200` | CORS origins for `/api/**`               |
| `app.rate-limit.enabled`           | `true`                  |                                          |
| `app.rate-limit.capacity`          | `120`                   | Burst size per client                    |
| `app.rate-limit.refill-per-minute` | `120`                   | Sustained requests per minute per client |
| `app.orchestration.max-attempts`   | `3`                     | Attempts per module before rollback      |
| `app.orchestration.module-latency-ms` | `900`                | Simulated work per module attempt        |
| `app.orchestration.worker-threads` | `4`                     | Pool the parallel channels run on        |
| `app.orchestration.ledger-capacity` | `500`                  | Lineage records retained                 |

## Before using this for real

The defaults are tuned for running on a laptop. For anything public:

- **Swap H2 for a real database.** Point `spring.datasource.*` at PostgreSQL and manage the schema
  with Flyway or Liquibase instead of `ddl-auto=update`.
- **Move rate-limit state out of the JVM.** `RateLimiter` holds its buckets in a local map, so each
  replica enforces its own quota. Use Redis behind more than one instance.
- **Fix the client-IP source.** `RateLimitFilter` trusts the first hop in `X-Forwarded-For`, which a
  caller can forge unless a known proxy sets it.
- **Add authentication.** Every endpoint is open, including `DELETE`, so anyone can remove anyone's
  link. There is no notion of an owner on `UrlMapping` yet.
- **Decide about untrusted targets.** Any public `http(s)` URL is accepted as-is, with no
  safe-browsing or malware check.
- **Persist the orchestration state.** The engine and its ledger are in-memory singletons, so a
  restart loses the run and its audit trail, and a second replica would not share either. Real
  audit-grade lineage needs a durable store, and the approval gate needs authentication so the
  verification key means something.
