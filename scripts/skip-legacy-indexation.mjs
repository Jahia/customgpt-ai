#!/usr/bin/env node
/**
 * Marks legacy content with jmix:skipCustomGptIndexation so it can never be re-indexed.
 *
 * WHY THIS EXISTS
 * ---------------
 * On 2026-09-30 a prune deleted 912 obsolete pages from CustomGPT project 68402 (127 /archives/,
 * 156 legacy-1/digital-experience-manager, 381 other legacy-1/, 207 jahia-cms/jahia-7.x, 41 non-public
 * Archives files). That prune was CustomGPT-side only -- nothing was changed in Jahia -- so every one of
 * those pages still carries its jmix:customGptIndexable sidecar holding a customGptPageId that now points
 * at a deleted CustomGPT page.
 *
 * A single republish of any of them brings the legacy content straight back:
 *
 *   republish -> module reads the stale sidecar id -> DELETE returns HTTP 403 (measured semantics for an
 *   already-deleted id, not 404) -> removeExistingPage() discards deleteCustomGptPage()'s boolean, so the
 *   failure is silent -> POST proceeds -> the Jahia 7.x page is back in the corpus.
 *
 * jmix:skipCustomGptIndexation is the module's real opt-out: Service.skipIndexationForNode() tests it, and
 * AbstractIndexBuilder consults that before building an index entry.
 *
 * Do NOT try to achieve this by removing jmix:customGptIndexable. That mixin is not an opt-in flag -- the
 * indexer adds it itself (CustomGptIndexerNodeHandler.getOrCreateMappingNode) purely to host the
 * customgptIndex child node. Stripping it destroys the sidecar, so the next republish finds no page id,
 * skips the DELETE entirely and POSTs a fresh copy. That creates duplicates rather than preventing them.
 *
 * WHAT IT DOES
 * ------------
 * Adds the mixin through Jahia's standard JCR GraphQL (jcr.mutateNode.addMixins), in EDIT and in LIVE.
 * It never deletes anything and never touches CustomGPT directly.
 *
 * Both workspaces, deliberately. IndexerJCRListener binds to LIVE (setWorkspace(Constants.LIVE_WORKSPACE))
 * and the index builder reads LIVE, so a mixin present only in EDIT has no effect until the page is
 * published -- and publishing 912 archived pages would also push whatever unrelated drafts are sitting in
 * EDIT. Writing LIVE directly keeps the blast radius to this one mixin. EDIT is written too so the next
 * publish from EDIT cannot silently undo it.
 *
 * Expect a side effect in LIVE: the mixin change fires IndexerJCRListener.handleSkipIndexMixinEvent, which
 * queues a mapping removal -- a CustomGPT DELETE per node. Those pages are already gone, so each returns
 * 403 and is discarded. Harmless, but it is real API traffic against an endpoint that degrades under
 * sustained volume (it starts answering `status: success` with an empty payload instead of 429), which is
 * why writes are batched with a pause between batches.
 *
 * USAGE
 * -----
 *   export JAHIA_URL=https://academy.jahia.com
 *   export JAHIA_USER=root
 *   export JAHIA_PASSWORD=...            # never logged, never written to the manifest
 *
 *   node scripts/skip-legacy-indexation.mjs                    # dry run (default): discovers, writes manifest
 *   node scripts/skip-legacy-indexation.mjs --apply            # performs the mutations
 *   node scripts/skip-legacy-indexation.mjs --roots=/sites/academy/home/archives,...
 *   node scripts/skip-legacy-indexation.mjs --apply --resume=out/skip-legacy-2026-10-02.json
 *
 * Run the dry run first and read the manifest. It exercises every read query this script uses, so if a
 * field name does not match your Jahia version you find out before anything is written.
 *
 * Flags:
 *   --apply              perform writes (default is dry run)
 *   --roots=a,b,c        override the legacy roots
 *   --types=a,b          override the node types to mark (default: read from the module settings)
 *   --workspaces=EDIT,LIVE
 *   --batch=50           nodes per batch
 *   --pause=1000         ms between batches
 *   --resume=FILE        re-use a previous manifest instead of re-discovering
 *   --out=DIR            manifest directory (default: ./out)
 */

import {writeFileSync, readFileSync, mkdirSync, existsSync} from 'node:fs';
import {join} from 'node:path';

const SKIP_MIXIN = 'jmix:skipCustomGptIndexation';

/**
 * The trees the 2026-09-30 prune emptied, as JCR paths.
 *
 * VERIFY THESE AGAINST YOUR INSTANCE BEFORE THE FIRST RUN. The prune recorded its targets as site URLs,
 * not JCR paths, and the manifest that held them lived in a session scratchpad and is gone. These are
 * derived with the rule established then (strip host, drop .html, prepend /sites/academy) but the position
 * of /home in academy URLs was never pinned down in writing. The script hard-fails on a root that does not
 * resolve rather than quietly marking nothing -- a silent zero is the failure mode to avoid here.
 */
const DEFAULT_ROOTS = [
    '/sites/academy/home/archives',
    '/sites/academy/home/legacy-1',
    '/sites/academy/home/jahia-cms/jahia-7.x'
];

/**
 * Content that must never be marked. The prune verified it touched no current Jahia 8.1/8.2, Forms,
 * jExperience 2.x/3.x, Cloud, Augmented Search, knowledge-base or security-advisory page; this reasserts it
 * on every run. A single match aborts the whole run before any write -- a bad root is far more likely than
 * a bad individual node, so failing the batch beats skipping the node.
 */
const PROTECTED_PATTERNS = [
    /\/jahia-8\.\d/i,
    /\/jahia-cms\/jahia-8/i,
    /\/forms(\/|$)/i,
    /\/jexperience/i,
    /\/jahia-cloud/i,
    /\/augmented-search/i,
    /\/knowledge-base/i,
    /\/security-advisor/i,
    /\/jsa-\d{4}/i
];

const args = Object.fromEntries(
    process.argv.slice(2).map(a => {
        const [k, v] = a.replace(/^--/, '').split('=');
        return [k, v ?? true];
    })
);

const APPLY = args.apply === true;
const BATCH = Number(args.batch ?? 50);
const PAUSE = Number(args.pause ?? 1000);
const OUT_DIR = String(args.out ?? 'out');
const WORKSPACES = String(args.workspaces ?? 'EDIT,LIVE').split(',').map(w => w.trim().toUpperCase());
const ROOTS = args.roots ? String(args.roots).split(',').map(r => r.trim()) : DEFAULT_ROOTS;

const {JAHIA_URL, JAHIA_USER} = process.env;
const SECRET = process.env.JAHIA_PASSWORD;

if (!JAHIA_URL || !JAHIA_USER || !SECRET) {
    fail('Set JAHIA_URL, JAHIA_USER and JAHIA_PASSWORD. The credential is read from the environment and is ' +
        'never logged or written to the manifest.');
}

const ENDPOINT = `${JAHIA_URL.replace(/\/$/, '')}/modules/graphql`;
const AUTH = 'Basic ' + Buffer.from(`${JAHIA_USER}:${SECRET}`).toString('base64');

function fail(message) {
    console.error(`\n  ABORT: ${message}\n`);
    process.exit(1);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/**
 * One GraphQL round trip. Jahia answers 200 with an `errors` array for a bad field or a permission
 * problem, so the HTTP status alone proves nothing -- both are checked, and the error is surfaced
 * verbatim rather than summarised, because a field-name mismatch against another Jahia version is the
 * most likely way this script breaks.
 */
async function gql(query, variables = {}) {
    let response;
    try {
        response = await fetch(ENDPOINT, {
            method: 'POST',
            headers: {'Content-Type': 'application/json', Authorization: AUTH},
            body: JSON.stringify({query, variables})
        });
    } catch (e) {
        // Node's fetch reports every transport problem as a bare "fetch failed"; the cause carries the
        // DNS / refused-connection / TLS detail that says which one it actually is.
        throw new Error(`cannot reach ${ENDPOINT}: ${e.cause?.message ?? e.message}`);
    }

    const text = await response.text();
    let payload;
    try {
        payload = JSON.parse(text);
    } catch {
        throw new Error(`HTTP ${response.status}: response was not JSON -- ${text.slice(0, 300)}`);
    }

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${JSON.stringify(payload).slice(0, 500)}`);
    }

    if (payload.errors?.length) {
        throw new Error(`GraphQL: ${payload.errors.map(e => e.message).join(' | ')}`);
    }

    return payload.data;
}

/**
 * The node types the indexer actually treats as main resources, read from the module rather than guessed,
 * so this marks exactly the set that would otherwise be indexed. Falls back to the module defaults if the
 * admin query is unavailable to this user.
 */
async function resolveTypes() {
    if (args.types) {
        return String(args.types).split(',').map(t => t.trim());
    }

    try {
        const data = await gql(`{ admin { customGpt { settings { contentIndexedMainResourceTypes } } } }`);
        const configured = data?.admin?.customGpt?.settings?.contentIndexedMainResourceTypes;
        if (configured) {
            return configured.split(',').map(t => t.trim()).filter(Boolean);
        }
    } catch (e) {
        console.warn(`  ! could not read module settings (${e.message}); falling back to defaults`);
    }

    return ['jnt:page', 'jnt:file'];
}

/** Hard-fails on any root that does not resolve. A missing root must not read as "nothing to do". */
async function verifyRoots() {
    const missing = [];
    for (const path of ROOTS) {
        const data = await gql(
            `query ($path: String!) { jcr(workspace: EDIT) { nodeByPath(path: $path) { path primaryNodeType { name } } } }`,
            {path}
        );
        if (!data?.jcr?.nodeByPath?.path) {
            missing.push(path);
        }
    }

    if (missing.length) {
        fail(`these roots do not resolve in EDIT:\n    ${missing.join('\n    ')}\n` +
            `  Derive the real paths and pass --roots=... . Marking nothing would look like success.`);
    }
}

/**
 * Every descendant of `root` of an indexed type, minus those already carrying the skip mixin, so re-runs
 * are cheap and the script is safe to resume. Paged, because these trees run to hundreds of nodes.
 */
async function discoverUnder(root, types) {
    const found = [];

    for (const type of types) {
        let offset = 0;
        for (;;) {
            const query = `SELECT * FROM [${type}] WHERE ISDESCENDANTNODE('${root.replace(/'/g, "''")}')`;
            const data = await gql(
                `query ($q: String!, $limit: Int, $offset: Int) {
                    jcr(workspace: EDIT) {
                        nodesByQuery(query: $q, queryLanguage: SQL2, limit: $limit, offset: $offset) {
                            nodes { path mixinTypes { name } }
                        }
                    }
                }`,
                {q: query, limit: 500, offset}
            );

            const nodes = data?.jcr?.nodesByQuery?.nodes ?? [];
            for (const node of nodes) {
                const already = (node.mixinTypes ?? []).some(m => m.name === SKIP_MIXIN);
                if (!already) {
                    found.push(node.path);
                }
            }

            if (nodes.length < 500) {
                break;
            }

            offset += nodes.length;
        }
    }

    return found;
}

/** Aborts the run if anything current slipped into the selection. Checked before a single write. */
function assertNothingProtected(paths) {
    const violations = paths.filter(p => PROTECTED_PATTERNS.some(re => re.test(p)));
    if (violations.length) {
        fail(`selection includes ${violations.length} protected path(s) -- nothing was written:\n    ` +
            violations.slice(0, 20).join('\n    ') +
            (violations.length > 20 ? `\n    ... and ${violations.length - 20} more` : ''));
    }
}

async function addMixin(path, workspace) {
    await gql(
        `mutation ($path: String!, $mixins: [String!]!) {
            jcr(workspace: ${workspace}) {
                mutateNode(pathOrId: $path) { addMixins(mixins: $mixins) }
            }
        }`,
        {path, mixins: [SKIP_MIXIN]}
    );
}

async function main() {
    console.log(`\n  ${APPLY ? 'APPLY' : 'DRY RUN'} -- ${ENDPOINT}`);
    console.log(`  mixin:      ${SKIP_MIXIN}`);
    console.log(`  workspaces: ${WORKSPACES.join(', ')}`);
    console.log(`  roots:\n    ${ROOTS.join('\n    ')}\n`);

    let paths;

    if (args.resume) {
        const manifest = JSON.parse(readFileSync(String(args.resume), 'utf8'));
        paths = manifest.paths;
        console.log(`  resumed ${paths.length} path(s) from ${args.resume}\n`);
    } else {
        await verifyRoots();
        const types = await resolveTypes();
        console.log(`  types:      ${types.join(', ')}\n`);

        paths = [];
        for (const root of ROOTS) {
            const under = await discoverUnder(root, types);
            console.log(`  ${String(under.length).padStart(5)}  ${root}`);
            paths.push(...under);
        }

        paths = [...new Set(paths)].sort();
    }

    console.log(`\n  ${paths.length} node(s) to mark (nodes already carrying the mixin were skipped)\n`);
    assertNothingProtected(paths);

    if (!existsSync(OUT_DIR)) {
        mkdirSync(OUT_DIR, {recursive: true});
    }

    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const manifestPath = join(OUT_DIR, `skip-legacy-${stamp}.json`);
    writeFileSync(manifestPath, JSON.stringify({
        generatedAt: new Date().toISOString(),
        endpoint: ENDPOINT,
        mixin: SKIP_MIXIN,
        workspaces: WORKSPACES,
        roots: ROOTS,
        applied: APPLY,
        count: paths.length,
        paths
    }, null, 2));
    console.log(`  manifest: ${manifestPath}`);

    if (!APPLY) {
        console.log('\n  Dry run -- nothing was written. Review the manifest, then re-run with --apply.\n');
        return;
    }

    const failures = [];
    let done = 0;

    for (let i = 0; i < paths.length; i += BATCH) {
        const batch = paths.slice(i, i + BATCH);

        for (const path of batch) {
            for (const workspace of WORKSPACES) {
                try {
                    await addMixin(path, workspace);
                } catch (e) {
                    failures.push({path, workspace, error: e.message});
                }
            }
            done++;
        }

        console.log(`  ${done}/${paths.length} marked${failures.length ? ` (${failures.length} failure(s))` : ''}`);

        if (i + BATCH < paths.length) {
            await sleep(PAUSE);
        }
    }

    const reportPath = join(OUT_DIR, `skip-legacy-${stamp}-result.json`);
    writeFileSync(reportPath, JSON.stringify({
        finishedAt: new Date().toISOString(),
        attempted: paths.length,
        failed: failures.length,
        failures
    }, null, 2));

    console.log(`\n  Done -- ${paths.length - failures.length}/${paths.length} marked, ${failures.length} failed.`);
    console.log(`  Result: ${reportPath}`);
    if (failures.length) {
        console.log('  Re-run with --resume on the manifest to retry; marking is idempotent.\n');
        process.exitCode = 1;
    } else {
        console.log('');
    }
}

main().catch(e => fail(e.message));
