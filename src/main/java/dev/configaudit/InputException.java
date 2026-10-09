package dev.configaudit;

/** Safe diagnostics deliberately exclude source text and configuration values. */
public final class InputException extends Exception {
    private static final long serialVersionUID = 1L;
    public InputException(String message) { super(message); }

    static InputException at(String source, int line, String message) {
        return new InputException(source + " line " + line + ": " + message);
    }
}
