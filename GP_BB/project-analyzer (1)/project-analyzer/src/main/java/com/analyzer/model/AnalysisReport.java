package com.analyzer.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnalysisReport {

    // ── Project Info ──────────────────────────────────────────
    private String projectPath;
    private String projectType;         // MAVEN | GRADLE | PLAIN_JAVA
    private String detectedJavaVersion;
    private long   totalJavaFiles;
    private String analyzedAt;

    // ── Issues by source ─────────────────────────────────────
    private List<Issue> internalIssues;   // JavaParser + Config + Graph
    private List<Issue> mavenIssues;
    private List<Issue> pmdIssues;
    private List<Issue> sonarIssues;
    private List<Issue> allIssues;        // الكل مجمع

    // ── AutoFix Results ───────────────────────────────────────
    private AutoFixResult autoFixResult;

    // ── Summary ───────────────────────────────────────────────
    private Map<IssueSeverity, Long> issuesBySeverity;
    private Map<IssueType, Long>     issuesByType;
    private Map<String, Long>        issuesByTool;

    private int totalIssuesFound;
    private int totalIssuesFixed;
    private int autoFixableCount;

    private String status;  // CLEAN | ISSUES_FOUND | FIXED | FAILED

    // ── Tool Availability ────────────────────────────────────
    private boolean mavenAvailable;
    private boolean pmdAvailable;
    private boolean sonarAvailable;
}
