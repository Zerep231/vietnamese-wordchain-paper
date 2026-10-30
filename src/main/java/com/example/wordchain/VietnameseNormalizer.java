package com.example.wordchain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonicalizes Vietnamese text with NFC and modern tone placement. */
public final class VietnameseNormalizer {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private VietnameseNormalizer() { }

    public static String normalize(String input) {
        if (input == null || input.isEmpty()) return "";
        String value = Normalizer.normalize(input, Normalizer.Form.NFC)
                .strip().toLowerCase(Locale.forLanguageTag("vi-VN"));
        value = WHITESPACE.matcher(value).replaceAll(" ");
        return modernTonePlacement(value);
    }

    /** Reposition legacy tones in oa/oe/uy without allocating a regex or per-syllable arrays. */
    private static String modernTonePlacement(String value) {
        StringBuilder result = null;
        for (int i = 0; i < value.length(); i++) {
            char first = value.charAt(i);
            if (i + 1 < value.length()) {
                char second = value.charAt(i + 1);
                char tonedFirst = 0;
                char plainSecond = 0;
                if (first == 'o') {
                    tonedFirst = switch (second) {
                        case 'à' -> 'ò'; case 'á' -> 'ó'; case 'ả' -> 'ỏ'; case 'ã' -> 'õ'; case 'ạ' -> 'ọ';
                        case 'è' -> 'ò'; case 'é' -> 'ó'; case 'ẻ' -> 'ỏ'; case 'ẽ' -> 'õ'; case 'ẹ' -> 'ọ';
                        default -> 0;
                    };
                    if (tonedFirst != 0) plainSecond = "àáảãạ".indexOf(second) >= 0 ? 'a' : 'e';
                } else if (first == 'u') {
                    tonedFirst = switch (second) {
                        case 'ỳ' -> 'ù'; case 'ý' -> 'ú'; case 'ỷ' -> 'ủ'; case 'ỹ' -> 'ũ'; case 'ỵ' -> 'ụ';
                        default -> 0;
                    };
                    if (tonedFirst != 0) plainSecond = 'y';
                }
                if (tonedFirst != 0) {
                    if (result == null) result = new StringBuilder(value.length()).append(value, 0, i);
                    result.append(tonedFirst).append(plainSecond);
                    i++;
                    continue;
                }
            }
            if (result != null) result.append(first);
        }
        return result == null ? value : Normalizer.normalize(result, Normalizer.Form.NFC);
    }
}