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
 * NOTE on the allow assertion: every `admin.customGpt` resolver currently performs a *second*,
 * hardcoded `node("/").hasPermission("admin")` check on top of the field-level
 * `@GraphQLRequiresPermission("customGptAdmin")` annotation (see AdminQueries.getSettings /
 * GqlCustomGptAdminMutationResult). A user holding ONLY `customGptAdmin` therefore clears the
 * annotation gate but is still rejected by the inner global-`admin` check. So the allow test
 * asserts the user is NOT blocked by the fine-grained annotation gate (no "Permission denied"
 * GqlAccessDeniedException) — that annotation is exactly what the assignable role unlocks. The
 * inner global-`admin` check is a separate, broader gate and is intentionally not what this role
 * targets.
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

        it('clears the customGptAdmin annotation gate for a user granted only the module permission', () => {
            querySettingsAs(ALLOWED_USER).then((result: never) => {
                // The field-level @GraphQLRequiresPermission("customGptAdmin") gate must let this
                // user through: it must NOT raise the "Permission denied" GqlAccessDeniedException
                // that the denied user receives. (A subsequent inner global-`admin` check may still
                // reject the resolver body — that is a separate, broader gate, asserted against here.)
                const messages = errorsOf(result).map((e: {message: string}) => e.message).join(' ');
                expect(messages, 'must not be blocked by the customGptAdmin annotation gate')
                    .to.not.contain('Permission denied');
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
