import {registry} from '@jahia/ui-extender';
import {CustomGptSettingsAdmin} from './CustomGptSettings';
import React from 'react';

export default () => {
    console.debug('%c customgpt-ai: activation in progress', 'color: #006633');
    registry.add('adminRoute', 'customgptAiSettings', {
        targets: ['administration-server-configuration:25'],
        requiredPermission: 'customGptAdmin',
        label: 'customgpt-ai:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(CustomGptSettingsAdmin)
    });
};
