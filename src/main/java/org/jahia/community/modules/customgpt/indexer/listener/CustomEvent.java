package org.jahia.community.modules.customgpt.indexer.listener;

import java.util.HashMap;
import java.util.Map;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import org.jahia.community.modules.customgpt.CustomGptConstants;

public class CustomEvent implements Event {

    private final Map<String, String> info = new HashMap<>();
    private int type;
    private String path;
    private String identifier;
    

    public CustomEvent(int type, String identifier, String path) {
        this.type = type;
        this.path = path;
        this.identifier = identifier;
    }

    public CustomEvent(int type, String identifier, String path, String customGptPageId) {
        this(type, identifier, path);
        info.put(CustomGptConstants.PROP_CUSTOM_GPT_PAGE_ID, customGptPageId);
    }

    @Override
    public int getType() {
        return type;
    }

    @Override
    public String getPath() throws RepositoryException {
        return path;
    }

    @Override
    public String getUserID() {
        return null;
    }

    @Override
    public String getIdentifier() throws RepositoryException {
        return identifier;
    }

    @Override
    public Map getInfo() throws RepositoryException {
        return info;
    }

    @Override
    public String getUserData() throws RepositoryException {
        return null;
    }

    @Override
    public long getDate() throws RepositoryException {
        return 0;
    }
}
