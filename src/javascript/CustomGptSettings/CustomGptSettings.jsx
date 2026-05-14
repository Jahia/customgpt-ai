import React, {useRef, useState} from 'react';
import {useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './CustomGptSettings.scss';
import {GET_SETTINGS, PURGE_ALL_PAGES, SAVE_SETTINGS} from './CustomGptSettings.gql';

export const CustomGptSettingsAdmin = () => {
    const {t} = useTranslation('customgpt-ai');
    const [saveStatus, setSaveStatus] = useState(null);

    const [projectName, setProjectName] = useState(null);

    const [formState, setFormState] = useState({
        contentIndexedMainResourceTypes: '',
        contentIndexedSubNodeTypes: '',
        contentIndexedFileExtensions: '',
        operationsBatchSize: 500,
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
        rateLimitRequestsPerSecond: 10
    });

    const saveStatusRef = useRef(null);
    const purgeStatusRef = useRef(null);

    const {loading} = useQuery(GET_SETTINGS, {
        fetchPolicy: 'network-only',
        onCompleted: data => {
            const s = data?.admin?.customGpt?.settings;
            if (s) {
                setProjectName(s.projectName ?? null);
                setFormState({
                    contentIndexedMainResourceTypes: s.contentIndexedMainResourceTypes ?? '',
                    contentIndexedSubNodeTypes: s.contentIndexedSubNodeTypes ?? '',
                    contentIndexedFileExtensions: s.contentIndexedFileExtensions ?? '',
                    operationsBatchSize: s.operationsBatchSize ?? 500,
                    projectId: s.projectId ?? '',
                    token: s.token ?? '',
                    jahiaUsername: s.jahiaUsername ?? '',
                    jahiaPassword: s.jahiaPassword ?? '',
                    jahiaServerCookieName: s.jahiaServerCookieName ?? '',
                    jahiaServerCookieValue: s.jahiaServerCookieValue ?? '',
                    jahiaServerCookieDomain: s.jahiaServerCookieDomain ?? '',
                    dryRun: s.dryRun ?? true,
                    scheduleJobASAP: s.scheduleJobASAP ?? false,
                    apiBaseUrl: s.apiBaseUrl ?? '',
                    rateLimitRequestsPerSecond: s.rateLimitRequestsPerSecond ?? 10
                });
            }
        }
    });

    const [saveSettings, {loading: saving}] = useMutation(SAVE_SETTINGS);
    const [purgeAllPages, {loading: purging}] = useMutation(PURGE_ALL_PAGES);
    const [purgeStatus, setPurgeStatus] = useState(null);

    const handleChange = field => e => {
        setSaveStatus(null);
        const value = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
        setFormState(prev => ({...prev, [field]: value}));
    };

    const handleNumberChange = field => e => {
        setSaveStatus(null);
        const value = e.target.value === '' ? '' : parseInt(e.target.value, 10);
        setFormState(prev => ({...prev, [field]: value}));
    };

    const handlePurge = async () => {
        if (!window.confirm(t('label.purgeConfirm'))) {
            return;
        }

        setPurgeStatus(null);
        try {
            const result = await purgeAllPages();
            const count = result.data?.admin?.customGpt?.purgeAllPages;
            setPurgeStatus({type: 'success', count});
        } catch (err) {
            console.error('Failed to purge pages:', err);
            setPurgeStatus({type: 'error'});
        }

        setTimeout(() => purgeStatusRef.current?.focus(), 50);
    };

    const handleSave = async () => {
        setSaveStatus(null);
        try {
            const result = await saveSettings({
                variables: {
                    contentIndexedMainResourceTypes: formState.contentIndexedMainResourceTypes || null,
                    contentIndexedSubNodeTypes: formState.contentIndexedSubNodeTypes || null,
                    contentIndexedFileExtensions: formState.contentIndexedFileExtensions || null,
                    operationsBatchSize: formState.operationsBatchSize === '' ? null : formState.operationsBatchSize,
                    projectId: formState.projectId || null,
                    token: formState.token || null,
                    jahiaUsername: formState.jahiaUsername || null,
                    jahiaPassword: formState.jahiaPassword || null,
                    jahiaServerCookieName: formState.jahiaServerCookieName || null,
                    jahiaServerCookieValue: formState.jahiaServerCookieValue || null,
                    jahiaServerCookieDomain: formState.jahiaServerCookieDomain || null,
                    dryRun: formState.dryRun,
                    scheduleJobASAP: formState.scheduleJobASAP,
                    apiBaseUrl: formState.apiBaseUrl || null,
                    rateLimitRequestsPerSecond: formState.rateLimitRequestsPerSecond === '' ? null : formState.rateLimitRequestsPerSecond
                }
            });
            setSaveStatus(result.data?.admin?.customGpt?.saveSettings ? 'success' : 'error');
        } catch (err) {
            console.error('Failed to save settings:', err);
            setSaveStatus('error');
        }

        setTimeout(() => saveStatusRef.current?.focus(), 50);
    };

    const srSaveMsg = saveStatus === 'success' ? t('label.saveSuccess') :
        saveStatus === 'error' ? t('label.saveError') : '';

    const srPurgeMsg = purgeStatus?.type === 'success' ? t('label.purgeSuccess', {count: purgeStatus.count}) :
        purgeStatus?.type === 'error' ? t('label.purgeError') : '';

    if (loading) {
        return (
            <div className={styles.cgpt_loading} role="status" aria-label={t('label.loading')}>
                <Loader size="big" aria-hidden="true"/>
            </div>
        );
    }

    return (
        <div className={styles.cgpt_container}>
            {/* Persistent live regions — always in DOM so AT registers them before content appears */}
            <div
                ref={saveStatusRef}
                tabIndex={-1}
                role={saveStatus === 'error' ? 'alert' : 'status'}
                aria-live={saveStatus === 'error' ? 'assertive' : 'polite'}
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {srSaveMsg}
            </div>
            <div
                ref={purgeStatusRef}
                tabIndex={-1}
                role={purgeStatus?.type === 'error' ? 'alert' : 'status'}
                aria-live={purgeStatus?.type === 'error' ? 'assertive' : 'polite'}
                aria-atomic="true"
                className={styles.cgpt_sr_only}
            >
                {srPurgeMsg}
            </div>

            <div className={styles.cgpt_header}>
                <h2>{t('label.settingsTitle')}</h2>
            </div>

            <div className={styles.cgpt_description}>
                <Typography>{t('label.settingsDescription')}</Typography>
            </div>

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
                        onChange={handleChange('contentIndexedMainResourceTypes')}
                    />
                    <span className={styles.cgpt_hint}>{t('label.commaSeparatedHint')}</span>
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
                        onChange={handleChange('contentIndexedSubNodeTypes')}
                    />
                    <span className={styles.cgpt_hint}>{t('label.commaSeparatedHint')}</span>
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
                        onChange={handleChange('contentIndexedFileExtensions')}
                    />
                    <span className={styles.cgpt_hint}>{t('label.commaSeparatedHint')}</span>
                </div>

                <div className={styles.cgpt_fieldGroup}>
                    <label className={styles.cgpt_label} htmlFor="cgpt-batch-size">
                        {t('label.operationsBatchSize')}
                    </label>
                    <input
                        type="number"
                        id="cgpt-batch-size"
                        className={styles.cgpt_input}
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
                        value={formState.rateLimitRequestsPerSecond}
                        onChange={handleNumberChange('rateLimitRequestsPerSecond')}
                    />
                    <span className={styles.cgpt_hint}>{t('label.rateLimitRequestsPerSecondHint')}</span>
                </div>

                <div className={styles.cgpt_fieldGroup}>
                    <label className={styles.cgpt_label} htmlFor="cgpt-project-id">
                        {t('label.projectId')}
                    </label>
                    <input
                        type="text"
                        id="cgpt-project-id"
                        className={styles.cgpt_input}
                        value={formState.projectId}
                        onChange={handleChange('projectId')}
                    />
                    {projectName && (
                        <span className={styles.cgpt_projectName}>{projectName}</span>
                    )}
                </div>

                <div className={styles.cgpt_fieldGroup}>
                    <label className={styles.cgpt_label} htmlFor="cgpt-token">
                        {t('label.token')}
                    </label>
                    <input
                        type="password"
                        id="cgpt-token"
                        className={styles.cgpt_input}
                        value={formState.token}
                        onChange={handleChange('token')}
                    />
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
                            onChange={handleChange('dryRun')}
                        />
                        {t('label.dryRun')}
                    </label>
                    <span className={styles.cgpt_hint}>{t('label.dryRunHint')}</span>
                </div>

                <div className={styles.cgpt_fieldGroup}>
                    <label className={styles.cgpt_checkboxLabel} htmlFor="cgpt-schedule-asap">
                        <input
                            type="checkbox"
                            id="cgpt-schedule-asap"
                            className={styles.cgpt_checkbox}
                            checked={formState.scheduleJobASAP}
                            onChange={handleChange('scheduleJobASAP')}
                        />
                        {t('label.scheduleJobASAP')}
                    </label>
                    <span className={styles.cgpt_hint}>{t('label.scheduleJobASAPHint')}</span>
                </div>
            </div>

            <div className={styles.cgpt_actions}>
                {saveStatus === 'success' && (
                    <div aria-hidden="true" className={`${styles.cgpt_alert} ${styles['cgpt_alert--success']}`}>
                        {t('label.saveSuccess')}
                    </div>
                )}
                {saveStatus === 'error' && (
                    <div aria-hidden="true" className={`${styles.cgpt_alert} ${styles['cgpt_alert--error']}`}>
                        {t('label.saveError')}
                    </div>
                )}
                <Button
                    type="button"
                    label={t('label.save')}
                    variant="primary"
                    isDisabled={saving}
                    onClick={handleSave}
                />
            </div>

            <div className={styles.cgpt_dangerZone}>
                <h3>{t('label.dangerZoneTitle')}</h3>
                {purgeStatus?.type === 'success' && (
                    <div aria-hidden="true" className={`${styles.cgpt_alert} ${styles['cgpt_alert--success']}`}>
                        {t('label.purgeSuccess', {count: purgeStatus.count})}
                    </div>
                )}
                {purgeStatus?.type === 'error' && (
                    <div aria-hidden="true" className={`${styles.cgpt_alert} ${styles['cgpt_alert--error']}`}>
                        {t('label.purgeError')}
                    </div>
                )}
                <Button
                    type="button"
                    label={t('label.purgeAllPages')}
                    variant="danger"
                    isDisabled={purging}
                    onClick={handlePurge}
                />
            </div>
        </div>
    );
};

export default CustomGptSettingsAdmin;
