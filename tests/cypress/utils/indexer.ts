import {createUser, deleteUser, executeGroovy, grantRoles} from '@jahia/cypress';

/**
 * Provisions the dedicated read-only account the module renders pages as, and mints its API token.
 *
 * The indexer is deliberately NOT root and deliberately cannot see everything. Rendering as a privileged
 * account puts its page furniture — Preview, Page Composer, Logout — into the indexed content; rendering as
 * nobody puts the login form there instead. Measured on Digitall: the two renders differ by exactly those
 * words.
 */
export const INDEXER_USER = 'customgpt-indexer';

// Throwaway credential for the local test container only.
export const INDEXER_SECRET = 'cypress-indexer-oNly-l0cal';

/**
 * The indexer's personal API token, fixed so the suite knows it without reading anything back.
 *
 * Jahia tokens are base64 of 32 bytes, so the plaintext below is exactly 32 characters — change its length
 * and the token is rejected at authentication. `provisionIndexerAccess.groovy` creates the token with this
 * exact value, owned by INDEXER_USER.
 *
 * It has to be minted server-side. Personal API tokens belong to the calling user, and this account cannot
 * reach `admin.personalApiTokens` — by design, since being able to mint credentials is not something a
 * read-only indexer should have. Minting it over `cy.apollo` instead produced a token owned by ROOT, which
 * is what the suite did until now: `cy.apollo` authenticates with a Basic root header baked into the Apollo
 * client at construction, and Jahia's GraphQL endpoint ignores session cookies entirely, so the
 * `cy.login(INDEXER_USER, …)` that preceded it had nothing to act on. Every run indexed as root.
 */
export const INDEXER_TOKEN = 'Y3lwcmVzc0luZGV4ZXJGaXhlZFRva2VuMDAwMDAwMDA=';

/**
 * Creates the account, grants it read access to one site, and gives it API access plus its token.
 *
 * `reader` alone is not enough to use the GraphQL API at all — see the Groovy fixture for why, and why the
 * grant has to be written in both workspaces.
 */
export const provisionIndexer = (siteKey: string): void => {
    deleteUser(INDEXER_USER);
    createUser(INDEXER_USER, INDEXER_SECRET);
    grantRoles(`/sites/${siteKey}`, ['reader'], INDEXER_USER, 'USER');
    executeGroovy('groovy/provisionIndexerAccess.groovy', {
        '@@INDEXER_USER@@': INDEXER_USER,
        '@@INDEXER_TOKEN@@': INDEXER_TOKEN
    });
};

/**
 * Provisions the indexer and saves the module configuration.
 *
 * The token is a constant rather than something minted mid-chain, so there is no longer a value that only
 * exists inside a `.then` — the Cypress queue trap this function used to be shaped around.
 */
export const configureIndexer = (
    siteKey: string,
    saveSettings: unknown,
    overrides: Record<string, unknown> = {}
): void => {
    provisionIndexer(siteKey);
    cy.apollo({
        mutation: saveSettings,
        variables: {
            contentIndexedMainResourceTypes: 'jnt:page,jmix:mainResource',
            projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
            token: Cypress.env('CUSTOMGPT_TOKEN'),
            jahiaApiToken: INDEXER_TOKEN,
            dryRun: false,
            operationsBatchSize: 500,
            // Hermetic: never inherit an override an earlier spec left behind.
            serverName: '',
            siteServerNames: '',
            userAgent: '',
            ...overrides
        }
    });
};
