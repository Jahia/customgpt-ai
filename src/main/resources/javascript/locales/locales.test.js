const en = require('./en.json');
const fr = require('./fr.json');
const de = require('./de.json');

// F10 (structural parity half): recursively flattens an object's keys into a sorted, dotted-path list so
// key presence can be compared independent of value content — guards against a translator adding a French
// label without the corresponding English/German key (or vice versa).
function flattenKeys(obj, prefix = '') {
    return Object.keys(obj).reduce((keys, key) => {
        const path = prefix ? `${prefix}.${key}` : key;
        const value = obj[key];
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
            return keys.concat(flattenKeys(value, path));
        }

        return keys.concat(path);
    }, []).sort();
}

describe('i18n locale key parity (F10)', () => {
    test('en, fr, and de locale files declare exactly the same set of keys', () => {
        const enKeys = flattenKeys(en);
        const frKeys = flattenKeys(fr);
        const deKeys = flattenKeys(de);

        expect(frKeys).toEqual(enKeys);
        expect(deKeys).toEqual(enKeys);
    });

    function assertAllValuesAreNonEmptyStrings(resource) {
        flattenKeys(resource).forEach(path => {
            const value = path.split('.').reduce((acc, segment) => acc[segment], resource);
            expect(typeof value).toBe('string');
            expect(value.trim().length).toBeGreaterThan(0);
        });
    }

    test('en locale has no empty or non-string translation value', () => {
        assertAllValuesAreNonEmptyStrings(en);
    });

    test('fr locale has no empty or non-string translation value', () => {
        assertAllValuesAreNonEmptyStrings(fr);
    });

    test('de locale has no empty or non-string translation value', () => {
        assertAllValuesAreNonEmptyStrings(de);
    });
});
