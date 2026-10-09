package dev.configaudit;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class ValueValidator {
    record Problem(String code, String message) { }

    private ValueValidator() { }

    static Problem validate(Schema.Setting setting, String value) {
        if (value.isBlank()) return new Problem("EMPTY_VALUE", "A present setting cannot be empty or whitespace-only.");
        return switch (setting.type()) {
            case STRING -> {
                int length = value.codePointCount(0, value.length());
                yield outside(setting, length) ? new Problem("STRING_LENGTH", "String length is outside the declared bounds.") : null;
            }
            case INTEGER, PORT -> number(setting, value);
            case BOOLEAN -> value.equals("true") || value.equals("false") ? null
                    : new Problem("INVALID_BOOLEAN", "Expected lowercase true or false.");
            case ENUM -> setting.choices().contains(value) ? null
                    : new Problem("INVALID_ENUM", "Value is not among the declared choices.");
            case URL -> url(setting, value);
        };
    }

    private static Problem number(Schema.Setting setting, String value) {
        try {
            if (!value.matches("-?(0|[1-9][0-9]*)")) throw new NumberFormatException();
            long number = Long.parseLong(value);
            if (setting.type() == Schema.Type.PORT && (number < 1 || number > 65535))
                return new Problem("INVALID_PORT", "Port must be between 1 and 65535.");
            if (outside(setting, number)) return new Problem("OUT_OF_RANGE", "Integer is outside the declared bounds.");
            return null;
        } catch (NumberFormatException e) {
            return new Problem("INVALID_INTEGER", "Expected a signed 64-bit decimal integer without leading zeros.");
        }
    }

    private static boolean outside(Schema.Setting setting, long value) {
        return (setting.min() != null && value < setting.min()) || (setting.max() != null && value > setting.max());
    }

    private static Problem url(Schema.Setting setting, String value) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null || uri.getRawFragment() != null)
                return new Problem("INVALID_URL", "Expected an absolute URL with a hostname and no fragment.");
            if (uri.getRawUserInfo() != null)
                return new Problem("URL_CREDENTIALS", "Credentials embedded in a URL are forbidden; use a separate secret setting.");
            int port = uri.getPort();
            if (uri.getRawAuthority().endsWith(":") || (port != -1 && (port < 1 || port > 65535)))
                return new Problem("INVALID_URL_PORT", "URL port must be between 1 and 65535.");
            if (!setting.schemes().isEmpty() && !setting.schemes().contains(uri.getScheme().toLowerCase(Locale.ROOT)))
                return new Problem("URL_SCHEME", "URL scheme is not allowed by the schema.");
            return null;
        } catch (URISyntaxException e) {
            return new Problem("INVALID_URL", "Expected a valid absolute URL with a hostname.");
        }
    }
}
