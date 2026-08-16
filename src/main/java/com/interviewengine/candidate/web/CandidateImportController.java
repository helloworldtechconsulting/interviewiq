package com.interviewengine.candidate.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.candidate.service.CandidateCsvParser;
import com.interviewengine.candidate.service.CandidateCsvParser.ParsedCsv;
import com.interviewengine.candidate.service.CandidateImportService;
import com.interviewengine.candidate.service.CandidateImportService.ImportPreview;
import com.interviewengine.candidate.service.CandidateImportService.ImportResult;
import com.interviewengine.shared.dto.ApiResponse;
import com.interviewengine.shared.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk candidate import (PRD v2.1 §7.3.1, §11 — three new endpoints).
 *
 * <ul>
 *   <li>{@code POST /api/v1/candidates/import/mapping}  — propose a column mapping</li>
 *   <li>{@code POST /api/v1/candidates/import/preview}  — validate; charges nothing</li>
 *   <li>{@code POST /api/v1/candidates/import/{id}/confirm} — atomic batch reservation</li>
 * </ul>
 *
 * <p>The three steps are separate on purpose, and the separation is the feature.
 * Mapping is proposed and confirmed rather than guessed; preview reports exactly
 * what will happen and takes no money; confirm reserves for the whole batch or
 * refuses it. Collapsing any two of them would reintroduce the half-imported
 * opening the spec exists to prevent.
 *
 * <p>The file is re-uploaded at each step rather than held server-side. A parked
 * upload would need storage, a TTL and a cleanup job for a file that is a few KB
 * and already sitting in the recruiter's browser.
 */
@RestController
@RequestMapping("/api/v1/candidates/import")
public class CandidateImportController {

    private static final Logger log = LoggerFactory.getLogger(CandidateImportController.class);

    /** 200 candidates of name, email and phone is a few tens of KB. */
    private static final long MAX_CSV_BYTES = 1024 * 1024;

    private final CandidateCsvParser parser;
    private final CandidateImportService importService;
    private final ObjectMapper objectMapper;

    public CandidateImportController(CandidateCsvParser parser,
                                     CandidateImportService importService,
                                     ObjectMapper objectMapper) {
        this.parser        = parser;
        this.importService = importService;
        this.objectMapper  = objectMapper;
    }

    /**
     * Reads the header row and proposes a mapping.
     *
     * <p>Fields with no confident match are simply absent from the proposal,
     * which is what prompts the UI to ask rather than assume — §7.3.1 is explicit
     * that "we do not guess silently".
     */
    @PostMapping("/mapping")
    public ApiResponse<Map<String, Object>> proposeMapping(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jobOpeningId") UUID jobOpeningId) {

        List<String> header = parser.readHeader(streamOf(file));
        return ApiResponse.ok(Map.of(
                "header", header,
                "proposedMapping", parser.proposeMapping(header)));
    }

    /**
     * Validates the file and reports what an import would do.
     *
     * <p>No wallet reservation is taken here. That is the point of a separate
     * step: the recruiter sees "47 valid, 3 duplicates, 2 invalid emails" and can
     * fix or skip rows before committing anything.
     */
    @PostMapping("/preview")
    public ApiResponse<ImportPreview> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jobOpeningId") UUID jobOpeningId,
            @RequestParam("mapping") String mappingJson) {

        ParsedCsv parsed = parser.parse(streamOf(file), readMapping(mappingJson));

        return ApiResponse.ok(importService.preview(
                jobOpeningId, originalName(file), readMapping(mappingJson), parsed.rows()));
    }

    /**
     * Imports, reserving for the whole batch first.
     *
     * <p>All-or-nothing inside one transaction: an insufficient balance rolls the
     * import back entirely and the opening is left exactly as it was.
     */
    @PostMapping("/{batchId}/confirm")
    public ApiResponse<ImportResult> confirm(
            @PathVariable UUID batchId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mapping") String mappingJson) {

        ParsedCsv parsed = parser.parse(streamOf(file), readMapping(mappingJson));

        ImportResult result = importService.confirmImport(batchId, parsed.rows());
        log.info("Bulk import confirmed: batchId={} imported={}", batchId, result.importedCount());
        return ApiResponse.ok(result);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private java.io.InputStream streamOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Please choose a CSV file to upload.");
        }
        if (file.getSize() > MAX_CSV_BYTES) {
            throw new ValidationException("The file is too large. A candidate CSV should be under 1 MB.");
        }
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new ValidationException("The file could not be read. Please check it is a valid CSV.");
        }
    }

    private Map<String, Integer> readMapping(String mappingJson) {
        try {
            return objectMapper.readValue(mappingJson, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            throw new ValidationException("The column mapping could not be read.");
        }
    }

    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "candidates.csv" : name;
    }
}
