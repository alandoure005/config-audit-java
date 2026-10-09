package dev.configaudit;

import java.util.Locale;

public final class ReportRenderer {
    public enum Format { TEXT, JSON }

    private ReportRenderer() { }

    public static String render(Auditor.Report report, Format format) {
        if (format == Format.JSON) return json(report);
        StringBuilder out = new StringBuilder("ConfigAudit: ").append(report.passed() ? "PASS" : "FAIL").append('\n');
        out.append("Settings: declared=").append(report.declared()).append(", present=").append(report.present())
                .append(", unknown=").append(report.unknown()).append('\n');
        out.append("Errors: ").append(report.errors()).append(", warnings: ").append(report.warnings()).append('\n');
        for (Auditor.Issue issue : report.issues()) {
            out.append(issue.severity()).append(' ').append(issue.key()).append(" [").append(issue.code()).append(']');
            if (issue.sensitive()) out.append(" (sensitive)");
            out.append(": ").append(issue.message()).append('\n');
        }
        return out.toString();
    }

    static String failure(String message, Format format) {
        return format == Format.JSON ? "{\"status\":\"error\",\"error\":{\"code\":\"INVALID_INPUT\",\"message\":" + quote(message) + "}}\n"
                : "ConfigAudit: ERROR\n" + message + "\n";
    }

    private static String json(Auditor.Report report) {
        StringBuilder out = new StringBuilder("{\"status\":").append(quote(report.passed() ? "pass" : "fail"));
        out.append(",\"settings\":{\"declared\":").append(report.declared()).append(",\"present\":").append(report.present())
                .append(",\"unknown\":").append(report.unknown()).append("},\"errors\":").append(report.errors())
                .append(",\"warnings\":").append(report.warnings()).append(",\"issues\":[");
        boolean first = true;
        for (Auditor.Issue issue : report.issues()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"severity\":").append(quote(issue.severity().name().toLowerCase(Locale.ROOT)))
                    .append(",\"key\":").append(quote(issue.key())).append(",\"code\":").append(quote(issue.code()))
                    .append(",\"message\":").append(quote(issue.message())).append(",\"sensitive\":").append(issue.sensitive()).append('}');
        }
        return out.append("]}\n").toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
