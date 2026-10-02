/**
 * Marks legacy content with jmix:skipCustomGptIndexation so it can never be re-indexed into CustomGPT.
 *
 * Run via Jahia's Groovy console or a provisioning script. Companion to
 * cleanup-legacy-customgpt-mixins.groovy, which removes the *old* indexed mixins; this one sets the
 * opt-out that keeps the content out from now on.
 *
 * WHY THIS EXISTS
 * ---------------
 * On 2026-09-30 a prune deleted 912 obsolete pages from the CustomGPT project (127 /archives/,
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
 * jmix:skipCustomGptIndexation is the module's real opt-out: Service.skipIndexationForNode() tests it and
 * AbstractIndexBuilder consults that before building an index entry.
 *
 * Do NOT try to achieve this by removing jmix:customGptIndexable. That mixin is not an opt-in flag -- the
 * indexer adds it itself (CustomGptIndexerNodeHandler.getOrCreateMappingNode) purely to host the
 * customgptIndex child node. Stripping it destroys the sidecar, so the next republish finds no page id,
 * skips the DELETE entirely and POSTs a fresh copy: duplicates instead of prevention.
 *
 * WORKSPACES
 * ----------
 * Both, deliberately. IndexerJCRListener binds to LIVE (setWorkspace(Constants.LIVE_WORKSPACE)) and the
 * index builder reads LIVE, so a mixin present only in EDIT does nothing until the page is published --
 * and publishing 912 archived pages would also push whatever unrelated drafts sit beside them in EDIT.
 * Writing LIVE directly keeps the blast radius to this one mixin. EDIT is written too so a later publish
 * cannot silently undo it.
 *
 * Consequence to expect: touching EDIT marks those nodes as modified, so they will appear as awaiting
 * publication. On archived trees that is noise rather than harm, and publishing them later is a no-op for
 * this mixin because LIVE already has it. Set WORKSPACES to LIVE only if you would rather avoid that --
 * but then a future publish from EDIT may drop the mixin from LIVE unless it is also registered in the
 * EDIT node's j:liveProperties (the mechanism the sibling cleanup script scrubs). That path is NOT
 * exercised here because it could not be verified against a running instance.
 *
 * SIDE EFFECT
 * -----------
 * The LIVE mixin change fires IndexerJCRListener.handleSkipIndexMixinEvent, which queues a mapping removal
 * -- one CustomGPT DELETE per node. Those pages are already deleted, so each returns 403 and is discarded.
 * Harmless, but real traffic against an API that degrades under sustained volume (it starts answering
 * `status: success` with an empty payload instead of 429), which is why saves are batched.
 *
 * USAGE
 * -----
 * Edit the configuration block below, then run. DRY_RUN is true by default: it reports exactly what it
 * would mark and writes nothing. Read the output, confirm the counts, then set DRY_RUN = false.
 *
 * The dry run is also the check that the roots are right -- a root that does not resolve is a hard
 * failure, not a skip, because marking nothing looks exactly like success.
 */

import org.jahia.api.Constants
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate
import org.jahia.services.usermanager.JahiaUserManagerService

import javax.jcr.query.Query

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/** true = report only, write nothing. Always run once with true. */
final boolean DRY_RUN = true

/**
 * The trees the 2026-09-30 prune emptied, resolved against the 2026-09-11 site export.
 *
 * The prune recorded its targets as site URLs and its manifest is gone, so these were recovered by
 * resolving the legacy URL prefixes through the site's jnt:vanityUrl nodes. None of the URL segments is a
 * JCR path segment: /archives is a vanity URL on the legacy-1 page, and jahia-cms exists nowhere in the
 * repository -- it appears only inside vanity URLs.
 *
 * READING PATHS OUT OF AN EXPORT: the export XML escapes any node name that is not a legal XML element
 * name, so the 7.3 docs appear there as <_x0037__3>. That escaping belongs to the file, not to the
 * repository -- the JCR name is 7_3 (siblings 8_1, 8_2), and the home 404 page exports as <_x0034_04>.
 * Decode _xHHHH_ back to its character before putting a path in this list or in PROTECTED.
 */
final List<String> ROOTS = [
        // "Archives" in the UI; its /archives vanity URL is why the prune logged 127 pages under that
        // prefix and 537 more under legacy-1/. One tree, two URL forms. 781 jnt:page descendants.
        '/sites/academy/home/documentation/legacy-1',
        // Jahia 7.3 docs. The node is named 7_3, not 7.3: the version separator is an underscore, and its
        // siblings are 8_1 and 8_2. 217 jnt:page descendants, against the prune's 207 jahia-cms/jahia-7.x.
        '/sites/academy/home/documentation/jahia/7_3',
        // Jahia 8.1 docs, 199 jnt:page. DIFFERENT IN KIND FROM THE ROOTS ABOVE -- read this before running.
        // Those were deleted from CustomGPT in September, so the DELETE each one queues here is a no-op
        // against an id that is already gone. These 199 are still in the live corpus, so marking them
        // actively removes them: the chatbot stops being able to answer about 8.1 at all. 8.1 is the
        // version immediately before current, not an archive, so that is a product decision about who the
        // chatbot serves, not cleanup. Comment this line out to run the archive roots on their own first.
        '/sites/academy/home/documentation/jahia/8_1'
]

/** The node types the indexer treats as main resources. Keep in step with the module settings. */
final List<String> TYPES = ['jnt:page', 'jnt:file']

final List<String> WORKSPACES = [Constants.EDIT_WORKSPACE, Constants.LIVE_WORKSPACE]

final int BATCH_SIZE = 100

/** Optional server-side manifest. Leave null to rely on the log, which records every path either way. */
final String MANIFEST_FILE = null

final String SKIP_MIXIN = 'jmix:skipCustomGptIndexation'

/**
 * Content that must never be marked: the current product documentation trees, reasserting on every run
 * what the prune verified it had not touched. One match aborts the whole run before any write -- a wrong
 * root is far likelier than a wrong individual node, so failing the batch beats skipping the node.
 *
 * These are whole-path prefixes, not substrings. A substring test is wrong here: legacy-1 holds pages like
 * .../legacy-1/1/sysadmin/release-notes/jexperience-1.11.0 -- jExperience 1.11 release notes, archived
 * content that must be marked -- and a bare "jexperience" test flags all 18 of them. What makes a page
 * current is where its tree starts, not a product name appearing somewhere along the way.
 */
final List<String> PROTECTED_PREFIXES = [
        '/sites/academy/home/documentation/forms',
        '/sites/academy/home/documentation/jexperience',
        '/sites/academy/home/documentation/jahia-cloud',
        '/sites/academy/home/documentation/augmented-search',
        '/sites/academy/home/documentation/knowledge-base',
        '/sites/academy/home/documentation/glossary',
        '/sites/academy/home/customer-center'
]

/**
 * Everything under documentation/jahia except the version trees being retired: 8_2 today, and any version
 * directory added later without anyone remembering to update this script. Keep this list in step with the
 * jahia/* entries in ROOTS -- they are deliberately two separate lists, so that a typo in one cannot
 * quietly disarm the other.
 */
final java.util.regex.Pattern PROTECTED_CURRENT_JAHIA =
        ~/^\/sites\/academy\/home\/documentation\/jahia\/(?!(7_3|8_1)(\/|$))/

// ---------------------------------------------------------------------------

final String TAG = '[customgpt-skip]'
def rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser()
def report = new StringBuilder()

def emit = { String line ->
    log.info("${TAG} ${line}")
    report.append(line).append('\n')
}

/** SQL2 string literals escape a single quote by doubling it. */
def sql2Literal = { String value -> value.replace("'", "''") }

// --- pass 1: verify the roots resolve -------------------------------------
// Done in EDIT, which holds every node including those never published.

def missingRoots = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
        rootUser, Constants.EDIT_WORKSPACE, null, { JCRSessionWrapper session ->
    ROOTS.findAll { !session.nodeExists(it) }
} as JCRCallback)

if (missingRoots) {
    def message = "${TAG} ABORT - these roots do not resolve in EDIT: ${missingRoots.join(', ')}. " +
            'Correct ROOTS and re-run; marking nothing would look like success.'
    log.error(message)
    return message
}

// --- pass 2: discover, per workspace --------------------------------------
// Per workspace rather than once in EDIT, because a node can exist in one and not the other (never
// published, or published then deleted from EDIT). Each session reports what it can actually see.

def selected = [:]

WORKSPACES.each { workspace ->
    selected[workspace] = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
            rootUser, workspace, null, { JCRSessionWrapper session ->

        def paths = [] as Set

        ROOTS.each { root ->
            TYPES.each { type ->
                def statement = "SELECT * FROM [${type}] WHERE ISDESCENDANTNODE('${sql2Literal(root)}')"
                def nodes = session.workspace.queryManager
                        .createQuery(statement, Query.JCR_SQL2)
                        .execute().nodes

                while (nodes.hasNext()) {
                    def node = nodes.nextNode()
                    // Already marked: skip, so the script is idempotent and safe to re-run after a failure.
                    if (!node.isNodeType(SKIP_MIXIN)) {
                        paths << node.path
                    }
                }
            }
        }

        return paths.toList().sort()
    } as JCRCallback)
}

// --- guard: nothing current may be in the selection ------------------------

def isProtected = { String path ->
    PROTECTED_PREFIXES.any { path == it || path.startsWith(it + '/') } ||
            PROTECTED_CURRENT_JAHIA.matcher(path).find()
}

def violations = selected.values().flatten().unique().findAll { path -> isProtected(path) }

if (violations) {
    def message = "${TAG} ABORT - selection includes ${violations.size()} protected path(s), nothing was " +
            "written. First offenders: ${violations.take(10).join(', ')}"
    log.error(message)
    return message
}

// --- manifest --------------------------------------------------------------

emit("${DRY_RUN ? 'DRY RUN' : 'APPLY'} - mixin ${SKIP_MIXIN}")
emit("roots: ${ROOTS.join(', ')}")
emit("types: ${TYPES.join(', ')}")

WORKSPACES.each { workspace ->
    emit("${workspace}: ${selected[workspace].size()} node(s) to mark")
    // Per root as well as in total. The roots differ in consequence -- the archives are already gone from
    // CustomGPT, 8_1 is not -- so a single number is the one thing that must not be the only number.
    ROOTS.each { root ->
        def n = selected[workspace].count { it == root || it.startsWith(root + '/') }
        emit("  ${String.format('%5d', n)}  ${root}")
    }
    selected[workspace].each { path -> log.info("${TAG} ${workspace} ${path}") }
}

if (MANIFEST_FILE) {
    // Durable on purpose: the 2026-09-30 prune kept its manifest in a session scratchpad and lost it,
    // which is why the roots above had to be reconstructed rather than read back.
    def manifest = new File(MANIFEST_FILE)
    manifest.withWriter('UTF-8') { writer ->
        writer.writeLine("# ${new Date().format('yyyy-MM-dd HH:mm:ss')} mixin=${SKIP_MIXIN} dryRun=${DRY_RUN}")
        WORKSPACES.each { workspace ->
            selected[workspace].each { path -> writer.writeLine("${workspace}\t${path}") }
        }
    }
    emit("manifest written to ${MANIFEST_FILE}")
}

if (DRY_RUN) {
    def message = "${TAG} DRY RUN - nothing written. Review the counts above, then set DRY_RUN = false."
    log.info(message)
    report.append(message)
    return report.toString()
}

// --- pass 3: apply ---------------------------------------------------------

def applied = [:]
def failures = []

WORKSPACES.each { workspace ->
    applied[workspace] = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
            rootUser, workspace, null, { JCRSessionWrapper session ->

        def count = 0
        def batchCount = 0

        selected[workspace].each { path ->
            try {
                // Re-read rather than trusting the discovery pass: time has passed, and a node that moved
                // or was removed in between must not abort the run.
                if (!session.nodeExists(path)) {
                    log.warn("${TAG} ${workspace} vanished since discovery, skipping: ${path}")
                    return
                }

                def node = session.getNode(path)
                if (node.isNodeType(SKIP_MIXIN)) {
                    return
                }

                node.addMixin(SKIP_MIXIN)
                count++
                batchCount++
                log.info("${TAG} ${workspace} marked ${path}")

                if (batchCount >= BATCH_SIZE) {
                    session.save()
                    batchCount = 0
                }
            } catch (Exception e) {
                // One bad node must not cost the other 911. Collected and reported at the end.
                failures << "${workspace} ${path}: ${e.message}"
                log.warn("${TAG} ${workspace} FAILED ${path}: ${e.message}")
            }
        }

        if (batchCount > 0) {
            session.save()
        }

        return count
    } as JCRCallback)
}

def summary = "${TAG} Done - " +
        WORKSPACES.collect { "${it}: ${applied[it]} marked" }.join(', ') +
        ", ${failures.size()} failure(s)."

log.info(summary)
if (failures) {
    failures.each { log.warn("${TAG} ${it}") }
    summary += " Re-run to retry: marking is idempotent."
}

report.append(summary)
return report.toString()
