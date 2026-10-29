package com.example.wordchain.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Service API for other plugins. Database-backed methods complete asynchronously. */
public interface WordChainApi {
    boolean isGameActive();
    String getCurrentKeyword();
    boolean isKnownPhrase(String phrase);
    CompletionStage<Integer> getWins(UUID playerId);
}
