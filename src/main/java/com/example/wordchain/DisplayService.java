package com.example.wordchain;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread owned Adventure bossbar/actionbar and countdown lifecycle. */
public final class DisplayService {
    private final Plugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private BossBar bar;
    private BukkitTask ticker;
    private long endsAt;
    private int duration;
    private int streak;
    private int lastActionbarSecond = -1;

    public DisplayService(Plugin plugin) { this.plugin = plugin; }

    public void start(String word, int streak) {
        stop();
        this.streak = Math.max(0, streak);
        duration = Math.max(1, plugin.getConfig().getInt("display.turn-seconds", 30));
        endsAt = System.nanoTime() + duration * 1_000_000_000L;
        lastActionbarSecond = -1;
        String title = plugin.getConfig().getString("display.bossbar-title", "<yellow>Nối từ • <white><word></white>")
                .replace("<word>", word);
        bar = BossBar.bossBar(mini.deserialize(title), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player player : Bukkit.getOnlinePlayers()) player.showBossBar(bar);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (bar == null) return;
            long remaining = Math.max(0, endsAt - System.nanoTime());
            float progress = Math.max(0f, Math.min(1f, (float) remaining / (duration * 1_000_000_000f)));
            bar.progress(progress);
            int seconds = (int) Math.ceil(remaining / 1_000_000_000.0);
            if (seconds != lastActionbarSecond) {
                String action = plugin.getConfig().getString("display.actionbar", "<gray>Còn <yellow><seconds></yellow> giây")
                        .replace("<seconds>", Integer.toString(seconds)).replace("<streak>", Integer.toString(streak));
                var component = mini.deserialize(action);
                for (Player player : Bukkit.getOnlinePlayers()) player.sendActionBar(component);
                lastActionbarSecond = seconds;
            }
            if (remaining == 0) stop();
        }, 0L, 5L);
    }

    public void showTo(Player player) { if (bar != null) player.showBossBar(bar); }

    public void stop() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        if (bar != null) for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(bar);
        bar = null;
    }
}
