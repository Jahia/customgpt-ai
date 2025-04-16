package org.jahia.community.modules.customgpt.settings;

import com.github.rholder.retry.WaitStrategy;
import com.google.gwt.thirdparty.guava.common.base.Splitter;
import java.util.*;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import org.apache.commons.lang.StringUtils;
import org.jahia.community.modules.customgpt.CustomGptConstants;
import org.jahia.osgi.FrameworkService;
import org.jahia.services.content.nodetypes.NodeTypeRegistry;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = {ManagedService.class, Config.class}, property = {
    "service.pid=org.jahia.community.modules.customgpt",
    "service.description=CustomGPT.ai configuration service",
    "service.vendor=Jahia Solutions Group SA"
}, immediate = true)
public class Config implements ManagedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(Config.class);
    private static final String JMIX_MAIN_RESOURCE = "jmix:mainResource";
    private static final int FIVE_HUNDRED = 500;

    private enum WaitStrategyType {
        FIBONACCI, EXPONENTIAL, FIXED
    }

    public enum MergeStrategy {
        MERGE,
        OVERRIDE;

        private static MergeStrategy makeStrategy(String strategy) {
            switch (strategy) {
                case "OVERRIDE":
                    return MergeStrategy.OVERRIDE;
                case "MERGE":
                default:
                    return MergeStrategy.MERGE;
            }
        }
    }

    private static final String CONFIG_NAMESPACE_PREFIX = "org.jahia.community.modules.customgpt";
    private static final String PROP_CONTENT_INDEXED_SUB_NODE_TYPES = CONFIG_NAMESPACE_PREFIX + ".content.indexedSubNodeTypes";
    private static final String PROP_CONTENT_INDEXED_MAIN_RESOURCE_TYPES = CONFIG_NAMESPACE_PREFIX + ".content.indexedMainResourceTypes";
    private static final String PROP_GUSTOM_GPT_PROJECT_ID = CONFIG_NAMESPACE_PREFIX + ".projectId";
    private static final String PROP_GUSTOM_GPT_TOKEN = CONFIG_NAMESPACE_PREFIX + ".token";
    private static final String PROP_JAHIA_USERNAME = CONFIG_NAMESPACE_PREFIX + ".jahia.username";
    private static final String PROP_JAHIA_PASSWORD = CONFIG_NAMESPACE_PREFIX + ".jahia.password";
    private static final String CONTENT_INDEXED_FILE_EXTENSIONS = CONFIG_NAMESPACE_PREFIX + ".content.indexedFileExtensions";
    private static final String BULK_OPERATIONS_BATCH_SIZE = CONFIG_NAMESPACE_PREFIX + ".operations.batch.size";
    private static final String REPLAY_MISSED_JCR_EVENTS = CONFIG_NAMESPACE_PREFIX + ".replayMissedJcrEvents";
    private static final String JOURNAL_EVENT_READER_KEY = CONFIG_NAMESPACE_PREFIX + ".journalEventReaderKey";
    private static final String REINDEX_PARENT_IF_MISSING = CONFIG_NAMESPACE_PREFIX + ".reindexParentIfMissing";
    private static final String SCHEDULE_JOB_ASAP = CONFIG_NAMESPACE_PREFIX + ".scheduleJobASAP";

    private Set<String> contentIndexedMainResources;
    private Set<String> contentIndexedSubNodes;
    private Set<String> indexedFileExtensions;
    private String journalEventReaderKey;
    private WaitStrategy waitStrategy;
    private boolean configured = false;
    private boolean reindexParentIfMissing;
    private boolean replayMissedJcrEvents;
    private boolean scheduleJobASAP;
    private int bulkOperationsBatchSize;
    private int maximumExtractedSize;
    private String customGptProjectId;
    private String customGptToken;
    private String jahiaUsername;
    private String jahiaPassword;

    @Override
    public void updated(Dictionary<String, ?> properties) throws ConfigurationException {
        if (properties == null) {
            return;
        }
        parse(properties);
        configured = true;
        FrameworkService.sendEvent(CustomGptConstants.EVENT_TOPIC,
                Collections.singletonMap("type", CustomGptConstants.EVENT_TYPE_CONFIG_UPDATED), true);
        LOGGER.info("CustomGpt configuration loaded");
    }

    public Set<String> getContentIndexedSubNodes() throws NotConfiguredException {
        checkConfigured();
        return new LinkedHashSet<>(contentIndexedSubNodes);
    }

    public Set<String> getContentIndexedMainResources() throws NotConfiguredException {
        checkConfigured();
        return new LinkedHashSet<>(contentIndexedMainResources);
    }

    public Set<String> getIndexedFileExtensions() {
        return new LinkedHashSet<>(indexedFileExtensions);
    }

    public WaitStrategy getWaitStrategy() throws NotConfiguredException {
        checkConfigured();
        return waitStrategy;
    }

    public int getBulkOperationsBatchSize() throws NotConfiguredException {
        checkConfigured();
        return bulkOperationsBatchSize;

    }

    public int getMaximumExtractedSize() {
        return maximumExtractedSize;
    }

    private void checkConfigured() throws NotConfiguredException {
        if (!configured) {
            throw new NotConfiguredException("customGpt search provider is not configured");
        }
    }

    private void parse(Dictionary<String, ?> properties) {
        // Populate sets of properties
        contentIndexedSubNodes = splitNodeTypeByComma((String) properties.get(PROP_CONTENT_INDEXED_SUB_NODE_TYPES));
        contentIndexedMainResources = splitNodeTypeByComma((String) properties.get(PROP_CONTENT_INDEXED_MAIN_RESOURCE_TYPES));
        updateSetToExcludeMainResourceType(contentIndexedMainResources);
        bulkOperationsBatchSize = getInt(properties, BULK_OPERATIONS_BATCH_SIZE, FIVE_HUNDRED);

        final String indexedFiles = (String) properties.get(CONTENT_INDEXED_FILE_EXTENSIONS);
        if (StringUtils.isEmpty(StringUtils.trim(indexedFiles))) {
            indexedFileExtensions = Collections.emptySet();
        } else {
            indexedFileExtensions = new LinkedHashSet<>(Splitter.on(",")
                    .omitEmptyStrings()
                    .trimResults()
                    .splitToList(indexedFiles == null ? "*" : indexedFiles));
        }

        replayMissedJcrEvents = getBoolean(properties, REPLAY_MISSED_JCR_EVENTS, true);
        journalEventReaderKey = getString(properties, JOURNAL_EVENT_READER_KEY, CustomGptConstants.CUSTOM_GPT_MODULE_NAME);

        reindexParentIfMissing = getBoolean(properties, REINDEX_PARENT_IF_MISSING, true);
        scheduleJobASAP = getBoolean(properties, SCHEDULE_JOB_ASAP, false);

        customGptProjectId = getString(properties, PROP_GUSTOM_GPT_PROJECT_ID, "");
        customGptToken = getString(properties, PROP_GUSTOM_GPT_TOKEN, "");
        jahiaUsername = getString(properties, PROP_JAHIA_USERNAME, "");
        jahiaPassword = getString(properties, PROP_JAHIA_PASSWORD, "");
    }

    private Set<String> splitNodeTypeByComma(String commaSeparated) {
        final Set<String> nodetypes = new LinkedHashSet<>();
        if (StringUtils.isEmpty(commaSeparated)) {
            return nodetypes;
        }
        for (String nodeType : Splitter.on(",").omitEmptyStrings().trimResults().splitToList(commaSeparated)) {
            try {
                NodeTypeRegistry.getInstance().getNodeType(nodeType);
                nodetypes.add(nodeType);
            } catch (RepositoryException e) {
                LOGGER.warn("unable to register nodetype [{}] from config file. {}", nodeType, e.getMessage());
            }
        }

        return nodetypes;
    }

    private int getInt(Dictionary<String, ?> properties, String key, int def) {
        if (properties.get(key) != null) {
            final Object val = properties.get(key);
            if (val instanceof Number) {
                return ((Number) val).intValue();
            } else if (val != null) {
                return Integer.parseInt(val.toString());
            }
        }
        return def;
    }

    private boolean getBoolean(Dictionary<String, ?> properties, String key, boolean def) {
        if (properties.get(key) != null) {
            final Object val = properties.get(key);
            if (val instanceof Boolean) {
                return (Boolean) val;
            } else if (val != null) {
                return Boolean.parseBoolean(val.toString());
            }
        }
        return def;
    }

    private String getString(Dictionary<String, ?> properties, String key, String def) {
        if (properties.get(key) != null) {
            final Object val = properties.get(key);
            if (val != null) {
                return val.toString();
            }
        }
        return def;
    }

    public boolean isConfigured() {
        return configured;
    }

    private void updateSetToExcludeMainResourceType(Set<String> set) {
        // Main resource is not set so don't try to exclude types that may extend it
        if (!set.contains(JMIX_MAIN_RESOURCE)) {
            return;
        }

        set.removeIf(type -> {
            try {
                return !type.equals(JMIX_MAIN_RESOURCE) && NodeTypeRegistry.getInstance().getNodeType(type).isNodeType(JMIX_MAIN_RESOURCE);
            } catch (NoSuchNodeTypeException e) {
                LOGGER.error("Failed to get information about node type: {}", e.getMessage());
                return true;
            }
        });
    }

    public boolean isReplayMissedJcrEvents() {
        return replayMissedJcrEvents;
    }

    public String getJournalEventReaderKey() {
        return journalEventReaderKey;
    }

    public boolean isReindexParentIfMissing() {
        return reindexParentIfMissing;
    }

    public boolean isScheduleJobASAP() {
        return scheduleJobASAP;
    }

    public String getCustomGptProjectId() {
        return customGptProjectId;
    }

    public String getCustomGptToken() {
        return customGptToken;
    }

    public String getJahiaUsername() {
        return jahiaUsername;
    }

    public String getJahiaPassword() {
        return jahiaPassword;
    }
}
