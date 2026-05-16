package org.jahia.community.modules.customgpt;

import java.util.Locale;

/**
 * Marker interface for a pending CustomGPT operation (index or delete) targeting a single JCR node / language pair.
 */
public interface CustomGptRequest {

    enum RequestType {
        INDEX(0),
        DELETE(1);
        private final int type;
        private final String lowercase;

        RequestType(int type) {
            this.type = type;
            this.lowercase = this.toString().toLowerCase(Locale.ROOT);
        }

        public int getType() {
            return type;
        }

        public String getLowercase() {
            return lowercase;
        }
    }

    RequestType requestType();

}
