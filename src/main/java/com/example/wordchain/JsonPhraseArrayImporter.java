package com.example.wordchain;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Consumer;

/** Streaming importer for JSON arrays containing objects with a phrase field. */
final class JsonPhraseArrayImporter {
    private JsonPhraseArrayImporter() { }

    static int importPhrases(Reader source, String fieldName, int maxLength, Consumer<String> sink) throws IOException {
        PushbackReader reader = new PushbackReader(source, 2);
        expect(reader, '[');
        int accepted = 0;
        char next = next(reader);
        while (next != ']') {
            if (next != '{') throw new IOException("Expected an object in dictionary JSON array");
            String phrase = null;
            char field = next(reader);
            while (field != '}') {
                if (field != '"') throw new IOException("Expected a JSON field name");
                reader.unread(field);
                String key = readString(reader);
                expect(reader, ':');
                char valueStart = next(reader);
                reader.unread(valueStart);
                if (key.equals(fieldName) && valueStart == '"') phrase = readString(reader);
                else skipValue(reader);
                field = next(reader);
                if (field == ',') field = next(reader);
                else if (field != '}') throw new IOException("Expected ',' or '}' in dictionary JSON object");
            }
            if (phrase != null) {
                String normalized = normalize(phrase);
                if (normalized.length() <= maxLength && normalized.matches("[\\p{L}\\p{M}]+ [\\p{L}\\p{M}]+")) {
                    sink.accept(normalized);
                    accepted++;
                }
            }
            next = next(reader);
            if (next == ',') next = next(reader);
            else if (next != ']') throw new IOException("Expected ',' or ']' in dictionary JSON array");
        }
        int trailing;
        while ((trailing = reader.read()) >= 0) if (!Character.isWhitespace(trailing)) throw new IOException("Unexpected data after dictionary JSON array");
        return accepted;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.trim().toLowerCase(Locale.forLanguageTag("vi-VN")).replaceAll("\\s+", " "), Normalizer.Form.NFC);
    }

    private static char next(PushbackReader reader) throws IOException {
        int value;
        do { value = reader.read(); } while (value >= 0 && Character.isWhitespace(value));
        if (value < 0) throw new IOException("Unexpected end of dictionary JSON array");
        return (char) value;
    }

    private static void expect(PushbackReader reader, char expected) throws IOException {
        char actual = next(reader);
        if (actual != expected) throw new IOException("Expected '" + expected + "' in dictionary JSON array");
    }

    private static String readString(PushbackReader reader) throws IOException {
        expect(reader, '"');
        StringBuilder value = new StringBuilder();
        int ch;
        while ((ch = reader.read()) >= 0) {
            if (ch == '"') return value.toString();
            if (ch != '\\') {
                if (value.length() >= 16_384) throw new IOException("Dictionary JSON string exceeds 16384 characters");
                value.append((char) ch);
                continue;
            }
            int escaped = reader.read();
            if (escaped < 0) break;
            switch (escaped) {
                case '"', '\\', '/' -> value.append((char) escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(reader.read(), 16);
                        if (digit < 0) throw new IOException("Invalid Unicode escape in dictionary JSON");
                        code = (code << 4) | digit;
                    }
                    value.append((char) code);
                }
                default -> throw new IOException("Invalid JSON string escape in dictionary JSON");
            }
            if (value.length() > 16_384) throw new IOException("Dictionary JSON string exceeds 16384 characters");
        }
        throw new IOException("Unterminated string in dictionary JSON");
    }

    private static void skipString(PushbackReader reader) throws IOException {
        boolean escaped = false;
        int ch;
        while ((ch = reader.read()) >= 0) {
            if (escaped) {
                if (ch == 'u') {
                    for (int i = 0; i < 4; i++) if (Character.digit(reader.read(), 16) < 0) throw new IOException("Invalid Unicode escape in dictionary JSON");
                } else if (ch != '"' && ch != '\\' && ch != '/' && ch != 'b' && ch != 'f' && ch != 'n' && ch != 'r' && ch != 't') {
                    throw new IOException("Invalid JSON escape in dictionary JSON");
                }
                escaped = false;
            } else if (ch == '\\') escaped = true;
            else if (ch == '"') return;
        }
        throw new IOException("Unterminated string in dictionary JSON");
    }

    private static void skipValue(PushbackReader reader) throws IOException {
        char first = next(reader);
        if (first == '"') { skipString(reader); return; }
        if (first == '{' || first == '[') {
            char[] closers = new char[128];
            int depth = 1;
            closers[0] = first == '{' ? '}' : ']';
            boolean quoted = false;
            boolean escaped = false;
            int ch;
            while (depth > 0 && (ch = reader.read()) >= 0) {
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == '"') quoted = false;
                    continue;
                }
                if (ch == '"') quoted = true;
                else if (ch == '{' || ch == '[') {
                    if (depth == closers.length) throw new IOException("Dictionary JSON nesting exceeds 128 levels");
                    closers[depth++] = ch == '{' ? '}' : ']';
                } else if (ch == closers[depth - 1]) depth--;
            }
            if (depth != 0) throw new IOException("Unclosed value in dictionary JSON");
            return;
        }
        int ch;
        while ((ch = reader.read()) >= 0) {
            if (ch == ',' || ch == '}' || ch == ']') { reader.unread(ch); return; }
        }
    }
}
