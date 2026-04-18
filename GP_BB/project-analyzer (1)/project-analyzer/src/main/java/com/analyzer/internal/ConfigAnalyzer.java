package com.analyzer.internal;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class ConfigAnalyzer {

    // ── Required keys per Spring Boot config ─────────────────────────────
    private static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            "datasource", List.of("spring.datasource.url",
                    "spring.datasource.username",
                    "spring.datasource.password"),
            "jpa",        List.of("spring.jpa.hibernate.ddl-auto"),
            "security",   List.of("spring.security.user.name",
                    "spring.security.user.password"),
            "server",     List.of("server.port")
    );

    // ── Known invalid / dangerous values ─────────────────────────────────
    private static final Map<String, List<String>> DANGEROUS_VALUES = Map.of(
            "spring.jpa.hibernate.ddl-auto", List.of("create-drop"),
            "spring.datasource.password",    List.of("password", "123456", "admin", "root", "secret")
    );

    public List<Issue> analyzeProject(String projectPath) {
        List<Issue> issues = new ArrayList<>();
        Path root = Paths.get(projectPath);

        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .forEach(file -> issues.addAll(analyzeFile(file)));
        } catch (IOException e) {
            log.error("Error scanning config files: {}", e.getMessage());
        }

        return issues;
    }

    public List<Issue> analyzeFile(Path filePath) {
        List<Issue> issues = new ArrayList<>();
        String name = filePath.getFileName().toString().toLowerCase();

        try {
            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                issues.addAll(analyzeYaml(filePath));
            } else if (name.endsWith(".properties")) {
                issues.addAll(analyzeProperties(filePath));
            } else if (name.equals(".env") || name.endsWith(".env")) {
                issues.addAll(analyzeEnv(filePath));
            }
        } catch (Exception e) {
            log.warn("Cannot analyze config file {}: {}", filePath, e.getMessage());
            issues.add(Issue.builder()
                    .file(filePath.toString())
                    .type(IssueType.WRONG_CONFIG_STRUCTURE)
                    .severity(IssueSeverity.MAJOR)
                    .tool("INTERNAL")
                    .message("Cannot parse config file: " + e.getMessage())
                    .autoFixable(false)
                    .build());
        }

        return issues;
    }

    // ── YAML Analysis ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Issue> analyzeYaml(Path filePath) throws IOException {
        List<Issue> issues = new ArrayList<>();
        String content = Files.readString(filePath);

        if (content.isBlank()) {
            issues.add(emptyFileIssue(filePath));
            return issues;
        }

        Yaml yaml = new Yaml();
        Map<String, Object> config;

        try {
            config = yaml.load(content);
        } catch (Exception e) {
            issues.add(Issue.builder()
                    .file(filePath.toString())
                    .type(IssueType.WRONG_CONFIG_STRUCTURE)
                    .severity(IssueSeverity.BLOCKER)
                    .tool("INTERNAL")
                    .message("Invalid YAML syntax: " + e.getMessage())
                    .suggestedFix("Fix YAML indentation and syntax")
                    .autoFixable(false)
                    .build());
            return issues;
        }

        if (config == null) return issues;

        Map<String, Object> flatConfig = flattenMap("", config);

        checkRequiredKeys(flatConfig, filePath, issues);
        checkDangerousValues(flatConfig, filePath, issues);
        checkEmptyValues(flatConfig, filePath, issues);

        return issues;
    }

    // ── Properties Analysis ───────────────────────────────────────────────

    private List<Issue> analyzeProperties(Path filePath) throws IOException {
        List<Issue> issues = new ArrayList<>();
        Properties props = new Properties();

        try (var reader = Files.newBufferedReader(filePath)) {
            props.load(reader);
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        props.forEach((k, v) -> flat.put(k.toString(), v));

        checkRequiredKeys(flat, filePath, issues);
        checkDangerousValues(flat, filePath, issues);
        checkEmptyValues(flat, filePath, issues);

        return issues;
    }

    // ── .env Analysis ────────────────────────────────────────────────────

    private List<Issue> analyzeEnv(Path filePath) throws IOException {
        List<Issue> issues = new ArrayList<>();
        Map<String, Object> envVars = new LinkedHashMap<>();

        for (String line : Files.readAllLines(filePath)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                envVars.put(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
            }
        }

        for (Map.Entry<String, Object> entry : envVars.entrySet()) {
            if (entry.getValue().toString().isBlank()) {
                issues.add(Issue.builder()
                        .file(filePath.toString())
                        .type(IssueType.MISSING_CONFIG_KEY)
                        .severity(IssueSeverity.MAJOR)
                        .tool("INTERNAL")
                        .message("Empty env variable: " + entry.getKey())
                        .suggestedFix("Set a value for " + entry.getKey())
                        .autoFixable(false)
                        .build());
            }
        }

        return issues;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void checkRequiredKeys(Map<String, Object> flat, Path filePath,
                                   List<Issue> issues) {
        boolean hasDataSource = flat.keySet().stream()
                .anyMatch(k -> k.startsWith("spring.datasource"));
        boolean hasJpa = flat.keySet().stream()
                .anyMatch(k -> k.startsWith("spring.jpa"));

        if (hasDataSource) {
            for (String req : REQUIRED_KEYS.get("datasource")) {
                if (!flat.containsKey(req)) {
                    issues.add(Issue.builder()
                            .file(filePath.toString())
                            .type(IssueType.MISSING_CONFIG_KEY)
                            .severity(IssueSeverity.MAJOR)
                            .tool("INTERNAL")
                            .message("Missing required datasource key: " + req)
                            .suggestedFix("Add '" + req + "' to your config file")
                            .autoFixable(true)
                            .ruleId(req)
                            .build());
                }
            }
        }

        if (hasJpa) {
            for (String req : REQUIRED_KEYS.get("jpa")) {
                if (!flat.containsKey(req)) {
                    issues.add(Issue.builder()
                            .file(filePath.toString())
                            .type(IssueType.MISSING_CONFIG_KEY)
                            .severity(IssueSeverity.MINOR)
                            .tool("INTERNAL")
                            .message("Missing JPA config key: " + req)
                            .suggestedFix("Add: " + req + ": validate")
                            .autoFixable(true)
                            .ruleId(req)
                            .build());
                }
            }
        }
    }

    private void checkDangerousValues(Map<String, Object> flat, Path filePath,
                                      List<Issue> issues) {
        for (Map.Entry<String, List<String>> entry : DANGEROUS_VALUES.entrySet()) {
            Object val = flat.get(entry.getKey());
            if (val != null && entry.getValue().stream()
                    .anyMatch(dangerous -> dangerous.equalsIgnoreCase(val.toString()))) {
                issues.add(Issue.builder()
                        .file(filePath.toString())
                        .type(IssueType.INVALID_CONFIG_VALUE)
                        .severity(IssueSeverity.CRITICAL)
                        .tool("INTERNAL")
                        .message("Dangerous/insecure value for '" + entry.getKey()
                                + "': '" + val + "'")
                        .suggestedFix("Use a secure value or environment variable")
                        .autoFixable(false)
                        .build());
            }
        }
    }

    private void checkEmptyValues(Map<String, Object> flat, Path filePath,
                                  List<Issue> issues) {
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            if (entry.getValue() == null || entry.getValue().toString().isBlank()) {
                issues.add(Issue.builder()
                        .file(filePath.toString())
                        .type(IssueType.INVALID_CONFIG_VALUE)
                        .severity(IssueSeverity.MINOR)
                        .tool("INTERNAL")
                        .message("Empty value for config key: " + entry.getKey())
                        .suggestedFix("Set a value for: " + entry.getKey())
                        .autoFixable(false)
                        .build());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenMap(String prefix, Map<String, Object> map) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flat.putAll(flattenMap(key, (Map<String, Object>) entry.getValue()));
            } else {
                flat.put(key, entry.getValue());
            }
        }
        return flat;
    }

    private Issue emptyFileIssue(Path filePath) {
        return Issue.builder()
                .file(filePath.toString())
                .type(IssueType.WRONG_CONFIG_STRUCTURE)
                .severity(IssueSeverity.MINOR)
                .tool("INTERNAL")
                .message("Config file is empty")
                .suggestedFix("Add required configuration properties")
                .autoFixable(false)
                .build();
    }

    private boolean isConfigFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".properties")
                || name.equals(".env") || name.endsWith(".env");
    }
}