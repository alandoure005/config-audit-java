package dev.configaudit;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Main {
    private static final String USAGE = """
            Usage: config-audit --schema FILE --config FILE --format env|properties
                                [--output text|json] [--unknown error|warn|ignore]
                   config-audit --help

            Exit codes: 0 pass, 1 validation failure, 2 usage/input failure.
            Values are never printed. Input files are UTF-8, at most 1 MiB each.
            """;

    private Main() { }

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--help")) { out.print(USAGE); return 0; }
        Options options;
        try { options = Options.parse(args); }
        catch (InputException e) { err.print("ConfigAudit: ERROR\n" + e.getMessage() + "\n" + USAGE); return 2; }
        try {
            Schema schema = Schema.parse(InputFiles.read(options.schema(), "schema"));
            var config = ConfigParser.parse(InputFiles.read(options.config(), "config"), options.format());
            Auditor.Report report = Auditor.audit(schema, config, options.unknown());
            out.print(ReportRenderer.render(report, options.output()));
            return report.passed() ? 0 : 1;
        } catch (InputException e) {
            err.print(ReportRenderer.failure(e.getMessage(), options.output()));
            return 2;
        }
    }

    private record Options(Path schema, Path config, ConfigParser.Format format,
                           ReportRenderer.Format output, Auditor.UnknownPolicy unknown) {
        static Options parse(String[] args) throws InputException {
            Map<String, String> options = new HashMap<>();
            Set<String> supported = Set.of("--schema", "--config", "--format", "--output", "--unknown");
            for (int i = 0; i < args.length; i += 2) {
                if (!supported.contains(args[i])) throw new InputException("Unknown command-line option.");
                if (i + 1 >= args.length || args[i + 1].isBlank() || args[i + 1].startsWith("--"))
                    throw new InputException("Every option requires a value.");
                if (options.putIfAbsent(args[i], args[i + 1]) != null) throw new InputException("Duplicate command-line option.");
            }
            if (!options.keySet().containsAll(Set.of("--schema", "--config", "--format")))
                throw new InputException("Schema, config, and format are required.");
            ConfigParser.Format format = switch (options.get("--format")) {
                case "env" -> ConfigParser.Format.ENV; case "properties" -> ConfigParser.Format.PROPERTIES;
                default -> throw new InputException("Format must be env or properties.");
            };
            ReportRenderer.Format output = switch (options.getOrDefault("--output", "text")) {
                case "text" -> ReportRenderer.Format.TEXT; case "json" -> ReportRenderer.Format.JSON;
                default -> throw new InputException("Output must be text or json.");
            };
            Auditor.UnknownPolicy unknown = switch (options.getOrDefault("--unknown", "error")) {
                case "error" -> Auditor.UnknownPolicy.ERROR; case "warn" -> Auditor.UnknownPolicy.WARN; case "ignore" -> Auditor.UnknownPolicy.IGNORE;
                default -> throw new InputException("Unknown-setting policy must be error, warn, or ignore.");
            };
            try { return new Options(Path.of(options.get("--schema")), Path.of(options.get("--config")), format, output, unknown); }
            catch (InvalidPathException e) { throw new InputException("Invalid input file path."); }
        }
    }
}
