package org.jahia.community.modules.customgpt.settings;

/** Thrown when a method requires the OSGi configuration to be loaded but {@link Config#updated} has not yet been called. */
public class NotConfiguredException extends Exception {

    private static final long serialVersionUID = 3840692546999664711L;

    public NotConfiguredException(String message) {
        super(message);
    }
}
