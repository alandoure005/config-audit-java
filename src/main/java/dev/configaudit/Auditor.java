package dev.configaudit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Auditor {
    public enum UnknownPolicy { ERROR, WARN, IGNORE }
    public enum Severity { ERROR, WARNING }
    public record Issue(Severity severity, String key, String code, String message, boolean sensitive) { }
    public record Report(int declared, int present, int unknown, List<Issue> issues) {
        public Report { issues = List.copyOf(issues); }
        public long errors() { return issues.stream().filter(i -> i.severity() == Severity.ERROR).count(); }
        public long warnings() { return issues.stream().filter(i -> i.severity() == Severity.WARNING).count(); }
        public boolean passed() { return errors() == 0; }
    }

    private Auditor() { }

    public static Report audit(Schema schema, Map<String, String> config, UnknownPolicy unknownPolicy) {
        List<Issue> issues = new ArrayList<>();
        Set<String> valid = new HashSet<>();
        for (Schema.Setting setting : schema.settings().values()) {
            String value = config.get(setting.name());
            if (value == null) {
                if (setting.required()) issues.add(issue(setting, "MISSING_REQUIRED", "Required setting is absent."));
                continue;
            }
            ValueValidator.Problem problem = ValueValidator.validate(setting, value);
            if (problem != null) issues.add(issue(setting, problem.code(), problem.message()));
            else valid.add(setting.name());
        }
        int unknown = 0;
        for (String key : config.keySet()) {
            if (schema.settings().containsKey(key)) continue;
            unknown++;
            if (unknownPolicy != UnknownPolicy.IGNORE)
                issues.add(new Issue(unknownPolicy == UnknownPolicy.ERROR ? Severity.ERROR : Severity.WARNING,
                        key, "UNKNOWN_SETTING", "Setting is not declared in the schema.", false));
        }
        for (Schema.Rule rule : schema.rules()) {
            Schema.Setting target = schema.settings().get(rule.target());
            if (rule.kind() == Schema.RuleKind.DIFFERENT) {
                if (valid.contains(rule.trigger()) && valid.contains(rule.target())
                        && equivalent(schema.settings().get(rule.trigger()), config.get(rule.trigger()), config.get(rule.target())))
                    issues.add(new Issue(Severity.ERROR, target.name(), "MUST_DIFFER",
                            "Value must differ from setting " + rule.trigger() + ".", target.secret() || schema.settings().get(rule.trigger()).secret()));
                continue;
            }
            if (!valid.contains(rule.trigger()) || !equivalent(schema.settings().get(rule.trigger()), config.get(rule.trigger()), rule.when())) continue;
            boolean present = config.containsKey(rule.target()) && !config.get(rule.target()).isBlank();
            switch (rule.kind()) {
                case REQUIRE_IF -> {
                    if (!present) issues.add(issue(target, "REQUIRED_BY_RULE", "Setting is required by a condition on " + rule.trigger() + "."));
                }
                case FORBID_IF -> {
                    if (config.containsKey(rule.target())) issues.add(issue(target, "FORBIDDEN_BY_RULE", "Setting must be absent under a condition on " + rule.trigger() + "."));
                }
                case EQUALS_IF -> {
                    if (!present || (valid.contains(rule.target()) && !equivalent(target, config.get(rule.target()), rule.expected())))
                        issues.add(issue(target, "RULE_VALUE", "Setting must match the value required by a condition on " + rule.trigger() + "."));
                }
                default -> throw new IllegalStateException("Unexpected rule kind");
            }
        }
        issues.sort(Comparator.comparing(Issue::key).thenComparing(Issue::code).thenComparing(Issue::message));
        return new Report(schema.settings().size(), config.size(), unknown, issues);
    }

    private static boolean equivalent(Schema.Setting setting, String left, String right) {
        // Numbers have one semantic zero even though -0 is a valid lexical representation.
        if (setting.type() == Schema.Type.INTEGER || setting.type() == Schema.Type.PORT) {
            try { return Long.parseLong(left) == Long.parseLong(right); }
            catch (NumberFormatException e) { return false; }
        }
        return left.equals(right);
    }

    private static Issue issue(Schema.Setting setting, String code, String message) {
        return new Issue(Severity.ERROR, setting.name(), code, message, setting.secret());
    }
}
