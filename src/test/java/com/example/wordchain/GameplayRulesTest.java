package com.example.wordchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameplayRulesTest {
    @Test
    void publicChatOnlyBelongsToActiveParticipants() {
        assertTrue(GameplayRules.shouldProcessPublicChat(true, true));
        assertFalse(GameplayRules.shouldProcessPublicChat(true, false));
        assertFalse(GameplayRules.shouldProcessPublicChat(false, true));
    }

    @Test
    void disallowsImmediateTwoPhraseReversalButAllowsOtherContinuation() {
        assertFalse(GameplayRules.allowsNextPhrase("kia đó", "kia đó"));
        assertTrue(GameplayRules.allowsNextPhrase("đó đây", "kia đó"));
        assertTrue(GameplayRules.allowsNextPhrase("kia đó", null));
    }

    @Test
    void validatesOptionalWagerAndRejectsInvalidAmounts() {
        assertTrue(GameplayRules.isValidWager(0, 100));
        assertTrue(GameplayRules.isValidWager(50, 100));
        assertFalse(GameplayRules.isValidWager(-1, 100));
        assertFalse(GameplayRules.isValidWager(Double.NaN, 100));
        assertFalse(GameplayRules.isValidWager(Double.POSITIVE_INFINITY, 100));
        assertFalse(GameplayRules.isValidWager(101, 100));
        assertFalse(GameplayRules.isValidWager(1.234, 100));
        assertTrue(GameplayRules.isValidWager(1.25, 100));
        assertFalse(GameplayRules.isValidWager(1, Double.POSITIVE_INFINITY));
    }
}
