import React from 'react';
import {useTranslation} from 'react-i18next';
import styles from './CustomGptSettings.scss';

/**
 * Editor for the per-site server-name overrides.
 *
 * Rows rather than a free-text blob so each site key and host is separately labelled, reachable by keyboard and
 * announced on its own. The rows are owned by the parent, which serialises them into the single
 * `siteServerNames` form field that is actually saved — keeping them as component state here would let the
 * displayed rows and the value about to be submitted drift apart.
 *
 * No PropTypes: nothing else in this bundle uses them, and `prop-types` is only a transitive dependency here,
 * so importing it directly would be a latent break.
 *
 * @param {{rows: Array<{siteKey: string, serverName: string}>, onChange: (rows: Array<{siteKey: string, serverName: string}>) => void}} props
 */
export const SiteServerNames = ({rows, onChange}) => {
    const {t} = useTranslation('customgpt-ai');

    const handleFieldChange = (index, field) => e => {
        const {value} = e.target;
        onChange(rows.map((row, i) => (i === index ? {...row, [field]: value} : row)));
    };

    const handleAdd = () => onChange([...rows, {siteKey: '', serverName: ''}]);

    const handleRemove = index => () => onChange(rows.filter((row, i) => i !== index));

    return (
        <fieldset className={styles.cgpt_siteServerNames}>
            <legend className={styles.cgpt_label}>{t('label.siteServerNames')}</legend>
            <span id="cgpt-site-server-names-hint" className={styles.cgpt_hint}>
                {t('label.siteServerNamesHint')}
            </span>
            {rows.map((row, index) => (
                // The row's identity IS its position: there is no stable key until the admin types one, and a
                // half-typed site key would make a key-based identity change on every keystroke.
                // eslint-disable-next-line react/no-array-index-key
                <div key={index} className={styles.cgpt_siteServerNameRow}>
                    <label className={styles.cgpt_sr_only} htmlFor={`cgpt-site-key-${index}`}>
                        {t('label.siteServerNameSiteKey')}
                    </label>
                    <input
                        type="text"
                        id={`cgpt-site-key-${index}`}
                        className={styles.cgpt_siteServerNameKey}
                        value={row.siteKey}
                        placeholder={t('label.siteServerNameSiteKey')}
                        aria-describedby="cgpt-site-server-names-hint"
                        onChange={handleFieldChange(index, 'siteKey')}
                    />
                    <label className={styles.cgpt_sr_only} htmlFor={`cgpt-site-server-name-${index}`}>
                        {t('label.siteServerNameValue')}
                    </label>
                    <input
                        type="text"
                        id={`cgpt-site-server-name-${index}`}
                        className={styles.cgpt_siteServerNameValue}
                        value={row.serverName}
                        placeholder={t('label.serverNamePlaceholder')}
                        aria-describedby="cgpt-site-server-names-hint"
                        onChange={handleFieldChange(index, 'serverName')}
                    />
                    <button
                        type="button"
                        className={styles.cgpt_siteServerNameRemove}
                        onClick={handleRemove(index)}
                    >
                        {t('label.siteServerNameRemove')}
                        {/* The visible label is just "Remove"; screen readers get which site it removes. */}
                        <span className={styles.cgpt_sr_only}>
                            {` ${row.siteKey || t('label.siteServerNameSiteKey')}`}
                        </span>
                    </button>
                </div>
            ))}
            <button
                type="button"
                className={styles.cgpt_siteServerNameAdd}
                onClick={handleAdd}
            >
                {t('label.siteServerNameAdd')}
            </button>
        </fieldset>
    );
};
