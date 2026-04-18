package com.analyzer.collector;

import com.analyzer.model.Issue;
import com.analyzer.model.IssueSeverity;
import com.analyzer.model.IssueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class IssueCollector {

    /**
     * يجمع كل الـ issues من كل الـ tools في list واحدة
     * ويشيل الـ duplicates
     */
    public List<Issue> collect(List<Issue>... issueLists) {
        List<Issue> all = new ArrayList<>();
        for (List<Issue> list : issueLists) {
            if (list != null) all.addAll(list);
        }

        // إزالة الـ duplicates (نفس الـ file + line + type + tool)
        List<Issue> deduplicated = deduplicate(all);

        // Sort: BLOCKER → CRITICAL → MAJOR → MINOR → INFO
        deduplicated.sort(Comparator
                .comparing((Issue i) -> i.getSeverity().ordinal())
                .thenComparing(i -> i.getFile() != null ? i.getFile() : "")
                .thenComparing(i -> i.getLine() != null ? i.getLine() : 0));

        log.info("IssueCollector: {} raw → {} after dedup", all.size(), deduplicated.size());
        return deduplicated;
    }

    /**
     * يجمع الـ issues في Map حسب الـ severity
     */
    public Map<IssueSeverity, Long> groupBySeverity(List<Issue> issues) {
        return issues.stream()
                .collect(Collectors.groupingBy(
                        Issue::getSeverity,
                        Collectors.counting()));
    }

    /**
     * يجمع الـ issues في Map حسب الـ type
     */
    public Map<IssueType, Long> groupByType(List<Issue> issues) {
        return issues.stream()
                .collect(Collectors.groupingBy(
                        Issue::getType,
                        Collectors.counting()));
    }

    /**
     * يجمع الـ issues في Map حسب الـ tool
     */
    public Map<String, Long> groupByTool(List<Issue> issues) {
        return issues.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getTool() != null ? i.getTool() : "UNKNOWN",
                        Collectors.counting()));
    }

    /**
     * يجيب بس الـ auto-fixable issues
     */
    public List<Issue> getAutoFixable(List<Issue> issues) {
        return issues.stream()
                .filter(Issue::isAutoFixable)
                .collect(Collectors.toList());
    }

    /**
     * يجيب الـ issues حسب severity معينة
     */
    public List<Issue> getBySeverity(List<Issue> issues, IssueSeverity severity) {
        return issues.stream()
                .filter(i -> i.getSeverity() == severity)
                .collect(Collectors.toList());
    }

    /**
     * يجيب الـ issues حسب ملف معين
     */
    public Map<String, List<Issue>> groupByFile(List<Issue> issues) {
        return issues.stream()
                .filter(i -> i.getFile() != null)
                .collect(Collectors.groupingBy(Issue::getFile));
    }

    // ── Private ───────────────────────────────────────────────────────────

    private List<Issue> deduplicate(List<Issue> issues) {
        Set<String> seen = new LinkedHashSet<>();
        List<Issue> result = new ArrayList<>();

        for (Issue issue : issues) {
            // key = file:line:type:tool:message (first 50 chars)
            String msg = issue.getMessage() != null
                    ? issue.getMessage().substring(0, Math.min(50, issue.getMessage().length()))
                    : "";
            String key = String.join("|",
                    issue.getFile()    != null ? issue.getFile() : "",
                    issue.getLine()    != null ? issue.getLine().toString() : "0",
                    issue.getType()    != null ? issue.getType().name() : "",
                    issue.getTool()    != null ? issue.getTool() : "",
                    msg);

            if (seen.add(key)) result.add(issue);
        }
        return result;
    }
}
