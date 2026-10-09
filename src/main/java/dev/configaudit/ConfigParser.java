package dev.configaudit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Parses data, never evaluates shell syntax or expands environment variables. */
public final class ConfigParser {
    public enum Format { ENV, PROPERTIES }
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private ConfigParser() { }

    public static Map<String, String> parse(String text, Format format) throws InputException {
        String[] lines = InputFiles.lines(text, "config");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            int startLine = i + 1;
            String line = lines[i];
            String stripped = format == Format.PROPERTIES ? stripPropertyWhitespace(line) : line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || (format == Format.PROPERTIES && stripped.startsWith("!"))) continue;
            if (format == Format.ENV) {
                env(line, startLine, result);
            } else {
                StringBuilder logical = new StringBuilder(line);
                while (continues(logical)) {
                    logical.setLength(logical.length() - 1);
                    if (++i >= lines.length || (i == lines.length - 1 && lines[i].isEmpty()))
                        throw error(startLine, "unfinished properties continuation.");
                    logical.append(stripPropertyWhitespace(lines[i]));
                }
                properties(logical.toString(), startLine, result);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void env(String line, int lineNumber, Map<String, String> result) throws InputException {
        line = line.stripLeading();
        if (line.startsWith("export") && line.length() > 6 && Character.isWhitespace(line.charAt(6)))
            line = line.substring(7).stripLeading();
        int equals = line.indexOf('=');
        if (equals < 0) throw error(lineNumber, "env assignment requires an equals sign.");
        String key = line.substring(0, equals).strip();
        if (!ENV_KEY.matcher(key).matches()) throw error(lineNumber, "invalid env setting name.");
        String raw = line.substring(equals + 1).stripLeading();
        String value;
        if (!raw.isEmpty() && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')) {
            char quote = raw.charAt(0);
            StringBuilder decoded = new StringBuilder();
            int i = 1;
            boolean closed = false;
            for (; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == quote) { closed = true; i++; break; }
                if (c == '\\' && quote == '"') {
                    if (++i >= raw.length()) throw error(lineNumber, "unfinished quoted escape.");
                    decoded.append(switch (raw.charAt(i)) {
                        case '\\' -> '\\'; case '"' -> '"'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '$' -> '$';
                        default -> throw error(lineNumber, "unsupported double-quoted escape.");
                    });
                } else decoded.append(c);
            }
            if (!closed) throw error(lineNumber, "unterminated quoted value.");
            String tail = raw.substring(i);
            if (!tail.isBlank() && !(Character.isWhitespace(tail.charAt(0)) && tail.stripLeading().startsWith("#")))
                throw error(lineNumber, "unexpected text after quoted value.");
            value = decoded.toString();
        } else {
            int comment = -1;
            for (int i = 0; i < raw.length(); i++) {
                if (raw.charAt(i) == '#' && (i == 0 || Character.isWhitespace(raw.charAt(i - 1)))) { comment = i; break; }
            }
            value = (comment < 0 ? raw : raw.substring(0, comment)).stripTrailing();
        }
        put(result, key, value, lineNumber);
    }

    private static void properties(String line, int lineNumber, Map<String, String> result) throws InputException {
        line = stripPropertyWhitespace(line);
        int separator = -1;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!escaped && (c == '=' || c == ':' || propertyWhitespace(c))) { separator = i; break; }
            if (c == '\\' && !escaped) escaped = true;
            else escaped = false;
        }
        if (separator < 0) throw error(lineNumber, "properties assignment requires a separator.");
        String key = decodeProperties(line.substring(0, separator), lineNumber);
        if (!Schema.KEY.matcher(key).matches()) throw error(lineNumber, "invalid properties setting name.");
        int valueStart = separator;
        while (valueStart < line.length() && propertyWhitespace(line.charAt(valueStart))) valueStart++;
        if (valueStart < line.length() && (line.charAt(valueStart) == '=' || line.charAt(valueStart) == ':')) valueStart++;
        while (valueStart < line.length() && propertyWhitespace(line.charAt(valueStart))) valueStart++;
        put(result, key, decodeProperties(line.substring(valueStart), lineNumber), lineNumber);
    }

    private static String decodeProperties(String raw, int line) throws InputException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { result.append(c); continue; }
            if (++i >= raw.length()) throw error(line, "unfinished properties escape.");
            char next = raw.charAt(i);
            switch (next) {
                case 't' -> result.append('\t');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 'f' -> result.append('\f');
                case '\\', '=', ':', ' ', '#', '!' -> result.append(next);
                case 'u' -> {
                    if (i + 4 >= raw.length()) throw error(line, "incomplete Unicode escape.");
                    int value = 0;
                    for (int j = 0; j < 4; j++) {
                        int digit = Character.digit(raw.charAt(++i), 16);
                        if (digit < 0) throw error(line, "invalid Unicode escape.");
                        value = (value << 4) | digit;
                    }
                    if (value == 0) throw error(line, "NUL escapes are forbidden.");
                    result.append((char) value);
                }
                default -> throw error(line, "unsupported properties escape.");
            }
        }
        // Unicode escapes may encode surrogate pairs, but lone surrogates are invalid text.
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i)))
                    throw error(line, "unpaired Unicode surrogate.");
            } else if (Character.isLowSurrogate(c)) throw error(line, "unpaired Unicode surrogate.");
        }
        return result.toString();
    }

    private static boolean continues(CharSequence line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }

    private static boolean propertyWhitespace(char c) { return c == ' ' || c == '\t' || c == '\f'; }

    private static String stripPropertyWhitespace(String text) {
        int i = 0;
        while (i < text.length() && propertyWhitespace(text.charAt(i))) i++;
        return text.substring(i);
    }

    private static void put(Map<String, String> result, String key, String value, int line) throws InputException {
        if (result.putIfAbsent(key, value) != null) throw error(line, "duplicate setting assignment.");
    }

    private static InputException error(int line, String message) { return InputException.at("config", line, message); }
}
