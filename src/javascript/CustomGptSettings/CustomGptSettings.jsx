import React, {useEffect, useRef, useState} from 'react';
import {useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './CustomGptSettings.scss';
import {GET_SETTINGS, PURGE_ALL_PAGES, SAVE_SETTINGS} from './CustomGptSettings.gql';
import {
    DEFAULT_BATCH_SIZE,
    DEFAULT_RATE_LIMIT,
    buildSaveVariables,
    coerceNumberInput,
    settingsToFormState,
    validateRequiredFields
} from './formHelpers';

export const CustomGptSettingsAdmin = () => {
    const {t} = useTranslation('customgpt-ai');
    const [saveStatus, setSaveStatus] = useState(null);
    const [purgeStatus, setPurgeStatus] = useState(null);
    const [showPurgeDialog, setShowPurgeDialog] = useState(false);
    const purgeDialogRef = useRef(null);
    const initializedRef = useRef(false);

    // Non-blocking field validation errors (guidance only — never gates the save)
    const [fieldErrors, setFieldErrors] = useState({projectId: '', token: ''});

    const [projectName, setProjectName] = useState(null);

    const [formState, setFormState] = useState({
        contentIndexedMainResourceTypes: '',
        contentIndexedSubNodeTypes: '',
        contentIndexedFileExtensions: '',
        operationsBatchSize: DEFAULT_BATCH_SIZE,
        projectId: '',
        token: '',
        jahiaUsername: '',
        jahiaPassword: '',
        jahiaServerCookieName: '',
        jahiaServerCookieValue: '',
        jahiaServerCookieDomain: '',
        dryRun: true,
        scheduleJobASAP: false,
        apiBaseUrl: '',
        rateLimitRequestsPerSecond: DEFAULT_RATE_LIMIT
    });

    useEffect(() => {
        document.title = `${t('label.settingsTitle')} - Jahia Administration`;
    }, [t]);

    useEffect(() => {
        if (showPurgeDialog) {
            purgeDialogRef.current?.showModal();
        }
    }, [showPurgeDialog]);

    // One-time initialization from query data — refetches do not clobber user edits
    const {data, loading} = useQuery(GET_SETTINGS, {
        fetchPolicy: 'network-only'
    });

    useEffect(() => {
        if (initializedRef.current) {
            return;
        }

        const s = data?.admin?.customGpt?.settings;
        if (s) {
            initializedRef.current = true;
            setProjectName(s.projectName ?? null);
            setFormState(settingsToFormState(s));
        }
    }, [data]);

    const [saveSettings, {loading: saving}] = useMutation(SAVE_SETTINGS);
    const [purgeAllPages, {loading: purging}] = useMutation(PURGE_ALL_PAGES);

    const handleChange = field => e => {
        setSaveStatus(null);
        const value = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
        setFormState(prev => ({...prev, [field]: value}));
        // Clear field error once the user provides a value
        if ((field === 'projectId' || field === 'token') && value !== '') {
            setFieldErrors(prev => ({...prev, [field]: ''}));
        }
    };

    const handleNumberChange = field => e => {
        setSaveStatus(null);
        const value = coerceNumberInput(e.target.value);
        setFormState(prev => ({...prev, [field]: value}));
    };

    const handlePurge = () => {
        setShowPurgeDialog(true);
    };

    const handlePurgeConfirm = async () => {
        // Keep dialog open while purging; close only after mutation settles
        try {
            const result = await purgeAllPages();
            const count = result.data?.admin?.customGpt?.purgeAllPages;
            purgeDialogRef.current?.close();
            setShowPurgeDialog(false);
            setPurgeStatus({type: 'success', count});
        } catch (err) {
            console.error('Failed to purge pages:', err);
            purgeDialogRef.current?.close();
            setShowPurgeDialog(false);
            setPurgeStatus({type: 'error'});
        } finally {
            // Return focus to the purge trigger button after dialog closes
            document.getElementById('cgpt-purge-button')?.focus();
        }
    };

    const handlePurgeCancel = () => {
        purgeDialogRef.current?.close();
    };

    const handleDialogClose = () => {
        setShowPurgeDialog(false);
        // Return focus to the purge trigger when dialog closes via Escape or Cancel
        document.getElementById('cgpt-purge-button')?.focus();
    };

    const handleSave = async () => {
        // Compute non-blocking guidance errors for required fields
        const errors = validateRequiredFields(formState, t);
        setFieldErrors(errors);

        // DO NOT early-return — always proceed with the mutation regardless of errors
        try {
            const result = await saveSettings({variables: buildSaveVariables(formState)});
            setSaveStatus(result.data?.admin?.customGpt?.saveSettings ? 'success' : 'error');
        } catch (err) {
            console.error('Failed to save settings:', err);
            setSaveStatus('error');
        }
    };

    if (loading) {
        return (
            <output className={styles.cgpt_loading} aria-label={t('label.loading')}>
                <Loader size="big" aria-hidden="true"/>
            </output>
        );
    }

    // Compute aria-describedby strings combining error id + projectName id where applicable
    const projectIdDescribedBy = [
        fieldErrors.projectId ? 'cgpt-project-id-error' : null,
        projectName ? 'cgpt-project-name' : null
    ].filter(Boolean).join(' ') || undefined;

    const tokenDescribedBy = fieldErrors.token ? 'cgpt-token-error' : undefined;

    return (
        <main className={styles.cgpt_container}>
            {/* Save status live regions — always in DOM so AT registers them at mount */}
            <output
                id="cgpt-save-status"
                aria-live="polite"
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {saveStatus === 'success' ? t('label.saveSuccess') : ''}
            </output>
            <div
                id="cgpt-save-error"
                role="alert"
                aria-live="assertive"
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {saveStatus === 'error' ? t('label.saveError') : ''}
            </div>

            {/* Purge status live regions — always in DOM */}
            <output
                id="cgpt-purge-status"
                aria-live="polite"
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {purgeStatus?.type === 'success' ? t('label.purgeSuccess', {count: purgeStatus.count}) : ''}
            </output>
            <div
                id="cgpt-purge-error"
                role="alert"
                aria-live="assertive"
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {purgeStatus?.type === 'error' ? t('label.purgeError') : ''}
            </div>

            <div className={styles.cgpt_header}>
                <h1>{t('label.settingsTitle')}</h1>
            </div>

            <div className={styles.cgpt_description}>
                <Typography>{t('label.settingsDescription')}</Typography>
            </div>

            <p className={styles.cgpt_requiredNote}>
                <span aria-hidden="true">* </span>{t('label.requiredFieldsNote')}
            </p>

            <form
                onSubmit={e => {
                    e.preventDefault();
                    handleSave();
                }}
            >
                <div className={styles.cgpt_form}>
                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-main-resource-types">
                            {t('label.contentIndexedMainResourceTypes')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-main-resource-types"
                            className={styles.cgpt_input}
                            value={formState.contentIndexedMainResourceTypes}
                            placeholder={t('label.contentIndexedMainResourceTypesPlaceholder')}
                            aria-describedby="cgpt-main-resource-types-hint"
                            onChange={handleChange('contentIndexedMainResourceTypes')}
                        />
                        <span id="cgpt-main-resource-types-hint" className={styles.cgpt_hint}>
                            {t('label.commaSeparatedHint')}
                        </span>
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-sub-node-types">
                            {t('label.contentIndexedSubNodeTypes')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-sub-node-types"
                            className={styles.cgpt_input}
                            value={formState.contentIndexedSubNodeTypes}
                            placeholder={t('label.contentIndexedSubNodeTypesPlaceholder')}
                            aria-describedby="cgpt-sub-node-types-hint"
                            onChange={handleChange('contentIndexedSubNodeTypes')}
                        />
                        <span id="cgpt-sub-node-types-hint" className={styles.cgpt_hint}>
                            {t('label.commaSeparatedHint')}
                        </span>
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-file-extensions">
                            {t('label.contentIndexedFileExtensions')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-file-extensions"
                            className={styles.cgpt_input}
                            value={formState.contentIndexedFileExtensions}
                            placeholder={t('label.contentIndexedFileExtensionsPlaceholder')}
                            aria-describedby="cgpt-file-extensions-hint"
                            onChange={handleChange('contentIndexedFileExtensions')}
                        />
                        <span id="cgpt-file-extensions-hint" className={styles.cgpt_hint}>
                            {t('label.commaSeparatedHint')}
                        </span>
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-batch-size">
                            {t('label.operationsBatchSize')}
                        </label>
                        <input
                            type="number"
                            id="cgpt-batch-size"
                            className={styles.cgpt_input}
                            min="1"
                            max="10000"
                            value={formState.operationsBatchSize}
                            onChange={handleNumberChange('operationsBatchSize')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-rate-limit">
                            {t('label.rateLimitRequestsPerSecond')}
                        </label>
                        <input
                            type="number"
                            id="cgpt-rate-limit"
                            className={styles.cgpt_input}
                            min="1"
                            max="1000"
                            value={formState.rateLimitRequestsPerSecond}
                            aria-describedby="cgpt-rate-limit-hint"
                            onChange={handleNumberChange('rateLimitRequestsPerSecond')}
                        />
                        <span id="cgpt-rate-limit-hint" className={styles.cgpt_hint}>
                            {t('label.rateLimitRequestsPerSecondHint')}
                        </span>
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-project-id">
                            {t('label.projectId')}<span aria-hidden="true"> *</span>
                        </label>
                        <input
                            type="text"
                            id="cgpt-project-id"
                            className={styles.cgpt_input}
                            value={formState.projectId}
                            aria-required="true"
                            aria-invalid={fieldErrors.projectId ? 'true' : undefined}
                            aria-describedby={projectIdDescribedBy || undefined}
                            onChange={handleChange('projectId')}
                        />
                        {fieldErrors.projectId && (
                            <span
                                id="cgpt-project-id-error"
                                role="alert"
                                className={styles.cgpt_fieldError}
                            >
                                {fieldErrors.projectId}
                            </span>
                        )}
                        {projectName && (
                            <output
                                id="cgpt-project-name"
                                className={styles.cgpt_projectName}
                            >
                                {projectName}
                            </output>
                        )}
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-token">
                            {t('label.token')}<span aria-hidden="true"> *</span>
                        </label>
                        <input
                            type="password"
                            id="cgpt-token"
                            className={styles.cgpt_input}
                            value={formState.token}
                            aria-required="true"
                            aria-invalid={fieldErrors.token ? 'true' : undefined}
                            aria-describedby={tokenDescribedBy}
                            onChange={handleChange('token')}
                        />
                        {fieldErrors.token && (
                            <span
                                id="cgpt-token-error"
                                role="alert"
                                className={styles.cgpt_fieldError}
                            >
                                {fieldErrors.token}
                            </span>
                        )}
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-api-base-url">
                            {t('label.apiBaseUrl')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-api-base-url"
                            className={styles.cgpt_input}
                            value={formState.apiBaseUrl}
                            placeholder={t('label.apiBaseUrlPlaceholder')}
                            autoComplete="url"
                            onChange={handleChange('apiBaseUrl')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-jahia-username">
                            {t('label.jahiaUsername')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-jahia-username"
                            className={styles.cgpt_input}
                            autoComplete="username"
                            value={formState.jahiaUsername}
                            onChange={handleChange('jahiaUsername')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-jahia-password">
                            {t('label.jahiaPassword')}
                        </label>
                        <input
                            type="password"
                            id="cgpt-jahia-password"
                            className={styles.cgpt_input}
                            autoComplete="current-password"
                            value={formState.jahiaPassword}
                            onChange={handleChange('jahiaPassword')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-cookie-name">
                            {t('label.jahiaServerCookieName')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-cookie-name"
                            className={styles.cgpt_input}
                            value={formState.jahiaServerCookieName}
                            onChange={handleChange('jahiaServerCookieName')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-cookie-value">
                            {t('label.jahiaServerCookieValue')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-cookie-value"
                            className={styles.cgpt_input}
                            value={formState.jahiaServerCookieValue}
                            onChange={handleChange('jahiaServerCookieValue')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_label} htmlFor="cgpt-cookie-domain">
                            {t('label.jahiaServerCookieDomain')}
                        </label>
                        <input
                            type="text"
                            id="cgpt-cookie-domain"
                            className={styles.cgpt_input}
                            value={formState.jahiaServerCookieDomain}
                            onChange={handleChange('jahiaServerCookieDomain')}
                        />
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_checkboxLabel} htmlFor="cgpt-dry-run">
                            <input
                                type="checkbox"
                                id="cgpt-dry-run"
                                className={styles.cgpt_checkbox}
                                checked={formState.dryRun}
                                aria-describedby="cgpt-dry-run-hint"
                                onChange={handleChange('dryRun')}
                            />
                            {t('label.dryRun')}
                        </label>
                        <span id="cgpt-dry-run-hint" className={styles.cgpt_hint}>
                            {t('label.dryRunHint')}
                        </span>
                    </div>

                    <div className={styles.cgpt_fieldGroup}>
                        <label className={styles.cgpt_checkboxLabel} htmlFor="cgpt-schedule-asap">
                            <input
                                type="checkbox"
                                id="cgpt-schedule-asap"
                                className={styles.cgpt_checkbox}
                                checked={formState.scheduleJobASAP}
                                aria-describedby="cgpt-schedule-asap-hint"
                                onChange={handleChange('scheduleJobASAP')}
                            />
                            {t('label.scheduleJobASAP')}
                        </label>
                        <span id="cgpt-schedule-asap-hint" className={styles.cgpt_hint}>
                            {t('label.scheduleJobASAPHint')}
                        </span>
                    </div>
                </div>

                <div className={styles.cgpt_actions}>
                    {saveStatus === 'success' && (
                        <div
                            aria-hidden="true"
                            className={`${styles.cgpt_alert} ${styles['cgpt_alert--success']}`}
                        >
                            {t('label.saveSuccess')}
                        </div>
                    )}
                    {saveStatus === 'error' && (
                        <div
                            aria-hidden="true"
                            className={`${styles.cgpt_alert} ${styles['cgpt_alert--error']}`}
                        >
                            {t('label.saveError')}
                        </div>
                    )}
                    <Button
                        type="submit"
                        label={t('label.save')}
                        variant="primary"
                        isDisabled={saving}
                        onClick={handleSave}
                    />
                </div>
            </form>

            <div className={styles.cgpt_dangerZone}>
                <h2>{t('label.dangerZoneTitle')}</h2>
                {purgeStatus?.type === 'success' && (
                    <div
                        aria-hidden="true"
                        className={`${styles.cgpt_alert} ${styles['cgpt_alert--success']}`}
                    >
                        {t('label.purgeSuccess', {count: purgeStatus.count})}
                    </div>
                )}
                {purgeStatus?.type === 'error' && (
                    <div
                        aria-hidden="true"
                        className={`${styles.cgpt_alert} ${styles['cgpt_alert--error']}`}
                    >
                        {t('label.purgeError')}
                    </div>
                )}
                <Button
                    type="button"
                    id="cgpt-purge-button"
                    label={t('label.purgeAllPages')}
                    variant="danger"
                    isDisabled={purging}
                    onClick={handlePurge}
                />
            </div>

            <dialog
                ref={purgeDialogRef}
                role="alertdialog"
                aria-labelledby="cgpt-purge-dialog-title"
                aria-describedby="cgpt-purge-dialog-desc"
                className={styles.cgpt_dialog}
                onClose={handleDialogClose}
            >
                <h2 id="cgpt-purge-dialog-title">{t('label.purgeConfirmTitle')}</h2>
                <p id="cgpt-purge-dialog-desc">{t('label.purgeConfirm')}</p>
                <div className={styles.cgpt_dialogActions}>
                    <Button
                        type="button"
                        label={t('label.cancel')}
                        isDisabled={purging}
                        onClick={handlePurgeCancel}
                    />
                    <Button
                        type="button"
                        label={purging ? t('label.purging') : t('label.confirmPurge')}
                        variant="danger"
                        isDisabled={purging}
                        onClick={handlePurgeConfirm}
                    />
                </div>
            </dialog>
        </main>
    );
};

export default CustomGptSettingsAdmin;
