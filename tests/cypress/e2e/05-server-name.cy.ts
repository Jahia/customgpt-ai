import {DocumentNode} from 'graphql';

/**
 * End-to-end coverage for the indexation server-name override.
 *
 * The override answers "index this site under this host instead of the one its own node names". The case it
 * exists for is a preproduction instance restored from a production export, whose sites still carry the
 * production sitemapIndexURL — index it unchanged and every citation points at production.
 *
 * What makes this test meaningful is that the two hosts DISAGREE: the site's sitemapIndexURL says one thing and
 * the override says another, so the URL CustomGPT ends up storing says which one actually won. Asserting
 * against a single host would pass whether the override were applied or ignored.
 */
describe('CustomGPT.ai indexation server name', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const setNodeProperty: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/setNodeProperty.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const startIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const addSite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addSite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listSites.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const apiBaseUrl = () => Cypress.env('CUSTOMGPT_API_BASE_URL') as string;

    // Both resolve to the same loopback inside the Jahia container (see docker-compose extra_hosts), so either
    // can actually be fetched. Only the stored citation URL tells them apart — which is the point.
    const SITEMAP_HOST = 'http://jahia.localhost:8080';
    const OVERRIDE_HOST = 'http://override.jahia.localhost:8080';

    const metadataFor = (pageId: string) =>
        cy.request({
            method: 'GET',
            url: `${apiBaseUrl()}/projects/${Cypress.env('CUSTOMGPT_PROJECT_ID')}/pages/${pageId}/metadata`,
            headers: {Authorization: `Bearer ${Cypress.env('CUSTOMGPT_TOKEN')}`},
            failOnStatusCode: false
        });

    const reindexAndReadStoredUrl = () => {
        cy.apollo({mutation: addSite, variables: {siteKey: siteKey()}});
        cy.apollo({mutation: startIndex, variables: {siteKeys: [siteKey()], force: true}});
        cy.waitUntil(
            () =>
                cy.apollo({query: listSites}).then(result => {
                    const sites = result.data.admin.customGpt.listSites.sites as Array<{
                        siteKey: string;
                        indexationStatus: string;
                    }>;
                    return sites.find(s => s.siteKey === siteKey())?.indexationStatus === 'COMPLETED';
                }),
            {timeout: 300000, interval: 10000, errorMsg: 'Timed out waiting for site indexation to complete'}
        );

        return cy
            .apollo({query: getNodeStatus, variables: {path: `/sites/${siteKey()}/home/customgptIndex`}})
            .its('data.jcr.nodeByPath.property.value')
            .then(pageId => {
                // Sampled until a URL appears: this API has been observed returning a correct envelope with a
                // dropped `url`, so one null read is not proof of absence.
                cy.waitUntil(() => metadataFor(pageId).then(r => r.status === 200 && Boolean(r.body?.data?.url)), {
                    timeout: 60000,
                    interval: 5000,
                    errorMsg: 'CustomGPT never returned a URL for the indexed home page'
                });
                return metadataFor(pageId).then(r => r.body.data.url as string);
            });
    };

    before(function () {
        if (!Cypress.env('CUSTOMGPT_PROJECT_ID') || !Cypress.env('CUSTOMGPT_TOKEN')) {
            this.skip();
        }

        cy.login();
        cy.apollo({
            mutation: setNodeProperty,
            variables: {
                pathOrId: `/sites/${siteKey()}`,
                propertyName: 'sitemapIndexURL',
                propertyValue: SITEMAP_HOST
            }
        });
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
                serverName: '',
                siteServerNames: ''
            }
        });
    });

    after(() => {
        // Leave no override behind: a later spec indexing this site would otherwise inherit it.
        cy.apollo({
            mutation: saveSettings,
            variables: {dryRun: true, scheduleJobASAP: false, serverName: '', siteServerNames: ''}
        });
    });

    it('falls back to the site sitemapIndexURL when no override is set', () => {
        reindexAndReadStoredUrl().should(url => {
            expect(url).to.contain('jahia.localhost:8080');
            expect(url).to.not.contain('override.');
        });
    });

    it('indexes under the per-site override instead of the sitemapIndexURL', () => {
        cy.apollo({
            mutation: saveSettings,
            variables: {scheduleJobASAP: true, siteServerNames: `${siteKey()}=${OVERRIDE_HOST}`}
        });

        reindexAndReadStoredUrl().should(url => {
            // The site node still says jahia.localhost; the override is what reached CustomGPT.
            expect(url).to.contain('override.jahia.localhost:8080');
        });
    });

    it('normalises a bare host name to an https origin', () => {
        // The form an admin reaches for, matching Jahia's own j:serverName. It must come back as an origin,
        // because callers concatenate it with a path.
        cy.apollo({mutation: saveSettings, variables: {serverName: 'academypp.jahia.com', siteServerNames: ''}});

        cy.apollo({query: getSettings})
            .its('data.admin.customGpt.settings.serverName')
            .should('eq', 'https://academypp.jahia.com');
    });

    it('ignores an override pointing at a private address', () => {
        // The render request carries the Jahia credentials, so a literal private/loopback IP is refused.
        cy.apollo({mutation: saveSettings, variables: {serverName: 'http://10.1.2.3:8080', siteServerNames: ''}});

        cy.apollo({query: getSettings})
            .its('data.admin.customGpt.settings.serverName')
            .should('be.empty');
    });
});
