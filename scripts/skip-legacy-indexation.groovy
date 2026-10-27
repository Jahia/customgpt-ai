/**
 * Marks legacy content with jmix:skipCustomGptIndexation so it can never be re-indexed into CustomGPT.
 *
 * Run via Jahia's Groovy console or a provisioning script. Companion to
 * cleanup-legacy-customgpt-mixins.groovy, which removes the *old* indexed mixins; this one sets the
 * opt-out that keeps the content out from now on.
 *
 * WHY THIS EXISTS
 * ---------------
 * Deleting obsolete pages from the CustomGPT project does not keep them out. A project-side prune changes
 * nothing in Jahia, so every pruned page still carries its jmix:customGptIndexable sidecar holding a
 * customGptPageId that now points at a deleted CustomGPT page.
 *
 * A single republish of any of them brings the legacy content straight back:
 *
 *   republish -> module reads the stale sidecar id -> DELETE returns HTTP 403 (measured semantics for an
 *   already-deleted id, not 404) -> removeExistingPage() discards deleteCustomGptPage()'s boolean, so the
 *   failure is silent -> POST proceeds -> the retired page is back in the corpus.
 *
 * jmix:skipCustomGptIndexation is the module's real opt-out: Service.skipIndexationForNode() tests it and
 * AbstractIndexBuilder consults that before building an index entry.
 *
 * Do NOT try to achieve this by removing jmix:customGptIndexable. That mixin is not an opt-in flag -- the
 * indexer adds it itself (CustomGptIndexerNodeHandler.getOrCreateMappingNode) purely to host the
 * customgptIndex child node. Stripping it destroys the sidecar, so the next republish finds no page id,
 * skips the DELETE entirely and POSTs a fresh copy: duplicates instead of prevention.
 *
 * WORKSPACES -- EDIT ONLY. DO NOT WRITE LIVE DIRECTLY.
 * ----------------------------------------------------
 * Adding a mixin straight into LIVE triggers a bug in the product. The supported route is to add the
 * mixin in EDIT and then PUBLISH the nodes; publication is what carries it to LIVE.
 *
 * Worth stating plainly, because the reasoning that argues for writing LIVE is persuasive and wrong:
 * IndexerJCRListener binds to LIVE and the index builder reads LIVE, so a mixin present only in EDIT
 * does nothing until publication. That is true about the indexer and wrong about the repository.
 *
 * The script therefore marks EDIT only. Publishing is a separate, deliberate step you perform afterwards
 * -- not automated here, because publishing a tree also pushes whatever unrelated drafts sit beside those
 * nodes in EDIT, and that decision is not this script's to make.
 *
 * NEITHER HALF CASCADES
 * ---------------------
 * Service.skipIndexationForNode() is a bare isNodeType() on the node it is handed, with no ancestor walk,
 * and handleSkipIndexMixinEvent resolves only the single node whose jcr:mixinTypes changed. So marking a
 * root does nothing for the tree beneath it -- neither removal nor future prevention.
 *
 * This script works because ROOTS is a QUERY SCOPE, not a marker: it queries every matching descendant
 * and marks each one individually.
 *
 * WHAT PUBLISHING DOES TO THE CORPUS -- measured; read this before publishing
 * --------------------------------------------------------------------------
 * Publishing the mixin addition does NOT simply remove the page. Measured over a batch: each page was
 * DELETEd and then immediately re-POSTed under a NEW page id, roughly one every 3.5 seconds, after which
 * the corpus sat completely static with every re-upload still present.
 *
 * Both halves follow from the ordering. The LIVE mixin change fires handleSkipIndexMixinEvent ->
 * tryQueueMappingRemoval -> DELETE. The publication ALSO fires an ordinary indexation event for the same
 * node, which re-uploads it. The re-upload lands last, so the net effect is a fresh document.
 *
 * It does not loop: the node now carries the mixin in LIVE, so skipIndexationForNode() returns true and
 * nothing indexes it again. But nothing removes the orphan either -- no further event fires for a node
 * that is already marked.
 *
 * CONSEQUENCE: publishing leaves the pages in the project, marked. Delete them from the project
 * afterwards. That deletion is permanent precisely BECAUSE the mixin is now in LIVE -- which is NOT true
 * of a deletion made before marking, where the next indexation run simply restores the page. Verify by
 * re-reading the corpus a few minutes later: an immediate read cannot tell the two apart.
 *
 * SIDE EFFECT
 * -----------
 * Marking EDIT writes nothing externally: the listener binds to LIVE, so no event fires until you publish.
 * It does mark every node as modified, so the tree shows as awaiting publication -- which is the handle
 * you then use to publish exactly these nodes.
 *
 * On publication each node generates one DELETE and one POST (above). That is real traffic against an API
 * which degrades badly under sustained volume -- it starts answering `status: success` with an empty
 * payload instead of 429 -- so publish in batches and re-measure between them.
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
 * The roots to retire. EMPTY BY DESIGN -- fill it in for your instance before running.
 *
 * ROOTS IS A QUERY SCOPE, NOT A MARKER. Nothing cascades: the script queries every matching descendant of
 * each root and marks each node individually. Listing a root does not mark the tree beneath it.
 *
 * TWO PATH TRAPS, both of which have produced roots that silently resolve to nothing:
 *
 * 1. A URL segment is not a JCR segment. Vanity URLs (jnt:vanityUrl) invent prefixes that exist nowhere in
 *    the repository, so a path copied out of a browser address bar or out of a prune log may have no node
 *    behind it at all. Resolve legacy URL prefixes through the site's jnt:vanityUrl nodes, not by hand.
 *
 * 2. Export XML escapes any node name that is not a legal XML element name -- which includes every name
 *    starting with a digit. A directory named 1_2 appears in an export as <_x0031__2>, and a page named
 *    404 as <_x0034_04>. That escaping belongs to the file, not to the repository. Decode _xHHHH_ back to
 *    its character before putting a path in this list or in PROTECTED_PREFIXES.
 *
 * Note also that version directories normally separate with an underscore, not a dot.
 *
 * BEFORE ADDING A ROOT, establish which of two kinds it is -- they differ in consequence, not in mechanism:
 *   - already removed from the CustomGPT project: the DELETE each node queues here is a no-op against an id
 *     that is already gone, and marking is pure prevention.
 *   - still in the live corpus: marking ACTIVELY REMOVES it, and the chatbot stops being able to answer
 *     about that content at all. That is a product decision about who the chatbot serves, not cleanup.
 * Run the first kind on its own first.
 */
final List<String> ROOTS = [
]

/** The node types the indexer treats as main resources. Keep in step with the module settings. */
final List<String> TYPES = ['jnt:page', 'jnt:file']

/**
 * EDIT only. Adding the mixin directly in LIVE triggers a product bug -- publish the marked nodes
 * instead, which is what carries the mixin to LIVE. See the WORKSPACES note in the header.
 */
final List<String> WORKSPACES = [Constants.EDIT_WORKSPACE]

final int BATCH_SIZE = 100

/** Optional server-side manifest. Leave null to rely on the log, which records every path either way. */
final String MANIFEST_FILE = null

final String SKIP_MIXIN = 'jmix:skipCustomGptIndexation'

/**
 * Content that must never be marked: the trees that are still current. The point is to reassert on every
 * run what you believe you are not touching. One match aborts the whole run before any write -- a wrong
 * root is far likelier than a wrong individual node, so failing the batch beats skipping the node.
 *
 * These are whole-path prefixes, not substrings. A substring test is wrong here: a retired tree routinely
 * holds pages whose names contain a current product's name -- archived release notes for a product that is
 * still shipping, say -- and a bare product-name test flags every one of them. What makes a page current is
 * where its tree starts, not a product name appearing somewhere along the way.
 */
final List<String> PROTECTED_PREFIXES = [
]

/**
 * Optional second guard, for the common shape where sibling version directories live under one parent and
 * only some are being retired. Written as a negative lookahead so that a version directory added LATER is
 * protected by default, rather than silently becoming eligible because nobody remembered to update a list.
 *
 * Keep it in step with the corresponding ROOTS entries -- deliberately two separate expressions, so that a
 * typo in one cannot quietly disarm the other. Leave null to disable.
 *
 * e.g. ~/^\/sites\/<site>\/<parent>\/(?!(<retired>|<retired>)(\/|$))/
 */
final java.util.regex.Pattern PROTECTED_CURRENT_PARENT = null

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

// --- pass 0: refuse to run unconfigured ------------------------------------
// An empty ROOTS would pass every guard below and report "0 node(s) to mark" as a success. Marking
// nothing must never look like having marked something.

if (!ROOTS) {
    def message = "${TAG} ABORT - ROOTS is empty. Fill it in for this instance; a run with no roots " +
            'would report success having written nothing.'
    log.error(message)
    return message
}

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
// With neither PROTECTED_PREFIXES nor PROTECTED_CURRENT_PARENT set, isProtected() is constantly false and
// the abort below can never fire. That is a legitimate configuration -- there may be nothing adjacent worth
// protecting -- but it must be stated rather than discovered, because the failure it guards against (a root
// one level too high) writes the mixin across current content and is only visible afterwards.

if (!PROTECTED_PREFIXES && PROTECTED_CURRENT_PARENT == null) {
    log.warn("${TAG} NO PROTECTION CONFIGURED - every selected node will be marked. Set PROTECTED_PREFIXES " +
            'to the current trees that must never be touched, or confirm deliberately that none exist.')
}

def isProtected = { String path ->
    PROTECTED_PREFIXES.any { path == it || path.startsWith(it + '/') } ||
            (PROTECTED_CURRENT_PARENT != null && PROTECTED_CURRENT_PARENT.matcher(path).find())
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
    // Per root as well as in total. The roots differ in consequence -- some are already gone from the
    // CustomGPT project, others are still live -- so one number is the thing that must not be the only one.
    ROOTS.each { root ->
        def n = selected[workspace].count { it == root || it.startsWith(root + '/') }
        emit("  ${String.format('%5d', n)}  ${root}")
    }
    selected[workspace].each { path -> log.info("${TAG} ${workspace} ${path}") }
}

if (MANIFEST_FILE) {
    // Durable on purpose. A manifest kept only in a session scratchpad is lost when the session ends, and
    // the roots then have to be reconstructed from URL prefixes rather than read back.
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
