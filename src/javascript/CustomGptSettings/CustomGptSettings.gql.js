import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query CustomGptGetSettings {
        admin {
            customGpt {
                settings {
                    contentIndexedMainResourceTypes
                    contentIndexedSubNodeTypes
                    contentIndexedFileExtensions
                    operationsBatchSize
                    projectId
                    projectName
                    token
                    jahiaApiToken
                    jahiaGraphqlEndpoint
                    userAgent
                    serverName
                    siteServerNames
                    dryRun
                    scheduleJobASAP
                    apiBaseUrl
                    rateLimitRequestsPerSecond
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
        $jahiaApiToken: String,
        $jahiaGraphqlEndpoint: String,
        $dryRun: Boolean,
        $scheduleJobASAP: Boolean,
        $apiBaseUrl: String,
        $rateLimitRequestsPerSecond: Int,
        $userAgent: String,
        $serverName: String,
        $siteServerNames: String
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
                    jahiaApiToken: $jahiaApiToken,
                    jahiaGraphqlEndpoint: $jahiaGraphqlEndpoint,
                    dryRun: $dryRun,
                    scheduleJobASAP: $scheduleJobASAP,
                    apiBaseUrl: $apiBaseUrl,
                    rateLimitRequestsPerSecond: $rateLimitRequestsPerSecond,
                    userAgent: $userAgent,
                    serverName: $serverName,
                    siteServerNames: $siteServerNames
                )
            }
        }
    }
`;
