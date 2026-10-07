import {DocumentNode} from 'graphql';

describe('CustomGPT.ai Settings', () => {
    const adminPath = '/jahia/administration/customgptAiSettings';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    before(() => {
        cy.login();
    });

    // Restore neutral settings after the suite
    after(() => {
        cy.apollo({
            mutation: saveSettings,
            variables: {
                contentIndexedMainResourceTypes: null,
                contentIndexedSubNodeTypes: null,
                contentIndexedFileExtensions: null,
                operationsBatchSize: 500,
                projectId: null,
                token: null,
                jahiaUsername: null,
                jahiaPassword: null,
                jahiaServerCookieName: null,
                jahiaServerCookieValue: null,
                jahiaServerCookieDomain: null,
                dryRun: true,
                scheduleJobASAP: false,
                apiBaseUrl: null
            }
        });
    });

    // ─── Settings API ────────────────────────────────────────────────────────────

    describe('Settings API', () => {
        it('returns all settings fields via GraphQL', () => {
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s).to.have.property('contentIndexedMainResourceTypes');
                    expect(s).to.have.property('contentIndexedSubNodeTypes');
                    expect(s).to.have.property('contentIndexedFileExtensions');
                    expect(s).to.have.property('operationsBatchSize');
                    expect(s).to.have.property('projectId');
                    expect(s).to.have.property('token');
                    expect(s).to.have.property('jahiaUsername');
                    expect(s).to.have.property('jahiaPassword');
                    expect(s).to.have.property('jahiaServerCookieName');
                    expect(s).to.have.property('jahiaServerCookieValue');
                    expect(s).to.have.property('jahiaServerCookieDomain');
                    expect(s).to.have.property('dryRun');
                    expect(s).to.have.property('scheduleJobASAP');
                    expect(s).to.have.property('apiBaseUrl');
                    expect(s).to.have.property('userAgent');
                    expect(s).to.have.property('serverName');
                    expect(s).to.have.property('siteServerNames');
                });
        });

        it('saves settings and returns true', () => {
            cy.apollo({
                mutation: saveSettings,
                variables: {
                    projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
                    token: Cypress.env('CUSTOMGPT_TOKEN'),
                    apiBaseUrl: 'https://api.customgpt.ai/test',
                    dryRun: true,
                    scheduleJobASAP: false,
                    operationsBatchSize: 250
                }
            })
                .its('data.admin.customGpt.saveSettings')
                .should('eq', true);
        });

        it('saves settings and reads them back consistently', () => {
            cy.apollo({
                mutation: saveSettings,
                variables: {
                    contentIndexedMainResourceTypes: 'jnt:page,jmix:mainResource',
                    contentIndexedSubNodeTypes: 'jmix:droppableContent',
                    contentIndexedFileExtensions: 'pdf,docx',
                    operationsBatchSize: 100,
                    projectId: 'roundtrip-project',
                    token: 'roundtrip-token',
                    jahiaUsername: 'root',
                    jahiaPassword: Cypress.env('SUPER_USER_PASSWORD'),
                    jahiaServerCookieName: 'roundtrip-cookie',
                    jahiaServerCookieValue: 'roundtrip-value',
                    jahiaServerCookieDomain: 'roundtrip.local',
                    dryRun: false,
                    scheduleJobASAP: true,
                    apiBaseUrl: 'https://app.customgpt.ai/api/v1',
                    userAgent: 'Mozilla/5.0 (compatible; RoundTrip/1.0)',
                    serverName: 'roundtrip.example.com',
                    siteServerNames: 'academy=https://academy.example.com'
                }
            });
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s.contentIndexedMainResourceTypes).to.eq('jnt:page,jmix:mainResource');
                    expect(s.contentIndexedSubNodeTypes).to.eq('jmix:droppableContent');
                    expect(s.contentIndexedFileExtensions).to.eq('pdf,docx');
                    expect(s.operationsBatchSize).to.eq(100);
                    expect(s.projectId).to.eq('roundtrip-project');
                    // Secrets are write-only: a stored value is masked, never echoed back in cleartext (SECURITY-746).
                    expect(s.token).to.eq('********');
                    expect(s.token).to.not.eq('roundtrip-token');
                    expect(s.jahiaUsername).to.eq('root');
                    expect(s.jahiaPassword).to.eq('********');
                    expect(s.jahiaPassword).to.not.eq(Cypress.env('SUPER_USER_PASSWORD'));
                    expect(s.jahiaServerCookieName).to.eq('roundtrip-cookie');
                    // NOT masked, unlike the token and the password: the cookie value pins a request to a
                    // node, it is not a credential, and masking it left the admin unable to read back what
                    // was stored.
                    expect(s.jahiaServerCookieValue).to.eq('roundtrip-value');
                    expect(s.jahiaServerCookieDomain).to.eq('roundtrip.local');
                    expect(s.dryRun).to.eq(false);
                    // The scheduleJobASAP flag is a one-shot trigger: the service resets it to
                    // false after scheduling the indexation jobs, so it never round-trips as true.
                    expect(s.scheduleJobASAP).to.eq(false);
                    expect(s.apiBaseUrl).to.eq('https://app.customgpt.ai/api/v1');
                    expect(s.userAgent).to.eq('Mozilla/5.0 (compatible; RoundTrip/1.0)');
                    // Normalised on the way in: a bare host is stored as an https origin.
                    expect(s.serverName).to.eq('https://roundtrip.example.com');
                    expect(s.siteServerNames).to.eq('academy=https://academy.example.com');
                });
        });

        it('removes a per-site server name when the list no longer contains it', () => {
            // The panel always submits the whole list, so a deleted row must disappear from the configuration.
            // Merging instead of replacing would leave it retargeting a site nobody can see listed.
            cy.apollo({
                mutation: saveSettings,
                variables: {siteServerNames: 'academy=https://academy.example.com\ndigitall=https://digitall.example.com'}
            });
            cy.apollo({
                mutation: saveSettings,
                variables: {siteServerNames: 'academy=https://academy.example.com'}
            });
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s.siteServerNames).to.eq('academy=https://academy.example.com');
                });
        });

        it('clears every per-site server name when an empty list is submitted', () => {
            cy.apollo({
                mutation: saveSettings,
                variables: {siteServerNames: 'academy=https://academy.example.com'}
            });
            cy.apollo({mutation: saveSettings, variables: {siteServerNames: ''}});
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s.siteServerNames).to.be.empty;
                });
        });

        it('clears fields by saving empty values', () => {
            cy.apollo({
                mutation: saveSettings,
                variables: {
                    contentIndexedMainResourceTypes: 'jnt:page',
                    projectId: 'clear-test',
                    token: 'clear-token'
                }
            });
            cy.apollo({
                mutation: saveSettings,
                variables: {
                    contentIndexedMainResourceTypes: '',
                    projectId: '',
                    token: ''
                }
            });
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s.contentIndexedMainResourceTypes).to.be.empty;
                    expect(s.projectId).to.be.empty;
                    expect(s.token).to.be.empty;
                });
        });

        it('rejects a non-https apiBaseUrl (the Bearer token must never travel over cleartext)', () => {
            // GraphQL errors are caught and yielded by cy.apollo instead of failing the test.
            cy.apollo({
                mutation: saveSettings,
                variables: {apiBaseUrl: 'http://insecure.example.com/api'}
            }).should(result => {
                const graphQLErrors = (result && result.graphQLErrors) || [];
                const errors = (result && result.errors) || [];
                expect(graphQLErrors.length + errors.length, JSON.stringify(result)).to.be.greaterThan(0);
            });
        });
    });

    // ─── Admin UI ────────────────────────────────────────────────────────────────

    describe('Admin UI', () => {
        it('shows the admin panel title', () => {
            cy.login();
            cy.visit(adminPath);
            cy.contains('CustomGPT.ai Settings').should('be.visible');
        });

        it('shows the main resource types input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-main-resource-types').should('be.visible');
        });

        it('shows the rendering user agent input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-user-agent').scrollIntoView().should('be.visible');
        });

        it('shows the server name input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-server-name').scrollIntoView().should('be.visible');
        });

        it('shows the per-site server names editor with an add button', () => {
            cy.login();
            cy.visit(adminPath);
            // scrollIntoView because these sit near the bottom of a long form: the admin pane clips its
            // overflow, and Cypress reports an element outside a clipping ancestor's bounds as not visible.
            cy.contains('button', 'Add a site').scrollIntoView().should('be.visible').click();
            // One click yields one editable row, each part separately labelled.
            cy.get('#cgpt-site-key-0').scrollIntoView().should('be.visible');
            cy.get('#cgpt-site-server-name-0').scrollIntoView().should('be.visible');
        });

        it('shows the server cookie value in clear text, not as a password field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-cookie-value').scrollIntoView().should('be.visible').should('have.attr', 'type', 'text');
        });

        it('shows the sub-node types input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-sub-node-types').should('be.visible');
        });

        it('shows the file extensions input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-file-extensions').should('be.visible');
        });

        it('shows the batch size number input', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-batch-size').should('be.visible');
        });

        it('shows the project ID input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-project-id').should('be.visible');
        });

        it('shows the API token password input', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-token').should('be.visible');
        });

        it('shows the API base URL input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-api-base-url').should('be.visible');
        });

        it('shows the Jahia username input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-jahia-username').should('be.visible');
        });

        it('shows the Jahia password input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-jahia-password').should('be.visible');
        });

        it('shows the server cookie name input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-cookie-name').should('be.visible');
        });

        it('shows the server cookie value input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-cookie-value').should('be.visible');
        });

        it('shows the server cookie domain input field', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-cookie-domain').should('be.visible');
        });

        it('shows the dry run checkbox', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-dry-run').should('exist');
        });

        it('shows the schedule ASAP checkbox', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-schedule-asap').should('exist');
        });

        it('shows the save button', () => {
            cy.login();
            cy.visit(adminPath);
            cy.contains('button', 'Save').should('be.visible');
        });

        it('shows success alert after saving via UI', () => {
            cy.login();
            cy.visit(adminPath);
            cy.get('#cgpt-project-id').clear();
            cy.get('#cgpt-project-id').type('ui-test-project');
            cy.contains('button', 'Save').click();
            cy.get('[class*="cgpt_alert--success"]', {timeout: 10000}).should('be.visible');
        });

        it('persists batch size change via the UI save button', () => {
            cy.login();
            cy.visit(adminPath);

            cy.get('#cgpt-batch-size').clear();
            cy.get('#cgpt-batch-size').type('750');

            cy.contains('button', 'Save').click();
            cy.get('[class*="cgpt_alert--success"]', {timeout: 10000}).should('be.visible');

            // Verify via GraphQL that the value was actually persisted
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings.operationsBatchSize')
                .should('eq', 750);
        });
    });
});
