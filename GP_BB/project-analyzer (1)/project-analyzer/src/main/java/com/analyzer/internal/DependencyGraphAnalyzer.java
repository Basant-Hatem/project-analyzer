package com.analyzer.internal;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DependencyGraphAnalyzer {

    private final JavaParser parser;

    public DependencyGraphAnalyzer() {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        this.parser = new JavaParser(cfg);
    }

    public List<Issue> analyze(List<Path> javaFiles) {
        List<Issue> issues = new ArrayList<>();

        // ── Step 1: Build the dependency graph ───────────────────────────
        Graph<String, DefaultEdge> graph =
                new DefaultDirectedGraph<>(DefaultEdge.class);

        // classname → filePath  (for reporting)
        Map<String, String> classToFile = new HashMap<>();
        // classname → list of classes it depends on
        Map<String, Set<String>> dependencies = new HashMap<>();
        // classname → is it used by anyone?
        Set<String> allClasses   = new HashSet<>();
        Set<String> usedClasses  = new HashSet<>();

        for (Path file : javaFiles) {
            try {
                var result = parser.parse(file);
                if (result.getResult().isEmpty()) continue;
                CompilationUnit cu = result.getResult().get();

                String pkg = cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString() + ".").orElse("");

                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                    String fqn = pkg + cls.getNameAsString();
                    allClasses.add(fqn);
                    classToFile.put(fqn, file.toString());
                    graph.addVertex(fqn);

                    // نجمع الـ types اللي بتستخدمها الـ class دي
                    Set<String> deps = new HashSet<>();
                    cls.findAll(ClassOrInterfaceType.class).forEach(t -> {
                        String typeName = t.getNameAsString();
                        // نبحث عن الـ FQN في نفس الـ package
                        String fqnDep = pkg + typeName;
                        deps.add(fqnDep);
                    });
                    dependencies.put(fqn, deps);
                });

            } catch (IOException e) {
                log.warn("Cannot parse {}", file);
            }
        }

        // ── Step 2: Add edges (only between known classes) ────────────────
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String from = entry.getKey();
            for (String to : entry.getValue()) {
                if (!to.equals(from) && allClasses.contains(to)) {
                    graph.addEdge(from, to);
                    usedClasses.add(to);
                }
            }
        }

        // ── Step 3: Detect circular dependencies ─────────────────────────
        CycleDetector<String, DefaultEdge> cycleDetector =
                new CycleDetector<>(graph);

        Set<String> cycleVertices = cycleDetector.findCycles();
        if (!cycleVertices.isEmpty()) {
            // نجمع الـ cycles في groups
            for (String vertex : cycleVertices) {
                Set<String> cycleSubset = cycleDetector.findCyclesContainingVertex(vertex);
                String cycleDesc = String.join(" → ", cycleSubset) + " → " + vertex;
                issues.add(Issue.builder()
                        .file(classToFile.getOrDefault(vertex, "unknown"))
                        .type(IssueType.CIRCULAR_DEPENDENCY)
                        .severity(IssueSeverity.CRITICAL)
                        .tool("INTERNAL")
                        .message("Circular dependency detected: " + cycleDesc)
                        .suggestedFix("Refactor to break the cycle using interfaces or dependency injection")
                        .autoFixable(false)
                        .build());
            }
        }

        // ── Step 4: Detect unused packages/classes ────────────────────────
        Set<String> unusedClasses = new HashSet<>(allClasses);
        unusedClasses.removeAll(usedClasses);

        for (String unused : unusedClasses) {
            String filePath = classToFile.getOrDefault(unused, "unknown");
            // نتجاهل الـ main classes و test classes و Spring Boot @SpringBootApplication
            if (unused.endsWith("Application") || unused.endsWith("Test")
                    || unused.contains("Config")) continue;

            issues.add(Issue.builder()
                    .file(filePath)
                    .type(IssueType.UNUSED_PACKAGE)
                    .severity(IssueSeverity.MINOR)
                    .tool("INTERNAL")
                    .message("Class appears unused within project: " + unused)
                    .suggestedFix("Verify if this class is used externally or remove it")
                    .autoFixable(false)
                    .build());
        }

        log.info("Graph: {} classes, {} edges, {} cycles, {} unused",
                allClasses.size(), graph.edgeSet().size(),
                cycleVertices.size(), unusedClasses.size());

        return issues;
    }
}
