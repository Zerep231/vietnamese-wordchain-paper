package com.example.wordchain;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Optional Vault bridge kept reflective so Vault remains a soft dependency. */
public final class RewardService {
    private final Plugin plugin;
    public RewardService(Plugin plugin) { this.plugin = plugin; }

    public void reward(UUID playerId, int streak) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Rewards must run on the server thread");
        double base = plugin.getConfig().getDouble("rewards.economy.base", 0);
        double rewardMultiplier = multiplier(streak);
        if (plugin.getConfig().getBoolean("rewards.economy.enabled", true) && base > 0) depositWager(playerId, base * rewardMultiplier);
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        for (String command : plugin.getConfig().getStringList("rewards.commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName() == null ? playerId.toString() : player.getName())
                    .replace("{uuid}", playerId.toString()).replace("{streak}", Integer.toString(streak))
                    .replace("{reward}", Double.toString(base * rewardMultiplier)));
        }
    }

    /** Shared logarithmic multiplier for Vault, item, and command rewards. */
    public double multiplier(int streak) {
        double rate = Math.max(0, plugin.getConfig().getDouble("streak-rewards.bonus-per-level", 0.35));
        double cap = Math.max(1.0, plugin.getConfig().getDouble("streak-rewards.max-multiplier", 3.0));
        return Math.min(cap, 1.0 + rate * (Math.log(Math.max(1, streak)) / Math.log(2)));
    }

    public boolean isEconomyAvailable() {
        requireMainThread();
        return economyProvider() != null;
    }

    public boolean hasFunds(UUID playerId, double amount) {
        requireMainThread();
        try {
            Object provider = economyProvider();
            if (provider == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            return (boolean) economyClass.getMethod("has", OfflinePlayer.class, double.class)
                    .invoke(provider, Bukkit.getOfflinePlayer(playerId), amount);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return false; }
    }

    public boolean withdrawWager(UUID playerId, double amount) {
        requireMainThread();
        return transact(playerId, amount, "withdrawPlayer");
    }

    public boolean depositWager(UUID playerId, double amount) {
        requireMainThread();
        return transact(playerId, amount, "depositPlayer");
    }

    private boolean transact(UUID playerId, double amount, String operation) {
        if (!Double.isFinite(amount) || amount <= 0) return false;
        try {
            Object provider = economyProvider();
            if (provider == null) return false;
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object response = economyClass.getMethod(operation, OfflinePlayer.class, double.class)
                    .invoke(provider, Bukkit.getOfflinePlayer(playerId), amount);
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return false; }
    }

    private Object economyProvider() {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            org.bukkit.plugin.RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
            return registration == null ? null : registration.getProvider();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return null; }
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Economy operations must run on the server thread");
    }
}
