import {DocumentNode} from 'graphql';

// F2 — incremental indexing via JCR publish/unpublish events, driven purely by
// IndexerJCRListener.onEvent() detecting the j:lastPublished property change. Deliberately never calls
// startIndex/startNodeIndex anywhere in this spec (unlike every other existing indexing spec) - the whole
// point is to isolate the autonomous listener path, which no existing test exercises in isolation.
describe('CustomGPT.ai autonomous JCR-listener indexing', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const publishNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/publishNode.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createPage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createPage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deletePage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deletePage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const apiBaseUrl = () => Cypress.env('CUSTOMGPT_API_BASE_URL') as string;
    const testPageName = 'cypress-autonomous-indexing-test';
    const testPagePath = () => `/sites/${siteKey()}/home/${testPageName}`;

    before(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            this.skip();
        }

        cy.login();
    });

    it('indexes a newly published page with no startIndex/startNodeIndex call ever made', () => {
        cy.apollo({
            mutation: createPage,
            variables: {parentPathOrId: `/sites/${siteKey()}/home`, name: testPageName}
        });

        // Publish only - IndexerJCRListener.onEvent() must detect the j:lastPublished change on its own
        // and call service.produceAsynchronousOperations(...) without any explicit index-triggering mutation.
        cy.apollo({
            mutation: publishNode,
            variables: {
                pathOrId: testPagePath(),
                languages: ['en'],
                publishSubNodes: true,
                includeSubTree: true
            }
        });

        cy.waitUntil(
            () =>
                cy
                    .apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
                    .then(result => Boolean(result.data.jcr.nodeByPath?.property?.value)),
            {
                timeout: 120000,
                interval: 2000,
                errorMsg: 'Timed out waiting for the autonomous JCR listener to index the newly published page'
            }
        );

        cy.apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
            .its('data.jcr.nodeByPath')
            .should(node => {
                expect(node).to.exist;
                expect(node.property).to.exist;
                expect(node.property.value).to.be.a('string').and.not.be.empty;
            })
            .then(node => cy.wrap(node.property.value).as('customGptPageId'));
    });

    it('removes the page from CustomGPT after an unpublish (delete + publish) with no explicit call', () => {
        cy.get('@customGptPageId').then(pageId => {
            cy.apollo({mutation: deletePage, variables: {path: testPagePath()}});

            // Publishing the deletion is what fires the "unpublish" JCR event the listener reacts to -
            // again, no startNodeIndex call anywhere in this spec.
            cy.apollo({
                mutation: publishNode,
                variables: {
                    pathOrId: `/sites/${siteKey()}/home`,
                    languages: ['en'],
                    publishSubNodes: true,
                    includeSubTree: true
                }
            });

            cy.waitUntil(
                () =>
                    cy
                        .request({
                            method: 'GET',
                            url: `${apiBaseUrl()}/projects/${Cypress.env('CUSTOMGPT_PROJECT_ID')}/pages/${pageId}`,
                            headers: {Authorization: `Bearer ${Cypress.env('CUSTOMGPT_TOKEN')}`},
                            failOnStatusCode: false
                        })
                        .then(response => response.status === 404),
                {
                    timeout: 120000,
                    interval: 5000,
                    errorMsg: 'Page was not autonomously removed from CustomGPT after JCR unpublish'
                }
            );
        });
    });
});
