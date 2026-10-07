// Pure, framework-free helpers extracted from CustomGptSettings.jsx for unit testing.
// No behavioral changes — these are the exact transforms that were previously inline.

export const DEFAULT_BATCH_SIZE = 500;
export const DEFAULT_RATE_LIMIT = 10;

// Maps server settings to the form state, applying defaults for missing values.
export const settingsToFormState = s => ({
    contentIndexedMainResourceTypes: s.contentIndexedMainResourceTypes ?? '',
    contentIndexedSubNodeTypes: s.contentIndexedSubNodeTypes ?? '',
    contentIndexedFileExtensions: s.contentIndexedFileExtensions ?? '',
    operationsBatchSize: s.operationsBatchSize ?? DEFAULT_BATCH_SIZE,
    projectId: s.projectId ?? '',
    token: s.token ?? '',
    jahiaUsername: s.jahiaUsername ?? '',
    jahiaPassword: s.jahiaPassword ?? '',
    jahiaServerCookieName: s.jahiaServerCookieName ?? '',
    jahiaServerCookieValue: s.jahiaServerCookieValue ?? '',
    jahiaServerCookieDomain: s.jahiaServerCookieDomain ?? '',
    serverName: s.serverName ?? '',
    siteServerNames: s.siteServerNames ?? '',
    dryRun: s.dryRun ?? true,
    scheduleJobASAP: s.scheduleJobASAP ?? false,
    apiBaseUrl: s.apiBaseUrl ?? '',
    rateLimitRequestsPerSecond: s.rateLimitRequestsPerSecond ?? DEFAULT_RATE_LIMIT
});

// Maps the form state to saveSettings mutation variables; empty strings become null.
export const buildSaveVariables = formState => {
    const text = value => value || null;
    const number = value => (value === '' ? null : value);
    return {
        contentIndexedMainResourceTypes: text(formState.contentIndexedMainResourceTypes),
        contentIndexedSubNodeTypes: text(formState.contentIndexedSubNodeTypes),
        contentIndexedFileExtensions: text(formState.contentIndexedFileExtensions),
        operationsBatchSize: number(formState.operationsBatchSize),
        projectId: text(formState.projectId),
        token: text(formState.token),
        jahiaUsername: text(formState.jahiaUsername),
        jahiaPassword: text(formState.jahiaPassword),
        jahiaServerCookieName: text(formState.jahiaServerCookieName),
        jahiaServerCookieValue: text(formState.jahiaServerCookieValue),
        jahiaServerCookieDomain: text(formState.jahiaServerCookieDomain),
        serverName: text(formState.serverName),
        // Always a string, never null: null reads as "not submitted" on the server and would leave rows the
        // admin deleted in the configuration for ever. '' is how "no per-site overrides" is expressed.
        siteServerNames: formState.siteServerNames ?? '',
        dryRun: formState.dryRun,
        scheduleJobASAP: formState.scheduleJobASAP,
        apiBaseUrl: text(formState.apiBaseUrl),
        rateLimitRequestsPerSecond: number(formState.rateLimitRequestsPerSecond)
    };
};

// Coerces a raw number-input string to an int, or '' when empty / NaN.
// Mirrors the previous inline logic in handleNumberChange — identical behavior.
export const coerceNumberInput = raw => {
    if (raw === '') {
        return '';
    }

    const n = Number.parseInt(raw, 10);
    return Number.isNaN(n) ? '' : n;
};

// Computes the non-blocking required-field guidance errors.
// `t` is the i18next translator; returns '' for a satisfied field.
export const validateRequiredFields = (formState, t) => ({
    projectId: formState.projectId === '' ? t('label.validationProjectIdRequired') : '',
    token: formState.token === '' ? t('label.validationTokenRequired') : ''
});

// The per-site server names travel as one `siteKey=serverName` per line, but the panel edits them as rows.
// Splitting on the FIRST `=` only: a server name may legitimately contain one (a query string, for instance),
// and the site key never may.
export const parseSiteServerNames = text =>
    (text ?? '')
        .split('\n')
        .map(line => line.trim())
        .filter(line => line !== '')
        .map(line => {
            const eq = line.indexOf('=');
            return eq < 0 ?
                {siteKey: line, serverName: ''} :
                {siteKey: line.slice(0, eq).trim(), serverName: line.slice(eq + 1).trim()};
        });

// Inverse of parseSiteServerNames. A wholly empty row is the one just added and not yet filled in, so it is
// dropped; a half-filled row is kept, so the admin can see which part is still missing instead of it vanishing.
export const formatSiteServerNames = rows =>
    (rows ?? [])
        .map(row => ({siteKey: (row.siteKey ?? '').trim(), serverName: (row.serverName ?? '').trim()}))
        .filter(row => row.siteKey !== '' || row.serverName !== '')
        .map(row => `${row.siteKey}=${row.serverName}`)
        .join('\n');
