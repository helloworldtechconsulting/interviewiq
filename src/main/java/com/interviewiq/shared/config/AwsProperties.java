package com.interviewiq.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for AWS services (S3, SES, Transcribe).
 *
 * <p>Bound to the {@code app.aws} namespace in YAML / environment variables.
 *
 * <p>Local stub mode: when {@code use-local-stub=true}, all S3 and SES calls
 * are replaced by log statements. No real AWS credentials are required in
 * local development.
 */
@ConfigurationProperties(prefix = "app.aws")
public class AwsProperties {

    /**
     * AWS region for all SDK clients. Defaults to ap-south-1.
     * Override via {@code APP_AWS_REGION} in production.
     */
    private String region = "ap-south-1";

    /**
     * Default S3 bucket for application objects (resumes, JDs, transcripts).
     * Override via {@code APP_AWS_S3_BUCKET} in production.
     */
    private String s3Bucket = "";

    /**
     * SES verified sender address. All outbound emails use this as the From address.
     * Override via {@code APP_AWS_SES_FROM_ADDRESS} in production.
     */
    private String sesFromAddress = "noreply@interviewiq.ai";

    /**
     * When true, all AWS calls are stubbed with log statements.
     * Must be false in staging and production.
     */
    private boolean useLocalStub = false;

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getSesFromAddress() { return sesFromAddress; }
    public void setSesFromAddress(String sesFromAddress) { this.sesFromAddress = sesFromAddress; }

    public boolean isUseLocalStub() { return useLocalStub; }
    public void setUseLocalStub(boolean useLocalStub) { this.useLocalStub = useLocalStub; }
}
