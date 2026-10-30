package com.example.wordchain;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Lightweight synchronous placeholders; expensive database reads stay out of this callback. */
public final class WordChainExpansion extends PlaceholderExpansion {
    private final WordChainPlugin plugin;

    public WordChainExpansion(WordChainPlugin plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "wordchain"; }
    @Override public String getAuthor() { return "WordChain"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, String params) {
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "active" -> Boolean.toString(plugin.placeholderGameActive());
            case "word", "keyword" -> plugin.placeholderCurrentWord();
            case "moves" -> Integer.toString(plugin.placeholderMoves());
            case "dictionary_size" -> Integer.toString(plugin.placeholderDictionarySize());
            default -> null;
        };
    }
}
