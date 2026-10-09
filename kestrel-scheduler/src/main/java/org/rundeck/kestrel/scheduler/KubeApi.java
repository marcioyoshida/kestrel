package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * The few Kubernetes API calls Kestrel needs (Leases and CronJobs in one namespace).
 * Paths are API paths such as {@code /apis/batch/v1/namespaces/kestrel/cronjobs}.
 */
public interface KubeApi {

    /**
     * @param path object path
     * @return the object, or null if it does not exist
     */
    ObjectNode get(String path) throws IOException;

    /**
     * @param collectionPath collection path, optionally with a query string
     * @return the list object (with {@code items})
     */
    ObjectNode list(String collectionPath) throws IOException;

    /**
     * Creates an object.
     *
     * @throws ConflictException if it already exists
     */
    ObjectNode create(String collectionPath, ObjectNode body) throws IOException;

    /**
     * Replaces an object. The body must carry {@code metadata.resourceVersion}.
     *
     * @throws ConflictException if the object changed since that version
     */
    ObjectNode replace(String path, ObjectNode body) throws IOException;

    /**
     * Deletes an object (background propagation). Missing objects are ignored.
     */
    void delete(String path) throws IOException;

    /** Optimistic-concurrency failure (HTTP 409). */
    class ConflictException extends IOException {
        /** @param message detail */
        public ConflictException(String message) {
            super(message);
        }
    }
}
