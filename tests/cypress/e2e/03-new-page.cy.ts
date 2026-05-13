import {DocumentNode} from 'graphql';

describe('CustomGPT.ai new page indexing', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const publishNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/publishNode.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const setNodePropertyValues: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setNodePropertyValues.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const addSitemapMixin: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addSitemapMixin.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const setNodeProperty: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setNodeProperty.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const triggerSitemapJob: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/triggerSitemapJob.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSchedulerJobs: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSchedulerJobs.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const addSite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addSite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const startIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const startNodeIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startNodeIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createPage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createPage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deletePage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deletePage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listSites.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const apiBaseUrl = () => Cypress.env('CUSTOMGPT_API_BASE_URL') as string;
    const testPageName = 'cypress-indexing-test';
    const testPagePath = () => `/sites/${siteKey()}/home/${testPageName}`;

    before(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            this.skip();
        }
    });

    // ─── New page lifecycle ──────────────────────────────────────────────────────

    describe('New page lifecycle', () => {
        it('new page has customGptPageId set after indexing', () => {
            cy.apollo({
                mutation: createPage,
                variables: {parentPathOrId: `/sites/${siteKey()}/home`, name: testPageName}
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

            cy.apollo({
                mutation: triggerSitemapJob,
                variables: {siteKey: siteKey()}
            });

            cy.waitUntil(
                () =>
                    cy.apollo({query: getSchedulerJobs}).then(result => {
                        const jobs = result.data.admin.jahia.scheduler.jobs as Array<{
                            group: string;
                            jobStatus: string;
                        }>;
                        return !jobs
                            .filter(j => j.group === 'SitemapCreationJob')
                            .some(j => j.jobStatus === 'EXECUTING');
                    }),
                {timeout: 60000, interval: 1000, errorMsg: 'Timed out waiting for sitemap generation to complete'}
            );

            cy.apollo({
                mutation: startNodeIndex,
                variables: {nodePaths: [testPagePath()]}
            });

            cy.waitUntil(
                () =>
                    cy
                        .apollo({query: getNodeStatus, variables: {path: testPagePath()}})
                        .then(result => Boolean(result.data.jcr.nodeByPath?.property?.value)),
                {timeout: 60000, interval: 5000, errorMsg: 'Timed out waiting for new page to be indexed in CustomGPT'}
            );
            cy.apollo({query: getNodeStatus, variables: {path: testPagePath()}})
                .its('data.jcr.nodeByPath')
                .should(node => {
                    expect(node.property).to.exist;
                    expect(node.property.value).to.be.a('string').and.not.be.empty;
                });
        });

        // Deactivated because of a Jahia bug: https://github.com/Jahia/jahia-private/issues/4165
        // it('page is removed from CustomGPT when deleted from JCR', () => {
        //     cy.apollo({query: getNodeStatus, variables: {path: testPagePath()}})
        //         .its('data.jcr.nodeByPath.property.value')
        //         .as('customGptPageId');
        //
        //     cy.apollo({mutation: deletePage, variables: {path: testPagePath()}});
        //
        //     cy.apollo({
        //         mutation: publishNode,
        //         variables: {
        //             pathOrId: testPagePath(),
        //             languages: ['en'],
        //             publishSubNodes: true,
        //             includeSubTree: true
        //         }
        //     });
        //
        //     cy.apollo({
        //         mutation: triggerSitemapJob,
        //         variables: {siteKey: siteKey()}
        //     });
        //
        //     cy.waitUntil(
        //         () =>
        //             cy.apollo({query: getSchedulerJobs}).then(result => {
        //                 const jobs = result.data.admin.jahia.scheduler.jobs as Array<{
        //                     group: string;
        //                     jobStatus: string;
        //                 }>;
        //                 return !jobs
        //                     .filter(j => j.group === 'SitemapCreationJob')
        //                     .some(j => j.jobStatus === 'EXECUTING');
        //             }),
        //         {timeout: 60000, interval: 1000, errorMsg: 'Timed out waiting for sitemap generation to complete'}
        //     );
        //
        //     cy.apollo({query: getNodeStatus, variables: {path: testPagePath()}})
        //         .its('data.jcr.nodeByPath')
        //         .should('be.null');
        //
        //     cy.get('@customGptPageId').then(pageId => {
        //         cy.waitUntil(
        //             () =>
        //                 cy
        //                     .request({
        //                         method: 'GET',
        //                         url: `${apiBaseUrl()}/projects/${Cypress.env('CUSTOMGPT_PROJECT_ID')}/pages/${pageId}`,
        //                         headers: {Authorization: `Bearer ${Cypress.env('CUSTOMGPT_TOKEN')}`},
        //                         failOnStatusCode: false
        //                     })
        //                     .then(response => response.status === 404),
        //             {timeout: 60000, interval: 5000, errorMsg: 'Page was not removed from CustomGPT after JCR deletion'}
        //         );
        //     });
        // });
    });
});
