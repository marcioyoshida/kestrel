package org.rundeck.kestrel.logstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The few object operations the log store needs (S3 in production, a map in tests). */
public interface ObjectStore {
    /** Writes an object (overwrites). */
    void put(String key, InputStream data, long length) throws IOException;

    /**
     * Copies an object into {@code out}.
     *
     * @return false if it does not exist
     */
    boolean get(String key, OutputStream out) throws IOException;

    /** @return true if the object exists */
    boolean exists(String key) throws IOException;

    /** Deletes an object; missing objects are ignored. */
    void delete(String key) throws IOException;
}
