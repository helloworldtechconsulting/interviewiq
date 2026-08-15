package com.interviewiq.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the S3-compatible storage clients (PRD v2.1 §9.2; Arch v4.0 §3, §8).
 *
 * <p>Replaces the AWS-only {@code AwsConfig}. The change is deliberately small —
 * "the code was never as AWS-locked as it looked" — and consists of an endpoint
 * override plus path-style addressing, which between them let the same code run
 * against S3, GCS interop, Cloudflare R2, DigitalOcean Spaces, MinIO or OCI.
 *
 * <p>Credentials follow the same principle. On AWS the default chain applies, so
 * Arch v4.0 §8's "no static cloud keys anywhere" holds; on other providers there
 * is no such chain, and their keys arrive from a Kubernetes Secret through the
 * External Secrets Operator — the application only ever sees environment
 * variables either way.
 */
@Configuration
public class ObjectStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageConfig.class);

    private final ObjectStorageProperties props;

    public ObjectStorageConfig(ObjectStorageProperties props) {
        this.props = props;
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration());

        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
            log.info("Object storage: endpoint override {} (path-style={})",
                    props.getEndpoint(), props.isPathStyleAccess());
        } else {
            log.info("Object storage: AWS S3 in region {}", props.getRegion());
        }
        return builder.build();
    }

    /**
     * The presigner must carry the same endpoint and addressing configuration as
     * the client.
     *
     * <p>If they diverge, pre-signed URLs are generated against the wrong host and
     * the browser upload fails with a signature error that looks like a
     * credential problem — an unpleasant thing to debug, and the reason both are
     * built from one properties object here.
     */
    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentialsProvider())
                .serviceConfiguration(serviceConfiguration());

        if (props.hasEndpointOverride()) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
        }
        return builder.build();
    }

    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(props.isPathStyleAccess())
                // Non-AWS stores generally do not implement the AWS-specific
                // chunked-encoding checksum behaviour, and enabling it produces
                // opaque upload failures against them.
                .chunkedEncodingEnabled(false)
                .build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        if (props.isUseLocalStub()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("local-stub-key", "local-stub-secret"));
        }
        if (props.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
        }
        // AWS: environment, instance profile or IRSA, in that order.
        return DefaultCredentialsProvider.create();
    }
}
