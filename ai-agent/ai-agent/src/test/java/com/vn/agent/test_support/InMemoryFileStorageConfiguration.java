package com.vn.agent.test_support;

import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only {@link FileStorage} bean for module tests that exercise task-file blobs.
 */
@TestConfiguration
public class InMemoryFileStorageConfiguration {

    @Bean
    FileStorage inMemoryFileStorage() {
        return new InMemoryFileStorage();
    }

    private static final class InMemoryFileStorage implements FileStorage {

        private static final String STORAGE_NAME = "fs";

        private final Map<String, byte[]> filesByPath = new ConcurrentHashMap<>();

        @Override
        @NonNull
        public String getStorageName() {
            return STORAGE_NAME;
        }

        @Override
        @NonNull
        public FileRef saveStream(@NonNull String fileName, InputStream inputStream,
                                  @NonNull Map<String, Object> parameters) {
            try {
                String path = UUID.randomUUID() + "-" + fileName;
                filesByPath.put(path, inputStream.readAllBytes());
                return new FileRef(STORAGE_NAME, path, fileName);
            } catch (IOException e) {
                throw new FileStorageException(FileStorageException.Type.IO_EXCEPTION, fileName, e);
            }
        }

        @Override
        @NonNull
        public InputStream openStream(@NonNull FileRef reference) {
            byte[] bytes = filesByPath.get(reference.getPath());
            if (bytes == null) {
                throw new FileStorageException(FileStorageException.Type.FILE_NOT_FOUND,
                        reference.getFileName());
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void removeFile(@NonNull FileRef reference) {
            byte[] removed = filesByPath.remove(reference.getPath());
            if (removed == null) {
                throw new FileStorageException(FileStorageException.Type.FILE_NOT_FOUND,
                        reference.getFileName());
            }
        }

        @Override
        public boolean fileExists(@NonNull FileRef reference) {
            return filesByPath.containsKey(reference.getPath());
        }
    }
}
