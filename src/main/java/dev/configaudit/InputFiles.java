package dev.configaudit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class InputFiles {
    static final int MAX_BYTES = 1_048_576;
    static final int MAX_LINES = 10_000;

    private InputFiles() { }

    static String read(Path path, String source) throws InputException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new InputException(source + " exceeds the 1 MiB limit.");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new InputException(source + " must contain valid UTF-8.");
        } catch (IOException | SecurityException e) {
            // Do not include exception messages: they may contain paths or input data.
            throw new InputException("Unable to read " + source + ".");
        }
    }

    static String[] lines(String text, String source) throws InputException {
        if (text.length() > MAX_BYTES) throw new InputException(source + " exceeds the input limit.");
        if (text.indexOf('\0') >= 0) throw new InputException(source + " contains a forbidden NUL character.");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        if (lines.length > MAX_LINES) throw new InputException(source + " exceeds the 10000-line limit.");
        return lines;
    }
}
