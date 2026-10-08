/*
 * Creates a published page that the indexing account cannot read, so the skip path can be exercised.
 *
 * Three details are load-bearing, and getting any of them wrong produces a page that LOOKS restricted while
 * the run never reaches the render at all - a test that passes for the wrong reason:
 *
 *  1. Created in `default` and PUBLISHED, not created separately in each workspace. Two separate nodes have
 *     different identifiers, and the module cannot resolve the live counterpart - it skips the node earlier,
 *     at "it does not exist in the live workspace".
 *  2. Created through a session carrying the English locale, so Jahia writes the `j:translation_en` child.
 *     The module opens its session with the indexation language; a page with no translation in that locale
 *     does not exist as far as that session is concerned, and is skipped by the same earlier guard.
 *  3. Inheritance broken in BOTH workspaces, with no grant. The indexer renders from `live`.
 *
 * The result: root sees the node (so it is collected), the indexer gets PathNotFoundException (so the render
 * skips it). Verified by the assertions in 07-restricted-node.cy.ts.
 */
import org.jahia.services.cache.CacheHelper
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRPublicationService
import org.jahia.services.content.JCRTemplate
import org.jahia.services.usermanager.JahiaUserManagerService
import org.slf4j.LoggerFactory

final def LOG = LoggerFactory.getLogger("CustomGptRestrictedPageFixture")
final String PATH = "/sites/@@SITE_KEY@@/home/@@PAGE_NAME@@"
final Locale LOCALE = Locale.ENGLISH

["live", "default"].each { workspace ->
    JCRTemplate.instance.doExecuteWithSystemSession(null, workspace, null, { session ->
        if (session.nodeExists(PATH)) {
            session.getNode(PATH).remove()
            session.save()
        }
        return null
    } as JCRCallback)
}

final String uuid = JCRTemplate.instance.doExecuteWithSystemSession(null, "default", LOCALE, { session ->
    final def page = session.getNode("/sites/@@SITE_KEY@@/home").addNode("@@PAGE_NAME@@", "jnt:page")
    page.setProperty("j:templateName", "simple")
    page.setProperty("jcr:title", "Restricted to the indexing account")
    session.save()
    return page.identifier
} as JCRCallback)

JCRPublicationService.instance.publishByMainId(uuid, "default", "live", null, true, null)

["default", "live"].each { workspace ->
    JCRTemplate.instance.doExecuteWithSystemSession(null, workspace, LOCALE, { session ->
        final def page = session.getNodeByIdentifier(uuid)
        page.setAclInheritanceBreak(true)
        session.save()
        return null
    } as JCRCallback)
}

CacheHelper.flushAllCaches()

// Fail loudly here rather than letting the spec pass for the wrong reason: root MUST still see the node
// through the same session type the module uses, or it never reaches the render.
final def rootUser = JahiaUserManagerService.instance.lookupRootUser().jahiaUser
JCRTemplate.instance.doExecuteWithSystemSessionAsUser(rootUser, "live", LOCALE, { session ->
    if (!session.nodeExists(PATH)) {
        throw new IllegalStateException(PATH + " is not visible to the indexer's own session type, so the"
                + " render would never be attempted and the skip path would not be exercised")
    }
    LOG.info("Restricted page {} is published, restricted, and still collectable as root", PATH)
    return null
} as JCRCallback)
