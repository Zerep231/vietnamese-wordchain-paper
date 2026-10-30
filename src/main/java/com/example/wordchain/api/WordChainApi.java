package com.example.wordchain.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.jetbrains.annotations.NotNull;

/** Service API for other plugins. Database-backed methods complete asynchronously.
 * All methods return non-null values; the current keyword is an empty string while inactive.
 */
public interface WordChainApi {
    boolean isGameActive();
    @NotNull String getCurrentKeyword();
    boolean isKnownPhrase(@NotNull String phrase);
    @NotNull CompletionStage<Integer> getWins(@NotNull UUID playerId);
}
