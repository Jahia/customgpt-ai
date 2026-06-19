module.exports = {
    testEnvironment: 'jsdom',
    setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
    // Ignore build output so Jest's haste map doesn't collide on the copied package.json.
    modulePathIgnorePatterns: ['<rootDir>/target/', '<rootDir>/src/main/resources/javascript/apps/'],
    moduleNameMapper: {
        // CSS-module imports resolve to a proxy returning the key as the class name.
        '\\.(scss|css)$': 'identity-obj-proxy'
    },
    transform: {
        // Isolated Babel config (configFile/babelrc false) so Jest never inherits the
        // webpack babel-loader pipeline or any root Babel config.
        '^.+\\.[jt]sx?$': ['babel-jest', {
            configFile: false,
            babelrc: false,
            presets: [
                ['@babel/preset-env', {targets: {node: 'current'}}],
                ['@babel/preset-react', {runtime: 'classic'}]
            ]
        }]
    },
    testMatch: ['**/?(*.)+(test).[jt]s?(x)'],
    collectCoverageFrom: [
        'src/javascript/CustomGptSettings/**/*.{js,jsx}'
    ]
};
