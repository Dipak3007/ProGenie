package com.progenie.shared.storage;

import java.io.InputStream;

/**
 * Private object storage for KYC documents. The local adapter writes to disk; an S3 adapter
 * (SeaweedFS locally, AWS S3 in the cloud) can replace it without touching callers.
 */
public interface FileStorage {

    /** Stores the content under {@code key} (e.g. "kyc/{genieId}/{docId}.pdf"). */
    void put(String key, InputStream content, long size, String contentType);

    /** Opens a stored object for reading. */
    InputStream get(String key);

    void delete(String key);
}
