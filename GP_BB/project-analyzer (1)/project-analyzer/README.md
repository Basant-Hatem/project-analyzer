# Project Analyzer - Spring Boot

##  Architecture
```
ProjectAnalyzer
├── InternalAnalysis
│     ├── ImportAnalyzer        (JavaParser - missing/unused/duplicate imports)
│     ├── ConfigAnalyzer        (SnakeYAML - yml/properties/.env)
│     └── DependencyGraphAnalyzer (JGraphT - circular deps)
├── ExternalToolsRunner
│     ├── MavenRunner           (pom.xml analysis + mvn commands)
│     ├── PMDRunner             (auto-download + static analysis)
│     └── SonarRunner           (auto-download scanner + API)
├── IssueCollector              (Unified Issue Model + dedup)
└── AutoFixEngine
      ├── ImportFixer           (add/remove imports)
      ├── DependencyFixer       (pom.xml / build.gradle)
      └── ConfigFixer           (yml / properties)
```

## Quick Start
```bash
mvn spring-boot:run
```

## API Usage

### Analyze a project
```bash
curl -X POST http://localhost:8080/api/v1/analyzer/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "projectPath": "/path/to/your/java/project",
    "autoFix": false,
    "sonarProjectKey": "my-project"
  }'
```

### With AutoFix enabled
```bash
curl -X POST http://localhost:8080/api/v1/analyzer/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "projectPath": "/path/to/your/java/project",
    "autoFix": true
  }'
```

### Check health
```bash
curl http://localhost:8080/api/v1/analyzer/health
```

## Configuration (application.yml)
```yaml
analyzer:
  tools:
    dir: ~/.analyzer-tools    # PMD & Sonar Scanner يتحملوا هنا تلقائياً
  sonar:
    url:   http://localhost:9000
    token: your-sonar-token   # اختياري
```

## Issue Model (Unified)
```json
{
  "file":         "src/main/java/.../UserService.java",
  "line":         15,
  "type":         "MISSING_IMPORT",
  "severity":     "BLOCKER",
  "tool":         "INTERNAL",
  "message":      "Missing import for: Service",
  "suggestedFix": "Add: import org.springframework.stereotype.Service;",
  "autoFixable":  true,
  "ruleId":       "org.springframework.stereotype.Service"
}
```

## Detected Issue Types
| Type | Tool | Auto-Fix |
|------|------|----------|
| MISSING_IMPORT | INTERNAL | ✅ |
| UNUSED_IMPORT | INTERNAL | ✅ |
| DUPLICATE_IMPORT | INTERNAL | ✅ |
| MISSING_DEPENDENCY | MAVEN | ✅ |
| VERSION_CONFLICT | MAVEN | ✅ |
| DEPRECATED_DEPENDENCY | MAVEN | ✅ |
| MISSING_CONFIG_KEY | INTERNAL | ✅ |
| INVALID_CONFIG_VALUE | INTERNAL | ❌ |
| CIRCULAR_DEPENDENCY | INTERNAL | ❌ |
| CODE_VIOLATION | PMD | ❌ |
| BUG / CODE_SMELL | SONAR | ❌ |
| SECURITY_VULNERABILITY | SONAR | ❌ |
