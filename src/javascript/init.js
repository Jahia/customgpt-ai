import {registry} from '@jahia/ui-extender';
import register from './CustomGptSettings/register';
import i18next from 'i18next';

export default function initCustomGpt() {
    registry.add('callback', 'customgpt-ai', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('customgpt-ai');
            register();
        }
    });
}
