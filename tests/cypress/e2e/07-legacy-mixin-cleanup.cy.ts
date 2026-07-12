import {DocumentNode} from 'graphql';

// F18 — legacy mixin migration/cleanup script (scripts/cleanup-legacy-customgpt-mixins.groovy).
//
// Feasibility note (Stage 5): `cy.executeGroovy()` IS supported by this repo's Cypress harness - it ships
// in @jahia/cypress (the version already pinned in tests/package.json) and drives Jahia's provisioning REST
// API to run a Groovy script. That command requires the script to live under the Cypress `fixtures` folder,
// so `tests/cypress/fixtures/cleanup-legacy-customgpt-mixins.groovy` is a symlink to the real
// `scripts/cleanup-legacy-customgpt-mixins.groovy` (single source of truth - no content duplication/drift).
//
// Stage 6 live-verification note: the symlink itself was a real bug under Docker. The build context for the
// Cypress test image is `tests/` only, so `COPY . /home/jahians` in the Dockerfile cannot see anything
// outside it - the symlink (`../../../scripts/...`) resolved to a dangling link inside the built image
// (confirmed directly: `docker run jahia/customgpt-ai:latest cat cypress/fixtures/cleanup-legacy-customgpt-
// mixins.groovy` reported "No such file or directory"). Fixed in `tests/ci.build.sh`, which now dereferences
// the symlink into a real file just for the Docker build and restores the tracked symlink afterwards.
//
// After that fix, `cy.executeGroovy()` genuinely loads and executes the real production script inside Jahia
// - confirmed live: re-running the actual script against this fresh sandbox produced a real Jahia-side JCR
// error (`InvalidQueryException: Selected node type does not exist: [jmix:customGptIndexed]`), not a
// Cypress/file-loading error, proving the provisioning round-trip genuinely works end to end.
//
// What remains structurally unresolvable within this stage: this spec's `before()` simulates "legacy" state
// by adding the `jmix:customGptIndexed` mixin to a freshly-created page. That mixin is intentionally NOT
// declared anywhere in the current module's CND (it predates this repo's tracked history - real legacy
// content only exists in repositories that had an older module version installed and later upgraded, which
// registers the type permanently in that repository's own JCR type registry independent of what the current
// module declares). A brand-new Cypress sandbox never had that older version installed, so the type was
// never registered here at all. Two independent runtime-registration approaches were tried directly against
// the live container and both failed to make the type usable for real node mutations/queries:
//   1. `NodeTypeRegistry.getInstance().addDefinitionsFile(...)` (Jahia's own deployment-time API) - the type
//      became visible to `NodeTypeManager.getNodeType()`/`hasNodeType()`, but `Node.addMixin(...)` still
//      threw `NoSuchNodeTypeException` (a different, lower persistence layer never got wired up).
//   2. Standard JCR `session.getWorkspace().getNodeTypeManager().registerNodeType(...)` - same outcome.
// Genuinely registering the type requires the real module-deployment pipeline (an actual OSGi bundle
// declaring it), which is test-infrastructure work out of scope for this execution stage. The two tests
// below are marked pending rather than left red or hacked around; see the Stage 6 execution report for the
// full investigation and a concrete recommendation (a dedicated legacy-schema fixture module) for whoever
// picks this up next.
describe('CustomGPT.ai legacy mixin cleanup script', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createPage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createPage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deletePage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deletePage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const publishNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/publishNode.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const addSitemapMixin: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addSitemapMixin.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const setNodeProperty: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setNodeProperty.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatusInWorkspace: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatusInWorkspace.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const testPageName = 'cypress-legacy-mixin-cleanup-test';
    const testPagePath = () => `/sites/${siteKey()}/home/${testPageName}`;
    const LEGACY_MIXIN = 'jmix:customGptIndexed';

    before(function () {
        cy.login();

        cy.apollo({
            mutation: createPage,
            variables: {parentPathOrId: `/sites/${siteKey()}/home`, name: testPageName}
        });

        // Simulate legacy state directly (as a real pre-migration node would have): add the legacy mixin
        // and set the customGptPageId property in EDIT, then publish so LIVE picks it up too.
        cy.apollo({
            mutation: addSitemapMixin,
            variables: {pathOrId: testPagePath(), mixins: [LEGACY_MIXIN]}
        });
        cy.apollo({
            mutation: setNodeProperty,
            variables: {pathOrId: testPagePath(), propertyName: 'customGptPageId', propertyValue: 'legacy-page-id-123'}
        });
        cy.apollo({
            mutation: publishNode,
            variables: {
                pathOrId: testPagePath(),
                languages: ['en'],
                publishSubNodes: true,
                includeSubTree: true
            }
        });
    });

    after(() => {
        cy.apollo({mutation: deletePage, variables: {path: testPagePath()}});
        cy.apollo({
            mutation: publishNode,
            variables: {
                pathOrId: `/sites/${siteKey()}/home`,
                languages: ['en'],
                publishSubNodes: true,
                includeSubTree: true
            }
        });
    });

    // Pending: see the Stage 6 investigation note above the describe() block - the legacy mixin type
    // cannot be genuinely registered in a fresh sandbox with the tools available in this stage.
    it.skip('has the legacy mixin/property present in both workspaces before running the script', () => {
        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'EDIT'}})
            .its('data.jcr.nodeByPath.property.value')
            .should('eq', 'legacy-page-id-123');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'LIVE'}})
            .its('data.jcr.nodeByPath.property.value')
            .should('eq', 'legacy-page-id-123');
    });

    // Pending: same reason as above - this test's assertions depend on the (unregisterable-in-sandbox)
    // legacy state established by the previous test.
    it.skip('removes the legacy mixin and customGptPageId property from both workspaces', () => {
        cy.executeGroovy('cleanup-legacy-customgpt-mixins.groovy');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'EDIT'}})
            .its('data.jcr.nodeByPath.property')
            .should('not.exist');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'LIVE'}})
            .its('data.jcr.nodeByPath.property')
            .should('not.exist');
    });
});
