# customGPT.ai — Jahia Module

Jahia module that integrates with the [CustomGPT.ai](https://customgpt.ai) API to index Jahia site content (pages and files) into a CustomGPT project, keeping it in sync with JCR publish/unpublish events.

## Features

- **Full-site indexing** — index all published pages and files of a site into a CustomGPT project
- **Incremental indexing** — JCR observation listener picks up per-node publish/unpublish and updates CustomGPT accordingly
- **Admin UI** — React settings panel under Jahia Administration (`/jahia/administration/customgptAiSettings`)
- **Purge all pages** — Danger-zone action that deletes every page in the CustomGPT project via the API
- **Dry-run mode** — simulate indexing without sending any data to CustomGPT
- **Rate limiting** — token-bucket interceptor limits outgoing requests to a configurable rate (default 10 req/s); exponential back-off with full jitter on HTTP 429, honouring `Retry-After`
- **Batch operations** — purge streams one API result page at a time, deleting each batch concurrently before fetching the next
- **i18n** — UI labels in English, French, and German

## Requirements

| Dependency | Version |
|---|---|
| Jahia | ≥ 8.2.3.0 |
| graphql-dxm-provider | ≥ 3.4.0 |
| sitemap module | any |

## Build

```bash
mvn install
```

The `frontend-maven-plugin` handles Node/Yarn installation and the webpack production build automatically during `generate-resources`.

## Configuration

The module uses the OSGi config PID `org.jahia.community.modules.customgpt`.  
Drop a `.cfg` file in `$JAHIA_HOME/digital-factory-data/karaf/etc/` or edit from the Admin UI:

| Property | Default | Description |
|---|---|---|
| `projectId` | _(empty)_ | CustomGPT project ID |
| `token` | _(empty)_ | CustomGPT API Bearer token |
| `apiBaseUrl` | `https://app.customgpt.ai/api/v1/` | CustomGPT API base URL |
| `content.indexedMainResourceTypes` | `jnt:page,jmix:mainResource` | Comma-separated main resource node types to index |
| `content.indexedSubNodeTypes` | `jmix:droppableContent` | Comma-separated sub-node types whose text content is included |
| `content.indexedFileExtensions` | `pdf` | Comma-separated file extensions to index |
| `operations.batch.size` | `500` | Batch size for concurrent deletions and indexing jobs |
| `jahia.apiToken` | _(empty)_ | Personal API token the indexer renders pages with. Create it for a dedicated read-only account, scoped to `graphql`. Write-only |
| `jahia.graphqlEndpoint` | `http://localhost:8080/modules/graphql` | Where pages are rendered from. Keep it local |
| `serverName` | _(empty)_ | Server name the pages are fetched from and cited under — `host`, `host:port` or a full `scheme://host[:port]`. A bare host is read as `https://`. Empty means each site's own `sitemapIndexURL` host |
| `site.<siteKey>.serverName` | _(empty)_ | Same, for one site only; wins over `serverName` |
| `userAgent` | _(empty)_ | User-Agent sent when fetching a page's rendered HTML. Set it when the site is behind bot protection that refuses the default agent. Printable ASCII, no line breaks |
| `dryRun` | `true` | When `true`, simulate indexing without calling CustomGPT |
| `scheduleJobASAP` | `false` | When `true`, schedule indexing jobs immediately; auto-resets to `false` after jobs are queued |
| `rateLimit.requestsPerSecond` | `10` | Token-bucket rate: maximum CustomGPT API requests per second. The OkHttp client reads this at startup — **a module restart is required** for changes to take effect |

### How pages are rendered

Pages are rendered through Jahia's own GraphQL endpoint, not fetched from their public URL:

```properties
org.jahia.community.modules.customgpt.jahia.apiToken=<token>
org.jahia.community.modules.customgpt.jahia.graphqlEndpoint=http://localhost:8080/modules/graphql
```

The token is a **personal API token**, sent as `Authorization: APIToken <value>`. Create it in Jahia for a
dedicated read-only account — `customgpt-indexer` — and scope it to `graphql`. No password is stored or
transmitted, and the token is revocable on its own.

**The endpoint is deliberately local, and deliberately not derived from `serverName`.** The two answer different
questions: `serverName` is the public URL stored as the citation, while this is where content is fetched from.
Pointing the fetch at the public host sends it back out through the proxy, WAF and bot protection that rendering
through GraphQL exists to avoid.

#### Render as a read-only account, not as an administrator

Whatever is rendered is what ends up in the knowledge base, and the account doing the rendering changes it:

| Rendered as | What lands in the corpus |
|---|---|
| An administrator | `Preview`, `Page Composer`, `Logout` and the account name |
| Nobody (unauthenticated) | The login form — `Username`, `Password`, `Remember Me` |
| A read-only account | The page |

Both of the first two were measured on the Digitall home page; they differ by exactly those words.

#### The indexer does not need to see everything

Content the account cannot read is **skipped**, logged at `INFO` as
`Skipping <path>: ... is not readable by the indexing account`, and is **not** counted as a failure — a
restricted indexer is the intended state, so treating it as an error would mark every site `FAILED` on every
run. GraphQL returns a structured access denial for those nodes, which is what makes the distinction reliable;
fetching the same page over HTTP answered `200` with a login form that was then indexed as if it were content.

Grant the account read access to whatever should be in the knowledge base, and nothing else.

#### Pages and content are rendered differently

A `jnt:page` is rendered as a complete document. Any other indexed node — `jmix:mainResource` content — is
rendered on its own, because a content node has no page template and asking for one raises
`TemplateNotFoundException`. A content fragment also carries no page furniture, which suits a knowledge base.

Files are not rendered at all: their bytes are read straight from the repository.

### Indexation server name

The URL a page is indexed under is `<server name>` + the page's outbound-rewritten path. By default the server
name is derived from the site's `sitemapIndexURL` property. Override it when the site node does not name the host
its pages are actually served from — a preproduction instance restored from a production export still carries the
production `sitemapIndexURL`, and a site may carry no `sitemapIndexURL` at all.

```properties
# every site on this instance
org.jahia.community.modules.customgpt.serverName=academypp.jahia.com
# one site only, wins over the above
org.jahia.community.modules.customgpt.site.academy.serverName=academy.jahia.com
```

A bare host name is what Jahia's own `j:serverName` holds, so that is the form to reach for. A scheme and a port
are both accepted (`https://academypp.jahia.com`, `academypp.jahia.com:8443`); a value with no scheme is read as
`https://`, because the indexer's API token travels with the request and must not be downgraded to
cleartext. Set `http://` explicitly if the host really is served over cleartext.

Resolution order: `site.<siteKey>.serverName` → `serverName` → the site's `sitemapIndexURL`.

Both tiers are editable in the admin panel: a **Server name** field and a **Per-site server names** list.
The panel always submits the whole list, so removing a row removes the override.

> **Saving is not immediately in effect.** `saveSettings` (and a direct `.cfg` edit) hands the new properties to
> ConfigurationAdmin, which delivers them to the module on its own thread — the Jahia log shows this as
> `[CM Configuration Updater (Update: pid=org.jahia.community.modules.customgpt)]` followed by
> `CustomGpt configuration loaded`. An indexation started in the gap runs against the *previous* configuration.
> After changing a server name, wait for that log line (or re-read the settings) before starting an index,
> otherwise the first run may still use the old host.
>
> Re-indexing the **same node** twice in quick succession is also not two runs: the module coalesces index
> operations per node, logging `Coalesced N index operation(s) for node(s) already queued in this publication;
> 0 operation(s) dispatched`. The node's key is held until the in-flight indexation *completes*, which is later
> than its `customGptPageId` appearing — so publishing a page and immediately re-indexing it drops the second
> request, and the page keeps the URL the first run gave it. Re-issue the request until one gets through, or
> wait for the first run to finish.

The host must not be a **literal** private/loopback/link-local IP address — the render request carries the Jahia
API token, so such a host is refused for the same SSRF reason one coming from `sitemapIndexURL` is.
Leaving the scheme out does not bypass that check. Hostnames are not resolved (a DNS lookup on a configured value would
itself be a vector), so a *name* that happens to point inward is accepted; the value is admin-supplied, like every
other property here. Userinfo (`https://user@host`) and any path are dropped, so a sitemap URL can be pasted
verbatim.

A value that fails validation is logged and ignored. That site then falls back to the instance-wide `serverName`
if one is set, and only to its own `sitemapIndexURL` if none is.

This server name is used for **both** jobs: it is the host the rendered HTML is fetched from, and it is the
citation URL stored in CustomGPT. Changing it does not rewrite pages already indexed. To move them, re-index the
site; `repairPageUrls` recomputes URLs through the same resolution and will pick up the new host, but a host change
makes *every* page stale, which trips its 25% plan-size ceiling — so a full re-index is the reliable route, and
`repairPageUrls` is for the scoped case where you pass explicit page ids.

### Rendering user agent

The module fetches each page's rendered HTML from Jahia before handing it to CustomGPT. Some sites sit behind
bot protection — a WAF rule, a CDN filter — that refuses the HTTP client's default agent. Such a site cannot be
indexed at all: the fetch is rejected before any content exists, and every node is recorded as a failure with
nothing identifying the agent as the reason.

```properties
org.jahia.community.modules.customgpt.userAgent=Mozilla/5.0 (compatible; JahiaIndexer/1.0; +https://academy.jahia.com)
```

It is editable in the admin panel (**Rendering user agent**) as well as in the `.cfg`.

The header is sent on the rendering request only — never on CustomGPT API calls — and regardless of scheme, since
it carries no secret. Leaving it empty sends no `User-Agent` override at all rather than an empty one, which is
itself a bot signature on some filters.

The value must be printable ASCII with no line breaks (tab is allowed inside). Anything else is reported and
ignored, and pages are fetched with the default agent: OkHttp rejects an illegal header value by throwing while
building the request, which would otherwise fail every page with nothing pointing back at the `.cfg`.

## Admin UI

Navigate to **Jahia Administration → CustomGPT.ai** (`/jahia/administration/customgptAiSettings`).

The panel allows:
- Editing all configuration properties
- Viewing the CustomGPT project name (resolved live from the API)
- Saving settings (writes the OSGi config file)
- **Purge All Pages** — deletes every page registered in the CustomGPT project (irreversible, requires confirmation)

## GraphQL API

All operations are exposed under the `admin.customGpt` namespace.

**Queries**
- `admin.customGpt.settings` — read all settings (including `projectName` resolved from the API)
- `admin.customGpt.listSites` — list indexed sites and their indexation status

**Mutations**
- `admin.customGpt.addSite(siteKey)` — register a site for indexing (adds `jmix:customGptIndexableSite` mixin)
- `admin.customGpt.saveSettings(...)` — persist settings to OSGi config
- `admin.customGpt.startIndex(siteKeys, force)` — trigger full-site indexing (all sites if `siteKeys` omitted)
- `admin.customGpt.startNodeIndex(nodePaths, inclDescendants)` — trigger indexing for specific nodes
- `admin.customGpt.purgeAllPages` — delete all pages in the CustomGPT project; returns the number of pages deleted

## JCR Data Model

Each indexed node gets a `customgptIndex` child node (type `jnt:customGptIndexEntry`) storing the CustomGPT `pageId` as a string property. This replaces the legacy `jmix:customGptIndexed` mixin approach.

### Migration from legacy mixins

Run `scripts/cleanup-legacy-customgpt-mixins.groovy` from the Jahia Groovy console to remove the old `jmix:customGptIndexed` / `jmix:customGptFileIndexed` mixins and the `customGptPageId` property from all nodes in both EDIT and LIVE workspaces.

## Testing

End-to-end tests are in `tests/` using [Cypress](https://cypress.io).

```bash
cd tests
cp .env.example .env          # fill in CUSTOMGPT_PROJECT_ID, CUSTOMGPT_TOKEN, etc.
yarn install
yarn cypress open             # interactive
yarn cypress run              # headless
```

Tests that require real CustomGPT credentials are skipped automatically when `CUSTOMGPT_PROJECT_ID` or `CUSTOMGPT_TOKEN` are not set.

CI scripts:
- `tests/ci.startup.sh` — start Docker stack
- `tests/ci.build.sh` — build the module JAR
- `tests/ci.postrun.sh` — collect results

## Module metadata

| Key | Value |
|---|---|
| Artifact | `org.jahia.modules.community:customgpt-ai` |
| Module type | `system` |
| Deploy on site | `system` |
| Jahia depends | `default`, `graphql-dxm-provider`, `sitemap` |
