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
    const startNodeIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startNodeIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createPage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createPage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const publishNode: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/publishNode.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deletePage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deletePage.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const apiBaseUrl = () => Cypress.env('CUSTOMGPT_API_BASE_URL') as string;
    // Each test indexes a DIFFERENT page. The module coalesces index operations per node - the log says
    // "Coalesced 1 index operation(s) for node(s) already queued in this publication; 0 operation(s)
    // dispatched" - so asking for the same page twice in quick succession dispatches only the first, and the
    // second test would assert against a page that was never re-indexed under the new override.
    const fallbackPath = () => `/sites/${siteKey()}/home`;
    // This spec creates its own page rather than borrowing one from Digitall. /sites/digitall/home/about was
    // the obvious candidate and is exactly wrong: 02-indexing DELETES it in its pre-indexing cleanup, so by
    // the time this spec runs startNodeIndex fails with PathNotFoundException on it.
    const OVERRIDE_PAGE = 'cypress-server-name-test';
    const overridePath = () => `/sites/${siteKey()}/home/${OVERRIDE_PAGE}`;

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

    /**
     * Saves settings and waits until the running Config actually reflects them.
     *
     * config.update() returns before ConfigurationAdmin has delivered the new properties - the delivery happens
     * on its own thread, visible in the Jahia log as "CM Configuration Updater". Triggering an index straight
     * after a save therefore races it, and the index reads the PREVIOUS configuration. Polling the settings
     * query is what makes the save observable, since it reads the same Config the indexer does.
     */
    const saveAndAwait = (variables: Record<string, unknown>, expectedSiteServerNames: string) => {
        cy.apollo({mutation: saveSettings, variables});
        cy.waitUntil(
            () =>
                cy
                    .apollo({query: getSettings})
                    .then(r => r.data?.admin?.customGpt?.settings?.siteServerNames === expectedSiteServerNames),
            {timeout: 30000, interval: 1000, errorMsg: `Config never picked up siteServerNames='${expectedSiteServerNames}'`}
        );
    };

    /**
     * Re-indexes the home page alone and returns the URL CustomGPT ends up storing for it.
     *
     * Deliberately NOT a whole-site index. This spec runs last, so a site-wide run inherits every node the
     * earlier specs left behind - the created-then-deleted cypress-indexing-test page among them - and a single
     * failed node now (correctly) fails the whole run. Indexing one page tests exactly what this spec is about,
     * is isolated from the other specs, and takes seconds instead of five minutes.
     *
     * The URL is captured inside the poll rather than re-read afterwards: this API has been observed returning
     * a correct envelope with a dropped payload, so a second request can hand back no `data` at all even though
     * the first succeeded.
     *
     * @param expectedHost the host the stored URL must end up carrying; polled for, because a re-index updates
     *                     the metadata of the SAME page id and the previous value is briefly still there
     */
    const reindexAndReadStoredUrl = (nodePath: string, expectedHost: string) => {
        const seen: {url: string} = {url: ''};

        // The page id is re-read on EVERY attempt, not captured once up front. A re-index does not patch the
        // existing CustomGPT page - the log shows "Removing page with the id ..." followed by a fresh one - so
        // an id read before the swap is a 404 by the time the new URL exists, and the poll could never see it.
        // The id changing is part of what is being waited for.
        // The index request is re-issued on EVERY attempt rather than once up front. The module coalesces
        // operations per node and holds the key until the in-flight indexation FUTURE completes - later than
        // the page id appearing - so a request sent just after publishing is silently dropped ("Coalesced 1
        // index operation(s); 0 operation(s) dispatched") and the page keeps the URL the previous run gave it.
        // There is no external signal for that window, so the test stops trying to time it and simply asks
        // again until one request gets through.
        cy.waitUntil(
            () => {
                cy.apollo({mutation: startNodeIndex, variables: {nodePaths: [nodePath]}});
                return cy
                    .apollo({query: getNodeStatus, variables: {path: `${nodePath}/customgptIndex`}})
                    .then(result => {
                        const pageId = result.data?.jcr?.nodeByPath?.property?.value as string | undefined;
                        if (!pageId) {
                            return false;
                        }

                        return metadataFor(pageId).then(response => {
                            const url = String(response.body?.data?.url ?? '');
                            // Captured here rather than re-read afterwards: this API intermittently returns a
                            // correct envelope with a dropped payload, so a second request can yield no data.
                            if (response.status === 200 && url.includes(expectedHost)) {
                                seen.url = url;
                                return true;
                            }

                            return false;
                        });
                    });
            },
            {
                timeout: 180000,
                // Each attempt issues an index request, so poll gently: most are coalesced no-ops until the
                // pending key clears.
                interval: 6000,
                errorMsg: `CustomGPT never stored a URL containing ${expectedHost} for ${nodePath}`
            }
        );

        return cy.wrap(seen).its('url');
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
            mutation: createPage,
            variables: {parentPathOrId: `/sites/${siteKey()}/home`, name: OVERRIDE_PAGE}
        });
        cy.apollo({
            mutation: publishNode,
            variables: {pathOrId: overridePath(), languages: ['en'], publishSubNodes: true, includeSubTree: true}
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
                scheduleJobASAP: false,
                serverName: '',
                siteServerNames: ''
            }
        });
    });

    after(() => {
        cy.apollo({mutation: deletePage, variables: {path: overridePath()}});
        // Leave no override behind: a later spec indexing this site would otherwise inherit it.
        cy.apollo({
            mutation: saveSettings,
            variables: {dryRun: true, scheduleJobASAP: false, serverName: '', siteServerNames: ''}
        });
    });

    it('falls back to the site sitemapIndexURL when no override is set', () => {
        reindexAndReadStoredUrl(fallbackPath(), 'jahia.localhost:8080').should(url => {
            expect(url).to.contain('jahia.localhost:8080');
            expect(url).to.not.contain('override.');
        });
    });

    it('indexes under the per-site override instead of the sitemapIndexURL', () => {
        saveAndAwait({siteServerNames: `${siteKey()}=${OVERRIDE_HOST}`}, `${siteKey()}=${OVERRIDE_HOST}`);

        reindexAndReadStoredUrl(overridePath(), 'override.jahia.localhost:8080').should(url => {
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
