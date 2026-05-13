# AGENTS.md — Architectural guide for AI coding agents

This document captures the contracts, patterns, and pitfalls needed to generate correct code for this codebase. Read it before writing any Java, GraphQL, or React code.

---

## Project overview

`customgpt-ai` is an OSGi/Jahia module (Java 11, OSGi DS, Blueprint). It:
1. Integrates with the CustomGPT.ai REST API (`https://app.customgpt.ai/api/v1/`) via OkHttp3.
2. Exposes a GraphQL API through `graphql-dxm-provider` (annotation-driven, not SDL).
3. Provides a React 17 admin settings panel registered as a Jahia `systemComponent`.

---

## Technology stack

| Layer | Technology |
|---|---|
| Build | Maven 3 + `frontend-maven-plugin` (Node v22.6.0, Yarn 1.22.21, webpack) |
| Backend | Java 11, OSGi DS (`@Component`, `@Reference`), Jahia 8.2+ |
| HTTP client | OkHttp3 4.12 (`customGptClient` with Bearer interceptor) |
| GraphQL | `graphql-dxm-provider` 3.4 — annotation-driven (`@GraphQLField`, `@GraphQLName`, etc.) |
| Frontend | React 17, i18next, CSS Modules (SCSS), Apollo Client |
| Testing | Cypress + TypeScript |

---

## Key source locations

```
src/main/java/org/jahia/community/modules/customgpt/
  service/Service.java                        # Central service: CustomGPT API calls, indexing
  settings/Config.java                        # OSGi ManagedService — reads .cfg properties
  graphql/extensions/
    GqlAdminQuery.java                        # @GraphQLField entry under admin.customGpt
    GqlCustomGptAdminMutation.java            # @GraphQLField entry under admin.customGpt (mutations)
    models/GqlCustomGptAdminMutationResult.java  # Mutation implementations
    models/GqlSettings.java                   # Settings DTO returned by getSettings query
    query/AdminQueries.java                   # Query implementations

src/javascript/CustomGptSettings/
  CustomGptSettings.jsx                       # Main React component
  CustomGptSettings.gql.js                   # GQL queries/mutations (Apollo gql tag)
  CustomGptSettings.scss                      # CSS Modules SCSS

src/main/resources/
  javascript/locales/{en,fr,de}.json          # i18next locale files (nested JSON)
  javascript/apps/                            # Webpack output (DO NOT edit manually)
  resources/customgpt-ai_{en,fr,de}.properties  # Jahia server-side i18n (NOT used by React)
  META-INF/configurations/org.jahia.community.modules.customgpt.cfg  # Default OSGi config
```

---

## OSGi / service patterns

### Config (`Config.java`)

- PID: `org.jahia.community.modules.customgpt`
- Implements `ManagedService`; `updated(Dictionary)` is called on every config change
- Properties use full-path keys: `org.jahia.community.modules.customgpt.projectId`, etc.
- Inject via `@Reference` in `Service.java`

### Service (`Service.java`)

- `@Component(service = Service.class, immediate = true)`
- Holds and manages the OkHttp3 client (`customGptClient`) with Bearer token `Authenticator` and `RateLimitInterceptor`
- Has a shared `ExecutorService` (`indexingExecutor`); purge creates a dedicated `batchExecutor` and shuts it down in `finally`
- `shutdownAndAwaitTermination(executor)` helper must be used for all executor shutdowns
- `resetScheduleJobASAP()` writes `scheduleJobASAP=false` back via `ConfigurationAdmin` — this will re-fire `Config.updated()` but with `false`, so it is safe (no infinite loop)

### Writing OSGi config programmatically

```java
// Always use ConfigurationAdmin, never write to files directly
@Reference
private ConfigurationAdmin configurationAdmin;

Configuration cfg = configurationAdmin.getConfiguration("org.jahia.community.modules.customgpt", null);
Dictionary<String, Object> props = cfg.getProperties();
props.put("org.jahia.community.modules.customgpt.scheduleJobASAP", "false");
cfg.update(props);
```

---

## GraphQL patterns

### Package structure

All GraphQL types must be in `graphql/extensions/` or sub-packages listed in `DXGraphQLProvider.java`'s `getQueries()` / `getMutations()` return lists.

### Annotations

Use `graphql-java-annotations` annotations:
- `@GraphQLField`, `@GraphQLName("camelCase")`, `@GraphQLDescription("...")`
- `@GraphQLNonNull` for mandatory fields
- Permission check: `GqlJahiaAdminMutation.requireAdminPermission(environment)` before any mutation side effect

### Admin permission check pattern

```java
if (!GqlJahiaAdminMutation.requireAdminPermission(environment)) {
    throw new GqlJahiaAdminMutationException("Insufficient permission");
}
```

### Accessing `Service` from a GQL model class

`BundleUtils` is the correct way (these classes are not OSGi components):
```java
Service customGptService = BundleUtils.getOsgiService(Service.class, null);
```

### Return types

- Boolean mutations: return `Boolean` (boxed)
- Count mutations (e.g., `purgeAllPages`): return `Integer` (boxed); throw checked exceptions wrapped in `RuntimeException` for GraphQL error propagation

---

## CustomGPT API patterns

All calls go through the `customGptClient` OkHttp3 instance (has Bearer auth header injected automatically).

### Base URL normalization

```java
String baseUrl = customGptConfig.getApiBaseUrl();
if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
```

### Pagination (`next_page_url`)

```java
String nextUrl = baseUrl + "/projects/" + projectId + "/pages";
while (nextUrl != null) {
    // GET nextUrl, parse JSON
    // data.pages.data → array of page objects with .id
    // data.pages.next_page_url → next page or null/JSONObject.NULL
    JSONObject body = new JSONObject(response.body().string());
    JSONObject pages = body.getJSONObject("data").getJSONObject("pages");
    JSONArray data = pages.getJSONArray("data");
    // ...
    Object next = pages.opt("next_page_url");
    nextUrl = (next instanceof String) ? (String) next : null;
}
```

### Batch concurrent deletion

```java
ExecutorService batchExecutor = Executors.newFixedThreadPool(batchSize);
try {
    for (int i = 0; i < total; i += batchSize) {
        List<Long> batch = pageIds.subList(i, Math.min(i + batchSize, total));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Long pageId : batch) {
            futures.add(CompletableFuture.runAsync(() -> {
                // DELETE /projects/{projectId}/pages/{pageId}
            }, batchExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
} finally {
    shutdownAndAwaitTermination(batchExecutor);
}
```

Use `AtomicInteger` for counters shared across `CompletableFuture` lambdas.

---

## Frontend patterns

### i18n — two separate systems

| System | Location | Used by |
|---|---|---|
| i18next (React) | `src/main/resources/javascript/locales/{en,fr,de}.json` | React UI (`useTranslation`) |
| Jahia i18n | `src/main/resources/resources/customgpt-ai_{en,fr,de}.properties` | Jahia server-side rendering |

**Never add React UI keys to `.properties` files.** Always add them to the nested JSON locale files.

JSON locale structure (nested under `"label"`):
```json
{
  "label": {
    "existingKey": "...",
    "newKey": "New label"
  }
}
```

### CSS Modules

SCSS file is `CustomGptSettings.scss`. All classes are accessed via `styles.cgpt_*`. The container must have:
```scss
.cgpt_container {
    height: 100%;
    overflow-y: auto;
    box-sizing: border-box;
}
```
to allow vertical scrolling in the admin panel.

### GQL hooks

Queries and mutations are defined in `CustomGptSettings.gql.js` using the `gql` tag and imported into the component. Apollo `useMutation` / `useQuery` hooks are used with `onCompleted` and `onError` callbacks.

### Admin registration

The admin UI entry is registered via `src/javascript/init.js` using `registry.add('adminRoute', ...)`. The route path is `customgptAiSettings`.

---

## Build

```bash
mvn install                         # full build including JS
mvn install -DskipTests             # skip tests
```

The `frontend-maven-plugin` (v1.15.0) runs three executions at `generate-resources`:
1. `install-node-and-yarn` — downloads Node v22.6.0 and Yarn v1.22.21 into `node/`
2. `yarn install` — installs JS dependencies
3. `yarn build:production` — runs webpack, outputs to `src/main/resources/javascript/apps/`

The following generated artefacts are gitignored — **never commit them**:
- `node/` — Node.js runtime downloaded at build time
- `src/main/resources/javascript/apps/*.js` and `*.js.map` — webpack bundle outputs
- `src/main/resources/javascript/apps/bom/` and `.well-known/` — SBOM outputs
- `tests/artifacts/*.jar` — test artifact JARs

---

## Testing

Cypress tests are in `tests/cypress/e2e/`. They require environment variables in `tests/.env`:
- `CUSTOMGPT_PROJECT_ID`, `CUSTOMGPT_TOKEN` — CustomGPT credentials (tests skip if absent)
- `JAHIA_SITE_KEY`, `SUPER_USER_PASSWORD` — Jahia credentials
- `CUSTOMGPT_API_BASE_URL` — CustomGPT API base URL

Tests use `cy.apollo(...)` for GraphQL calls. GraphQL fixture files are in `tests/cypress/fixtures/graphql/`.

---

## Common pitfalls

1. **Never edit `src/main/resources/javascript/apps/` directly** — it is webpack output.
2. **`.properties` files are NOT i18next** — React UI translations belong in JSON locale files only.
3. **`GqlSettings` uses a Builder** — construct it with `GqlSettings.builder().<setters>.build()`. All 16 fields have a corresponding fluent setter. The GraphQL schema is unaffected (schema reflects getters, not the constructor).
4. **OkHttp response bodies must be closed** — always use try-with-resources or explicit `close()` on `Response`.
5. **`scheduleJobASAP` self-reset** — resetting via ConfigurationAdmin re-fires `Config.updated()` with `scheduleJobASAP=false`, which is safe because the event listener checks the value before re-indexing.
6. **Base URL trailing slash** — always strip before appending path segments to avoid double slashes.
