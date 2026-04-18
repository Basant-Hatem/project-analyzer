package com.analyzer.external;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
public class SonarRunner {

    private static final String SCANNER_VERSION  = "5.0.1.3006";
    private static final String SCANNER_DOWNLOAD =
            "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/" +
            "sonar-scanner-cli-" + SCANNER_VERSION + ".zip";

    @Value("${analyzer.sonar.url:http://localhost:9000}")
    private String sonarUrl;

    @Value("${analyzer.sonar.token:#{null}}")
    private String sonarToken;

    @Value("${analyzer.sonar.scanner.home:#{null}}")
    private String scannerHome;

    @Value("${analyzer.tools.dir:${user.home}/.analyzer-tools}")
    private String toolsDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Availability ──────────────────────────────────────────────────────

    public boolean isScannerAvailable() {
        return findScannerExecutable() != null;
    }

    public boolean isSonarServerReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sonarUrl + "/api/system/status"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isFullyAvailable() {
        return isScannerAvailable() && isSonarServerReachable()
                && sonarToken != null && !sonarToken.isBlank();
    }

    /**
     * يحاول يجيب Sonar Scanner من النت لو مش موجود
     */
    public boolean ensureScannerAvailable() {
        if (isScannerAvailable()) return true;
        log.info("Sonar Scanner not found. Attempting to download...");
        return downloadScanner();
    }

    // ── Main runner ───────────────────────────────────────────────────────

    public List<Issue> analyze(String projectPath, String projectKey) {
        List<Issue> issues = new ArrayList<>();

        if (!ensureScannerAvailable()) {
            log.warn("Sonar Scanner not available - skipping Sonar analysis");
            return issues;
        }

        if (!isSonarServerReachable()) {
            log.warn("SonarQube server not reachable at {} - skipping", sonarUrl);
            return issues;
        }

        if (sonarToken == null || sonarToken.isBlank()) {
            log.warn("SonarQube token not configured - skipping Sonar analysis");
            return issues;
        }

        String scanner = findScannerExecutable();
        if (scanner == null) return issues;

        try {
            // Run sonar-scanner
            List<String> cmd = Arrays.asList(
                scanner,
                "-Dsonar.projectKey="    + projectKey,
                "-Dsonar.sources=src",
                "-Dsonar.host.url="      + sonarUrl,
                "-Dsonar.token="         + sonarToken,
                "-Dsonar.scm.disabled=true"
            );

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(new File(projectPath))
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(300, TimeUnit.SECONDS);

            if (output.contains("EXECUTION SUCCESS")) {
                log.info("SonarQube scan completed. Fetching issues...");
                // انتظر شوية للـ analysis تتم
                Thread.sleep(5000);
                issues.addAll(fetchIssuesFromApi(projectKey));
            } else {
                log.warn("SonarQube scan failed");
            }
        } catch (Exception e) {
            log.error("Sonar analysis failed: {}", e.getMessage());
        }

        return issues;
    }

    // ── Fetch issues from Sonar API ───────────────────────────────────────

    private List<Issue> fetchIssuesFromApi(String projectKey) {
        List<Issue> issues = new ArrayList<>();
        try {
            String url = sonarUrl + "/api/issues/search?componentKeys="
                    + projectKey + "&pageSize=500";

            HttpClient client = buildHttpClient();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (sonarToken != null)
                reqBuilder.header("Authorization",
                        "Bearer " + sonarToken);

            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root   = objectMapper.readTree(response.body());
                JsonNode issArr = root.get("issues");

                if (issArr != null && issArr.isArray()) {
                    for (JsonNode iss : issArr) {
                        issues.add(mapSonarIssue(iss));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Cannot fetch Sonar issues: {}", e.getMessage());
        }
        return issues;
    }

    private Issue mapSonarIssue(JsonNode iss) {
        String component = iss.path("component").asText("");
        String filePath  = component.contains(":")
                ? component.substring(component.indexOf(':') + 1) : component;
        int    line      = iss.path("line").asInt(0);
        String message   = iss.path("message").asText("");
        String severity  = iss.path("severity").asText("MINOR");
        String type      = iss.path("type").asText("CODE_SMELL");
        String rule      = iss.path("rule").asText("");

        return Issue.builder()
                .file(filePath)
                .line(line)
                .type(mapSonarType(type))
                .severity(mapSonarSeverity(severity))
                .tool("SONAR")
                .ruleId(rule)
                .message(message)
                .suggestedFix("See SonarQube for details: " + sonarUrl
                        + "/coding_rules?rule_key=" + rule)
                .autoFixable(false)
                .build();
    }

    // ── Download ──────────────────────────────────────────────────────────

    private boolean downloadScanner() {
        try {
            Path toolsDirPath = Paths.get(toolsDir);
            Files.createDirectories(toolsDirPath);
            Path zipFile = toolsDirPath.resolve("sonar-scanner.zip");

            log.info("Downloading Sonar Scanner from: {}", SCANNER_DOWNLOAD);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<Path> response = client.send(
                    HttpRequest.newBuilder().uri(URI.create(SCANNER_DOWNLOAD))
                            .timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofFile(zipFile));

            if (response.statusCode() == 200) {
                extractZip(zipFile, toolsDirPath);
                Files.deleteIfExists(zipFile);
                log.info("Sonar Scanner installed");
                return isScannerAvailable();
            }
        } catch (Exception e) {
            log.warn("Cannot download Sonar Scanner: {}", e.getMessage());
        }
        return false;
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir))
                    throw new IOException("ZIP slip: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    if (entry.getName().contains("/bin/"))
                        target.toFile().setExecutable(true);
                }
                zis.closeEntry();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String findScannerExecutable() {
        String bin = getScannerBin();

        if (scannerHome != null) {
            Path e = Paths.get(scannerHome, "bin", bin);
            if (Files.exists(e)) return e.toString();
        }

        try {
            Optional<Path> found = Files.walk(Paths.get(toolsDir), 3)
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

    private HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private IssueType mapSonarType(String type) {
        return switch (type) {
            case "BUG"              -> IssueType.BUG;
            case "VULNERABILITY"    -> IssueType.SECURITY_VULNERABILITY;
            case "CODE_SMELL"       -> IssueType.CODE_SMELL;
            default                 -> IssueType.CODE_VIOLATION;
        };
    }

    private IssueSeverity mapSonarSeverity(String sev) {
        return switch (sev) {
            case "BLOCKER"  -> IssueSeverity.BLOCKER;
            case "CRITICAL" -> IssueSeverity.CRITICAL;
            case "MAJOR"    -> IssueSeverity.MAJOR;
            case "MINOR"    -> IssueSeverity.MINOR;
            default         -> IssueSeverity.INFO;
        };
    }

    private String getScannerBin() {
        return System.getProperty("os.name").toLowerCase().contains("windows")
                ? "sonar-scanner.bat" : "sonar-scanner";
    }
}
