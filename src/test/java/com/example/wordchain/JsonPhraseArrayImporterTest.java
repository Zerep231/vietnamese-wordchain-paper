package com.example.wordchain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonPhraseArrayImporterTest {
    @Test
    void streamsOnlyNormalizedTwoSyllableTextValues() throws Exception {
        String json = """
                [
                  {"text":"BỆNH NHÂN","valid":true,"meta":{"source":"test"}},
                  {"text":"bệnh nhân nội trú","valid":true},
                  {"text":"đường\\u0020phố","valid":true},
                  {"other":"bỏ qua","text":"một"}
                ]
                """;
        List<String> imported = new ArrayList<>();

        int count = JsonPhraseArrayImporter.importPhrases(new StringReader(json), "text", 48, imported::add);

        assertEquals(2, count);
        assertEquals(List.of("bệnh nhân", "đường phố"), imported);
    }

    @Test
    void rejectsMalformedOrTruncatedArraysInsteadOfCachingThem() {
        assertThrows(IOException.class, () -> JsonPhraseArrayImporter.importPhrases(
                new StringReader("[{\"text\":\"bệnh nhân\"}"), "text", 48, ignored -> { }));
        assertThrows(IOException.class, () -> JsonPhraseArrayImporter.importPhrases(
                new StringReader("[{\"text\":\"bệnh nhân\"}] trailing"), "text", 48, ignored -> { }));
    }
}
