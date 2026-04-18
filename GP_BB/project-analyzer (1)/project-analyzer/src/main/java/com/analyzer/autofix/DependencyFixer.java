package com.analyzer.autofix;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DependencyFixer {

    // الـ dependency catalog: artifactId → [groupId, version]
    private static final Map<String, String[]> DEP_CATALOG = new LinkedHashMap<>() {{
        put("h2",           new String[]{"com.h2database",        "2.2.224"});
        put("mysql-connector-java",
                            new String[]{"mysql",                 "8.3.0"});
        put("mysql-connector-j",
                            new String[]{"com.mysql",             null}); // spring managed
        put("postgresql",   new String[]{"org.postgresql",        "42.7.1"});
        put("jakarta.persistence-api",
                            new String[]{"jakarta.persistence",   "3.1.0"});
        put("junit-jupiter",new String[]{"org.junit.jupiter",     null}); // spring managed
        put("spring-boot-starter-web",
                            new String[]{"org.springframework.boot", null});
        put("spring-boot-starter-data-jpa",
                            new String[]{"org.springframework.boot", null});
        put("spring-boot-starter-security",
                            new String[]{"org.springframework.boot", null});
        put("spring-boot-starter-validation",
                            new String[]{"org.springframework.boot", null});
        put("lombok",       new String[]{"org.projectlombok",     null});
        put("mapstruct",    new String[]{"org.mapstruct",         "1.5.5.Final"});
        put("modelmapper",  new String[]{"org.modelmapper",       "3.2.0"});
    }};

    public List<String> fix(List<Issue> depIssues, String projectPath) {
        List<String> fixed = new ArrayList<>();
        Path pomPath    = Paths.get(projectPath, "pom.xml");
        Path gradlePath = Paths.get(projectPath, "build.gradle");

        if (Files.exists(pomPath)) {
            fixed.addAll(fixMaven(depIssues, pomPath));
        } else if (Files.exists(gradlePath)) {
            fixed.addAll(fixGradle(depIssues, gradlePath));
        }

        return fixed;
    }

    // ── Maven Fix ─────────────────────────────────────────────────────────

    private List<String> fixMaven(List<Issue> issues, Path pomPath) {
        List<String> fixed = new ArrayList<>();
        try {
            MavenXpp3Reader reader  = new MavenXpp3Reader();
            Model           model   = reader.read(new FileReader(pomPath.toFile()));
            boolean         changed = false;

            for (Issue issue : issues) {
                if (!issue.isAutoFixable()) continue;

                if (issue.getType() == IssueType.MISSING_DEPENDENCY) {
                    changed |= addMissingDependency(model, issue, fixed);
                }
                if (issue.getType() == IssueType.UNUSED_DEPENDENCY) {
                    changed |= removeUnusedDependency(model, issue, fixed);
                }
                if (issue.getType() == IssueType.DEPRECATED_DEPENDENCY) {
                    changed |= replaceDeprecated(model, issue, fixed);
                }
            }

            if (changed) {
                MavenXpp3Writer writer = new MavenXpp3Writer();
                writer.write(new FileWriter(pomPath.toFile()), model);
                log.info("Updated pom.xml");
            }

        } catch (Exception e) {
            log.error("Failed to fix Maven dependencies: {}", e.getMessage());
        }
        return fixed;
    }

    private boolean addMissingDependency(Model model, Issue issue,
                                          List<String> fixed) {
        String msg = issue.getMessage();
        if (msg == null) return false;

        // نحاول نستخرج الـ artifactId من الـ message أو الـ suggestedFix
        String artifactId = extractArtifactId(issue);
        if (artifactId == null || !DEP_CATALOG.containsKey(artifactId)) return false;

        // تأكد إنه مش موجود أصلاً
        boolean exists = model.getDependencies().stream()
                .anyMatch(d -> d.getArtifactId().equals(artifactId));
        if (exists) return false;

        String[] info      = DEP_CATALOG.get(artifactId);
        Dependency newDep  = new Dependency();
        newDep.setGroupId(info[0]);
        newDep.setArtifactId(artifactId);
        if (info[1] != null) newDep.setVersion(info[1]);

        model.addDependency(newDep);
        fixed.add("Added: " + info[0] + ":" + artifactId);
        log.info("Added dependency: {}:{}", info[0], artifactId);
        return true;
    }

    private boolean removeUnusedDependency(Model model, Issue issue,
                                            List<String> fixed) {
        String artifactId = extractArtifactId(issue);
        if (artifactId == null) return false;

        int before = model.getDependencies().size();
        model.getDependencies().removeIf(d ->
                d.getArtifactId().equals(artifactId));
        int after = model.getDependencies().size();

        if (before != after) {
            fixed.add("Removed unused: " + artifactId);
            return true;
        }
        return false;
    }

    private boolean replaceDeprecated(Model model, Issue issue,
                                       List<String> fixed) {
        // javax.persistence → jakarta.persistence
        if (issue.getMessage() != null &&
                issue.getMessage().contains("jakarta.persistence")) {
            model.getDependencies().removeIf(d ->
                    "javax.persistence".equals(d.getGroupId()));

            boolean jakartaExists = model.getDependencies().stream()
                    .anyMatch(d -> d.getArtifactId()
                            .contains("jakarta.persistence-api"));
            if (!jakartaExists) {
                Dependency jakarta = new Dependency();
                jakarta.setGroupId("jakarta.persistence");
                jakarta.setArtifactId("jakarta.persistence-api");
                jakarta.setVersion("3.1.0");
                model.addDependency(jakarta);
                fixed.add("Replaced javax.persistence → jakarta.persistence");
                return true;
            }
        }
        // JUnit 4 → JUnit 5
        if (issue.getMessage() != null && issue.getMessage().contains("JUnit 5")) {
            model.getDependencies().removeIf(d ->
                    "junit".equals(d.getGroupId()) && "junit".equals(d.getArtifactId()));
            fixed.add("Removed JUnit 4 (add JUnit 5 via spring-boot-starter-test)");
            return true;
        }
        return false;
    }

    // ── Gradle Fix ────────────────────────────────────────────────────────

    private List<String> fixGradle(List<Issue> issues, Path gradlePath) {
        List<String> fixed = new ArrayList<>();
        try {
            String content = Files.readString(gradlePath);
            StringBuilder newContent = new StringBuilder(content);

            for (Issue issue : issues) {
                if (!issue.isAutoFixable()) continue;
                if (issue.getType() == IssueType.MISSING_DEPENDENCY) {
                    String artifactId = extractArtifactId(issue);
                    if (artifactId != null && DEP_CATALOG.containsKey(artifactId)
                            && !content.contains(artifactId)) {
                        String[] info = DEP_CATALOG.get(artifactId);
                        String dep = info[1] != null
                                ? info[0] + ":" + artifactId + ":" + info[1]
                                : info[0] + ":" + artifactId;
                        String line = "\n    implementation '" + dep + "'";
                        int idx = newContent.indexOf("dependencies {");
                        if (idx >= 0) {
                            newContent.insert(idx + "dependencies {".length(), line);
                            fixed.add("Added to gradle: " + dep);
                        }
                    }
                }
            }

            if (!fixed.isEmpty())
                Files.writeString(gradlePath, newContent.toString());

        } catch (Exception e) {
            log.error("Failed to fix Gradle dependencies: {}", e.getMessage());
        }
        return fixed;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String extractArtifactId(Issue issue) {
        if (issue.getRuleId() != null && !issue.getRuleId().isBlank())
            return issue.getRuleId();

        // نحاول من الـ message
        String msg = issue.getMessage();
        if (msg == null) return null;

        // مثلاً: "JPA enabled but no database driver found" → h2
        if (msg.contains("database driver")) return "h2";
        if (msg.contains("javax.persistence")) return "jakarta.persistence-api";
        if (msg.contains("JUnit 4")) return null; // handled separately

        // Extract "groupId:artifactId" pattern
        if (msg.contains(":")) {
            String[] parts = msg.split("\\s+");
            for (String p : parts) {
                if (p.contains(":")) {
                    String[] ga = p.split(":");
                    if (ga.length >= 2) return ga[1].replaceAll("[^a-zA-Z0-9-.]", "");
                }
            }
        }
        return null;
    }

    // ── Public utility ────────────────────────────────────────────────────

    public boolean addDependency(Path pomPath, String groupId,
                                  String artifactId, String version) {
        if (!Files.exists(pomPath)) return false;
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(pomPath.toFile()));

            boolean exists = model.getDependencies().stream()
                    .anyMatch(d -> d.getArtifactId().equals(artifactId));
            if (exists) return false;

            Dependency dep = new Dependency();
            dep.setGroupId(groupId);
            dep.setArtifactId(artifactId);
            if (version != null) dep.setVersion(version);
            model.addDependency(dep);

            new MavenXpp3Writer().write(new FileWriter(pomPath.toFile()), model);
            return true;
        } catch (Exception e) {
            log.error("Cannot add dependency: {}", e.getMessage());
            return false;
        }
    }
}
