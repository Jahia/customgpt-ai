import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query {
        admin {
            customGpt {
                settings {
                    contentIndexedMainResourceTypes
                    contentIndexedSubNodeTypes
                    contentIndexedFileExtensions
                    operationsBatchSize
                    projectId
                    token
                    jahiaUsername
                    jahiaPassword
                    jahiaServerCookieName
                    jahiaServerCookieValue
                    jahiaServerCookieDomain
                    dryRun
                    scheduleJobASAP
                    apiBaseUrl
                }
            }
        }
    }
`;

export const PURGE_ALL_PAGES = gql`
    mutation CustomGptPurgeAllPages {
        admin {
            customGpt {
                purgeAllPages
            }
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation CustomGptSaveSettings(
        $contentIndexedMainResourceTypes: String,
        $contentIndexedSubNodeTypes: String,
        $contentIndexedFileExtensions: String,
        $operationsBatchSize: Int,
        $projectId: String,
        $token: String,
        $jahiaUsername: String,
        $jahiaPassword: String,
        $jahiaServerCookieName: String,
        $jahiaServerCookieValue: String,
        $jahiaServerCookieDomain: String,
        $dryRun: Boolean,
        $scheduleJobASAP: Boolean,
        $apiBaseUrl: String
    ) {
        admin {
            customGpt {
                saveSettings(
                    contentIndexedMainResourceTypes: $contentIndexedMainResourceTypes,
                    contentIndexedSubNodeTypes: $contentIndexedSubNodeTypes,
                    contentIndexedFileExtensions: $contentIndexedFileExtensions,
                    operationsBatchSize: $operationsBatchSize,
                    projectId: $projectId,
                    token: $token,
                    jahiaUsername: $jahiaUsername,
                    jahiaPassword: $jahiaPassword,
                    jahiaServerCookieName: $jahiaServerCookieName,
                    jahiaServerCookieValue: $jahiaServerCookieValue,
                    jahiaServerCookieDomain: $jahiaServerCookieDomain,
                    dryRun: $dryRun,
                    scheduleJobASAP: $scheduleJobASAP,
                    apiBaseUrl: $apiBaseUrl
                )
            }
        }
    }
`;
