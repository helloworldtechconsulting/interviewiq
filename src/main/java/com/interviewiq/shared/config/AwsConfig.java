package com.interviewiq.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * Creates AWS SDK v2 client beans.
 *
 * <p>Local stub mode: {@link AwsProperties#isUseLocalStub()} = {@code true} injects
 * static dummy credentials so clients initialise without requiring real AWS credentials
 * on developer machines. The credential values are never actually sent to AWS because
 * {@link com.interviewiq.storage.service.StorageService} and
 * {@link com.interviewiq.email.service.EmailService} short-circuit before making any
 * SDK call when the stub flag is set.
 *
 * <p>Production: clients use {@link DefaultCredentialsProvider}, which resolves
 * credentials from environment variables, instance profile, or ECS task role in order.
 */
@Configuration
public class AwsConfig {

    private final AwsProperties props;

    public AwsConfig(AwsProperties props) {
        this.props = props;
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    @Bean
    public SesClient sesClient() {
        return SesClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentialsProvider())
                .build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        if (props.isUseLocalStub()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("local-stub-key", "local-stub-secret"));
        }
        return DefaultCredentialsProvider.create();
    }
}
