import {defineConfig} from 'cypress';
import fs from 'fs';

export default defineConfig({
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
        configFile: 'reporter-config.json'
    },
    screenshotsFolder: './results/screenshots',
    video: true,
    videosFolder: './results/videos',
    viewportWidth: 1366,
    viewportHeight: 768,
    watchForFileChanges: false,
    e2e: {
        env: {
            CUSTOMGPT_PROJECT_ID: process.env.CUSTOMGPT_PROJECT_ID,
            CUSTOMGPT_TOKEN: process.env.CUSTOMGPT_TOKEN
        },
        setupNodeEvents(on, config) {
            // Delete videos for tests that did not fail
            on(
                'after:spec',
                (spec: Cypress.Spec, results: CypressCommandLine.RunResult) => {
                    if (results && results.video) {
                        const failures = results.tests.some(test =>
                            test.attempts.some(attempt => attempt.state === 'failed')
                        );
                        if (!failures) {
                            fs.unlinkSync(results.video);
                        }
                    }
                }
            );
            // eslint-disable-next-line @typescript-eslint/no-var-requires
            return require('./cypress/plugins/index.js')(on, config);
        },
        excludeSpecPattern: '*.ignore.ts',
        baseUrl: 'http://localhost:8080'
    }
});
