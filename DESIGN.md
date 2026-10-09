# Design notes

## Problem and boundary

Many deployment mistakes are valid text: a port is in range but collides with a second listener, an optional debug switch is present in production, or TLS is enabled without a certificate-path setting. ConfigAudit separates parsing from a deployment contract so these cases can be checked before launching a process.

The boundary is deliberately narrow. The tool reads two bounded local files, validates their contents, and writes one report. It does not inspect the surrounding environment or make network calls. A successful audit means the data satisfies the given schema, not that a service is reachable or secure.

## Data flow

```text
CLI options
    |
    +--> bounded UTF-8 reader --> Schema.parse --> immutable schema
    |
    +--> bounded UTF-8 reader --> ConfigParser --> immutable assignments
                                                       |
                        ValueValidator <---------- Auditor
                                                       |
                                                immutable report
                                                       |
                                              text / JSON renderer
```

- `Main` owns options, stream selection, and exit codes. Its `run` method accepts output streams so command behavior can be tested without replacing JVM globals.
- `InputFiles` handles byte limits and strict UTF-8 decoding. Parsers additionally limit line counts and reject NUL characters.
- `Schema` compiles the declaration language, validates options and references, and checks rule literals before any configuration is audited.
- `ConfigParser` preserves duplicate-detection information that a direct load into `java.util.Properties` would lose. Env and properties syntax are distinct and selected explicitly.
- `ValueValidator` is shared by schema-literal checking and runtime value checking. It returns stable codes with fixed messages, without retaining the rejected value.
- `Auditor` checks declared settings, handles unknown keys, then evaluates cross-field rules whose triggers passed type validation. It stores no values in the resulting report.
- `ReportRenderer` consumes only findings and counts. It cannot print configuration values because it does not receive them.

## Safety of diagnostics

The strongest default is to omit all values, rather than guess which strings are secrets. `secret=true` is metadata on findings, not permission to display other values. Parser exceptions contain only a source category, line number where relevant, and a fixed explanation. File-system and URI exception messages are never copied into reports because they can contain input paths or values.

Unknown keys still appear by name. The parser restricts names to a small printable alphabet to avoid multiline or terminal-control injection. Callers embedding the library must supply parsed data, or enforce the same constraints themselves. Public record constructors and `Auditor.audit` are not a separate untrusted-input validation boundary; the supported untrusted input boundary is the CLI/parsers.

The tests inject a recognizable canary into valid values, rejected values, rule literals, unknown-setting values, malformed quoting, and input filenames. Both renderers and input/usage errors are checked for leakage. This is regression evidence for the covered paths, not a claim of formal noninterference or memory erasure.

## Determinism

Each setting contributes at most one primary type finding. Rules can add their own findings. Invalid triggers are skipped to prevent misleading cascades. Findings sort by key, code, and message; neither source map iteration order nor timestamps affect reports. Integer comparisons use numeric equality; other types compare exact strings. Reports and parsed models defensively copy collections.

Exit `1` distinguishes a violated deployment contract from exit `2`, where no reliable audit could be completed. This lets a CI consumer separate a configuration defect from malformed input or an invocation mistake. Warnings do not change a passing exit code.

## Complexity and resource limits

Let `B` be total input size, `S` the number of settings, `R` the number of rules, and `I` the number of findings. Parsing is linear in input size; auditing is approximately `O(B + S + R)` plus `O(I log I)` for stable finding order. Inputs are each capped at 1 MiB and 10,000 split lines to bound memory and work. The program retains decoded inputs and values in memory during the audit; it is intended for configuration files, not bulk datasets.

## Tradeoffs

1. **A dedicated schema language instead of JSON/YAML.** It avoids dependencies and ambiguous coercion. The cost is deliberately limited literals: no quoted spaces, nested structures, or arbitrary logical expressions.
2. **Strict properties dialect instead of silent compatibility.** Duplicate assignments, unknown escapes, and incomplete continuations are errors. This avoids hiding typos but means some permissive Java properties files need cleanup. UTF-8 is explicit, unlike the historical `Properties.load(InputStream)` encoding.
3. **A standard-library test harness.** It keeps a fresh checkout buildable with only the JDK and Make. The suite uses named independent groups, nonzero failure status, real temporary input files, and explicit assertions. It gives up JUnit's ecosystem, IDE integration, and coverage tooling.
4. **Local syntactic URL checks.** Network probing would make results depend on connectivity and might transmit credentials. Hostnames and ports are validated without DNS or HTTP requests. Credentials in URLs are rejected to encourage separate secret settings.
5. **Schema consistency checks stop short of satisfiability.** References and literal types are checked, but contradictory rules can still make a schema impossible to satisfy. For a small explicit deployment contract, keeping the rule evaluator inspectable is preferable to adding a solver.
6. **No selective value-preview mode.** Troubleshooting requires examining the original file locally. This cost keeps logs and CI artifacts safer by construction.

## Useful next changes

- Add a version field before committing to a long-lived external report contract.
- Add explicit tests and a Windows launcher if native Windows, without WSL, becomes a supported target.
- Add richer declarative rules only with precise missing/invalid-value semantics and tests; avoid embedding general-purpose script execution.
- Consider a mature CLI/test library if the option surface grows enough to justify a dependency manager.
