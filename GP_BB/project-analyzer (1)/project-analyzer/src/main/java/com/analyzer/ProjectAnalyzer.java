package com.analyzer;

import com.analyzer.autofix.*;
import com.analyzer.collector.IssueCollector;
import com.analyzer.external.*;
import com.analyzer.internal.*;
import com.analyzer.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAnalyzer {

    // ── Internal ──────────────────────────────────────────────────────────
    private final ImportAnalyzer          importAnalyzer;
    private final ConfigAnalyzer          configAnalyzer;
    private final DependencyGraphAnalyzer graphAnalyzer;

    // ── External ──────────────────────────────────────────────────────────
    private final MavenRunner  mavenRunner;
    private final PMDRunner    pmdRunner;
    private final SonarRunner  sonarRunner;

    // ── Collector ─────────────────────────────────────────────────────────
    private final IssueCollector issueCollector;

    // ── AutoFix ───────────────────────────────────────────────────────────
    private final ImportFixer     importFixer;
    private final DependencyFixer dependencyFixer;
    private final ConfigFixer     configFixer;

    // ─────────────────────────────────────────────────────────────────────

    public AnalysisReport analyze(String projectPath, boolean autoFix,
                                   String sonarProjectKey) {
        log.info("═══════════ Starting ProjectAnalyzer on: {} ═══════════", projectPath);
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // ── Validate path ──────────────────────────────────────────────────
        if (!Files.exists(Paths.get(projectPath)))
            return failedReport(projectPath, "Project path does not exist");

        // ── Detect project info ────────────────────────────────────────────
        String projectType    = detectProjectType(projectPath);
        String javaVersion    = detectJavaVersion(projectPath);
        List<Path> javaFiles  = collectJavaFiles(projectPath);

        log.info("Type={}, Java={}, Files={}", projectType, javaVersion, javaFiles.size());

        // ── STEP 1: InternalAnalysis ───────────────────────────────────────
        log.info("── Step 1: InternalAnalysis ──────────────────────────────");

        log.info("  ImportAnalyzer...");
        List<Issue> importIssues = importAnalyzer.analyzeAll(javaFiles);

        log.info("  ConfigAnalyzer...");
        List<Issue> configIssues = configAnalyzer.analyzeProject(projectPath);

        log.info("  DependencyGraphAnalyzer...");
        List<Issue> graphIssues  = graphAnalyzer.analyze(javaFiles);

        List<Issue> internalIssues = issueCollector.collect(
                importIssues, configIssues, graphIssues);
        log.info("  Internal issues: {}", internalIssues.size());

        // ── STEP 2: ExternalToolsRunner ────────────────────────────────────
        log.info("── Step 2: ExternalToolsRunner ───────────────────────────");

        log.info("  MavenRunner...");
        List<Issue> mavenIssues = mavenRunner.analyze(projectPath);

        log.info("  PMDRunner...");
        List<Issue> pmdIssues   = pmdRunner.analyze(projectPath);

        log.info("  SonarRunner...");
        String projKey = sonarProjectKey != null
                ? sonarProjectKey
                : Paths.get(projectPath).getFileName().toString();
        List<Issue> sonarIssues = sonarRunner.analyze(projectPath, projKey);

        // ── STEP 3: IssueCollector ─────────────────────────────────────────
        log.info("── Step 3: IssueCollector ────────────────────────────────");
        List<Issue> allIssues = issueCollector.collect(
                internalIssues, mavenIssues, pmdIssues, sonarIssues);
        log.info("  Total issues after dedup: {}", allIssues.size());

        // ── STEP 4: AutoFixEngine ──────────────────────────────────────────
        AutoFixResult autoFixResult = null;
        if (autoFix) {
            log.info("── Step 4: AutoFixEngine ─────────────────────────────────");
            autoFixResult = runAutoFix(allIssues, projectPath);
            log.info("  Fixed: {}, Failed: {}",
                    autoFixResult.getTotalFixed(), autoFixResult.getTotalFailed());
        }

        // ── Build Report ───────────────────────────────────────────────────
        int autoFixableCount = (int) allIssues.stream()
                .filter(Issue::isAutoFixable).count();

        String status = allIssues.isEmpty()       ? "CLEAN"
                      : autoFix && autoFixResult != null
                        && autoFixResult.getTotalFixed() > 0 ? "FIXED"
                      :                                        "ISSUES_FOUND";

        log.info("═══════════ Analysis Complete. Status={} ═══════════", status);

        return AnalysisReport.builder()
                .projectPath(projectPath)
                .projectType(projectType)
                .detectedJavaVersion(javaVersion)
                .totalJavaFiles(javaFiles.size())
                .analyzedAt(timestamp)
                .internalIssues(internalIssues)
                .mavenIssues(mavenIssues)
                .pmdIssues(pmdIssues)
                .sonarIssues(sonarIssues)
                .allIssues(allIssues)
                .autoFixResult(autoFixResult)
                .issuesBySeverity(issueCollector.groupBySeverity(allIssues))
                .issuesByType(issueCollector.groupByType(allIssues))
                .issuesByTool(issueCollector.groupByTool(allIssues))
                .totalIssuesFound(allIssues.size())
                .totalIssuesFixed(autoFixResult != null ? autoFixResult.getTotalFixed() : 0)
                .autoFixableCount(autoFixableCount)
                .status(status)
                .mavenAvailable(mavenRunner.isAvailable())
                .pmdAvailable(pmdRunner.isAvailable())
                .sonarAvailable(sonarRunner.isFullyAvailable())
                .build();
    }

    // ── AutoFix orchestration ─────────────────────────────────────────────

    private AutoFixResult runAutoFix(List<Issue> allIssues, String projectPath) {
        List<Issue> fixable = issueCollector.getAutoFixable(allIssues);

        // ImportFixer
        List<String> importFixed = importFixer.fix(fixable);

        // DependencyFixer
        List<String> depFixed = dependencyFixer.fix(fixable, projectPath);

        // ConfigFixer
        List<String> configFixed = configFixer.fix(fixable, projectPath);

        List<String> allFixed = new ArrayList<>();
        allFixed.addAll(importFixed);
        allFixed.addAll(depFixed);
        allFixed.addAll(configFixed);

        // تجميع الـ added imports
        List<String> addedImports = allIssues.stream()
                .filter(i -> i.getType() == com.analyzer.model.IssueType.MISSING_IMPORT
                          && i.isAutoFixable() && i.getRuleId() != null)
                .map(Issue::getRuleId)
                .distinct()
                .collect(Collectors.toList());

        Path pomPath = Paths.get(projectPath, "pom.xml");

        return AutoFixResult.builder()
                .addedImports(addedImports)
                .removedImports(new ArrayList<>())
                .addedDependencies(depFixed)
                .resolvedConflicts(new ArrayList<>())
                .pomUpdated(!depFixed.isEmpty() && Files.exists(pomPath))
                .gradleUpdated(false)
                .addedConfigKeys(configFixed)
                .fixedConfigValues(new ArrayList<>())
                .configUpdated(!configFixed.isEmpty())
                .fixedFiles(allFixed)
                .failedFiles(new ArrayList<>())
                .totalFixed(allFixed.size())
                .totalFailed(0)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String detectProjectType(String projectPath) {
        Path root = Paths.get(projectPath);
        boolean maven  = Files.exists(root.resolve("pom.xml"));
        boolean gradle = Files.exists(root.resolve("build.gradle"))
                      || Files.exists(root.resolve("build.gradle.kts"));
        if (maven && gradle) return "MAVEN_AND_GRADLE";
        if (maven)  return "MAVEN";
        if (gradle) return "GRADLE";
        return "PLAIN_JAVA";
    }

    private String detectJavaVersion(String projectPath) {
        Path pom = Paths.get(projectPath, "pom.xml");
        if (Files.exists(pom)) {
            try {
                String content = Files.readString(pom);
                for (String tag : List.of("java.version",
                        "maven.compiler.source", "maven.compiler.release")) {
                    int s = content.indexOf("<" + tag + ">");
                    int e = content.indexOf("</" + tag + ">");
                    if (s >= 0 && e >= 0)
                        return content.substring(s + tag.length() + 2, e).trim();
                }
            } catch (IOException ignored) {}
        }
        return System.getProperty("java.version").split("\\.")[0];
    }

    private List<Path> collectJavaFiles(String projectPath) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(Paths.get(projectPath),
                    new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (f.toString().endsWith(".java")) files.add(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path d,
                                                          BasicFileAttributes a) {
                    String n = d.getFileName().toString();
                    if (Set.of("target","build",".git","out").contains(n))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Cannot scan files: {}", e.getMessage());
        }
        return files;
    }

    private AnalysisReport failedReport(String path, String reason) {
        return AnalysisReport.builder()
                .projectPath(path).status("FAILED")
                .allIssues(List.of(Issue.builder()
                        .type(IssueType.WRONG_CONFIG_STRUCTURE)
                        .severity(IssueSeverity.BLOCKER)
                        .tool("SYSTEM").message(reason).build()))
                .totalIssuesFound(1).totalIssuesFixed(0)
                .build();
    }
}
