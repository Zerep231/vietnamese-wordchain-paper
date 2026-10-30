package com.example.wordchain;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** Optional Vault bridge kept reflective so Vault remains a soft dependency. */
public final class RewardService {
    private final Plugin plugin;
    public RewardService(Plugin plugin) { this.plugin = plugin; }

    public void reward(UUID playerId, int streak) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Rewards must run on the server thread");
        double base = plugin.getConfig().getDouble("rewards.economy.base", 0);
        double rewardMultiplier = multiplier(streak);
        if (plugin.getConfig().getBoolean("rewards.economy.enabled", true) && base > 0) deposit(playerId, base * rewardMultiplier);
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

    private void deposit(UUID id, double amount) {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration = Bukkit.getServicesManager().getRegistration(economyClass);
            if (registration == null) return;
            Object provider = registration.getClass().getMethod("getProvider").invoke(registration);
            Method deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            deposit.invoke(provider, Bukkit.getOfflinePlayer(id), amount);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Vault is optional; item and command rewards continue to work without it.
        }
    }
}
