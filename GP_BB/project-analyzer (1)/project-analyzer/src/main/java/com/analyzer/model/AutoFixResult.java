package com.analyzer.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AutoFixResult {

    // ImportFixer
    private List<String> addedImports;
    private List<String> removedImports;

    // DependencyFixer
    private List<String> addedDependencies;
    private List<String> resolvedConflicts;
    private boolean      pomUpdated;
    private boolean      gradleUpdated;

    // ConfigFixer
    private List<String> addedConfigKeys;
    private List<String> fixedConfigValues;
    private boolean      configUpdated;

    // Summary
    private List<String> fixedFiles;
    private List<String> failedFiles;
    private int          totalFixed;
    private int          totalFailed;
}
