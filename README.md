# customGPT.ai — Jahia Module

Jahia module that integrates with the [CustomGPT.ai](https://customgpt.ai) API to index Jahia site content (pages and files) into a CustomGPT project, keeping it in sync with JCR publish/unpublish events.

## Features

- **Full-site indexing** — index all published pages and files of a site into a CustomGPT project
- **Incremental indexing** — JCR observation listener picks up per-node publish/unpublish and updates CustomGPT accordingly
- **Admin UI** — React settings panel under Jahia Administration (`/jahia/administration/customgptAiSettings`)
- **Purge all pages** — Danger-zone action that deletes every page in the CustomGPT project via the API
- **Dry-run mode** — simulate indexing without sending any data to CustomGPT
- **Batch operations** — configurable batch size for concurrent page deletions
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
| `jahia.username` | _(empty)_ | Jahia user for rendering pages during indexing |
| `jahia.password` | _(empty)_ | Jahia password for the rendering user |
| `jahia.serverCookie.name/value/domain` | _(empty)_ | Optional server cookie injected during rendering |
| `dryRun` | `true` | When `true`, simulate indexing without calling CustomGPT |
| `scheduleJobASAP` | `false` | When `true`, schedule indexing jobs immediately; auto-resets to `false` after jobs are queued |

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
