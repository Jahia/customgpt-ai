import {createUser, deleteUser, grantRoles} from '@jahia/cypress';

/**
 * Provisions the dedicated read-only account the module renders pages as, and mints its API token.
 *
 * The indexer is deliberately NOT root and deliberately cannot see everything. Rendering as a privileged
 * account puts its page furniture — Preview, Page Composer, Logout — into the indexed content; rendering as
 * nobody puts the login form there instead. Measured on Digitall: the two renders differ by exactly those
 * words. Content this account cannot read is skipped by the module rather than indexed, so a restricted
 * indexer is the intended state, not a misconfiguration.
 */
export const INDEXER_USER = 'customgpt-indexer';

// Throwaway credential for the local test container only. It exists so the harness can log in as the indexer
// to mint its own token — personal API tokens belong to the calling user, so there is no way to mint one for
// somebody else.
export const INDEXER_SECRET = 'cypress-indexer-oNly-l0cal';

/** Creates the account and grants it read access to one site. */
export const provisionIndexer = (siteKey: string): void => {
    deleteUser(INDEXER_USER);
    createUser(INDEXER_USER, INDEXER_SECRET);
    grantRoles(`/sites/${siteKey}`, ['reader'], INDEXER_USER, 'USER');
};

/**
 * Logs in as the indexer, mints a graphql-scoped token, and hands it back.
 *
 * The session is restored to root afterwards: everything else in these specs runs as an administrator.
 */
export const mintIndexerToken = (createToken: unknown): Cypress.Chainable<string> => {
    cy.logout();
    cy.login(INDEXER_USER, INDEXER_SECRET);
    return cy
        .apollo({
            mutation: createToken,
            variables: {name: `customgpt-indexer-${Date.now()}`, scopes: ['graphql']}
        })
        .then(result => {
            const token = result.data?.admin?.personalApiTokens?.createToken as string;
            cy.logout();
            cy.login();
            return cy.wrap(token);
        });
};

/**
 * Provisions the indexer, mints its token and saves the module configuration in one chain.
 *
 * One call rather than three because the token is only known inside a `.then`. Cypress builds its command
 * queue up front, so a token assigned to an outer variable is still the empty string when the saveSettings
 * command is *queued* - the classic trap. Everything that depends on the token therefore has to be composed
 * inside the callback that produces it.
 */
export const configureIndexer = (
    siteKey: string,
    saveSettings: unknown,
    createToken: unknown,
    overrides: Record<string, unknown> = {}
): void => {
    provisionIndexer(siteKey);
    mintIndexerToken(createToken).then(token => {
        expect(token, 'indexer API token').to.be.a('string').and.not.be.empty;
        cy.apollo({
            mutation: saveSettings,
            variables: {
                contentIndexedMainResourceTypes: 'jnt:page,jmix:mainResource',
                projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
                token: Cypress.env('CUSTOMGPT_TOKEN'),
                jahiaApiToken: token,
                dryRun: false,
                operationsBatchSize: 500,
                // Hermetic: never inherit an override an earlier spec left behind.
                serverName: '',
                siteServerNames: '',
                userAgent: '',
                ...overrides
            }
        });
    });
};
