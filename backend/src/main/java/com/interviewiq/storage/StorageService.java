package com.interviewiq.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final Storage storage;
    private final Tika tika;

    @Value("${app.gcs.bucket-name}")
    private String bucketName;

    public String uploadFile(String path, MultipartFile file) throws IOException {
        try {
            BlobId blobId = BlobId.of(bucketName, path);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(file.getContentType())
                    .build();

            storage.create(blobInfo, file.getBytes());
            log.info("File uploaded to GCS: {}", path);

            return "gs://" + bucketName + "/" + path;
        } catch (IOException e) {
            log.error("Failed to upload file to GCS: {}", path, e);
            throw e;
        }
    }

    public String extractTextFromFile(MultipartFile file) throws IOException {
        try {
            String text = tika.parseToString(file.getInputStream());
            log.info("Text extracted from file: {}", file.getOriginalFilename());
            return text;
        } catch (TikaException | SAXException e) {
            log.warn("Failed to extract text from file: {}", file.getOriginalFilename(), e);
            return "";
        }
    }

    public byte[] downloadFile(String gcsPath) throws IOException {
        try {
            String path = extractPathFromGcsUri(gcsPath);
            BlobId blobId = BlobId.of(bucketName, path);
            Blob blob = storage.get(blobId);

            if (blob == null) {
                throw new IOException("File not found: " + gcsPath);
            }

            byte[] content = blob.getContent();
            log.info("File downloaded from GCS: {}", gcsPath);
            return content;
        } catch (Exception e) {
            log.error("Failed to download file from GCS: {}", gcsPath, e);
            throw new IOException("Failed to download file", e);
        }
    }

    public void deleteFile(String gcsPath) {
        try {
            String path = extractPathFromGcsUri(gcsPath);
            BlobId blobId = BlobId.of(bucketName, path);
            boolean deleted = storage.delete(blobId);

            if (deleted) {
                log.info("File deleted from GCS: {}", gcsPath);
            } else {
                log.warn("File not found for deletion: {}", gcsPath);
            }
        } catch (Exception e) {
            log.error("Failed to delete file from GCS: {}", gcsPath, e);
        }
    }

    public boolean fileExists(String gcsPath) {
        try {
            String path = extractPathFromGcsUri(gcsPath);
            BlobId blobId = BlobId.of(bucketName, path);
            Blob blob = storage.get(blobId);
            return blob != null && blob.exists();
        } catch (Exception e) {
            log.warn("Error checking file existence: {}", gcsPath, e);
            return false;
        }
    }

    private String extractPathFromGcsUri(String gcsUri) {
        if (gcsUri.startsWith("gs://")) {
            return gcsUri.substring(5 + bucketName.length() + 1);
        }
        return gcsUri;
    }

    @org.springframework.context.annotation.Bean
    public Tika tika() {
        return new Tika();
    }
}
