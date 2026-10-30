package com.example.wordchain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordUseLimitTest {
    @Test void honorsConfiguredUseLimitAndClampsItToSupportedRange() {
        assertTrue(WordChainPlugin.mayUseWord(Map.of("hòa bình", 1), "hòa bình", 3));
        assertFalse(WordChainPlugin.mayUseWord(Map.of("hòa bình", 3), "hòa bình", 3));
        assertFalse(WordChainPlugin.mayUseWord(Map.of("hòa bình", 5), "hòa bình", 99));
        assertFalse(WordChainPlugin.mayUseWord(Map.of("hòa bình", 1), "hòa bình", 1));
    }
}
