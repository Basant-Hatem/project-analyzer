package com.analyzer.model;

public enum IssueType {
    // Import issues
    MISSING_IMPORT,
    UNUSED_IMPORT,
    DUPLICATE_IMPORT,

    // Dependency issues
    MISSING_DEPENDENCY,
    VERSION_CONFLICT,
    DEPRECATED_DEPENDENCY,
    UNUSED_DEPENDENCY,

    // Config issues
    MISSING_CONFIG_KEY,
    INVALID_CONFIG_VALUE,
    WRONG_CONFIG_STRUCTURE,

    // Code issues (PMD)
    CODE_VIOLATION,
    UNUSED_CODE,
    DUPLICATE_CODE,
    CODING_STANDARD,

    // Graph issues
    CIRCULAR_DEPENDENCY,
    UNUSED_PACKAGE,

    // Sonar issues
    BUG,
    CODE_SMELL,
    SECURITY_VULNERABILITY
}
