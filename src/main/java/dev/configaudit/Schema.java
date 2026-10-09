package dev.configaudit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A small, explicit schema language; no expressions, scripts, or external references. */
public record Schema(Map<String, Setting> settings, List<Rule> rules) {
    static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9_./:@+,-]+|-[0-9]+");

    public enum Type { STRING, INTEGER, PORT, BOOLEAN, ENUM, URL }
    public enum RuleKind { REQUIRE_IF, FORBID_IF, EQUALS_IF, DIFFERENT }
    public record Setting(String name, Type type, boolean required, boolean secret,
                          Long min, Long max, Set<String> choices, Set<String> schemes) {
        public Setting {
            choices = Set.copyOf(choices);
            schemes = Set.copyOf(schemes);
        }
    }
    public record Rule(RuleKind kind, String trigger, String when, String target,
                       String expected, int line) { }

    public Schema {
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        rules = List.copyOf(rules);
    }

    public static Schema parse(String text) throws InputException {
        Map<String, Setting> settings = new LinkedHashMap<>();
        List<Rule> rules = new ArrayList<>();
        String[] lines = InputFiles.lines(text, "schema");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] words = line.split("\\s+");
            if (words[0].equals("setting")) {
                Setting setting = parseSetting(words, i + 1);
                if (settings.putIfAbsent(setting.name(), setting) != null)
                    throw error(i + 1, "duplicate setting declaration.");
            } else if (words[0].equals("rule")) {
                rules.add(parseRule(words, i + 1));
            } else throw error(i + 1, "expected a setting or rule directive.");
        }
        if (settings.isEmpty()) throw new InputException("schema must declare at least one setting.");
        for (Rule rule : rules) {
            Setting trigger = settings.get(rule.trigger());
            Setting target = settings.get(rule.target());
            if (trigger == null || target == null) throw error(rule.line(), "rule references an undeclared setting.");
            if (rule.kind() == RuleKind.DIFFERENT) {
                if (rule.trigger().equals(rule.target())) throw error(rule.line(), "different requires two distinct settings.");
                if (trigger.type() != target.type()) throw error(rule.line(), "different requires settings of the same type.");
            } else {
                if (ValueValidator.validate(trigger, rule.when()) != null)
                    throw error(rule.line(), "rule trigger literal violates its setting constraints.");
                if (rule.kind() == RuleKind.EQUALS_IF && ValueValidator.validate(target, rule.expected()) != null)
                    throw error(rule.line(), "rule expected literal violates its setting constraints.");
            }
        }
        return new Schema(settings, rules);
    }

    private static Setting parseSetting(String[] words, int line) throws InputException {
        if (words.length < 4) throw error(line, "setting requires a name, type, and required or optional marker.");
        if (!KEY.matcher(words[1]).matches()) throw error(line, "invalid setting name.");
        Type type;
        try { type = Type.valueOf(words[2].toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw error(line, "unknown setting type."); }
        if (!words[2].equals(type.name().toLowerCase(java.util.Locale.ROOT)))
            throw error(line, "type names must be lowercase.");
        boolean required;
        if (words[3].equals("required")) required = true;
        else if (words[3].equals("optional")) required = false;
        else throw error(line, "expected required or optional.");

        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 4; i < words.length; i++) {
            int equals = words[i].indexOf('=');
            if (equals <= 0 || equals == words[i].length() - 1) throw error(line, "malformed setting option.");
            String name = words[i].substring(0, equals);
            if (!Set.of("secret", "min", "max", "choices", "schemes").contains(name))
                throw error(line, "unknown setting option.");
            if (options.putIfAbsent(name, words[i].substring(equals + 1)) != null)
                throw error(line, "duplicate setting option.");
        }
        String secretOption = options.getOrDefault("secret", "false");
        if (!secretOption.equals("true") && !secretOption.equals("false"))
            throw error(line, "secret must be true or false.");
        Long min = bound(options.get("min"), line);
        Long max = bound(options.get("max"), line);
        if ((min != null || max != null) && type != Type.INTEGER && type != Type.STRING)
            throw error(line, "min and max are supported only for integer and string settings.");
        if (min != null && max != null && min > max) throw error(line, "min cannot exceed max.");
        if (type == Type.STRING && ((min != null && min < 0) || (max != null && max < 0)))
            throw error(line, "string length bounds cannot be negative.");
        if (options.containsKey("choices") != (type == Type.ENUM))
            throw error(line, "choices is required for enum and forbidden for other types.");
        if (options.containsKey("schemes") && type != Type.URL)
            throw error(line, "schemes is supported only for URL settings.");
        Set<String> choices = items(options.get("choices"), false, line);
        Set<String> schemes = items(options.get("schemes"), true, line);
        return new Setting(words[1], type, required, secretOption.equals("true"), min, max, choices, schemes);
    }

    private static Long bound(String value, int line) throws InputException {
        if (value == null) return null;
        try {
            if (!value.matches("-?(0|[1-9][0-9]*)")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw error(line, "bounds must be signed 64-bit decimal integers."); }
    }

    private static Set<String> items(String value, boolean schemes, int line) throws InputException {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String item : value.split(",", -1)) {
            boolean valid = schemes ? item.matches("[a-z][a-z0-9+.-]*") : item.matches("[A-Za-z0-9_.:/@+-]+");
            if (!valid || !result.add(item)) throw error(line, "list options require unique, nonempty valid items.");
        }
        return result;
    }

    private static Rule parseRule(String[] words, int line) throws InputException {
        if (words.length < 2) throw error(line, "missing rule kind.");
        RuleKind kind = switch (words[1]) {
            case "require-if" -> RuleKind.REQUIRE_IF;
            case "forbid-if" -> RuleKind.FORBID_IF;
            case "equals-if" -> RuleKind.EQUALS_IF;
            case "different" -> RuleKind.DIFFERENT;
            default -> throw error(line, "unknown rule kind.");
        };
        int count = switch (kind) { case DIFFERENT -> 4; case EQUALS_IF -> 6; default -> 5; };
        if (words.length != count) throw error(line, "incorrect number of rule arguments.");
        String target = words[kind == RuleKind.DIFFERENT ? 3 : 4];
        if (!KEY.matcher(words[2]).matches() || !KEY.matcher(target).matches())
            throw error(line, "invalid rule setting name.");
        String when = kind == RuleKind.DIFFERENT ? null : words[3];
        String expected = kind == RuleKind.EQUALS_IF ? words[5] : null;
        if ((when != null && !LITERAL.matcher(when).matches()) || (expected != null && !LITERAL.matcher(expected).matches()))
            throw error(line, "rule literals must be nonempty single tokens.");
        return new Rule(kind, words[2], when, target, expected, line);
    }

    private static InputException error(int line, String message) { return InputException.at("schema", line, message); }
}
