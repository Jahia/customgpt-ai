/**
 * Removes the legacy jmix:customGptIndexed / jmix:customGptFileIndexed mixins
 * and the associated customGptPageId property from all content nodes in both
 * the EDIT (default) and LIVE workspaces.
 *
 * In EDIT, the j:liveProperties tracking entries for those mixins and the
 * property are also removed so the node stays consistent with the live state.
 *
 * Run via Jahia's Groovy console or provisioning script.
 */

import org.jahia.api.Constants
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate
import org.jahia.services.usermanager.JahiaUserManagerService
import javax.jcr.query.Query

final String MIXIN_INDEXED      = "jmix:customGptIndexed"
final String MIXIN_FILE_INDEXED = "jmix:customGptFileIndexed"
final String PROP_PAGE_ID       = "customGptPageId"
final String PROP_LIVE_PROPS    = "j:liveProperties"
final int    BATCH_SIZE         = 100

def rootUser = JahiaUserManagerService.getInstance().lookupRootUser().getJahiaUser()
def counts   = [(Constants.LIVE_WORKSPACE): 0, (Constants.EDIT_WORKSPACE): 0]

[Constants.LIVE_WORKSPACE, Constants.EDIT_WORKSPACE].each { workspace ->
    counts[workspace] = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
            rootUser, workspace, null, { JCRSessionWrapper session ->

        def count      = 0
        def batchCount = 0

        [MIXIN_INDEXED, MIXIN_FILE_INDEXED].each { mixin ->
            def nodes = session.workspace.queryManager
                    .createQuery("SELECT * FROM [${mixin}]", Query.JCR_SQL2)
                    .execute().nodes

            while (nodes.hasNext()) {
                def node = nodes.nextNode()
                try {
                    // 1. Remove the customGptPageId property
                    if (node.hasProperty(PROP_PAGE_ID)) {
                        node.getProperty(PROP_PAGE_ID).remove()
                    }

                    // 2. In EDIT, scrub j:liveProperties tracking entries
                    if (workspace == Constants.EDIT_WORKSPACE && node.hasProperty(PROP_LIVE_PROPS)) {
                        def current  = node.getProperty(PROP_LIVE_PROPS).values.collect { it.string }
                        def filtered = current.findAll { v ->
                            v != PROP_PAGE_ID &&
                            v != "jcr:mixinTypes=${MIXIN_INDEXED}" &&
                            v != "jcr:mixinTypes=${MIXIN_FILE_INDEXED}"
                        }
                        if (filtered.size() < current.size()) {
                            if (filtered.isEmpty()) {
                                node.getProperty(PROP_LIVE_PROPS).remove()
                            } else {
                                node.setProperty(PROP_LIVE_PROPS, filtered as String[])
                            }
                        }
                    }

                    // 3. Remove the mixin
                    node.removeMixin(mixin)

                    count++
                    batchCount++
                    log.info("[customgpt-cleanup] Removed ${mixin} from ${node.path} [${workspace}]")

                    if (batchCount >= BATCH_SIZE) {
                        session.save()
                        batchCount = 0
                    }
                } catch (Exception e) {
                    log.warn("[customgpt-cleanup] Failed on ${node.path} [${workspace}]: ${e.message}")
                }
            }
        }

        if (batchCount > 0) {
            session.save()
        }

        return count
    } as JCRCallback)
}

def msg = "[customgpt-cleanup] Done — LIVE: ${counts[Constants.LIVE_WORKSPACE]} node(s), " +
          "EDIT: ${counts[Constants.EDIT_WORKSPACE]} node(s)"
log.info(msg)
return msg
