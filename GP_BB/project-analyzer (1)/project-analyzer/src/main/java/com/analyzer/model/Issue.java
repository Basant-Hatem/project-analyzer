package com.analyzer.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Issue {

    // ── Location ──────────────────────────────────────────────
    private String file;          // الملف اللي فيه المشكلة
    private Integer line;         // رقم السطر
    private Integer column;       // رقم العمود

    // ── Classification ────────────────────────────────────────
    private IssueType     type;
    private IssueSeverity severity;
    private String        tool;   // INTERNAL | MAVEN | PMD | SONAR

    // ── Description ───────────────────────────────────────────
    private String message;       // وصف المشكلة
    private String suggestedFix;  // الحل المقترح
    private boolean autoFixable;  // ممكن نصلحه تلقائي؟

    // ── Extra ─────────────────────────────────────────────────
    private String ruleId;        // rule name (PMD/Sonar)
    private String snippet;       // الكود اللي فيه المشكلة
}
