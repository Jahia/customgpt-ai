import {DocumentNode} from 'graphql';
import {createUser, deleteUser, grantRoles} from '@jahia/cypress';

/**
 * Regression tests for the fine-grained `customGptAdmin` permission.
 *
 * These guard against the gate being silently removed or mismatched across the stack:
 *  - Backend: `@GraphQLRequiresPermission("customGptAdmin")` on the `admin.customGpt`
 *    query/mutation extensions is enforced as a root-node ACL check.
 *  - Frontend: `requiredPermission: 'customGptAdmin'` in register.jsx gates the admin route.
 *  - RBAC content: the module ships the assignable `customgpt-ai-administrator` role
 *    (src/main/import/roles.xml) granting ONLY `administrationAccess` + `customGptAdmin`.
 *
 * The "allowed" user is granted that role and nothing else — never `admin` — so the tests prove
 * fine-grained granularity, not merely that a full administrator can pass.
 *
 * The allow path uses the read-only `getSettings` query (no secrets are returned in cleartext;
 * token / passwords / cookie value are masked to `********`), so it is a safe gated op.
 *
 * NOTE on the allow assertion: the role works END-TO-END. Both gates resolve `customGptAdmin`:
 *  - the field-level `@GraphQLRequiresPermission("customGptAdmin")` annotation, and
 *  - the inner server-level resolver check (`AdminQueries.getSettings` /
 *    `GqlCustomGptAdminMutationResult`), which now checks `customGptAdmin` on the root path
 *    instead of the broader global `admin` permission.
 * So a user holding ONLY `customGptAdmin` clears both gates: the allow test asserts FULL success —
 * the gated query returns data with NO GraphQL errors at all. (Per-site operations still enforce the
 * separate site-scoped `site-admin` permission, which is out of scope for this server-role.)
 */
describe('CustomGPT.ai — permission enforcement', () => {
    const ROLE_NAME = 'customgpt-ai-administrator';
    const DENIED_USER = 'cgptDeniedUser';
    const ALLOWED_USER = 'cgptAllowedUser';
    const PASSWORD = 'CgptPerm9PwdTest';
    const ADMIN_PATH = '/jahia/administration/customgptAiSettings';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');

    const errorsOf = (result: {graphQLErrors?: Array<{message: string}>; errors?: Array<{message: string}>}) =>
        result.graphQLErrors ?? result.errors ?? [];

    const querySettingsAs = (username: string) => {
        cy.apolloClient({username, password: PASSWORD});
        return cy.apollo({query: getSettings});
    };

    before(() => {
        cy.login();
        createUser(DENIED_USER, PASSWORD);
        createUser(ALLOWED_USER, PASSWORD);
        // The annotation resolves the permission on the JCR root node, so grant the
        // module-shipped single-permission role on `/`.
        grantRoles('/', [ROLE_NAME], ALLOWED_USER, 'USER');
    });

    after(() => {
        cy.apolloClient(); // reset the current Apollo client back to root
        cy.login();
        deleteUser(DENIED_USER);
        deleteUser(ALLOWED_USER);
    });

    describe('GraphQL API authorization', () => {
        it('denies the gated query for a user without the permission', () => {
            querySettingsAs(DENIED_USER).then((result: never) => {
                const errs = errorsOf(result);
                expect(errs, 'denial errors').to.have.length.greaterThan(0);
                expect(errs.map((e: {message: string}) => e.message).join(' ')).to.contain('Permission denied');
            });
        });

        it('allows the gated query end-to-end for a user granted only the module permission', () => {
            querySettingsAs(ALLOWED_USER).then((result: {data?: {admin?: {customGpt?: {settings?: unknown}}}}) => {
                // The role works end-to-end: both the field-level
                // @GraphQLRequiresPermission("customGptAdmin") annotation AND the inner server-level
                // resolver check now resolve `customGptAdmin`, so the query must succeed fully —
                // no GraphQL errors at all, and the gated `settings` payload must be returned.
                const errs = errorsOf(result);
                expect(errs.map((e: {message: string}) => e.message).join(' '), 'no GraphQL errors').to.equal('');
                expect(errs, 'no GraphQL errors').to.have.length(0);
                expect(result.data?.admin?.customGpt?.settings, 'gated settings payload returned').to.not.be.undefined;
            });
        });
    });

    describe('Admin UI authorization', () => {
        it('hides the admin panel from a user without the permission', () => {
            cy.login(DENIED_USER, PASSWORD);
            cy.visit(ADMIN_PATH, {failOnStatusCode: false});
            cy.contains('CustomGPT.ai Settings').should('not.exist');
        });

        it('shows the admin panel to a user granted only the module permission', () => {
            cy.login(ALLOWED_USER, PASSWORD);
            cy.visit(ADMIN_PATH);
            cy.contains('CustomGPT.ai Settings').should('be.visible');
        });
    });
});
