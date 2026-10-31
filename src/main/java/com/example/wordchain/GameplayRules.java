package com.example.wordchain;

import java.math.BigDecimal;

/** Small pure rules shared by public play, duels, and external gameplay integrations. */
final class GameplayRules {
    private GameplayRules() { }

    static boolean shouldProcessPublicChat(boolean gameActive, boolean participant) {
        return gameActive && participant;
    }

    static boolean allowsNextPhrase(String candidate, String previousPhrase) {
        return previousPhrase == null || !previousPhrase.equals(candidate);
    }

    static boolean isValidWager(double amount, double maximum) {
        if (!Double.isFinite(amount) || !Double.isFinite(maximum)
                || amount < 0 || maximum < 0 || amount > maximum) return false;
        return BigDecimal.valueOf(amount).stripTrailingZeros().scale() <= 2;
    }
}
