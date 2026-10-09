package dev.configaudit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free tests; explicit assertions run even without the -ea flag. */
public final class TestSuite {
    private static int passed;
    private static int failed;
    private static final String CANARY = "DO_NOT_LEAK_9e7a_CANARY";

    @FunctionalInterface private interface Test { void run() throws Exception; }
    private record Invocation(int status, String out, String err) { }

    private TestSuite() { }

    public static void main(String[] args) throws Exception {
        test("env quotes, exports, comments, literal shell syntax", TestSuite::envParsing);
        test("env rejects malformed and duplicate assignments", TestSuite::envFailures);
        test("properties separators, continuation and Unicode", TestSuite::propertiesParsing);
        test("properties rejects malformed escapes and duplicate keys", TestSuite::propertiesFailures);
        test("input limits, BOM, NUL and line endings", TestSuite::inputLimits);
        test("strict schema options and rule references", TestSuite::schemaFailures);
        test("schema collections are immutable", TestSuite::immutability);
        test("all types and numeric boundary values", TestSuite::typeValidation);
        test("URL shape, credentials, schemes and ports", TestSuite::urlValidation);
        test("conditional requirements and invalid triggers", TestSuite::conditionalRules);
        test("forbidden values must be absent even if blank", TestSuite::forbidRule);
        test("equality rules and numeric zero equivalence", TestSuite::equalityRules);
        test("different rules are typed and skip invalid values", TestSuite::differentRules);
        test("unknown setting policies change severity and status", TestSuite::unknownPolicies);
        test("reports are deterministic, immutable and value-free", TestSuite::reportSafety);
        test("JSON escaping and golden pass output", TestSuite::jsonOutput);
        test("CLI help, strict usage and exit codes", TestSuite::cliBehavior);
        test("CLI input failures preserve JSON and redact diagnostics", TestSuite::cliInputFailures);
        test("shipped examples match the documented outcomes", TestSuite::examples);
        System.out.println("\nTests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void test(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { failed++; System.err.println("FAIL " + name); e.printStackTrace(System.err); }
    }

    private static void envParsing() throws Exception {
        var config = env("""
                # comment
                export A = hello # trailing comment
                B='space # dollar $HOME'
                C="line\\nnext\\t\\\"quote\\\"\\\\end"
                D=abc#fragment
                E=$(echo NEVER_EXECUTED)
                F=${HOME}
                G= # empty value
                H="value" # comment
                """);
        equal("hello", config.get("A"));
        equal("space # dollar $HOME", config.get("B"));
        equal("line\nnext\t\"quote\"\\end", config.get("C"));
        equal("abc#fragment", config.get("D"));
        equal("$(echo NEVER_EXECUTED)", config.get("E"));
        equal("${HOME}", config.get("F"));
        equal("", config.get("G"));
        equal("value", config.get("H"));
    }

    private static void envFailures() throws Exception {
        for (String text : List.of("A=x\nA=y", "A x", "9A=x", "A.B=x", "A='unterminated",
                "A=\"bad\\q\"", "A=\"x\"tail", "A=\"x\"#comment", "export A", "export=ok\nexport=bad"))
            rejects(() -> env(text), "config");
    }

    private static void propertiesParsing() throws Exception {
        var config = properties("# comment\n! another\nA : hello\nB world\nC=multi\\\n   line\nD=\\u0041\\t\\:\\=\\ \\#\\!\\\\\nE=\\uD83D\\uDE80\nF=one  \n");
        equal("hello", config.get("A"));
        equal("world", config.get("B"));
        equal("multiline", config.get("C"));
        equal("A\t:= #!\\", config.get("D"));
        equal("🚀", config.get("E"));
        equal("one  ", config.get("F"));
        equal("value", properties("\\u0041=value").get("A"));
        equal("\\", properties("A=\\\\").get("A"));
    }

    private static void propertiesFailures() throws Exception {
        for (String text : List.of("A=x\nA=y", "A=x\n\\u0041=y", "A", "A=\\q", "A=\\uXYZ1",
                "A=\\u12", "A=\\u0000", "A=\\uD800", "A=\\uDC00", "A=\\uD800x", "A=unfinished\\", "A=unfinished\\\n", "a\\ b=x"))
            rejects(() -> properties(text), "config");
        // Only space, tab and form feed may precede a properties comment. An
        // EM SPACE is part of an invalid key, not permission to hide that line.
        for (String text : List.of("\u2003#TOKEN=hidden\nA=ok", "\u2003!TOKEN=hidden\nA=ok", "\u2003\nA=ok"))
            rejects(() -> properties(text), "config line 1");
        equal(Map.of("A", "ok"), properties(" \t\f# comment\n \t\f! comment\nA=ok"));
    }

    private static void inputLimits() throws Exception {
        equal(Map.of("A", "1", "B", "2", "C", "3"), env("\uFEFFA=1\r\nB=2\rC=3"));
        rejects(() -> env("A=zero\0"), "NUL");
        rejects(() -> env("A=" + "x".repeat(InputFiles.MAX_BYTES)), "limit");
        rejects(() -> env("#\n".repeat(InputFiles.MAX_LINES)), "line limit");
        rejects(() -> Schema.parse("setting A string required\0"), "NUL");
    }

    private static void schemaFailures() throws Exception {
        for (String schema : List.of("", "# empty", "unknown directive", "setting A", "setting 9A string required",
                "setting A STRING required", "setting A decimal required", "setting A string maybe",
                "setting A string required secret=TRUE", "setting A string required min=1 min=2",
                "setting A string required strange=true", "setting A string required min=", "setting A string required min=01",
                "setting A string required min=-1", "setting A integer required min=5 max=2",
                "setting A integer required max=9223372036854775808", "setting A boolean required min=1",
                "setting A enum required", "setting A enum required choices=a,a", "setting A enum required choices=a,",
                "setting A string required choices=a", "setting A url required schemes=HTTPS",
                "setting A string required schemes=https", "setting A string required\nsetting A string optional"))
            rejects(() -> Schema.parse(schema), "schema");
        String base = "setting A boolean required\nsetting B string optional\n";
        for (String rule : List.of("rule", "rule script A B", "rule require-if A true", "rule require-if A true B extra",
                "rule require-if A true C", "rule require-if A perhaps B", "rule equals-if A true B",
                "rule different A A", "rule different A B", "rule require-if A 'true' B"))
            rejects(() -> Schema.parse(base + rule), "schema line 3");
        rejects(() -> Schema.parse("setting A boolean required\nrule equals-if A true A FALSE"), "expected literal");
        Schema.parse("rule require-if A true B\n" + base); // Forward references are deliberate.
    }

    private static void immutability() throws Exception {
        Schema schema = Schema.parse("setting A enum required choices=x,y");
        unsupported(() -> schema.settings().clear());
        unsupported(() -> schema.settings().get("A").choices().add("z"));
        unsupported(() -> schema.rules().clear());
        unsupported(() -> env("A=1").put("B", "2"));
    }

    private static void typeValidation() throws Exception {
        valid("integer", Long.toString(Long.MIN_VALUE)); valid("integer", Long.toString(Long.MAX_VALUE));
        for (String value : List.of("9223372036854775808", "-9223372036854775809", "1.0", "+1", "01", "1e2", " 1"))
            invalid("integer", value, "INVALID_INTEGER");
        valid("port", "1"); valid("port", "65535");
        invalid("port", "0", "INVALID_PORT"); invalid("port", "65536", "INVALID_PORT");
        valid("integer min=-1 max=1", "-1"); valid("integer min=-1 max=1", "1");
        invalid("integer min=-1 max=1", "2", "OUT_OF_RANGE");
        valid("boolean", "true"); valid("boolean", "false"); invalid("boolean", "TRUE", "INVALID_BOOLEAN");
        invalid("boolean", "1", "INVALID_BOOLEAN");
        valid("enum choices=red,blue", "red"); invalid("enum choices=red,blue", "Red", "INVALID_ENUM");
        valid("string min=1 max=1", "🚀"); invalid("string min=1 max=1", "ab", "STRING_LENGTH");
        for (String value : List.of("", " ", "\t\n")) invalid("string", value, "EMPTY_VALUE");
        equal(Set.of("MISSING_REQUIRED"), codes(audit("setting A string required", Map.of())));
        equal(Set.of(), codes(audit("setting A string optional", Map.of())));
        invalid("string min=0", "", "EMPTY_VALUE");
    }

    private static void urlValidation() throws Exception {
        valid("url schemes=https", "https://example.test:443/path?q=a");
        valid("url schemes=https", "HTTPS://example.test/path");
        valid("url schemes=http", "http://[::1]:8080/");
        valid("url schemes=postgresql", "postgresql://db.example.test:5432/app");
        for (String value : List.of("/relative", "mailto:user@example.test", "https:///path", "https://bad host", "https://example.test/#fragment"))
            invalid("url", value, "INVALID_URL");
        invalid("url schemes=https", "http://example.test", "URL_SCHEME");
        invalid("url", "https://user:" + CANARY + "@example.test", "URL_CREDENTIALS");
        for (String value : List.of("https://example.test:0", "https://example.test:65536", "https://example.test:"))
            invalid("url", value, "INVALID_URL_PORT");
    }

    private static void conditionalRules() throws Exception {
        String schema = "setting TLS boolean required\nsetting CERT string optional\nrule require-if TLS true CERT";
        equal(Set.of("REQUIRED_BY_RULE"), codes(audit(schema, Map.of("TLS", "true"))));
        check(audit(schema, Map.of("TLS", "false")).passed(), "False trigger must not require target");
        check(audit(schema, Map.of("TLS", "true", "CERT", "/path")).passed(), "Valid pair must pass");
        equal(Set.of("INVALID_BOOLEAN"), codes(audit(schema, Map.of("TLS", "bad"))));
        equal(Set.of("EMPTY_VALUE", "REQUIRED_BY_RULE"), codes(audit(schema, Map.of("TLS", "true", "CERT", ""))));
    }

    private static void forbidRule() throws Exception {
        String schema = "setting PROD boolean required\nsetting DEBUG boolean optional\nrule forbid-if PROD true DEBUG";
        equal(Set.of("FORBIDDEN_BY_RULE"), codes(audit(schema, Map.of("PROD", "true", "DEBUG", "false"))));
        equal(Set.of("FORBIDDEN_BY_RULE", "EMPTY_VALUE"), codes(audit(schema, Map.of("PROD", "true", "DEBUG", ""))));
        check(audit(schema, Map.of("PROD", "true")).passed(), "Absent forbidden target must pass");
    }

    private static void equalityRules() throws Exception {
        String schema = "setting ENV enum required choices=prod,dev\nsetting TLS boolean optional\nrule equals-if ENV prod TLS true";
        equal(Set.of("RULE_VALUE"), codes(audit(schema, Map.of("ENV", "prod", "TLS", "false"))));
        equal(Set.of("RULE_VALUE"), codes(audit(schema, Map.of("ENV", "prod"))));
        equal(Set.of("INVALID_BOOLEAN"), codes(audit(schema, Map.of("ENV", "prod", "TLS", "invalid"))));
        check(audit(schema, Map.of("ENV", "prod", "TLS", "true")).passed(), "Expected match must pass");
        check(audit(schema, Map.of("ENV", "dev")).passed(), "Inactive rule must pass");
        String numeric = "setting A integer required\nsetting B integer required\nrule equals-if A -0 B 0";
        check(audit(numeric, Map.of("A", "0", "B", "-0")).passed(), "Numeric zeros should be equivalent");
    }

    private static void differentRules() throws Exception {
        String schema = "setting A port required\nsetting B port optional\nrule different A B";
        equal(Set.of("MUST_DIFFER"), codes(audit(schema, Map.of("A", "8080", "B", "8080"))));
        check(audit(schema, Map.of("A", "8080", "B", "9090")).passed(), "Distinct ports must pass");
        check(audit(schema, Map.of("A", "8080")).passed(), "Missing optional port must skip comparison");
        equal(Set.of("INVALID_INTEGER"), codes(audit(schema, Map.of("A", "bad", "B", "8080"))));
        var sensitive = audit("setting A string required secret=true\nsetting B string required\nrule different A B", Map.of("A", CANARY, "B", CANARY));
        check(sensitive.issues().get(0).sensitive(), "Either secret operand must mark the comparison sensitive");
    }

    private static void unknownPolicies() throws Exception {
        Schema schema = Schema.parse("setting A string required");
        var config = Map.of("A", "ok", "EXTRA", CANARY);
        var strict = Auditor.audit(schema, config, Auditor.UnknownPolicy.ERROR);
        equal(1L, strict.errors()); check(!strict.passed(), "Unknown must fail by default");
        var warning = Auditor.audit(schema, config, Auditor.UnknownPolicy.WARN);
        equal(1L, warning.warnings()); check(warning.passed(), "Warning must not fail");
        var ignored = Auditor.audit(schema, config, Auditor.UnknownPolicy.IGNORE);
        equal(0, ignored.issues().size()); equal(1, ignored.unknown()); equal(2, ignored.present());
    }

    private static void reportSafety() throws Exception {
        String schema = "setting A boolean required secret=true\nsetting B port required\nsetting C string optional";
        var first = audit(schema, Map.of("A", CANARY, "B", "0", "C", CANARY, "Z", CANARY));
        var second = audit(schema, env("Z=" + CANARY + "\nC=" + CANARY + "\nB=0\nA=" + CANARY));
        equal(List.of("A", "B", "Z"), first.issues().stream().map(Auditor.Issue::key).toList());
        for (var format : ReportRenderer.Format.values()) {
            String rendered = ReportRenderer.render(first, format);
            check(!rendered.contains(CANARY), "Report leaked a configuration value");
            equal(rendered, ReportRenderer.render(second, format));
        }
        unsupported(() -> first.issues().clear());
        rejects(() -> env("A=\"" + CANARY), "config line 1");
        rejects(() -> Schema.parse("setting A enum required choices=" + CANARY + "," + CANARY), "schema line 1");
        rejects(() -> Schema.parse("setting A boolean required\nrule require-if A " + CANARY + " A"), "schema line 2");
    }

    private static void jsonOutput() throws Exception {
        equal("\"a\\\"b\\\\c\\n\\r\\t\\u0001\\ud83d\\ude80\"", ReportRenderer.quote("a\"b\\c\n\r\t\u0001🚀"));
        equal("{\"status\":\"pass\",\"settings\":{\"declared\":1,\"present\":1,\"unknown\":0},\"errors\":0,\"warnings\":0,\"issues\":[]}\n",
                ReportRenderer.render(audit("setting A string required", Map.of("A", "ok")), ReportRenderer.Format.JSON));
        equal("{\"status\":\"error\",\"error\":{\"code\":\"INVALID_INPUT\",\"message\":\"safe\\nmessage\"}}\n",
                ReportRenderer.failure("safe\nmessage", ReportRenderer.Format.JSON));
    }

    private static void cliBehavior() throws Exception {
        Invocation help = invoke("--help"); equal(0, help.status()); check(help.out().contains("Usage:"), "Help must display usage"); equal("", help.err());
        for (String[] args : List.of(new String[]{}, new String[]{"--help", "extra"}, new String[]{"--config"},
                new String[]{"--schema", "a", "--config", "b", "--format", "unknown"},
                new String[]{"--schema", "a", "--schema", "b"}, new String[]{"--schema", "--config"},
                new String[]{"--" + CANARY, "value"})) {
            Invocation result = invoke(args); equal(2, result.status()); equal("", result.out());
            check(!result.err().contains(CANARY), "Usage error leaked an argument");
        }
        withTempDirectory(dir -> {
            Path schema = dir.resolve("schema"), config = dir.resolve("config");
            Files.writeString(schema, "setting PORT port required");
            Files.writeString(config, "PORT=8080");
            Invocation pass = invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env");
            equal(0, pass.status()); equal("", pass.err()); check(pass.out().contains("PASS"), "Expected pass text");
            Files.writeString(config, "PORT=0");
            Invocation fail = invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env", "--output", "json");
            equal(1, fail.status()); equal("", fail.err()); check(fail.out().contains("\"status\":\"fail\""), "Expected failure JSON");
            Files.writeString(config, "PORT=8080\nEXTRA=ok");
            equal(0, invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env", "--unknown", "warn").status());
        });
    }

    private static void cliInputFailures() throws Exception {
        withTempDirectory(dir -> {
            Path schema = dir.resolve("schema"), config = dir.resolve(CANARY);
            Files.writeString(schema, "setting A string required");
            List<byte[]> badInputs = new ArrayList<>();
            badInputs.add(new byte[]{(byte) 0xC3, (byte) 0x28});
            badInputs.add(("A=\"" + CANARY).getBytes(StandardCharsets.UTF_8));
            badInputs.add(new byte[InputFiles.MAX_BYTES + 1]);
            for (byte[] input : badInputs) {
                Files.write(config, input);
                Invocation result = invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env", "--output", "json");
                equal(2, result.status()); equal("", result.out());
                check(result.err().startsWith("{\"status\":\"error\""), "Input error must respect JSON output");
                check(!result.err().contains(CANARY), "Input error leaked data or path");
            }
            Files.delete(config);
            Invocation absent = invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env");
            equal(2, absent.status()); check(!absent.err().contains(CANARY), "Missing file error leaked its path");
            Files.writeString(config, "A=ok");
            Files.writeString(schema, "setting A boolean required\nrule require-if A " + CANARY + " A");
            Invocation invalidSchema = invoke("--schema", schema.toString(), "--config", config.toString(), "--format", "env");
            equal(2, invalidSchema.status()); check(!invalidSchema.err().contains(CANARY), "Schema diagnostic leaked a literal");
        });
    }

    private static void examples() throws Exception {
        Schema schema = Schema.parse(Files.readString(Path.of("examples/service.schema")));
        var env = ConfigParser.parse(Files.readString(Path.of("examples/valid.env")), ConfigParser.Format.ENV);
        var properties = ConfigParser.parse(Files.readString(Path.of("examples/valid.properties")), ConfigParser.Format.PROPERTIES);
        equal(env, properties);
        check(Auditor.audit(schema, env, Auditor.UnknownPolicy.ERROR).passed(), "Valid example must pass");
        var bad = ConfigParser.parse(Files.readString(Path.of("examples/invalid.env")), ConfigParser.Format.ENV);
        equal(Set.of("MUST_DIFFER", "STRING_LENGTH", "URL_SCHEME", "RULE_VALUE", "FORBIDDEN_BY_RULE", "OUT_OF_RANGE", "UNKNOWN_SETTING"),
                codes(Auditor.audit(schema, bad, Auditor.UnknownPolicy.ERROR)));
    }

    @FunctionalInterface private interface DirectoryTest { void run(Path directory) throws Exception; }
    private static void withTempDirectory(DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("config-audit-test-");
        try { test.run(directory); }
        finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Invocation invoke(String... args) throws IOException {
        var outBytes = new ByteArrayOutputStream(); var errBytes = new ByteArrayOutputStream();
        int status;
        try (var out = new PrintStream(outBytes, true, StandardCharsets.UTF_8); var err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            status = Main.run(args, out, err);
        }
        return new Invocation(status, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, String> env(String text) throws InputException { return ConfigParser.parse(text, ConfigParser.Format.ENV); }
    private static Map<String, String> properties(String text) throws InputException { return ConfigParser.parse(text, ConfigParser.Format.PROPERTIES); }
    private static Auditor.Report audit(String schema, Map<String, String> config) throws InputException {
        return Auditor.audit(Schema.parse(schema), config, Auditor.UnknownPolicy.ERROR);
    }
    private static Set<String> codes(Auditor.Report report) { return Set.copyOf(report.issues().stream().map(Auditor.Issue::code).toList()); }
    private static String declaration(String definition) {
        int space = definition.indexOf(' ');
        return "setting A " + (space < 0 ? definition + " required" : definition.substring(0, space) + " required" + definition.substring(space));
    }
    private static void valid(String definition, String value) throws Exception {
        equal(Set.of(), codes(audit(declaration(definition), Map.of("A", value))));
    }
    private static void invalid(String definition, String value, String code) throws Exception {
        equal(Set.of(code), codes(audit(declaration(definition), Map.of("A", value))));
    }
    private static void rejects(Test test, String diagnostic) throws Exception {
        try { test.run(); throw new AssertionError("Expected InputException"); }
        catch (InputException e) {
            check(e.getMessage().contains(diagnostic), "Unexpected safe diagnostic: " + e.getMessage());
            check(!e.getMessage().contains(CANARY), "Diagnostic leaked a secret canary");
        }
    }
    private static void unsupported(Test test) throws Exception {
        try { test.run(); throw new AssertionError("Expected immutable collection"); }
        catch (UnsupportedOperationException expected) { /* Expected. */ }
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
