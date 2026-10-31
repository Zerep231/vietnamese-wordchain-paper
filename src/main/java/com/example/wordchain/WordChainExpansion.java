package com.example.wordchain;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Lightweight cached placeholders; database reads stay out of this synchronous callback. */
public final class WordChainExpansion extends PlaceholderExpansion {
    private static final java.util.regex.Pattern TOP_PLACEHOLDER = java.util.regex.Pattern.compile("top_(\\d+)_(name|wins|streak|best_streak)");
    private final WordChainPlugin plugin;

    public WordChainExpansion(WordChainPlugin plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "wordchain"; }
    @Override public String getAuthor() { return "WordChain"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        String normalized = params.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher top = TOP_PLACEHOLDER.matcher(normalized);
        if (top.matches()) {
            try { return plugin.placeholderLeaderboard(Integer.parseInt(top.group(1)), top.group(2)); }
            catch (NumberFormatException ignored) { return ""; }
        }
        return switch (normalized) {
            case "active" -> Boolean.toString(plugin.placeholderGameActive());
            case "word", "keyword" -> plugin.placeholderCurrentWord();
            case "moves" -> Integer.toString(plugin.placeholderMoves());
            case "dictionary_size" -> Integer.toString(plugin.placeholderDictionarySize());
            case "participating" -> Boolean.toString(player != null && plugin.placeholderParticipating(player.getUniqueId()));
            case "duel_active" -> Boolean.toString(player != null && plugin.placeholderInDuel(player.getUniqueId()));
            case "duel_wager" -> player == null ? "0" : plugin.placeholderDuelWager(player.getUniqueId());
            default -> null;
        };
    }
}
