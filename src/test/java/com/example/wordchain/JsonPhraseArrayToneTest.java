package com.example.wordchain;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonPhraseArrayToneTest {
    @Test void jsonImportUsesSharedVietnameseNormalization() throws Exception {
        List<String> imported = new ArrayList<>();
        JsonPhraseArrayImporter.importPhrases(new StringReader("[{\"phrase\":\"hoà bình\"}]"), "phrase", 48, imported::add);
        assertEquals(List.of("hòa bình"), imported);
    }
}
