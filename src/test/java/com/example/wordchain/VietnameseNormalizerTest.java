package com.example.wordchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VietnameseNormalizerTest {
    @Test void canonicalizesLegacyTonePlacement() {
        assertEquals("hòa bình", VietnameseNormalizer.normalize("  hoà   bình "));
        assertEquals("khỏe mạnh", VietnameseNormalizer.normalize("khoẻ mạnh"));
        assertEquals("thúy", VietnameseNormalizer.normalize("thuý"));
    }

    @Test void composesDecomposedUnicodeBeforeCanonicalization() {
        assertEquals("hòa bình", VietnameseNormalizer.normalize("ho\u0300a bình"));
    }
}
