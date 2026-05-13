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
                    projectId: Cypress.env('CUSTOMGPT_PROJECT_ID'),
                    token: Cypress.env('CUSTOMGPT_TOKEN'),
                    jahiaUsername: 'root',
                    jahiaPassword: Cypress.env('SUPER_USER_PASSWORD'),
                    jahiaServerCookieName: 'roundtrip-cookie',
                    jahiaServerCookieValue: 'roundtrip-value',
                    jahiaServerCookieDomain: 'roundtrip.local',
                    dryRun: false,
                    scheduleJobASAP: true,
                    apiBaseUrl: 'https://app.customgpt.ai/api/v1'
                }
            });
            cy.apollo({query: getSettings})
                .its('data.admin.customGpt.settings')
                .should(s => {
                    expect(s.contentIndexedMainResourceTypes).to.eq('jnt:page,jmix:mainResource');
                    expect(s.contentIndexedSubNodeTypes).to.eq('jmix:droppableContent');
                    expect(s.contentIndexedFileExtensions).to.eq('pdf,docx');
                    expect(s.operationsBatchSize).to.eq(100);
                    expect(s.projectId).to.eq(Cypress.env('CUSTOMGPT_PROJECT_ID'));
                    expect(s.token).to.eq(Cypress.env('CUSTOMGPT_TOKEN'));
                    expect(s.jahiaUsername).to.eq('root');
                    expect(s.jahiaPassword).to.eq(Cypress.env('SUPER_USER_PASSWORD'));
                    expect(s.jahiaServerCookieName).to.eq('roundtrip-cookie');
                    expect(s.jahiaServerCookieValue).to.eq('roundtrip-value');
                    expect(s.jahiaServerCookieDomain).to.eq('roundtrip.local');
                    expect(s.dryRun).to.eq(false);
                    expect(s.scheduleJobASAP).to.eq(true);
                    expect(s.apiBaseUrl).to.eq('https://app.customgpt.ai/api/v1');
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
