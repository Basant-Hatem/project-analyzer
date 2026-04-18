package com.analyzer.external;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

@Slf4j
@Component
public class PMDRunner {

    private static final String PMD_VERSION  = "7.0.0";
    private static final String PMD_DOWNLOAD =
            "https://github.com/pmd/pmd/releases/download/pmd_releases/" +
            PMD_VERSION + "/pmd-dist-" + PMD_VERSION + "-bin.zip";

    @Value("${analyzer.pmd.home:#{null}}")
    private String pmdHome;

    @Value("${analyzer.tools.dir:${user.home}/.analyzer-tools}")
    private String toolsDir;

    // ── Availability ──────────────────────────────────────────────────────

    public boolean isAvailable() {
        return findPmdExecutable() != null;
    }

    public boolean ensureAvailable() {
        if (isAvailable()) return true;
        log.info("PMD not found. Downloading PMD {}...", PMD_VERSION);
        return downloadPmd();
    }

    // ── Main runner ───────────────────────────────────────────────────────

    public List<Issue> analyze(String projectPath) {
        List<Issue> issues = new ArrayList<>();

        if (!ensureAvailable()) {
            log.warn("PMD not available - skipping PMD analysis");
            return issues;
        }

        String pmdBin = findPmdExecutable();
        if (pmdBin == null) return issues;

        Path srcPath = Paths.get(projectPath, "src");
        if (!Files.exists(srcPath)) {
            log.warn("No src folder found in {}", projectPath);
            return issues;
        }

        Path rulesetFile = writeRuleset();
        if (rulesetFile == null) return issues;

        try {
            Path reportFile = Files.createTempFile("pmd-report-", ".xml");

            List<String> cmd = Arrays.asList(
                pmdBin, "check",
                "--dir",         srcPath.toString(),
                "--rulesets",    rulesetFile.toString(),
                "--format",      "xml",
                "--report-file", reportFile.toString(),
                "--no-fail-on-violation"
            );

            log.info("Running PMD on: {}", srcPath);
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(new File(projectPath))
                    .redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(120, TimeUnit.SECONDS);

            if (Files.exists(reportFile) && Files.size(reportFile) > 0) {
                issues.addAll(parsePmdReport(reportFile));
                log.info("PMD found {} issues", issues.size());
            }

            Files.deleteIfExists(reportFile);
            Files.deleteIfExists(rulesetFile);

        } catch (Exception e) {
            log.error("PMD analysis failed: {}", e.getMessage());
        }

        return issues;
    }

    // ── PMD XML Report Parser ─────────────────────────────────────────────

    private List<Issue> parsePmdReport(Path reportFile) {
        List<Issue> issues = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(reportFile.toFile());
            NodeList files = doc.getElementsByTagName("file");

            for (int i = 0; i < files.getLength(); i++) {
                Element fileEl = (Element) files.item(i);
                String filePath = fileEl.getAttribute("name");
                NodeList violations = fileEl.getElementsByTagName("violation");

                for (int j = 0; j < violations.getLength(); j++) {
                    Element v = (Element) violations.item(j);
                    issues.add(Issue.builder()
                            .file(filePath)
                            .line(parseIntSafe(v.getAttribute("beginline")))
                            .column(parseIntSafe(v.getAttribute("begincolumn")))
                            .type(mapPmdType(v.getAttribute("ruleset")))
                            .severity(mapPmdSeverity(v.getAttribute("priority")))
                            .tool("PMD")
                            .ruleId(v.getAttribute("rule"))
                            .message("[" + v.getAttribute("rule") + "] " +
                                     v.getTextContent().trim())
                            .suggestedFix(getSuggestedFix(v.getAttribute("rule")))
                            .autoFixable(false)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Cannot parse PMD report: {}", e.getMessage());
        }
        return issues;
    }

    // ── Download PMD ──────────────────────────────────────────────────────

    private boolean downloadPmd() {
        try {
            Path toolsDirPath = Paths.get(toolsDir);
            Files.createDirectories(toolsDirPath);
            Path zipFile = toolsDirPath.resolve("pmd.zip");

            log.info("Downloading from: {}", PMD_DOWNLOAD);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<Path> response = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(PMD_DOWNLOAD))
                            .timeout(Duration.ofMinutes(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofFile(zipFile));

            if (response.statusCode() == 200) {
                log.info("Extracting PMD...");
                extractZip(zipFile, toolsDirPath);
                Files.deleteIfExists(zipFile);
                log.info("PMD ready at: {}", toolsDirPath);
                return isAvailable();
            }
            log.warn("Download failed with status: {}", response.statusCode());
            return false;

        } catch (Exception e) {
            log.warn("Cannot download PMD: {}. PMD will be skipped.", e.getMessage());
            return false;
        }
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir))
                    throw new IOException("ZIP slip: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    if (entry.getName().endsWith("/bin/pmd") ||
                        entry.getName().endsWith("/bin/pmd.bat"))
                        target.toFile().setExecutable(true);
                }
                zis.closeEntry();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String findPmdExecutable() {
        String bin = getPmdBin();

        if (pmdHome != null && !pmdHome.isBlank()) {
            Path exec = Paths.get(pmdHome, "bin", bin);
            if (Files.exists(exec)) return exec.toString();
        }

        try {
            Optional<Path> found = Files.walk(Paths.get(toolsDir), 4)
                    .filter(p -> p.getFileName().toString().equals(bin))
                    .filter(p -> p.toString().contains("bin"))
                    .findFirst();
            if (found.isPresent()) return found.get().toString();
        } catch (IOException ignored) {}

        try {
            Process p = new ProcessBuilder(bin, "--version")
                    .redirectErrorStream(true).start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return bin;
        } catch (Exception ignored) {}

        return null;
    }

    private Path writeRuleset() {
        try {
            Path file = Files.createTempFile("pmd-ruleset-", ".xml");
            Files.writeString(file, """
                <?xml version="1.0"?>
                <ruleset name="Analyzer Rules"
                    xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0
                        https://pmd.sourceforge.net/ruleset_2_0_0.xsd">
                  <description>Project Analyzer PMD Rules</description>
                  <rule ref="category/java/bestpractices.xml"/>
                  <rule ref="category/java/design.xml"/>
                  <rule ref="category/java/errorprone.xml"/>
                  <rule ref="category/java/performance.xml"/>
                  <rule ref="category/java/security.xml"/>
                </ruleset>
                """);
            return file;
        } catch (IOException e) {
            log.error("Cannot write PMD ruleset: {}", e.getMessage());
            return null;
        }
    }

    private IssueType mapPmdType(String ruleset) {
        if (ruleset == null) return IssueType.CODE_VIOLATION;
        return switch (ruleset.toLowerCase()) {
            case "best practices" -> IssueType.CODING_STANDARD;
            case "code style"     -> IssueType.CODING_STANDARD;
            case "design"         -> IssueType.CODE_SMELL;
            case "error prone"    -> IssueType.BUG;
            case "performance"    -> IssueType.CODE_SMELL;
            case "security"       -> IssueType.SECURITY_VULNERABILITY;
            default               -> IssueType.CODE_VIOLATION;
        };
    }

    private IssueSeverity mapPmdSeverity(String priority) {
        return switch (priority) {
            case "1" -> IssueSeverity.BLOCKER;
            case "2" -> IssueSeverity.CRITICAL;
            case "3" -> IssueSeverity.MAJOR;
            case "4" -> IssueSeverity.MINOR;
            default  -> IssueSeverity.INFO;
        };
    }

    private String getSuggestedFix(String rule) {
        return switch (rule) {
            case "UnusedLocalVariable"    -> "Remove unused local variable";
            case "EmptyCatchBlock"        -> "Handle the exception or add a log statement";
            case "SystemPrintln"          -> "Use a logger instead of System.out.println";
            case "AvoidDuplicateLiterals" -> "Extract duplicate literals to a constant";
            case "TooManyMethods"         -> "Refactor class - too many methods (SRP violation)";
            case "GodClass"               -> "Split into smaller classes";
            default                       -> "Review PMD rule: " + rule;
        };
    }

    private String getPmdBin() {
        return System.getProperty("os.name").toLowerCase().contains("windows")
                ? "pmd.bat" : "pmd";
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
