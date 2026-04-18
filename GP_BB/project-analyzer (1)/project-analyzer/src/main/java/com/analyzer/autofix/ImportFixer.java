package com.analyzer.autofix;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ImportFixer {

    /**
     * يصلح كل الـ import issues في الـ files المحددة
     * Returns: list of fixed file paths
     */
    public List<String> fix(List<Issue> importIssues) {
        Set<String> fixedFiles = new LinkedHashSet<>();

        // نجمع الـ issues حسب الـ file
        Map<String, List<Issue>> byFile = importIssues.stream()
                .filter(i -> i.getFile() != null && i.isAutoFixable())
                .filter(i -> i.getType() == IssueType.MISSING_IMPORT
                        || i.getType() == IssueType.UNUSED_IMPORT
                        || i.getType() == IssueType.DUPLICATE_IMPORT)
                .collect(Collectors.groupingBy(Issue::getFile));

        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            try {
                boolean fixed = fixFile(entry.getKey(), entry.getValue());
                if (fixed) fixedFiles.add(entry.getKey());
            } catch (Exception e) {
                log.error("Failed to fix imports in {}: {}", entry.getKey(), e.getMessage());
            }
        }

        return new ArrayList<>(fixedFiles);
    }

    private boolean fixFile(String filePath, List<Issue> issues) throws IOException {
        Path path = Paths.get(filePath);
        String content = Files.readString(path);
        String[] lines = content.split("\n", -1);

        // 1. نجمع الـ imports اللي محتاج نضيفهم (MISSING)
        List<String> toAdd = issues.stream()
                .filter(i -> i.getType() == IssueType.MISSING_IMPORT)
                .map(Issue::getRuleId)  // ruleId = FQN
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // 2. نجمع الـ imports اللي محتاج نشيلهم (UNUSED / DUPLICATE)
        Set<String> toRemove = issues.stream()
                .filter(i -> i.getType() == IssueType.UNUSED_IMPORT
                        || i.getType() == IssueType.DUPLICATE_IMPORT)
                .map(Issue::getRuleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        boolean changed = false;
        StringBuilder sb = new StringBuilder();
        boolean importsAdded = false;
        int insertAfterLine = findInsertPoint(lines);
        Set<String> seenImports = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // تخطي الـ imports اللي محتاج نشيلهم
            if (trimmed.startsWith("import ")) {
                String importFqn = trimmed
                        .replace("import ", "")
                        .replace(";", "")
                        .trim();

                // شيل الـ duplicates
                if (!seenImports.add(importFqn)) {
                    changed = true;
                    continue;
                }

                // شيل الـ unused
                if (toRemove.contains(importFqn)) {
                    changed = true;
                    continue;
                }
            }

            sb.append(line).append("\n");

            // ضيف الـ missing imports في المكان الصح
            if (!importsAdded && i == insertAfterLine) {
                for (String imp : toAdd) {
                    if (!seenImports.contains(imp)) {
                        sb.append("import ").append(imp).append(";\n");
                        seenImports.add(imp);
                        changed = true;
                    }
                }
                importsAdded = true;
            }
        }

        // لو مفيش import section خالص نضيف في الأول
        if (!importsAdded && !toAdd.isEmpty()) {
            StringBuilder prefix = new StringBuilder();
            for (String imp : toAdd)
                prefix.append("import ").append(imp).append(";\n");
            prefix.append("\n");
            sb.insert(0, prefix);
            changed = true;
        }

        if (changed) {
            Files.writeString(path, sb.toString());
            log.info("Fixed imports in: {} (added={}, removed={})",
                    filePath, toAdd.size(), toRemove.size());
        }

        return changed;
    }

    private int findInsertPoint(String[] lines) {
        int packageLine = -1;
        int firstImportLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("package "))  { packageLine = i; }
            if (t.startsWith("import ")  && firstImportLine < 0) { firstImportLine = i - 1; }
        }

        if (firstImportLine >= 0) return firstImportLine;
        if (packageLine >= 0)     return packageLine;
        return -1;
    }
}
