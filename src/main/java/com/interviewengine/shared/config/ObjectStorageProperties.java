package com.interviewengine.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible object storage configuration (PRD v2.1 §6.1, §9.2; Arch v4.0 §3).
 *
 * <p>Bound to {@code app.storage}. Replaces the AWS-specific {@code app.aws}
 * namespace for storage concerns.
 *
 * <p>The portability move here is small and high-value: "One SDK + an endpoint
 * override runs on S3, GCS interop, R2, Spaces, MinIO, OCI." Arch v4.0 costs it
 * at about half a day, and it is what makes the storage layer genuinely
 * cloud-agnostic rather than nominally so.
 *
 * <p><strong>Cloudflare R2 charges zero egress</strong>, which is material here:
 * recording playback is the only meaningful egress this product generates, and a
 * 30-minute interview is roughly 135 MB.
 */
@ConfigurationProperties(prefix = "app.storage")
public class ObjectStorageProperties {

    /**
     * The S3-compatible API endpoint.
     *
     * <p>Empty means real AWS S3, resolved from the region. Set it for anything
     * else: {@code https://<account>.r2.cloudflarestorage.com} for R2,
     * {@code https://storage.googleapis.com} for GCS interop,
     * {@code https://<region>.digitaloceanspaces.com} for Spaces, or a local
     * MinIO URL in development.
     */
    private String endpoint = "";

    /**
     * Region name. Still required by the SDK's signing logic even where the
     * provider ignores it — R2 expects {@code auto}.
     */
    private String region = "ap-south-1";

    private String bucket = "";

    /**
     * Static credentials for a non-AWS provider.
     *
     * <p>Left empty on AWS so the default credential chain (environment,
     * instance profile, IRSA) applies — Arch v4.0 §8 requires no static cloud
     * keys anywhere, and that remains true on AWS. Non-AWS S3-compatible stores
     * have no such chain, so their keys arrive here from a Kubernetes Secret via
     * the External Secrets Operator.
     */
    private String accessKey = "";

    private String secretKey = "";

    /**
     * Path-style addressing ({@code endpoint/bucket/key}) rather than
     * virtual-host style ({@code bucket.endpoint/key}).
     *
     * <p>Required by MinIO and by most S3-compatible stores, and harmless on
     * providers that support both. Defaults on, because the failure mode when it
     * is wrong — DNS resolution failures against a bucket-prefixed hostname — is
     * confusing enough to be worth defaulting to the compatible choice.
     */
    private boolean pathStyleAccess = true;

    /**
     * When true, storage calls are replaced by log statements. Local development
     * only; must be false in staging and production.
     */
    private boolean useLocalStub = false;

    /** Recording retention in days, stated in the candidate consent notice (§7.5.3). */
    private int recordingRetentionDays = 7;

    /** Whether static credentials were supplied, i.e. a non-AWS provider. */
    public boolean hasStaticCredentials() {
        return !accessKey.isBlank() && !secretKey.isBlank();
    }

    /** Whether an explicit endpoint override was supplied. */
    public boolean hasEndpointOverride() {
        return !endpoint.isBlank();
    }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public boolean isUseLocalStub() { return useLocalStub; }
    public void setUseLocalStub(boolean useLocalStub) { this.useLocalStub = useLocalStub; }

    public int getRecordingRetentionDays() { return recordingRetentionDays; }
    public void setRecordingRetentionDays(int recordingRetentionDays) { this.recordingRetentionDays = recordingRetentionDays; }
}
