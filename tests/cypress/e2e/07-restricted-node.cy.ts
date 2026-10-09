import {DocumentNode} from 'graphql';
import {executeGroovy} from '@jahia/cypress';
import {configureIndexer} from '../utils/indexer';

/**
 * The skip path, end to end: a node the indexing account may not read must be skipped, and the run must still
 * complete.
 *
 * This is the case the module is built around and the one nothing covered. Before the classification was
 * corrected, this exact page produced
 *
 *     ERROR [Service] - Indexation failed due to: 1 node(s) could not be indexed: [.../restricted [en]]
 *
 * and the site never left INDEXING. One restricted page failed an entire site. The reason is that Jahia HIDES
 * content the caller may not read rather than reporting a denial, so a restricted node arrives as
 * PathNotFoundException - never as the access error the module was matching on.
 */
describe('CustomGPT.ai restricted content', function () {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const addSite: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/addSite.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const startIndex: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/startIndex.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listSites: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listSites.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getNodeStatus: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getNodeStatus.graphql');

    const siteKey = () => Cypress.env('JAHIA_SITE_KEY') as string;
    const pageName = 'cypress-restricted-page';
    const pagePath = () => `/sites/${siteKey()}/home/${pageName}`;
    const replacements = () => ({'@@SITE_KEY@@': siteKey(), '@@PAGE_NAME@@': pageName});

    const siteStatus = () =>
        cy.apollo({query: listSites}).then(result => {
            const sites = result.data.admin.customGpt.listSites.sites as Array<{
                siteKey: string;
                indexationStatus: string;
            }>;
            return sites.find(s => s.siteKey === siteKey())?.indexationStatus;
        });

    before(function () {
        cy.login();
        configureIndexer(siteKey(), saveSettings, {scheduleJobASAP: false});
        cy.apollo({mutation: addSite, variables: {siteKey: siteKey()}});
        executeGroovy('groovy/createRestrictedPage.groovy', replacements());

        cy.apollo({mutation: startIndex, variables: {siteKeys: [siteKey()], force: true}});

        // Wait for a TERMINAL state rather than for COMPLETED: waiting for success alone turns a regression
        // into a five-minute timeout instead of a failed assertion that says what went wrong.
        cy.waitUntil(
            () => siteStatus().then(status => status === 'COMPLETED' || status === 'FAILED'),
            {timeout: 300000, interval: 5000, errorMsg: 'Timed out waiting for the indexation run to finish'}
        );
    });

    after(function () {
        executeGroovy('groovy/removeRestrictedPage.groovy', replacements());
        cy.apollo({mutation: saveSettings, variables: {dryRun: true, scheduleJobASAP: false}});
    });

    it('completes the run rather than failing the whole site', function () {
        siteStatus().should('eq', 'COMPLETED');
    });

    it('leaves the restricted page unindexed', function () {
        // Skipped, not indexed: no page id was ever written back for it.
        cy.apollo({query: getNodeStatus, variables: {path: pagePath()}}).then(result => {
            const property = result.data?.jcr?.nodeByPath?.property;
            expect(property, 'customGptPageId on a page the indexer may not read').to.be.null;
        });
    });
});
