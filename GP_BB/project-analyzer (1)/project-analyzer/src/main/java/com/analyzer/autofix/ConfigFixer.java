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
public class ConfigFixer {

    // Default values for known config keys
    private static final Map<String, String> DEFAULT_VALUES = new LinkedHashMap<>() {{
        put("spring.jpa.hibernate.ddl-auto",         "validate");
        put("spring.jpa.show-sql",                   "false");
        put("spring.jpa.properties.hibernate.format_sql", "true");
        put("server.port",                            "8080");
        put("spring.datasource.driver-class-name",   "com.mysql.cj.jdbc.Driver");
        put("management.endpoints.web.exposure.include", "health,info");
        put("logging.level.root",                    "INFO");
        put("spring.mvc.pathmatch.use-suffix-pattern","false");
    }};

    public List<String> fix(List<Issue> configIssues, String projectPath) {
        List<String> fixed = new ArrayList<>();

        // نجمع الـ issues حسب الـ file
        Map<String, List<Issue>> byFile = configIssues.stream()
                .filter(i -> i.isAutoFixable()
                          && i.getType() == IssueType.MISSING_CONFIG_KEY)
                .filter(i -> i.getFile() != null)
                .collect(Collectors.groupingBy(Issue::getFile));

        for (Map.Entry<String, List<Issue>> entry : byFile.entrySet()) {
            try {
                boolean fixed_ = fixConfigFile(entry.getKey(), entry.getValue());
                if (fixed_) fixed.add(entry.getKey());
            } catch (Exception e) {
                log.error("Failed to fix config {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return fixed;
    }

    private boolean fixConfigFile(String filePath, List<Issue> issues) throws IOException {
        Path path = Paths.get(filePath);
        String name = path.getFileName().toString().toLowerCase();
        boolean changed = false;

        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            changed = fixYaml(path, issues);
        } else if (name.endsWith(".properties")) {
            changed = fixProperties(path, issues);
        }

        if (changed) log.info("Fixed config: {}", filePath);
        return changed;
    }

    // ── YAML Fix ──────────────────────────────────────────────────────────

    private boolean fixYaml(Path path, List<Issue> issues) throws IOException {
        String content = Files.readString(path);
        StringBuilder sb = new StringBuilder(content);
        boolean changed = false;

        for (Issue issue : issues) {
            String key = issue.getRuleId(); // ruleId = config key
            if (key == null || !DEFAULT_VALUES.containsKey(key)) continue;

            // تحقق إنه مش موجود أصلاً
            if (content.contains(toYamlKey(key))) continue;

            // ضيف الـ key في نهاية الملف كـ comment + value
            String defaultVal = DEFAULT_VALUES.get(key);
            sb.append("\n# Added by Project Analyzer\n");
            sb.append(toYamlLine(key, defaultVal)).append("\n");
            changed = true;
            log.info("Added config key: {} = {}", key, defaultVal);
        }

        if (changed) Files.writeString(path, sb.toString());
        return changed;
    }

    // ── Properties Fix ────────────────────────────────────────────────────

    private boolean fixProperties(Path path, List<Issue> issues) throws IOException {
        String content = Files.readString(path);
        StringBuilder sb = new StringBuilder(content);
        boolean changed = false;

        for (Issue issue : issues) {
            String key = issue.getRuleId();
            if (key == null || !DEFAULT_VALUES.containsKey(key)) continue;
            if (content.contains(key + "=")) continue;

            String defaultVal = DEFAULT_VALUES.get(key);
            sb.append("\n# Added by Project Analyzer\n");
            sb.append(key).append("=").append(defaultVal).append("\n");
            changed = true;
        }

        if (changed) Files.writeString(path, sb.toString());
        return changed;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * يحول "spring.datasource.url" لـ YAML path للبحث
     */
    private String toYamlKey(String dotKey) {
        String[] parts = dotKey.split("\\.");
        return parts[parts.length - 1] + ":";
    }

    /**
     * يحول "spring.jpa.show-sql" لـ YAML nested line
     * هيضيفه flat في نهاية الملف (مش nested لأن أبسط)
     */
    private String toYamlLine(String dotKey, String value) {
        // نستخدم flat format في آخر الملف
        return dotKey.replace(".", ":\n  ").replace("\n  " + dotKey.split("\\.")[dotKey.split("\\.").length-1],
                ": " + value);
    }
}
