# ConfigAudit

A dependency-free Java CLI that checks deployment configuration before a service starts. Define a contract once, audit an `.env` or UTF-8 properties file, and use the exit status to stop a deployment when required settings, types, URLs, ports, or related settings disagree.

Reports contain setting names and actionable error codes. **They never contain configuration values**, including values that were not marked sensitive. The tool reads only the two files passed to it; it does not read the process environment, resolve DNS, execute commands, expand variables, or contact services.

## Quick start

Requirements: JDK 17 or newer and Make on Linux/macOS (or WSL). No Maven, Gradle, dependency download, or server is needed.

```sh
make check build
java -jar build/config-audit.jar \
  --schema examples/service.schema \
  --config examples/valid.env \
  --format env
```

```text
ConfigAudit: PASS
Settings: declared=11, present=10, unknown=0
Errors: 0, warnings: 0
```

Try the deliberately failing example:

```sh
java -jar build/config-audit.jar \
  --schema examples/service.schema \
  --config examples/invalid.env \
  --format env --output json
```

This returns **1** and reports seven errors: an insecure API URL scheme, a short database password, a forbidden debug setting, conflicting ports, disabled production TLS, an unknown key, and a worker count outside the allowed range. Example values are fictional.

## CLI contract

```text
config-audit --schema FILE --config FILE --format env|properties
             [--output text|json] [--unknown error|warn|ignore]
config-audit --help
```

Invoke `config-audit` above as `java -jar build/config-audit.jar`. Input format is explicit; filenames do not influence parsing. Options may appear in any order; duplicates, missing arguments, and unknown options are rejected.

| Exit | Meaning | Stream |
| --- | --- | --- |
| `0` | Passed, possibly with warnings; also `--help` | stdout |
| `1` | Configuration was parsed but violates the contract | stdout |
| `2` | Invalid CLI, unreadable input, malformed schema/config, or input limit exceeded | stderr |

`--output json` applies to audit reports and input failures. CLI usage errors are always plain text because there may not be a valid output option. Errors never include input paths, raw source lines, exception stack traces, or values. JSON output ends with one newline and does not include timestamps.

Unknown settings fail by default. `--unknown warn` permits extra keys but reports them; `--unknown ignore` skips those findings while retaining the unknown-key count. The default is useful for catching misspellings. Explicitly relaxing it is useful when auditing a shared configuration file.

The `present` count includes every parsed key, including unknown keys. A present empty setting is different from an absent setting and is invalid even when optional. An empty config file is permitted and will fail any required settings.

## Schema

One directive per line. Blank lines and full-line `#` comments are allowed. Keywords are lowercase; setting names and values are case-sensitive. No inline schema comments, quoting, substitutions, or executable expressions are supported.

```text
setting APP_ENV enum required choices=development,staging,production
setting HTTP_PORT port required
setting METRICS_PORT port required
setting API_BASE_URL url required schemes=https
setting DATABASE_PASSWORD string required min=12 secret=true
setting WORKERS integer required min=1 max=32
setting TLS_ENABLED boolean required
setting TLS_CERT_PATH string optional
setting DEBUG boolean optional

rule equals-if APP_ENV production TLS_ENABLED true
rule require-if TLS_ENABLED true TLS_CERT_PATH
rule forbid-if APP_ENV production DEBUG
rule different HTTP_PORT METRICS_PORT
```

Setting declaration:

```text
setting NAME TYPE required|optional [OPTION=VALUE ...]
```

Names match `[A-Za-z_][A-Za-z0-9_.-]{0,127}`. Env names additionally exclude dots and hyphens; use properties for those names.

| Type | Validation | Options |
| --- | --- | --- |
| `string` | Nonblank string | `min`, `max`: Unicode code-point count |
| `integer` | Signed 64-bit decimal; no `+`, leading zeroes, whitespace, fractions, or exponent | `min`, `max`: inclusive |
| `port` | Integer from 1 through 65535 | None |
| `boolean` | Exactly `true` or `false` | None |
| `enum` | Exact match to a declared choice | `choices=a,b,c` required |
| `url` | Absolute hierarchical URI with a host and no fragment or embedded user credentials; explicit port must be 1–65535 | `schemes=https,postgresql` optional |

All types support `secret=true|false` (default `false`). Sensitive findings receive a flag; **redaction does not depend on this flag**. Non-secret values also never appear in reports. Scheme matching is case-insensitive; scheme declarations must be lowercase. Without `schemes`, any URI scheme satisfying the URL shape is accepted. URLs are checked syntactically, without checking reachability or credentials. Unicode hostnames must be represented as ASCII/punycode.

Enum items use letters, numbers, and `_ . : / @ + -`, without spaces. Comma-separated options must have unique nonempty items. Unknown, duplicate, or type-incompatible options and contradictory bounds are rejected. Rule references may appear before their declarations, but must resolve before auditing starts. Rule literals must satisfy the referenced setting's validation constraints.

| Rule | Meaning when the trigger is valid and matches |
| --- | --- |
| `rule require-if TRIGGER VALUE TARGET` | Target must be present and nonblank |
| `rule forbid-if TRIGGER VALUE TARGET` | Target key must be entirely absent, even if its value would be empty or `false` |
| `rule equals-if TRIGGER VALUE TARGET EXPECTED` | Target must be present and equal the specified value |
| `rule different FIRST SECOND` | When both values are present and valid, they must differ; settings must have the same type |

Integer comparisons are numeric (`0` and `-0` are equivalent); other comparisons are exact strings. Invalid triggers do not activate rules, and invalid targets keep their primary type error instead of accumulating equality errors. Missing or empty targets can produce both their own validation error and a conditional requirement error. `different` skips absent optional values. The schema parser checks syntax, references, types, and literal validity; it does not prove that the complete set of rules is logically satisfiable.

## Input dialects

Both inputs must be valid UTF-8; one leading UTF-8 BOM is accepted. Files are capped at 1 MiB and 10,000 split lines, including a final empty line. NUL characters are forbidden. Duplicate keys fail, including duplicates introduced through properties Unicode escapes. No “last value wins” behavior is used.

### Env

```dotenv
export APP_ENV=production
MESSAGE="line one\nline two"
LITERAL='${HOME} # stays literal'
NAME=catalog # inline comment
FRAGMENT=abc#def
```

- Assignments use `NAME=value`; optional `export` and surrounding whitespace are accepted.
- Single quotes preserve literal contents. Double quotes support `\\`, `\"`, `\n`, `\r`, `\t`, and `\$`. Unknown escapes fail.
- An unquoted `#` starts a comment only at the beginning of the value or after whitespace. After a quoted value, a comment must be separated by whitespace.
- Unquoted leading/trailing whitespace is stripped; quoted whitespace is retained.
- No multiline quoted values, variable expansion, command substitution, shell evaluation, or interpolation. Unquoted backslashes are literal. Shell-looking text is data.

### Properties

This is an explicitly strict UTF-8 properties dialect, rather than every permissive behavior of `java.util.Properties`.

```properties
service.name=catalog
HTTP_PORT: 8080
MESSAGE text with spaces
LONG_VALUE=continued-\
    value
UNICODE=\u0041
```

- Supports `=`, `:`, and whitespace separators; full-line `#`/`!` comments; and backslash continuations with continuation indentation removed.
- Only space, tab, and form feed count as properties whitespace, including before comments. Other Unicode spaces cannot hide an invalid key.
- Supports `\\`, `\t`, `\n`, `\r`, `\f`, `\uXXXX`, and escaped space, `=`, `:`, `#`, `!`.
- Trailing spaces in values are preserved; there are no inline comments.
- Requires a separator and rejects unknown escapes, unfinished continuations, invalid Unicode, NUL escapes, and unpaired surrogates. A trailing newline alone does not finish a continuation.

## Reports and automation

A failing text finding looks like:

```text
ERROR DATABASE_PASSWORD [STRING_LENGTH] (sensitive): String length is outside the declared bounds.
```

A passing JSON report:

```json
{"status":"pass","settings":{"declared":1,"present":1,"unknown":0},"errors":0,"warnings":0,"issues":[]}
```

Findings have `severity`, `key`, `code`, `message`, and `sensitive`. They sort by key, then code and message, regardless of file key order. Input failure JSON has `status: "error"` and an `error` object with `code: "INVALID_INPUT"` and a safe `message`.

In a deployment script, let a nonzero status stop the next command:

```sh
set -eu
java -jar build/config-audit.jar \
  --schema service.schema --config deployment.local.env --format env
# Put the deployment command here, after the audit succeeds.
```

Run an explicit check before collecting a report if the surrounding CI/script ignores exit codes. Do not pipe into another program and assume the audit's exit status is preserved without the shell's pipe-failure handling.

## Development and tests

```sh
make check     # strict javac warnings + dependency-free test suite
make build     # executable build/config-audit.jar
make demo      # equivalent passing env and properties examples
make clean    # remove generated build directory
```

Compilation uses `--release 17 -Xlint:all -Werror`. The suite exercises parsers, type boundaries, rule semantics, immutable models, output determinism, JSON escaping, CLI streams/statuses, invalid UTF-8, input limits, and malformed inputs containing secret canaries. It also checks that both passing fixtures mean the same thing and that the failing fixture produces the expected findings.

GitHub Actions is configured for JDK 17, 21, and 25. It runs the suite and examples and independently parses the failing JSON report with Python's standard JSON library. The Java CLI itself has no Python dependency. See [DESIGN.md](DESIGN.md) for the architecture and tradeoffs.

## Limits

This is a local configuration-contract checker. It does not retrieve secrets, decrypt files, discover settings, validate a deployment platform, prove a certificate exists, connect to databases, or act as a secret scanner. It intentionally reads only the specified files. Values remain in ordinary JVM strings while processing; they are not securely erased from memory. Names and safe diagnostics are included in reports, so names themselves should not contain sensitive data. Do not use a report as proof of a secure production deployment.
