package org.jahia.community.modules.customgpt;

/**
 * Container for constants used by the CustomGPT module.
 */
public class CustomGptConstants {

    /**
     * Types of indexing supported by the module.
     */
    public enum IndexType {
        FILE("file"),
        CONTENT("content");

        private final String type;

        IndexType(String type) {
            this.type = type;
        }

        /**
         * Returns the type as it should be sent to the API.
         *
         * @return string representation of the index type
         */
        public String getType() {
            return type;
        }
    }

    /**
     * Available configuration sections.
     */
    public enum CustomGptConfigurationType {
        MAPPING,
        SETTINGS
    }
    public static final int MAX_RETRIES = 3;
    public static final String PATH_DELIMITER = "/";
    public static final String CUSTOM_GPT_MODULE_NAME = "customgpt-ai";
    public static final String EVENT_TOPIC = "org/jahia/community/modules/" + CUSTOM_GPT_MODULE_NAME;
    public static final String EVENT_TYPE_CONFIG_UPDATED = "configurationUpdated";
    public static final String EVENT_TYPE_CONFIG_UPDATED_REQUIRE_REINDEX = "configurationUpdatedRequireReindex";
    public static final String EVENT_TYPE_TRANSPORT_CLIENT_SERVICE_AVAILABLE = "customGptTransportClientServiceAvailable";
    public static final String MIX_CUSTOM_GPT_INDEXED = "jmix:customGptIndexed";
    public static final String MIX_CUSTOM_GPT_FILE_INDEXED = "jmix:customGptFileIndexed";
    public static final String MIX_INDEXABLE_SITE = "jmix:customGptIndexableSite";
    public static final String MIX_SKIP_INDEX = "jmix:skipCustomGptIndexation";
    public static final String PATH_SITES = "/sites/";
    public static final String PROP_CUSTOM_GPT_PAGE_ID = "customGptPageId";
    public static final String PROP_SITE_KEY = "siteKey";
    public static final String PERM_SITE_ADMIN = "site-admin";


    private CustomGptConstants() {
        throw new IllegalStateException("Utility class");
    }
}
