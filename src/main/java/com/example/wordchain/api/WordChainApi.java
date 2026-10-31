package com.example.wordchain.api;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Service API for external menu, scoreboard, hologram, and game integrations. Database-backed methods complete asynchronously.
 * All methods return non-null values; the current keyword is an empty string while inactive.
 */
public interface WordChainApi {
    boolean isGameActive();
    @NotNull String getCurrentKeyword();
    boolean isKnownPhrase(@NotNull String phrase);
    @NotNull CompletionStage<Integer> getWins(@NotNull UUID playerId);

    /** Returns a database-backed leaderboard snapshot without blocking the calling thread. */
    @NotNull CompletionStage<List<LeaderboardEntry>> getLeaderboard(int limit);

    /** Opens the built-in GUI. Implementations must marshal the call to Paper's main thread. */
    default void openMenu(@NotNull Player player) { }

    record LeaderboardEntry(@NotNull String playerName, int wins, int currentStreak, int bestStreak) { }
}
