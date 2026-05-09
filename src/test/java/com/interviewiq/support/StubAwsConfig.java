package com.interviewiq.support;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * Test-only configuration that supplies Mockito-stub replacements for every
 * AWS SDK client bean. This guarantees no integration test ever attempts to
 * reach a real AWS endpoint, even if the production {@code AwsConfig} is
 * unintentionally on the classpath.
 *
 * <p>Each {@code @Bean} is marked {@code @Primary} so it wins over the
 * production bean when both are present.
 */
@TestConfiguration
public class StubAwsConfig {

    @Bean
    @Primary
    public S3Client testS3Client() {
        return Mockito.mock(S3Client.class);
    }

    @Bean
    @Primary
    public S3Presigner testS3Presigner() {
        return Mockito.mock(S3Presigner.class);
    }

    @Bean
    @Primary
    public SesClient testSesClient() {
        return Mockito.mock(SesClient.class);
    }

    @Bean
    @Primary
    public PollyClient testPollyClient() {
        return Mockito.mock(PollyClient.class);
    }
}
