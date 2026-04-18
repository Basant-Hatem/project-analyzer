package com.analyzer.controller;

import com.analyzer.ProjectAnalyzer;
import com.analyzer.model.AnalysisReport;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/analyzer")
@RequiredArgsConstructor
@Validated
public class AnalyzerController {

    private final ProjectAnalyzer projectAnalyzer;

    /**
     * POST /api/v1/analyzer/analyze
     *
     * {
     *   "projectPath": "/path/to/java/project",
     *   "autoFix": false,
     *   "sonarProjectKey": "my-project"   (optional)
     * }
     */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest request) {

        if (!Files.exists(Paths.get(request.projectPath()))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Path does not exist: " + request.projectPath()));
        }

        log.info("Analyze request: path={}, autoFix={}", request.projectPath(), request.autoFix());

        AnalysisReport report = projectAnalyzer.analyze(
                request.projectPath(),
                request.autoFix(),
                request.sonarProjectKey());

        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/v1/analyzer/health
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "Project Analyzer",
                "version", "1.0.0"
        ));
    }

    /**
     * GET /api/v1/analyzer/tools
     * يشوف إيه الـ tools المتاحة
     */
    @GetMapping("/tools")
    public ResponseEntity<?> checkTools() {
        // نعمل quick check بدون تحليل مشروع
        return ResponseEntity.ok(Map.of(
                "java",  System.getProperty("java.version"),
                "info",  "Use POST /analyze to check tool availability per project"
        ));
    }

    // ── Request record ────────────────────────────────────────────────────

    public record AnalyzeRequest(
            @NotBlank String projectPath,
            boolean autoFix,
            String sonarProjectKey
    ) {}
}
