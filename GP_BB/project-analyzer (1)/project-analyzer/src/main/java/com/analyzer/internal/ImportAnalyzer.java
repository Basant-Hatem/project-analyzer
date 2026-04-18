package com.analyzer.internal;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ImportAnalyzer {

    // ── Known type → FQN mapping ─────────────────────────────────────────
    private static final Map<String, String> KNOWN_IMPORTS = new LinkedHashMap<>() {{
        // java.util
        put("List","java.util.List"); put("ArrayList","java.util.ArrayList");
        put("LinkedList","java.util.LinkedList");
        put("Map","java.util.Map"); put("HashMap","java.util.HashMap");
        put("LinkedHashMap","java.util.LinkedHashMap");
        put("Set","java.util.Set"); put("HashSet","java.util.HashSet");
        put("TreeSet","java.util.TreeSet"); put("TreeMap","java.util.TreeMap");
        put("Optional","java.util.Optional");
        put("Arrays","java.util.Arrays"); put("Collections","java.util.Collections");
        put("Objects","java.util.Objects"); put("UUID","java.util.UUID");
        put("Date","java.util.Date"); put("Calendar","java.util.Calendar");
        put("Queue","java.util.Queue"); put("Deque","java.util.Deque");
        put("Stack","java.util.Stack"); put("Properties","java.util.Properties");
        // java.util.stream
        put("Stream","java.util.stream.Stream");
        put("Collectors","java.util.stream.Collectors");
        put("IntStream","java.util.stream.IntStream");
        // java.time
        put("LocalDate","java.time.LocalDate");
        put("LocalDateTime","java.time.LocalDateTime");
        put("LocalTime","java.time.LocalTime");
        put("ZonedDateTime","java.time.ZonedDateTime");
        put("Instant","java.time.Instant");
        put("Duration","java.time.Duration");
        put("Period","java.time.Period");
        put("DateTimeFormatter","java.time.format.DateTimeFormatter");
        // java.math
        put("BigDecimal","java.math.BigDecimal");
        put("BigInteger","java.math.BigInteger");
        // java.io / java.nio
        put("File","java.io.File"); put("IOException","java.io.IOException");
        put("InputStream","java.io.InputStream");
        put("OutputStream","java.io.OutputStream");
        put("BufferedReader","java.io.BufferedReader");
        put("FileReader","java.io.FileReader");
        put("PrintWriter","java.io.PrintWriter");
        put("Path","java.nio.file.Path"); put("Paths","java.nio.file.Paths");
        put("Files","java.nio.file.Files");
        // Spring
        put("SpringApplication","org.springframework.boot.SpringApplication");
        put("SpringBootApplication","org.springframework.boot.autoconfigure.SpringBootApplication");
        put("Component","org.springframework.stereotype.Component");
        put("Service","org.springframework.stereotype.Service");
        put("Repository","org.springframework.stereotype.Repository");
        put("Controller","org.springframework.stereotype.Controller");
        put("RestController","org.springframework.web.bind.annotation.RestController");
        put("Autowired","org.springframework.beans.factory.annotation.Autowired");
        put("GetMapping","org.springframework.web.bind.annotation.GetMapping");
        put("PostMapping","org.springframework.web.bind.annotation.PostMapping");
        put("PutMapping","org.springframework.web.bind.annotation.PutMapping");
        put("DeleteMapping","org.springframework.web.bind.annotation.DeleteMapping");
        put("RequestMapping","org.springframework.web.bind.annotation.RequestMapping");
        put("RequestBody","org.springframework.web.bind.annotation.RequestBody");
        put("RequestParam","org.springframework.web.bind.annotation.RequestParam");
        put("PathVariable","org.springframework.web.bind.annotation.PathVariable");
        put("ResponseEntity","org.springframework.http.ResponseEntity");
        put("HttpStatus","org.springframework.http.HttpStatus");
        put("Value","org.springframework.beans.factory.annotation.Value");
        put("Bean","org.springframework.context.annotation.Bean");
        put("Configuration","org.springframework.context.annotation.Configuration");
        put("Transactional","org.springframework.transaction.annotation.Transactional");
        put("Cacheable","org.springframework.cache.annotation.Cacheable");
        put("Async","org.springframework.scheduling.annotation.Async");
        put("Scheduled","org.springframework.scheduling.annotation.Scheduled");
        // Lombok
        put("Data","lombok.Data"); put("Builder","lombok.Builder");
        put("Getter","lombok.Getter"); put("Setter","lombok.Setter");
        put("Slf4j","lombok.extern.slf4j.Slf4j");
        put("Log4j2","lombok.extern.log4j.Log4j2");
        put("NoArgsConstructor","lombok.NoArgsConstructor");
        put("AllArgsConstructor","lombok.AllArgsConstructor");
        put("RequiredArgsConstructor","lombok.RequiredArgsConstructor");
        put("EqualsAndHashCode","lombok.EqualsAndHashCode");
        put("ToString","lombok.ToString");
        // JPA
        put("Entity","jakarta.persistence.Entity");
        put("Table","jakarta.persistence.Table");
        put("Id","jakarta.persistence.Id");
        put("Column","jakarta.persistence.Column");
        put("GeneratedValue","jakarta.persistence.GeneratedValue");
        put("GenerationType","jakarta.persistence.GenerationType");
        put("OneToMany","jakarta.persistence.OneToMany");
        put("ManyToOne","jakarta.persistence.ManyToOne");
        put("ManyToMany","jakarta.persistence.ManyToMany");
        put("JoinColumn","jakarta.persistence.JoinColumn");
        put("MappedSuperclass","jakarta.persistence.MappedSuperclass");
        put("Enumerated","jakarta.persistence.Enumerated");
        put("EnumType","jakarta.persistence.EnumType");
        // Validation
        put("NotNull","jakarta.validation.constraints.NotNull");
        put("NotBlank","jakarta.validation.constraints.NotBlank");
        put("NotEmpty","jakarta.validation.constraints.NotEmpty");
        put("Size","jakarta.validation.constraints.Size");
        put("Min","jakarta.validation.constraints.Min");
        put("Max","jakarta.validation.constraints.Max");
        put("Email","jakarta.validation.constraints.Email");
        put("Pattern","jakarta.validation.constraints.Pattern");
        put("Valid","jakarta.validation.Valid");
        // Testing
        put("Test","org.junit.jupiter.api.Test");
        put("BeforeEach","org.junit.jupiter.api.BeforeEach");
        put("AfterEach","org.junit.jupiter.api.AfterEach");
        put("BeforeAll","org.junit.jupiter.api.BeforeAll");
        put("AfterAll","org.junit.jupiter.api.AfterAll");
        put("Assertions","org.junit.jupiter.api.Assertions");
        put("Mock","org.mockito.Mock");
        put("InjectMocks","org.mockito.InjectMocks");
        put("Mockito","org.mockito.Mockito");
        put("ExtendWith","org.junit.jupiter.api.extension.ExtendWith");
        put("MockitoExtension","org.mockito.junit.jupiter.MockitoExtension");
        put("SpringBootTest","org.springframework.boot.test.context.SpringBootTest");
        // Logging
        put("Logger","org.slf4j.Logger");
        put("LoggerFactory","org.slf4j.LoggerFactory");
        // Jackson
        put("JsonProperty","com.fasterxml.jackson.annotation.JsonProperty");
        put("JsonIgnore","com.fasterxml.jackson.annotation.JsonIgnore");
        put("ObjectMapper","com.fasterxml.jackson.databind.ObjectMapper");
        put("JsonNode","com.fasterxml.jackson.databind.JsonNode");
    }};

    private static final Set<String> BUILTINS = Set.of(
            "void","int","long","double","float","boolean","char","byte","short",
            "String","Object","Number","Integer","Long","Double","Float","Boolean",
            "Character","Byte","Short","Void","Exception","RuntimeException",
            "IllegalArgumentException","NullPointerException","IllegalStateException",
            "UnsupportedOperationException","Override","SuppressWarnings","Deprecated",
            "FunctionalInterface","System","Math","Thread","Runnable","Comparable",
            "Iterable","Enum","Record","var","T","E","K","V","R","N","U"
    );

    // ── Public API ────────────────────────────────────────────────────────

    public List<Issue> analyze(Path filePath) {
        List<Issue> issues = new ArrayList<>();
        try {
            // Create a fresh parser per call — JavaParser is NOT thread-safe
            ParserConfiguration cfg = new ParserConfiguration();
            cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
            JavaParser localParser = new JavaParser(cfg);

            ParseResult<CompilationUnit> result = localParser.parse(filePath);

            // File may have syntax errors — only proceed if a result was produced
            if (!result.getResult().isPresent()) {
                log.debug("JavaParser produced no result for: {}", filePath);
                return issues;
            }

            CompilationUnit cu = result.getResult().get();
            String relativePath = filePath.toString();

            List<ImportDeclaration> imports = cu.getImports();
            List<String> importNames = imports.stream()
                    .map(ImportDeclaration::getNameAsString)
                    .collect(Collectors.toList());

            // 1. Duplicate imports
            findDuplicates(imports, relativePath, issues);

            // 2. Missing imports
            findMissing(cu, importNames, relativePath, issues);

            // 3. Unused imports
            findUnused(cu, imports, relativePath, issues);

        } catch (IOException e) {
            log.warn("Cannot read file: {}", filePath, e);
        } catch (Throwable t) {
            // Catch AssertionError / any JavaParser internal failures gracefully
            log.warn("JavaParser failed on file (skipping): {} — {}", filePath, t.getMessage());
        }
        return issues;
    }

    public List<Issue> analyzeAll(List<Path> files) {
        // Sequential stream — JavaParser instances are created per-call but
        // sharing state via parallelStream previously caused AssertionErrors.
        return files.stream()
                .flatMap(f -> analyze(f).stream())
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void findDuplicates(List<ImportDeclaration> imports,
                                String filePath, List<Issue> issues) {
        Set<String> seen = new HashSet<>();
        for (ImportDeclaration imp : imports) {
            String name = imp.getNameAsString();
            if (!seen.add(name)) {
                issues.add(Issue.builder()
                        .file(filePath)
                        .line(imp.getBegin().map(p -> p.line).orElse(0))
                        .type(IssueType.DUPLICATE_IMPORT)
                        .severity(IssueSeverity.MINOR)
                        .tool("INTERNAL")
                        .message("Duplicate import: " + name)
                        .suggestedFix("Remove duplicate import statement")
                        .autoFixable(true)
                        .build());
            } else {
                seen.add(name);
            }
        }
    }

    private void findMissing(CompilationUnit cu, List<String> existingImports,
                             String filePath, List<Issue> issues) {
        Set<String> referenced = new LinkedHashSet<>();

        cu.findAll(AnnotationExpr.class)
                .forEach(a -> referenced.add(a.getNameAsString()));
        cu.findAll(ClassOrInterfaceType.class)
                .forEach(t -> referenced.add(t.getNameAsString()));

        for (String typeName : referenced) {
            if (BUILTINS.contains(typeName)) continue;
            boolean imported = existingImports.stream()
                    .anyMatch(i -> i.endsWith("." + typeName) || i.endsWith(".*"));
            if (!imported && KNOWN_IMPORTS.containsKey(typeName)) {
                String fqn = KNOWN_IMPORTS.get(typeName);
                issues.add(Issue.builder()
                        .file(filePath)
                        .type(IssueType.MISSING_IMPORT)
                        .severity(IssueSeverity.BLOCKER)
                        .tool("INTERNAL")
                        .message("Missing import for type: " + typeName)
                        .suggestedFix("Add: import " + fqn + ";")
                        .autoFixable(true)
                        .ruleId(fqn)
                        .build());
            }
        }
    }

    private void findUnused(CompilationUnit cu, List<ImportDeclaration> imports,
                            String filePath, List<Issue> issues) {
        // نجمع كل الـ identifiers المستخدمة في الكود
        Set<String> usedNames = new HashSet<>();
        cu.findAll(AnnotationExpr.class).forEach(a -> usedNames.add(a.getNameAsString()));
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> usedNames.add(t.getNameAsString()));
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> usedNames.add(c.getNameAsString()));
        cu.findAll(EnumDeclaration.class).forEach(e -> usedNames.add(e.getNameAsString()));

        for (ImportDeclaration imp : imports) {
            if (imp.isAsterisk()) continue; // نتجاهل wildcard imports
            String fqn = imp.getNameAsString();
            String simpleName = fqn.contains(".")
                    ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;

            if (!usedNames.contains(simpleName)) {
                issues.add(Issue.builder()
                        .file(filePath)
                        .line(imp.getBegin().map(p -> p.line).orElse(0))
                        .type(IssueType.UNUSED_IMPORT)
                        .severity(IssueSeverity.MINOR)
                        .tool("INTERNAL")
                        .message("Unused import: " + fqn)
                        .suggestedFix("Remove unused import: " + fqn)
                        .autoFixable(true)
                        .ruleId(fqn)
                        .build());
            }
        }
    }

    public String resolveImport(String typeName) {
        return KNOWN_IMPORTS.get(typeName);
    }
}