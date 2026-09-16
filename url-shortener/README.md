# URL Shortener

Spring Boot API plus an Angular front end. Paste a long URL, get a short one, and see how many
times each link has been followed.

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
cd backend  && mvn test      # 34 tests: JUnit 5 + MockMvc
cd frontend && npm test      # 6 tests: vitest + HttpTestingController
```

## API

| Method   | Path                  | Purpose                                     |
| -------- | --------------------- | ------------------------------------------- |
| `POST`   | `/api/urls`           | Create a short link (`201`)                 |
| `GET`    | `/api/urls`           | List links, newest first (`?page=&size=`)   |
| `GET`    | `/api/urls/{code}`    | Link details and click count                |
| `DELETE` | `/api/urls/{code}`    | Delete a link (`204`)                       |
| `GET`    | `/{code}`             | Redirect to the target (`302`) and count it |

Create a link:

```
curl -X POST http://localhost:8080/api/urls \
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
- **Redirects use `302` with `Cache-Control: no-store`** so browsers keep coming back through the
  service and clicks keep being counted.
- **Rate limiting is a per-IP token bucket** over `/api/**` only; redirects are never limited.

## Configuration

All keys live in `backend/src/main/resources/application.properties`.

| Key                                | Default                 | Notes                                    |
| ---------------------------------- | ----------------------- | ---------------------------------------- |
| `app.base-url`                     | `http://localhost:8080` | Prefix for the returned `shortUrl`       |
| `app.code-length`                  | `7`                     | Generated code width (4-10)              |
| `app.max-code-attempts`            | `5`                     | Retries on a code collision              |
| `app.allowed-origins`              | `http://localhost:4200` | CORS origins for `/api/**`               |
| `app.rate-limit.enabled`           | `true`                  |                                          |
| `app.rate-limit.capacity`          | `30`                    | Burst size per client                    |
| `app.rate-limit.refill-per-minute` | `30`                    | Sustained requests per minute per client |

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
