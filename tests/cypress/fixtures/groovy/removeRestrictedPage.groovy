import org.jahia.services.cache.CacheHelper
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRTemplate

final String PATH = "/sites/@@SITE_KEY@@/home/@@PAGE_NAME@@"

["live", "default"].each { workspace ->
    JCRTemplate.instance.doExecuteWithSystemSession(null, workspace, null, { session ->
        if (session.nodeExists(PATH)) {
            session.getNode(PATH).remove()
            session.save()
        }
        return null
    } as JCRCallback)
}
CacheHelper.flushAllCaches()
