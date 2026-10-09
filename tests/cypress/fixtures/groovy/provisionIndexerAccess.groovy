/*
 * Gives the indexing account the access it needs to be a REAL restricted indexer, and mints its token.
 *
 * Two things the GraphQL API needs that `reader` does not provide:
 *
 *  1. `api-access`. Without it every query is refused at the ROOT field with GqlAccessDeniedException - not
 *     per node, the whole API. `reader` grants only jcr:read_live; of the roles Jahia ships, only `editor`
 *     and `owner` carry api-access, and both drag in write permissions and (for editor) privileged access,
 *     which would put edit-mode furniture into the rendered output. Hence a role granting api-access alone.
 *
 *  2. The grant has to exist in BOTH workspaces, and the role must carry `j:privilegedAccess`. A grant
 *     written only in `default`, or a role without that flag, leaves the API refusing everything - a silent,
 *     total denial that reads exactly like a bad credential, which is what made this hard to diagnose.
 *
 * `j:privilegedAccess` is not free: it makes the account a PRIVILEGED user, which widens what it can read
 * (it can reach /settings and /users, for instance). It is still a long way from root, and it still respects
 * broken ACL inheritance - a node with inheritance broken and no grant is invisible to it - so it remains a
 * meaningful test of restricted indexing. But do not read this account as "reader plus API access".
 *
 * The token is created with a FIXED value so the suite knows it without reading anything back. Personal API
 * tokens belong to the calling user, and the indexer deliberately cannot call `admin.personalApiTokens` -
 * so it cannot mint its own over GraphQL, and minting it as root (which is what the suite used to do by
 * accident) produces a ROOT token that bypasses nothing and tests nothing.
 */
import org.jahia.osgi.FrameworkService
import org.jahia.services.cache.CacheHelper
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRTemplate
import org.jahia.services.usermanager.JahiaUserManagerService
import org.slf4j.LoggerFactory

final def LOG = LoggerFactory.getLogger("CustomGptIndexerProvisioning")
final String INDEXER_USER = "@@INDEXER_USER@@"
final String INDEXER_TOKEN = "@@INDEXER_TOKEN@@"
final String ROLE = "customgpt-api-access"
final String TOKEN_NAME = "cypress-indexer"

final String userPath = JahiaUserManagerService.instance.lookupUser(INDEXER_USER).localPath

// 1. The role. Only api-access: content read stays with the `reader` grant on the site.
JCRTemplate.instance.doExecuteWithSystemSession(null, "default", null, { session ->
    final def roles = session.getNode("/roles")
    final def role = roles.hasNode(ROLE) ? roles.getNode(ROLE) : roles.addNode(ROLE, "jnt:role")
    role.setProperty("j:permissionNames", ["api-access"] as String[])
    role.setProperty("j:roleGroup", "live-role")
    // Without this the grant is inert and every query is refused at the root field.
    role.setProperty("j:privilegedAccess", true)
    session.save()
    return null
} as JCRCallback)

// 2. Granted at the repository root, in both workspaces.
["default", "live"].each { workspace ->
    JCRTemplate.instance.doExecuteWithSystemSession(null, workspace, null, { session ->
        session.getNode("/").grantRoles("u:" + INDEXER_USER, [ROLE] as Set)
        session.save()
        return null
    } as JCRCallback)
}

// 3. A token owned by the indexer, with a value the suite already knows.
final def bundleContext = FrameworkService.instance.bundleContext
final def tokenService = bundleContext.getService(
        bundleContext.getServiceReference("org.jahia.modules.apitokens.TokenService"))

JCRTemplate.instance.doExecuteWithSystemSession(null, "default", null, { session ->
    // Whoever currently holds this value has to go first, wherever it lives. The value is fixed, so a token
    // left behind by an earlier run - possibly under a DIFFERENT user - shadows the one created below and
    // authentication silently resolves to that other account.
    final def existing = tokenService.verifyToken(INDEXER_TOKEN, session)
    if (existing != null) {
        tokenService.deleteToken(existing.key, session)
        session.save()
    }
    final String tokenPath = userPath + "/tokens/" + TOKEN_NAME
    if (session.nodeExists(tokenPath)) {
        session.getNode(tokenPath).remove()
        session.save()
    }
    tokenService.tokenBuilder(userPath, TOKEN_NAME, session)
            .setScopes(["graphql"])
            .setActive(true)
            .setToken(INDEXER_TOKEN)
            .create()
    session.save()
    return null
} as JCRCallback)

CacheHelper.flushAllCaches()
LOG.info("Provisioned {} with the {} role and a personal API token", INDEXER_USER, ROLE)
