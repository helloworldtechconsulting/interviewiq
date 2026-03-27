package com.interviewiq.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class GcsConfig {

    @Value("${app.gcs.project-id}")
    private String projectId;

    @Bean
    public Storage storage() {
        try {
            log.info("Initializing Google Cloud Storage with project: {}", projectId);
            return StorageOptions.newBuilder()
                    .setProjectId(projectId)
                    .build()
                    .getService();
        } catch (Exception e) {
            log.error("Failed to initialize Google Cloud Storage", e);
            throw new RuntimeException("Failed to initialize Google Cloud Storage", e);
        }
    }
}
