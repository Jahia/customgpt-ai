package org.jahia.community.modules.customgpt;

import java.util.Locale;

/**
 * Represents a request handled by the CustomGPT indexing service. Implementations
 * describe the operation that should be performed for a given node (for example
 * indexing or deletion).
 *
 * @param <T> concrete request type
 */
public interface CustomGptRequest<T> {

    /**
     * Type of request that can be sent to CustomGPT.
     */
    enum RequestType {
        /** Request for indexing a node. */
        INDEX(0),
        /** Request for deleting a node from the index. */
        DELETE(1);

        private final int type;
        private final String lowercase;

        RequestType(int type) {
            this.type = type;
            this.lowercase = this.toString().toLowerCase(Locale.ROOT);
        }

        /**
         * Numerical value associated with this request type.
         *
         * @return the numeric representation of the type
         */
        public int getType() {
            return type;
        }

        /**
         * Lower case representation of the enum value.
         *
         * @return the lower case string of the type
         */
        public String getLowercase() {
            return lowercase;
        }
    }

    /**
     * The type of request represented by this instance.
     *
     * @return the request type
     */
   public RequestType requestType();

}
