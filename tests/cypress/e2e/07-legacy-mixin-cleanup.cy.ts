import {DocumentNode} from 'graphql';

// F18 — legacy mixin migration/cleanup script (scripts/cleanup-legacy-customgpt-mixins.groovy).
//
// Feasibility note (Stage 5): `cy.executeGroovy()` IS supported by this repo's Cypress harness - it ships
// in @jahia/cypress (the version already pinned in tests/package.json) and drives Jahia's provisioning REST
// API to run a Groovy script. That command requires the script to live under the Cypress `fixtures` folder,
// so `tests/cypress/fixtures/cleanup-legacy-customgpt-mixins.groovy` is a symlink to the real
// `scripts/cleanup-legacy-customgpt-mixins.groovy` (single source of truth - no content duplication/drift).
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

    it('has the legacy mixin/property present in both workspaces before running the script', () => {
        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'EDIT'}})
            .its('data.jcr.nodeByPath.property.value')
            .should('eq', 'legacy-page-id-123');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'LIVE'}})
            .its('data.jcr.nodeByPath.property.value')
            .should('eq', 'legacy-page-id-123');
    });

    it('removes the legacy mixin and customGptPageId property from both workspaces', () => {
        cy.executeGroovy('cleanup-legacy-customgpt-mixins.groovy');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'EDIT'}})
            .its('data.jcr.nodeByPath.property')
            .should('not.exist');

        cy.apollo({query: getNodeStatusInWorkspace, variables: {path: testPagePath(), workspace: 'LIVE'}})
            .its('data.jcr.nodeByPath.property')
            .should('not.exist');
    });
});
