package com.analyzer.external;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MavenRunner {

    private static final int TIMEOUT_SECONDS = 120;

    // ── Tool availability ─────────────────────────────────────────────────
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(getMvnCommand(), "--version")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Main analysis ─────────────────────────────────────────────────────

    public List<Issue> analyze(String projectPath) {
        List<Issue> issues = new ArrayList<>();
        Path pomPath = Paths.get(projectPath, "pom.xml");

        if (!Files.exists(pomPath)) {
            log.info("No pom.xml found in {}", projectPath);
            return issues;
        }

        // 1. Static pom.xml analysis (always runs)
        issues.addAll(analyzePomStatic(pomPath));

        // 2. Run maven dependency:analyze (if Maven available)
        if (isAvailable()) {
            issues.addAll(runMavenDependencyAnalyze(projectPath));
            issues.addAll(runMavenValidate(projectPath));
        } else {
            log.info("Maven not found in PATH - skipping runtime analysis");
        }

        return issues;
    }

    // ── Static pom.xml analysis ───────────────────────────────────────────

    public List<Issue> analyzePomStatic(Path pomPath) {
        List<Issue> issues = new ArrayList<>();
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(pomPath.toFile()));
            List<Dependency> deps = model.getDependencies();

            Set<String> seen = new HashSet<>();
            for (Dependency dep : deps) {
                String key = dep.getGroupId() + ":" + dep.getArtifactId();

                // Duplicate check
                if (!seen.add(key)) {
                    issues.add(Issue.builder()
                            .file(pomPath.toString())
                            .type(IssueType.VERSION_CONFLICT)
                            .severity(IssueSeverity.MAJOR)
                            .tool("MAVEN")
                            .message("Duplicate dependency: " + key)
                            .suggestedFix("Remove duplicate entry from pom.xml")
                            .autoFixable(true)
                            .build());
                    continue;
                }

                // Missing version (not spring-managed)
                if (dep.getVersion() == null && !isSpringManaged(dep.getArtifactId())) {
                    issues.add(Issue.builder()
                            .file(pomPath.toString())
                            .type(IssueType.MISSING_DEPENDENCY)
                            .severity(IssueSeverity.MAJOR)
                            .tool("MAVEN")
                            .message("Missing version for: " + key)
                            .suggestedFix("Add <version> tag or use Spring BOM management")
                            .autoFixable(false)
                            .build());
                }

                // Deprecated dependencies
                checkDeprecated(dep, pomPath.toString(), issues);
            }

            // Check companion dependencies
            checkMissingCompanions(deps, pomPath.toString(), issues);

        } catch (Exception e) {
            log.error("Error parsing pom.xml: {}", e.getMessage());
            issues.add(Issue.builder()
                    .file(pomPath.toString())
                    .type(IssueType.WRONG_CONFIG_STRUCTURE)
                    .severity(IssueSeverity.BLOCKER)
                    .tool("MAVEN")
                    .message("Cannot parse pom.xml: " + e.getMessage())
                    .autoFixable(false)
                    .build());
        }
        return issues;
    }

    // ── Maven commands ────────────────────────────────────────────────────

    private List<Issue> runMavenDependencyAnalyze(String projectPath) {
        List<Issue> issues = new ArrayList<>();
        try {
            String output = runCommand(projectPath,
                    getMvnCommand(), "dependency:analyze", "-q", "--no-transfer-progress");

            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.contains("Used undeclared dependencies")) {
                    // next lines are the undeclared deps
                } else if (line.startsWith("[WARNING]") && line.contains(":")) {
                    String dep = line.replace("[WARNING]", "").trim();
                    issues.add(Issue.builder()
                            .file(projectPath + "/pom.xml")
                            .type(IssueType.MISSING_DEPENDENCY)
                            .severity(IssueSeverity.MAJOR)
                            .tool("MAVEN")
                            .message("Used but undeclared dependency: " + dep)
                            .suggestedFix("Add dependency to pom.xml: " + dep)
                            .autoFixable(true)
                            .build());
                } else if (line.contains("Unused declared dependencies")) {
                    // unused deps warning
                } else if (line.startsWith("[WARNING]")) {
                    String dep = line.replace("[WARNING]", "").trim();
                    if (!dep.isEmpty()) {
                        issues.add(Issue.builder()
                                .file(projectPath + "/pom.xml")
                                .type(IssueType.UNUSED_DEPENDENCY)
                                .severity(IssueSeverity.MINOR)
                                .tool("MAVEN")
                                .message("Declared but unused dependency: " + dep)
                                .suggestedFix("Remove unused dependency from pom.xml")
                                .autoFixable(true)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Maven dependency:analyze failed: {}", e.getMessage());
        }
        return issues;
    }

    private List<Issue> runMavenValidate(String projectPath) {
        List<Issue> issues = new ArrayList<>();
        try {
            String output = runCommand(projectPath,
                    getMvnCommand(), "validate", "--no-transfer-progress");
            if (output.contains("BUILD FAILURE")) {
                issues.add(Issue.builder()
                        .file(projectPath + "/pom.xml")
                        .type(IssueType.WRONG_CONFIG_STRUCTURE)
                        .severity(IssueSeverity.BLOCKER)
                        .tool("MAVEN")
                        .message("Maven validation failed. Check pom.xml structure")
                        .suggestedFix("Review BUILD FAILURE output and fix pom.xml")
                        .autoFixable(false)
                        .snippet(output.substring(0, Math.min(500, output.length())))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Maven validate failed: {}", e.getMessage());
        }
        return issues;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String runCommand(String workDir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(new File(workDir))
                .redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return output;
    }

    private String getMvnCommand() {
        return System.getProperty("os.name").toLowerCase().contains("windows")
                ? "mvn.cmd" : "mvn";
    }

    private boolean isSpringManaged(String artifactId) {
        return Set.of("spring-boot-starter", "spring-boot-starter-web",
                "spring-boot-starter-test", "spring-boot-starter-data-jpa",
                "spring-boot-starter-security", "lombok", "jackson-databind",
                "spring-boot-starter-actuator", "spring-boot-starter-validation"
        ).contains(artifactId);
    }

    private void checkDeprecated(Dependency dep, String pomFile, List<Issue> issues) {
        String g = dep.getGroupId(), a = dep.getArtifactId();
        if ("javax.persistence".equals(g))
            issues.add(Issue.builder().file(pomFile)
                    .type(IssueType.DEPRECATED_DEPENDENCY)
                    .severity(IssueSeverity.MAJOR).tool("MAVEN")
                    .message("Deprecated: use jakarta.persistence (Spring Boot 3+)")
                    .suggestedFix("Replace javax.persistence with jakarta.persistence")
                    .autoFixable(true).build());

        if ("junit".equals(g) && "junit".equals(a))
            issues.add(Issue.builder().file(pomFile)
                    .type(IssueType.DEPRECATED_DEPENDENCY)
                    .severity(IssueSeverity.MAJOR).tool("MAVEN")
                    .message("JUnit 4 is deprecated → migrate to JUnit 5")
                    .suggestedFix("Replace junit:junit with org.junit.jupiter:junit-jupiter")
                    .autoFixable(true).build());
    }

    private void checkMissingCompanions(List<Dependency> deps,
                                         String pomFile, List<Issue> issues) {
        boolean hasJpa = deps.stream().anyMatch(d -> d.getArtifactId().contains("data-jpa"));
        boolean hasDb  = deps.stream().anyMatch(d ->
                d.getArtifactId().contains("h2") ||
                d.getArtifactId().contains("mysql") ||
                d.getArtifactId().contains("postgresql"));
        if (hasJpa && !hasDb)
            issues.add(Issue.builder().file(pomFile)
                    .type(IssueType.MISSING_DEPENDENCY)
                    .severity(IssueSeverity.BLOCKER).tool("MAVEN")
                    .message("JPA enabled but no database driver found")
                    .suggestedFix("Add H2, MySQL, or PostgreSQL driver dependency")
                    .autoFixable(true).build());
    }

    public List<String> getDeclaredDependencies(Path pomPath) {
        List<String> list = new ArrayList<>();
        if (!Files.exists(pomPath)) return list;
        try {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(new FileReader(pomPath.toFile()));
            for (Dependency d : model.getDependencies())
                list.add(d.getGroupId() + ":" + d.getArtifactId()
                        + (d.getVersion() != null ? ":" + d.getVersion() : ""));
        } catch (Exception e) { log.error("Error reading deps: {}", e.getMessage()); }
        return list;
    }
}
