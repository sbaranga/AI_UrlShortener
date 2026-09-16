# System Specification Prompt: URL Shortener Service Architecture

 You are acting as a Senior Staff Engineer. Generate a production-ready, full-stack URL Shortener codebase from scratch using Java (Spring Boot) and Angular. Do not use shortcuts, placeholders, or omissions. Every file must be complete, compiled, and immediately usable.

---

## 🎯 Project Overview & Deliverables
Build a high-performance URL shortener with real-time analytics dashboards and platform reliability patterns.

### Architectural Constraints
*   **Backend:** Java 17+, Spring Boot 3.x, Spring Data JPA, H2 In-Memory Database.
*   **Frontend:** Angular 16+, TypeScript, RxJS, standard HTML5/CSS3.
*   **Inter-service:** Backend runs on `http://localhost:8080`, Frontend on `http://localhost:4200`. CORS must be globally permitted from port 4200.

---

## 🏗️ Technical Implementation Requirements

### 1. Backend Layer (Java Spring Boot)
Generate the following package structure under `src/main/java/com/example/urlshortener/`:

*   **`pom.xml`**: Include dependencies for `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `h2` (runtime scope), and `spring-boot-starter-validation`.
*   **`model/UrlMapping.java`**: Database entity containing `id` (Long), `originalUrl` (String, length 2048, URL validated), `shortKey` (String, unique, indexed), `clickCount` (long), and `createdAt` (LocalDateTime). Include indexing annotations.
*   **`repository/UrlRepository.java`**: JpaRepository interface exposing `findByShortKey` and `existsByShortKey`.
*   **`util/Base62Encoder.java`**: Utility class using an alphanumeric string configuration to generate a robust 7-character string.
*   **`security/RateLimiter.java`**: An in-memory, thread-safe Token Bucket rate limiter using a `ConcurrentHashMap` to guard routes by IP addresses.
*   **`controller/UrlController.java`**: REST Controller handling:
    1. `POST /api/v1/shorten`: Receives a JSON payload containing the long URL, ensures it doesn't collide, saves it, and returns the entity.
    2. `GET /{shortKey}`: Increments the click counter atomically, saves it, and issues an HTTP 302 Found redirect to the destination.
    3. `GET /api/v1/analytics`: Fetches all tracked mappings for the dashboard interface.

### 2. Frontend Layer (Angular 16+)
Generate the following core modules inside `src/app/`:

*   **`url.service.ts`**: Angular injectable service using `HttpClient` to process text formatting pipelines matching backend endpoints.
*   **`app.component.ts`**: Controls the user logic. Must support instant state pushing, dynamic error capturing, and invoking backend endpoints.
*   **`app.component.html`**: Clean UI showing an input bar for link generation and a tracking metrics table visualizing the destination, shortened hash, and total redirect clicks.

---

## 🏁 Output Execution Rules
1. Provide the complete code file-by-file with explicit file paths matching a clean multi-module directory structure.
2. Ensure the code handles HTTP 302 redirects properly to preserve analytics logging integrity.
3. Write pure, self-contained files that can be instantly compiled inside VS Code.