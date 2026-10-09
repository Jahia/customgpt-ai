import {INDEXER_TOKEN, INDEXER_USER, provisionIndexer} from '../utils/indexer';

/**
 * Guards the premise every other indexing spec rests on: that the suite indexes as a restricted account.
 *
 * It did not. `mintIndexerToken` logged in as the indexer and then minted the token over `cy.apollo`, which
 * carries a Basic root header baked into the Apollo client at construction; Jahia's GraphQL endpoint ignores
 * session cookies, so the login had nothing to act on and every token was minted for ROOT. The suite was
 * green throughout, because indexing as root works fine - it just proves nothing about the account the
 * module is documented to use.
 *
 * These assertions are cheap and would have failed loudly on day one.
 */
const siteKey = (): string => Cypress.env('JAHIA_SITE_KEY') || 'digitall';

interface GraphQLBody {
    data?: {
        currentUser?: {name: string};
        jcr?: {nodeByPath?: {renderedContent?: {output: string}}};
    };
    errors?: unknown[];
}

const renderQuery = (path: string): string =>
    `{ jcr(workspace: LIVE) { nodeByPath(path: "${path}") { ` +
    'renderedContent(templateType: "html", contextConfiguration: "page", language: "en") { output } } } }';

/**
 * Asks Jahia as the indexer, over HTTP, with no browser session.
 *
 * The cookie matters: Jahia takes API access from the token but IDENTITY from the session, so the same
 * request sent from a logged-in browser is answered as root. Clearing cookies is what makes this an
 * assertion about the token rather than about whoever happens to be logged in.
 */
const askAsIndexer = (query: string) => {
    cy.clearCookies();
    return cy.request({
        method: 'POST',
        url: '/modules/graphql',
        headers: {Authorization: `APIToken ${INDEXER_TOKEN}`},
        body: {query},
        failOnStatusCode: false
    });
};

describe('CustomGPT.ai indexing account', function () {
    before(function () {
        cy.login();
        provisionIndexer(siteKey());
    });

    it('indexes as the indexer, not as root', function () {
        askAsIndexer('{ currentUser { name } }').then((res: Cypress.Response<GraphQLBody>) => {
            // A refusal here means the account lost api-access, not that the token is root's - keep the two apart.
            expect(JSON.stringify(res.body), 'indexer may use the GraphQL API').to.not.contain('Permission denied');
            expect(res.body.data?.currentUser?.name, 'token owner').to.eq(INDEXER_USER);
        });
    });

    it('can render a page it is allowed to read', function () {
        askAsIndexer(renderQuery(`/sites/${siteKey()}/home`)).then((res: Cypress.Response<GraphQLBody>) => {
            expect(res.body.data?.jcr?.nodeByPath?.renderedContent?.output).to.contain('<html');
        });
    });

    it('renders without the page furniture a privileged account would add', function () {
        askAsIndexer(renderQuery(`/sites/${siteKey()}/home`)).then((res: Cypress.Response<GraphQLBody>) => {
            const html = res.body.data?.jcr?.nodeByPath?.renderedContent?.output ?? '';
            expect(html).to.not.contain('Page Composer');
            expect(html).to.not.contain('gwt-dx-toolbar');
        });
    });
});
