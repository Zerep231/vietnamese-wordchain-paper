package com.example.wordchain.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired on the main thread for each accepted move. */
public final class WordChainMoveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String playerName;
    private final String phrase;
    public WordChainMoveEvent(UUID playerId, String playerName, String phrase) { this.playerId=playerId; this.playerName=playerName; this.phrase=phrase; }
    public UUID getPlayerId(){return playerId;}
    public String getPlayerName(){return playerName;}
    public String getPhrase(){return phrase;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
