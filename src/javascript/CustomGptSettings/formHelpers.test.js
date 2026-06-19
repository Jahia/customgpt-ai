import {
    DEFAULT_BATCH_SIZE,
    DEFAULT_RATE_LIMIT,
    buildSaveVariables,
    coerceNumberInput,
    settingsToFormState,
    validateRequiredFields
} from './formHelpers';

describe('coerceNumberInput', () => {
    test('returns the parsed integer for a valid numeric string', () => {
        expect(coerceNumberInput('250')).toBe(250);
    });

    test('returns empty string for an empty input (fallback)', () => {
        expect(coerceNumberInput('')).toBe('');
    });

    test('returns empty string for a non-numeric / NaN input (fallback)', () => {
        expect(coerceNumberInput('abc')).toBe('');
    });

    test('parses leading-numeric values the way parseInt does', () => {
        expect(coerceNumberInput('42px')).toBe(42);
    });
});

describe('buildSaveVariables — empty strings become null', () => {
    test('coerces empty text fields to null and keeps populated ones', () => {
        const vars = buildSaveVariables({
            contentIndexedMainResourceTypes: '',
            contentIndexedSubNodeTypes: 'jnt:text',
            contentIndexedFileExtensions: '',
            operationsBatchSize: 500,
            projectId: '',
            token: 'secret',
            jahiaUsername: '',
            jahiaPassword: '',
            jahiaServerCookieName: '',
            jahiaServerCookieValue: '',
            jahiaServerCookieDomain: '',
            dryRun: true,
            scheduleJobASAP: false,
            apiBaseUrl: '',
            rateLimitRequestsPerSecond: 10
        });

        expect(vars.contentIndexedMainResourceTypes).toBeNull();
        expect(vars.contentIndexedSubNodeTypes).toBe('jnt:text');
        expect(vars.projectId).toBeNull();
        expect(vars.token).toBe('secret');
        expect(vars.apiBaseUrl).toBeNull();
    });

    test('coerces empty-string numeric fields to null but keeps numeric values (including 0-safe path)', () => {
        const vars = buildSaveVariables({
            operationsBatchSize: '',
            rateLimitRequestsPerSecond: 10,
            dryRun: false,
            scheduleJobASAP: true
        });

        expect(vars.operationsBatchSize).toBeNull();
        expect(vars.rateLimitRequestsPerSecond).toBe(10);
    });

    test('passes boolean flags through unchanged', () => {
        const vars = buildSaveVariables({dryRun: false, scheduleJobASAP: true});
        expect(vars.dryRun).toBe(false);
        expect(vars.scheduleJobASAP).toBe(true);
    });
});

describe('settingsToFormState — settings to form mapping', () => {
    test('applies defaults for missing values', () => {
        const formState = settingsToFormState({});
        expect(formState.contentIndexedMainResourceTypes).toBe('');
        expect(formState.operationsBatchSize).toBe(DEFAULT_BATCH_SIZE);
        expect(formState.rateLimitRequestsPerSecond).toBe(DEFAULT_RATE_LIMIT);
        expect(formState.dryRun).toBe(true);
        expect(formState.scheduleJobASAP).toBe(false);
        expect(formState.projectId).toBe('');
    });

    test('preserves provided values, including falsy dryRun=false', () => {
        const formState = settingsToFormState({
            projectId: 'proj-123',
            token: 'tok',
            operationsBatchSize: 250,
            dryRun: false,
            scheduleJobASAP: true,
            apiBaseUrl: 'https://api.example.com'
        });
        expect(formState.projectId).toBe('proj-123');
        expect(formState.token).toBe('tok');
        expect(formState.operationsBatchSize).toBe(250);
        expect(formState.dryRun).toBe(false);
        expect(formState.scheduleJobASAP).toBe(true);
        expect(formState.apiBaseUrl).toBe('https://api.example.com');
    });
});

describe('validateRequiredFields', () => {
    const t = key => key;

    test('reports both required errors when projectId and token are empty', () => {
        const errors = validateRequiredFields({projectId: '', token: ''}, t);
        expect(errors.projectId).toBe('label.validationProjectIdRequired');
        expect(errors.token).toBe('label.validationTokenRequired');
    });

    test('reports no errors when both required fields are present', () => {
        const errors = validateRequiredFields({projectId: 'p', token: 't'}, t);
        expect(errors.projectId).toBe('');
        expect(errors.token).toBe('');
    });

    test('reports only the empty field', () => {
        const errors = validateRequiredFields({projectId: 'p', token: ''}, t);
        expect(errors.projectId).toBe('');
        expect(errors.token).toBe('label.validationTokenRequired');
    });
});
