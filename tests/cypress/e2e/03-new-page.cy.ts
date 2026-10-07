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

    const metadataFor = (pageId: string) =>
        cy.request({
            method: 'GET',
            url: `${apiBaseUrl()}/projects/${Cypress.env('CUSTOMGPT_PROJECT_ID')}/pages/${pageId}/metadata`,
            headers: {Authorization: `Bearer ${Cypress.env('CUSTOMGPT_TOKEN')}`},
            failOnStatusCode: false
        });
    const testPageName = 'cypress-indexing-test';
    const testPagePath = () => `/sites/${siteKey()}/home/${testPageName}`;

    before(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            this.skip();
        }
    });

    // ─── New page lifecycle ──────────────────────────────────────────────────────

    describe('New page lifecycle', () => {
        // Configure this spec's own prerequisites rather than inheriting whatever the previous spec left behind.
        // 02-indexing's after() hook resets dryRun to true, and a dry run skips indexation entirely, so relying on
        // its settings made this spec silently unable to pass depending on execution order.
        //
        // The site setup below is the same principle applied to two prerequisites that only became load-bearing
        // once indexation started refusing to guess. Both used to degrade silently and now fail:
        //   - the site must carry jmix:customGptIndexableSite, or the registration gate drops every operation;
        //   - the site must have a usable sitemapIndexURL, or the public URL cannot be resolved.
        // Both were imported into this spec and never called, so it depended entirely on 02-indexing running
        // first. Every mutation here is idempotent, so running the spec alone now works.
        beforeEach(() => {
            cy.apollo({
                mutation: saveSettings,
                variables: {
                    contentIndexedMainResourceTypes: 'jnt:page,jmix:mainResource',
                    projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
                    token: Cypress.env('CUSTOMGPT_TOKEN'),
                    jahiaUsername: 'root',
                    jahiaPassword: Cypress.env('SUPER_USER_PASSWORD'),
                    dryRun: false,
                    scheduleJobASAP: false,
                    operationsBatchSize: 500,
                    // Hermetic: never inherit an override an earlier spec left behind.
                    serverName: '',
                    siteServerNames: '',
                    userAgent: ''
                }
            });

            const sitePath = `/sites/${siteKey()}`;

            cy.apollo({
                mutation: setNodePropertyValues,
                variables: {pathOrId: sitePath, propertyName: 'j:languages', propertyValues: ['en']}
            });

            cy.apollo({
                mutation: addSitemapMixin,
                variables: {pathOrId: sitePath, mixins: ['jseomix:sitemap']}
            });

            cy.apollo({
                mutation: setNodeProperty,
                variables: {pathOrId: sitePath, propertyName: 'sitemapIndexURL', propertyValue: 'http://jahia.localhost:8080'}
            });

            cy.apollo({
                mutation: addSite,
                variables: {siteKey: siteKey()}
            });
        });

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
                        .apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
                        .then(result => Boolean(result.data?.jcr?.nodeByPath?.property?.value)),
                {timeout: 60000, interval: 1000, errorMsg: 'Timed out waiting for customGptPageId to be set on the new page'}
            );

            cy.apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
                .its('data.jcr.nodeByPath')
                .should(node => {
                    expect(node).to.exist;
                    expect(node.property).to.exist;
                    expect(node.property.value).to.be.a('string').and.not.be.empty;
                });

            // The id alone is minted before the metadata write and survives its rejection, so it says nothing
            // about whether the page was really indexed. Read the URL back: this is the assertion that tells
            // an indexed page from an empty shell.
            cy.apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
                .its('data.jcr.nodeByPath.property.value')
                .then(pageId => {
                    // Sampled until a URL appears: this API has been observed returning a correct envelope
                    // with a dropped `url`, so one null read is not proof of absence.
                    cy.waitUntil(() => metadataFor(pageId).then(r => r.status === 200 && Boolean(r.body?.data?.url)), {
                        timeout: 60000,
                        interval: 5000,
                        errorMsg: 'CustomGPT never returned a URL for the newly published page'
                    });

                    metadataFor(pageId).should(response => {
                        expect(response.status).to.eq(200);
                        expect(response.body.data.url).to.contain('jahia.localhost:8080');
                        expect(response.body.data.url).to.contain(testPageName);
                    });
                });
        });

        it('page is removed from CustomGPT when deleted from JCR', () => {
            cy.apollo({query: getNodeStatus, variables: {path: `${testPagePath()}/customgptIndex`}})
                .its('data.jcr.nodeByPath.property.value')
                .as('customGptPageId');

            cy.apollo({mutation: deletePage, variables: {path: testPagePath()}});

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

            cy.get('@customGptPageId').then(pageId => {
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
                    {timeout: 60000, interval: 5000, errorMsg: 'Page was not removed from CustomGPT after JCR deletion'}
                );
            });
        });
    });
});
