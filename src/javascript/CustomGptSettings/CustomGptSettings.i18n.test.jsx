import React from 'react';
import {render, screen, act} from '@testing-library/react';
import i18next from 'i18next';
import {initReactI18next} from 'react-i18next';
import en from '../../main/resources/javascript/locales/en.json';
import fr from '../../main/resources/javascript/locales/fr.json';
import CustomGptSettingsAdmin from './CustomGptSettings';

// F10 (render half): unlike CustomGptSettings.test.jsx (which mocks react-i18next entirely, echoing the
// translation key so assertions stay locale-independent), THIS file wires up the REAL i18next +
// react-i18next stack with the actual locale JSON as resources, proving the i18next wiring genuinely swaps
// languages rather than silently falling back to English.

jest.mock('@jahia/moonstone', () => {
    const ReactLib = require('react');
    return {
        Button: ({label, id, type, isDisabled, onClick}) =>
            ReactLib.createElement('button', {id, type: type === 'submit' ? 'submit' : 'button', disabled: isDisabled, onClick}, label),
        Loader: () => ReactLib.createElement('div', {'data-testid': 'loader'}),
        Typography: ({children}) => ReactLib.createElement('div', null, children)
    };
});

let mockUseQueryReturn;

jest.mock('@apollo/client', () => ({
    gql: () => ({}),
    useQuery: () => mockUseQueryReturn,
    useMutation: () => [jest.fn(), {loading: false}]
}));

jest.mock('./CustomGptSettings.gql', () => ({
    GET_SETTINGS: 'GET_SETTINGS',
    SAVE_SETTINGS: 'SAVE_SETTINGS',
    PURGE_ALL_PAGES: 'PURGE_ALL_PAGES'
}));

beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function () {
        this.open = true;
    };

    HTMLDialogElement.prototype.close = function () {
        this.open = false;
    };

    return i18next.use(initReactI18next).init({
        lng: 'fr',
        fallbackLng: 'en',
        ns: ['customgpt-ai'],
        defaultNS: 'customgpt-ai',
        resources: {
            en: {'customgpt-ai': en},
            fr: {'customgpt-ai': fr}
        },
        interpolation: {escapeValue: false},
        react: {useSuspense: false}
    });
});

beforeEach(() => {
    mockUseQueryReturn = {data: undefined, loading: false};
});

describe('CustomGptSettingsAdmin i18n (F10)', () => {
    test('renders the French translation, not the English default, when the active language is fr', () => {
        expect(i18next.language).toBe('fr');

        render(<CustomGptSettingsAdmin/>);

        // A representative label: the panel title, rendered from the real fr.json string.
        expect(screen.getByText(fr.label.settingsTitle)).toBeInTheDocument();
        expect(fr.label.settingsTitle).not.toEqual(en.label.settingsTitle);
        expect(screen.queryByText(en.label.settingsTitle)).not.toBeInTheDocument();

        // The danger-zone purge button is a second representative label.
        expect(screen.getByRole('button', {name: fr.label.purgeAllPages})).toBeInTheDocument();
    });

    test('falls back to English for a language with no loaded resources', async () => {
        await act(async () => {
            await i18next.changeLanguage('de');
        });

        // 'de' resources were deliberately NOT loaded above - i18next must fall back to 'en'.
        const {unmount} = render(<CustomGptSettingsAdmin/>);

        expect(screen.getByText(en.label.settingsTitle)).toBeInTheDocument();
        unmount();

        // Restore for subsequent tests/files sharing the global i18next instance.
        await act(async () => {
            await i18next.changeLanguage('fr');
        });
    });
});
