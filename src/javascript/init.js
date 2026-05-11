import {registry} from '@jahia/ui-extender';
import register from './CustomGptSettings/register';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'customgpt-ai', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('customgpt-ai', () => {
                console.debug('%c customgpt-ai: i18n namespace loaded', 'color: #006633');
            });
            register();
            console.debug('%c customgpt-ai: activation completed', 'color: #006633');
        }
    });
}
