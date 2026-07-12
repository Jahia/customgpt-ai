import {DocumentNode} from 'graphql';

// F3 (residual) — `startNodeIndex(nodePaths, inclDescendants: true)`: every existing test (see
// 03-new-page.cy.ts) only ever exercises the default/undefined `inclDescendants` case. This spec proves
// that when `inclDescendants: true` is passed, descendants of the targeted node are indexed too, not just
// the node itself.
describe('CustomGPT.ai node indexing with descendants', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const publishNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/publishNode.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createPage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createPage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deletePage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deletePage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const startNodeIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startNodeIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const parentPageName = 'cypress-descendant-parent';
    const childPageName = 'cypress-descendant-child';
    const parentPagePath = () => `/sites/${siteKey()}/home/${parentPageName}`;
    const childPagePath = () => `${parentPagePath()}/${childPageName}`;

    before(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            this.skip();
        }

        cy.login();

        cy.apollo({
            mutation: createPage,
            variables: {parentPathOrId: `/sites/${siteKey()}/home`, name: parentPageName}
        });
        cy.apollo({
            mutation: createPage,
            variables: {parentPathOrId: parentPagePath(), name: childPageName}
        });

        cy.apollo({
            mutation: publishNode,
            variables: {
                pathOrId: parentPagePath(),
                languages: ['en'],
                publishSubNodes: true,
                includeSubTree: true
            }
        });
    });

    after(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            return;
        }

        cy.apollo({mutation: deletePage, variables: {path: parentPagePath()}});
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

    it('indexes the descendant page when inclDescendants: true is passed', () => {
        cy.apollo({
            mutation: startNodeIndex,
            variables: {nodePaths: [parentPagePath()], inclDescendants: true}
        });

        cy.waitUntil(
            () =>
                cy
                    .apollo({query: getNodeStatus, variables: {path: `${parentPagePath()}/customgptIndex`}})
                    .then(result => Boolean(result.data.jcr.nodeByPath?.property?.value)),
            {timeout: 60000, interval: 1000, errorMsg: 'Timed out waiting for customGptPageId to be set on the parent page'}
        );

        cy.waitUntil(
            () =>
                cy
                    .apollo({query: getNodeStatus, variables: {path: `${childPagePath()}/customgptIndex`}})
                    .then(result => Boolean(result.data.jcr.nodeByPath?.property?.value)),
            {
                timeout: 60000,
                interval: 1000,
                errorMsg: 'Timed out waiting for customGptPageId to be set on the descendant page - inclDescendants: true should have indexed it too'
            }
        );

        cy.apollo({query: getNodeStatus, variables: {path: `${childPagePath()}/customgptIndex`}})
            .its('data.jcr.nodeByPath')
            .should(node => {
                expect(node).to.exist;
                expect(node.property).to.exist;
                expect(node.property.value).to.be.a('string').and.not.be.empty;
            });
    });
});
