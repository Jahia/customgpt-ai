import {DocumentNode} from 'graphql';

describe('CustomGPT.ai Indexing', function () {
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

        cy.login();

        // ── Pre-indexing cleanup ───────────────────────────────────────────────
        const pagesToRemove = [
            '/sites/digitall/home/about',
            '/sites/digitall/home/corporate-responsibility',
            '/sites/digitall/home/our-companies',
            '/sites/digitall/home/newsroom',
            '/sites/digitall/home/investors',
            '/sites/digitall/home/landing',
            '/sites/digitall/home/demo-roles-and-users'
        ];

        pagesToRemove.forEach(path => {
            cy.apollo({mutation: deletePage, variables: {path}});
        });

        cy.apollo({
            mutation: publishNode,
            variables: {
                pathOrId: '/sites/digitall/home',
                languages: ['en'],
                publishSubNodes: true,
                includeSubTree: true
            }
        });

        // ── Site configuration ─────────────────────────────────────────────────
        const sitePath = `/sites/${siteKey()}`;

        cy.apollo({
            mutation: setNodePropertyValues,
            variables: {pathOrId: sitePath, propertyName: 'j:languages', propertyValues: ['en']}
        });

        // ── Sitemap setup ──────────────────────────────────────────────────────

        cy.apollo({
            mutation: addSitemapMixin,
            variables: {pathOrId: sitePath, mixins: ['jseomix:sitemap']}
        });

        cy.apollo({
            mutation: setNodeProperty,
            variables: {pathOrId: sitePath, propertyName: 'sitemapIndexURL', propertyValue: 'http://jahia.localhost:8080'}
        });

        cy.apollo({
            mutation: setNodeProperty,
            variables: {pathOrId: sitePath, propertyName: 'sitemapCacheDuration', propertyValue: '4'}
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

        // ── CustomGPT setup ────────────────────────────────────────────────────
        cy.apollo({
            mutation: saveSettings,
            variables: {
                contentIndexedMainResourceTypes: 'jnt:page,jmix:mainResource',
                projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
                token: Cypress.env('CUSTOMGPT_TOKEN'),
                jahiaUsername: 'root',
                jahiaPassword: Cypress.env('SUPER_USER_PASSWORD'),
                dryRun: false,
                scheduleJobASAP: true,
                operationsBatchSize: 500
            }
        });

        cy.apollo({
            mutation: addSite,
            variables: {siteKey: siteKey()}
        });

        cy.apollo({
            mutation: startIndex,
            variables: {siteKeys: [siteKey()], force: true}
        });

        cy.waitUntil(
            () =>
                cy.apollo({query: listSites}).then(result => {
                    const sites = result.data.admin.customGpt.listSites.sites as Array<{
                        siteKey: string;
                        indexationStatus: string;
                    }>;
                    const site = sites.find(s => s.siteKey === siteKey());
                    return site?.indexationStatus === 'COMPLETED';
                }),
            {timeout: 300000, interval: 10000, errorMsg: 'Timed out waiting for site indexation to complete'}
        );
    });

    after(() => {
        // Always reset the dry-run / scheduling state, even when a test above fails —
        // Cypress runs `after` hooks unconditionally, so this keeps the module config clean.
        cy.apollo({
            mutation: saveSettings,
            variables: {dryRun: true, scheduleJobASAP: false}
        });
    });

    // ─── Site indexing ───────────────────────────────────────────────────────────

    describe('Site indexing', () => {
        it('shows the site as COMPLETED in listSites', () => {
            cy.apollo({query: listSites})
                .its('data.admin.customGpt.listSites.sites')
                .should(sites => {
                    const site = (sites as Array<{ siteKey: string; indexationStatus: string }>).find(
                        s => s.siteKey === siteKey()
                    );
                    expect(site).to.exist;
                    expect(site.indexationStatus).to.eq('COMPLETED');
                });
        });

        it('sets customGptPageId on the home page', () => {
            cy.apollo({query: getNodeStatus, variables: {path: `/sites/${siteKey()}/home/customgptIndex`}})
                .its('data.jcr.nodeByPath')
                .should(node => {
                    expect(node).to.exist;
                    expect(node.property).to.exist;
                    expect(node.property.value).to.be.a('string').and.not.be.empty;
                });
        });

        // A page id alone proves only that CustomGPT MINTED a page - the id is assigned before the metadata
        // write, so it is present even when that write is rejected. For a long time every node in this harness
        // failed with HTTP 422 "The url is not a valid URL" (the bare host `jahia` has no dot, which Laravel's
        // url rule requires) and these tests still passed. Read the URL back out of CustomGPT: it is the only
        // assertion that distinguishes an indexed page from an empty shell.
        it('stores the page URL in CustomGPT, not just a page id', () => {
            cy.apollo({query: getNodeStatus, variables: {path: `/sites/${siteKey()}/home/customgptIndex`}})
                .its('data.jcr.nodeByPath.property.value')
                .then(pageId => {
                    // The metadata read is sampled more than once: this API has been observed returning a
                    // correct envelope with a dropped `url`, so a single null is not proof of absence.
                    cy.waitUntil(() => metadataFor(pageId).then(r => r.status === 200 && Boolean(r.body?.data?.url)), {
                        timeout: 60000,
                        interval: 5000,
                        errorMsg: 'CustomGPT never returned a URL for the indexed home page'
                    });

                    metadataFor(pageId).should(response => {
                        expect(response.status).to.eq(200);
                        // The host comes from the site's sitemapIndexURL, set in the before hook.
                        expect(response.body.data.url).to.contain('jahia.localhost:8080');
                        expect(response.body.data.url).to.contain(`/sites/${siteKey()}/home`);
                    });
                });
        });
    });
});
