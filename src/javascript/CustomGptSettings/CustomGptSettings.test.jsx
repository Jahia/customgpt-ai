import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import CustomGptSettingsAdmin from './CustomGptSettings';

// ── Mocks ────────────────────────────────────────────────────────────────────
// Translate by echoing the key so assertions stay independent of locale copy.
jest.mock('react-i18next', () => ({
    useTranslation: () => ({t: key => key})
}));

jest.mock('@jahia/moonstone', () => {
    const React = require('react');
    return {
        // Forward id/type/onClick/disabled so role/label queries, focus, and native form
        // submission work. The Save button is type="submit" and relies solely on the form's
        // onSubmit (no onClick) — clicking it submits the form once via jsdom.
        Button: ({label, id, type, isDisabled, onClick}) =>
            React.createElement('button', {id, type: type === 'submit' ? 'submit' : 'button', disabled: isDisabled, onClick}, label),
        Loader: () => React.createElement('div', {'data-testid': 'loader'}),
        Typography: ({children}) => React.createElement('div', null, children)
    };
});

// Apollo hooks are mocked per-test via these controllable refs.
let mockUseQueryReturn;
let mockSaveSettings;
let mockPurgeAllPages;

jest.mock('@apollo/client', () => ({
    gql: () => ({}),
    useQuery: () => mockUseQueryReturn,
    useMutation: fn => {
        // Identify which mutation by the document; the component passes SAVE_SETTINGS first.
        if (fn === 'SAVE_SETTINGS') {
            return [mockSaveSettings, {loading: false}];
        }

        return [mockPurgeAllPages, {loading: false}];
    }
}));

// Make the gql document objects identifiable strings for the useMutation mock above.
jest.mock('./CustomGptSettings.gql', () => ({
    GET_SETTINGS: 'GET_SETTINGS',
    SAVE_SETTINGS: 'SAVE_SETTINGS',
    PURGE_ALL_PAGES: 'PURGE_ALL_PAGES'
}));

// Jsdom does not implement <dialog>.showModal / close — polyfill before importing component.
beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function () {
        this.open = true;
    };

    HTMLDialogElement.prototype.close = function () {
        this.open = false;
        this.dispatchEvent(new Event('close'));
    };
});

const fullSettings = {
    contentIndexedMainResourceTypes: 'jnt:page',
    contentIndexedSubNodeTypes: 'jnt:text',
    contentIndexedFileExtensions: 'pdf',
    operationsBatchSize: 250,
    projectId: 'proj-123',
    projectName: 'My Project',
    token: 'secret-token',
    jahiaUsername: 'root',
    jahiaPassword: 'pw',
    jahiaServerCookieName: '',
    jahiaServerCookieValue: '',
    jahiaServerCookieDomain: '',
    dryRun: false,
    scheduleJobASAP: true,
    apiBaseUrl: 'https://api.example.com',
    rateLimitRequestsPerSecond: 5
};

const settingsData = settings => ({admin: {customGpt: {settings}}});

beforeEach(() => {
    mockSaveSettings = jest.fn().mockResolvedValue({data: {admin: {customGpt: {saveSettings: true}}}});
    mockPurgeAllPages = jest.fn().mockResolvedValue({data: {admin: {customGpt: {purgeAllPages: 3}}}});
    mockUseQueryReturn = {data: undefined, loading: false};
});

describe('CustomGptSettingsAdmin', () => {
    test('renders the loading state while the query is in flight', () => {
        mockUseQueryReturn = {data: undefined, loading: true};
        render(<CustomGptSettingsAdmin/>);
        expect(screen.getByRole('status', {name: 'label.loading'})).toBeInTheDocument();
        expect(screen.getByTestId('loader')).toBeInTheDocument();
    });

    test('renders the form populated with the fetched settings', () => {
        mockUseQueryReturn = {data: settingsData(fullSettings), loading: false};
        render(<CustomGptSettingsAdmin/>);
        expect(screen.getByLabelText('label.contentIndexedMainResourceTypes')).toHaveValue('jnt:page');
        // Required-field labels carry a trailing " *" marker, so match by prefix.
        expect(screen.getByLabelText(/^label\.projectId/)).toHaveValue('proj-123');
        expect(screen.getByLabelText(/^label\.token/)).toHaveValue('secret-token');
        // ProjectName is rendered as guidance next to the projectId field.
        expect(screen.getByText('My Project')).toBeInTheDocument();
    });

    test('shows an inline aria error and sets aria-invalid when a required field is empty on save', async () => {
        mockUseQueryReturn = {
            data: settingsData({...fullSettings, projectId: '', token: ''}),
            loading: false
        };
        render(<CustomGptSettingsAdmin/>);

        const projectId = screen.getByLabelText(/^label\.projectId/);
        const token = screen.getByLabelText(/^label\.token/);
        expect(projectId).not.toHaveAttribute('aria-invalid', 'true');

        fireEvent.click(screen.getByRole('button', {name: 'label.save'}));

        await waitFor(() => {
            expect(projectId).toHaveAttribute('aria-invalid', 'true');
        });
        expect(token).toHaveAttribute('aria-invalid', 'true');
        // Inline alerts surface the validation copy.
        const alerts = screen.getAllByRole('alert');
        const messages = alerts.map(a => a.textContent);
        expect(messages).toContain('label.validationProjectIdRequired');
        expect(messages).toContain('label.validationTokenRequired');

        // Non-blocking: the save mutation still fires regardless of validation errors.
        await waitFor(() => expect(mockSaveSettings).toHaveBeenCalled());
    });

    test('opens the purge dialog and returns focus to the trigger on cancel', async () => {
        mockUseQueryReturn = {data: settingsData(fullSettings), loading: false};
        render(<CustomGptSettingsAdmin/>);

        const trigger = document.getElementById('cgpt-purge-button');
        expect(trigger).toBeInTheDocument();

        fireEvent.click(trigger);

        const dialog = screen.getByRole('alertdialog');
        await waitFor(() => expect(dialog.open).toBe(true));

        fireEvent.click(screen.getByRole('button', {name: 'label.cancel'}));

        // Cancel closes the dialog, which fires onClose → focus returns to the trigger.
        await waitFor(() => expect(dialog.open).toBe(false));
        await waitFor(() => expect(document.activeElement).toBe(trigger));
        // No purge mutation should have been issued on cancel.
        expect(mockPurgeAllPages).not.toHaveBeenCalled();
    });
});
