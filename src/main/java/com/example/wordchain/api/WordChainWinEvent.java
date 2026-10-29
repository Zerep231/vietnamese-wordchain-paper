package com.example.wordchain.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired synchronously after a match is won and its persistent stats are updated. */
public final class WordChainWinEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String playerName;
    private final int streak;
    private final boolean duel;

    public WordChainWinEvent(UUID playerId, String playerName, int streak, boolean duel) {
        this.playerId = playerId; this.playerName = playerName; this.streak = streak; this.duel = duel;
    }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public int getStreak() { return streak; }
    public boolean isDuel() { return duel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
