package com.example.wordchain;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import com.example.wordchain.api.WordChainApi;
import com.example.wordchain.api.WordChainWinEvent;
import com.example.wordchain.api.WordChainMoveEvent;
import org.bukkit.plugin.ServicePriority;

public final class WordChainPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String TUDIEN_ARCHIVE_SOURCE = "tu-dien-archive|https://github.com/NhanAZ-Drops/tu-dien-tieng-viet/releases/download/v0.4.0-beta/tu-dien-tieng-viet-0.4.0-beta-documented.tar.gz";
    private static final String MEGUMI_JSON_SOURCE = "json-array|https://raw.githubusercontent.com/MegumiKatou02/Vietnamese-dictionary-api/main/vietnamese_words.json";
    private final Map<UUID, Long> reportCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> wrongMoveNotice = new ConcurrentHashMap<>();
    private final Map<UUID,DuelInvite> duelInvites = new ConcurrentHashMap<>();
    private final Map<UUID,DuelMatch> duels = new ConcurrentHashMap<>();
    private final Set<String> recentStartingWords = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicBoolean importRunning = new AtomicBoolean();
    private final Object configWriteLock = new Object();
    private volatile long configRevision;
    private volatile long writtenConfigRevision;
    private final Set<String> dictionary = ConcurrentHashMap.newKeySet();
    private final Set<UUID> publicParticipants = ConcurrentHashMap.newKeySet();
    private volatile List<WordChainApi.LeaderboardEntry> leaderboardCache = List.of();
    private volatile List<String> dictionaryIndex = List.of();
    private volatile Map<String,List<String>> dictionaryByFirstSyllable = Map.of();
    private volatile boolean dictionaryReady;
    private WordChainApi api;
    private RewardService rewardService;
    private DisplayService displayService;
    private org.bukkit.scheduler.BukkitTask nextMatchTask;
    private org.bukkit.scheduler.BukkitTask publicIdleTask;
    private org.bukkit.scheduler.BukkitTask gameVoteTask;
    private GameVote gameVote;
    private Connection connection;
    private String databaseUrl;
    private WordGame game;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateConfigDefaults();
        try {
            databaseUrl = "jdbc:sqlite:" + getDataFolder().toPath().resolve("wordchain.db");
            connection = new org.sqlite.JDBC().connect(databaseUrl, new Properties());
            if (connection == null) throw new SQLException("SQLite JDBC driver rejected the database URL");
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS words (phrase TEXT PRIMARY KEY, first_syllable TEXT NOT NULL, last_syllable TEXT NOT NULL)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_words_first ON words(first_syllable)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS wins (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, wins INTEGER NOT NULL DEFAULT 0, current_streak INTEGER NOT NULL DEFAULT 0, best_streak INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS word_reports (phrase TEXT PRIMARY KEY, reporter_uuid TEXT NOT NULL, reporter_name TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING')");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_status ON word_reports(status, created_at)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS dictionary_sources (source TEXT PRIMARY KEY, imported_at INTEGER NOT NULL, entries_added INTEGER NOT NULL)");
            }
            ensureColumn("wins", "current_streak", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn("wins", "best_streak", "INTEGER NOT NULL DEFAULT 0");
        } catch (Exception e) { getLogger().severe("Cannot open word-chain database: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        game = new WordGame();
        rewardService = new RewardService(this);
        displayService = new DisplayService(this);
        api = new WordChainApi() {
            @Override public boolean isGameActive() { return game.isActive(); }
            @Override public String getCurrentKeyword() { return game.isActive() ? game.getLastWord() : ""; }
            @Override public boolean isKnownPhrase(String phrase) { return dictionary.contains(normalizePhrase(phrase)); }
            @Override public java.util.concurrent.CompletionStage<Integer> getWins(UUID id) { return lookupWins(id); }
            @Override public java.util.concurrent.CompletionStage<List<WordChainApi.LeaderboardEntry>> getLeaderboard(int limit) { return lookupLeaderboard(limit); }
            @Override public void openMenu(Player player) { openPlayerMenu(player); }
        };
        getServer().getServicesManager().register(WordChainApi.class, api, this, ServicePriority.Normal);
        Objects.requireNonNull(getCommand("wordchain")).setExecutor(this);
        getCommand("wordchain").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new WordChainExpansion(this).register();
        }
        refreshLeaderboardCache();
        Bukkit.getScheduler().runTaskTimer(this, this::refreshLeaderboardCache, 1200L, 1200L);
        boolean refresh = getConfig().getBoolean("dictionary.refresh-on-start", true);
        if (refresh) scheduleImport(null, false);
        else Bukkit.getScheduler().runTaskAsynchronously(this, this::loadDictionaryCache);
        getLogger().info("WordChain enabled; loading indexed dictionary asynchronously.");
    }

    private void migrateConfigDefaults() {
        boolean changed = false;
        List<String> sources = new ArrayList<>(getConfig().getStringList("dictionary.sources"));
        if (!sources.contains(TUDIEN_ARCHIVE_SOURCE)) { sources.add(TUDIEN_ARCHIVE_SOURCE); getConfig().set("dictionary.sources", sources); changed = true; }
        if (!getConfig().getBoolean("dictionary.migrations.megumi-json-added", false)) {
            if (!sources.contains(MEGUMI_JSON_SOURCE)) sources.add(MEGUMI_JSON_SOURCE);
            getConfig().set("dictionary.sources", sources);
            getConfig().set("dictionary.migrations.megumi-json-added", true);
            changed = true;
        }
        if (!getConfig().contains("game.recent-start-limit")) { getConfig().set("game.recent-start-limit", 20); changed = true; }
        if (!getConfig().contains("game.reset-vote-seconds")) { getConfig().set("game.reset-vote-seconds", 30); changed = true; }
        if (!getConfig().contains("duel.max-wager")) { getConfig().set("duel.max-wager", 1_000_000); changed = true; }
        if (!getConfig().contains("game.idle-timeout-minutes")) { getConfig().set("game.idle-timeout-minutes", 15); changed = true; }
        if (!getConfig().contains("duel.default-stake-type")) { getConfig().set("duel.default-stake-type", "auto"); changed = true; }
        if (changed) saveConfig();
    }

    @Override public void onDisable() {
        cancelPublicIdleTimeout();
        for (DuelMatch match : new HashSet<>(duels.values())) {
            finishDuel(match, null, "Máy chủ đang tắt; duel đã dừng.");
        }
        if (displayService != null) displayService.stop();
        if (api != null) getServer().getServicesManager().unregister(WordChainApi.class, api);
        cancelNextMatch();
        flushConfigNow();
        synchronized (this) {
            try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException e) { getLogger().warning(e.getMessage()); }
        }
    }

    // Async chat is handed to the main thread before touching game state or Bukkit player state.
    @EventHandler public void onChat(AsyncChatEvent event) {
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()); UUID id = event.getPlayer().getUniqueId(); String name = event.getPlayer().getName();
        DuelMatch duel = duels.get(id);
        if (duel != null) {
            event.setCancelled(true); // Duel chat is private; never publish duel messages to the server chat.
            // AsyncChatEvent already runs asynchronously: do the immutable hash lookup here,
            // then cross to the server thread only for the actual duel/game mutation.
            String phrase = normalizeCandidate(input);
            if (phrase != null) {
                boolean known = dictionary.contains(phrase);
                Bukkit.getScheduler().runTask(this, () -> { if (known) processMove(id, name, phrase); else notifyDuelError(id, "<red>Từ chưa có trong từ điển."); });
            }
            else Bukkit.getScheduler().runTask(this, () -> notifyDuelError(id, "<red>Trong duel, hãy gửi đúng một cụm gồm hai tiếng."));
            return;
        }
        // Public game words are private to opted-in participants. Spectators retain normal chat.
        if (!GameplayRules.shouldProcessPublicChat(game.isActive(), publicParticipants.contains(id))) return;
        String phrase = normalizeCandidate(input);
        if (phrase == null) return;
        event.setCancelled(true); // Accepted moves are announced once by processMove; invalid moves get private feedback.
        boolean known = dictionary.contains(phrase);
        Bukkit.getScheduler().runTask(this, () -> {
            if (known) processMove(id, name, phrase); else notifyInvalidMove(id, phrase);
        });
    }

    private void processMove(UUID id, String playerName, String raw) {
        DuelMatch duel=duels.get(id); if(duel!=null){ processDuelMove(duel,id,playerName,raw); return; }
        if (!game.isActive()) return;
        if (!publicParticipants.contains(id)) { notifyDuelError(id, "<yellow>Muốn tham gia ván? Dùng <aqua>/noitu accept</aqua> trước khi nối từ."); return; }
        if (!game.maySubmit(id, System.currentTimeMillis(), getConfig().getLong("anti-abuse.minimum-reaction-delay-ms", 200), getConfig().getBoolean("anti-abuse.anti-solo", true))) {
            notifyDuelError(id, game.getLastPlayer() != null && game.getLastPlayer().equals(id)
                    ? "<red>Hãy chờ người chơi khác nối từ trước." : "<red>Vui lòng chờ một chút trước khi trả lời.");
            return;
        }
        if (!game.tryMove(raw, this::isValidWord)) { if (!game.hasLegalContinuation()) resetPublicRound(); else notifyInvalidMove(id, raw); return; }
        game.recordPlayer(id, System.currentTimeMillis());
        armPublicIdleTimeout();
        displayService.start(game.getLastWord(), game.getMoves());
        String clean = raw;
        Bukkit.broadcast(message("<green>" + escape(playerName) + " nối thành công: <yellow>" + escape(clean) + "</yellow>. Tiếng tiếp theo: <gold>" + escape(game.requiredSyllable()) + "</gold>.</green>"));
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id, playerName, clean));
        if (game.getMoves() >= getConfig().getInt("game.target-moves", 10)) {
            cancelGameVote("<yellow>Đề xuất đã bị hủy vì ván vừa kết thúc.");
            game.stop();
            saveWin(id, playerName, true, false, null);
            Bukkit.broadcast(message("<gold><bold>" + escape(playerName) + " đã chiến thắng trò Nối Từ!"));
            startNextMatch();
        } else if (!game.hasLegalContinuation()) {
            resetPublicRound();
        }
    }

    private void resetPublicRound() {
        String next = randomStartingWord();
        if (next == null) { cancelGameVote("<yellow>Đề xuất đã bị hủy vì ván vừa kết thúc."); game.stop(); Bukkit.broadcast(message("<yellow>Ván đã được đặt lại vì không còn nước nối hợp lệ; chưa có từ mở màn phù hợp.")); }
        else { rememberStartingWord(next); game.start(next); Bukkit.broadcast(message("<yellow>Không còn nước nối hợp lệ. Ván mới bắt đầu với từ khóa ngẫu nhiên: <gold>" + escape(next) + "</gold>.")); announcePublicJoin(next); }
    }

    private void announcePublicJoin(String startingWord) {
        Bukkit.broadcast(message("<green>Ván Nối Từ đã mở! Từ đầu tiên: <yellow>" + escape(startingWord)
                + "</yellow>. Muốn tham gia, bấm <click:run_command:'/noitu accept'><hover:show_text:'Bấm để tham gia'><aqua><underlined>[ACCEPT]</underlined></aqua></hover></click> hoặc gõ <aqua>/noitu accept</aqua>."));
    }

    private void joinPublicGame(Player player) {
        if (!game.isActive()) { player.sendMessage(message("<yellow>Chưa có ván công khai nào đang mở.")); return; }
        if (duels.containsKey(player.getUniqueId())) { player.sendMessage(message("<red>Bạn đang ở trong duel.")); return; }
        boolean joined = publicParticipants.add(player.getUniqueId());
        player.sendMessage(message(joined ? "<green>Bạn đã tham gia ván Nối Từ!" : "<yellow>Bạn đã tham gia ván này rồi."));
        if (joined) Bukkit.broadcast(message("<gray>» <yellow>" + escape(player.getName()) + "</yellow> đã tham gia ván."));
    }

    private void leavePublicGame(Player player) {
        UUID id = player.getUniqueId();
        if (!publicParticipants.remove(id)) {
            player.sendMessage(message("<yellow>Bạn chưa tham gia ván công khai."));
            return;
        }
        if (id.equals(game.getLastPlayer())) game.clearLastPlayer(id);
        player.sendMessage(message("<green>Bạn đã rời ván công khai; chat thường của bạn được giữ nguyên."));
        Bukkit.broadcast(message("<gray>» <yellow>" + escape(player.getName()) + "</yellow> đã rời ván Nối Từ."));
    }

    private void armPublicIdleTimeout() {
        if (publicIdleTask != null) publicIdleTask.cancel();
        long minutes = Math.max(1, Math.min(1440, getConfig().getLong("game.idle-timeout-minutes", 15)));
        publicIdleTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!game.isActive()) return;
            UUID lastPlayer = game.getLastPlayer();
            String lastName = lastPlayer == null ? null : Bukkit.getOfflinePlayer(lastPlayer).getName();
            game.stop();
            publicParticipants.clear();
            if (lastPlayer != null && lastName != null) {
                saveWin(lastPlayer, lastName, true, false, null);
                Bukkit.broadcast(message("<gold>Ván công khai tự kết thúc vì không có lượt nối trong " + minutes
                        + " phút. Người nối cuối cùng, <yellow>" + escape(lastName) + "</yellow>, thắng ván!"));
            } else {
                Bukkit.broadcast(message("<yellow>Ván công khai đã hủy vì không có ai nối từ trong " + minutes + " phút."));
            }
        }, minutes * 60L * 20L);
    }

    private void cancelPublicIdleTimeout() {
        if (publicIdleTask != null) { publicIdleTask.cancel(); publicIdleTask = null; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!canControl(sender)) return deny(sender);
                if (!dictionaryReady || importRunning.get()) { sender.sendMessage(message("<yellow>Đang nạp từ điển, vui lòng thử lại sau.")); return true; }
                if (game.isActive()) { sender.sendMessage(message("<yellow>Ván đang diễn ra. Dùng /wordchain reset để bắt đầu lại.")); return true; }
                String start = args.length > 1 ? normalizePhrase(String.join(" ", Arrays.copyOfRange(args, 1, args.length))) : randomStartingWord();
                if (start == null || !isTwoSyllablePhrase(start) || !isValidWord(start) || !hasContinuation(start, Map.of(start, 1), initialHardRun(start))) { sender.sendMessage(message("<red>Không có từ mở màn phù hợp. Hãy nạp thêm dữ liệu từ điển.")); return true; }
                cancelGameVote("<yellow>Đề xuất đã bị hủy vì ván mới được bắt đầu.");
                rememberStartingWord(start);
                cancelNextMatch(); publicParticipants.clear(); game.start(start); if (sender instanceof Player starter) publicParticipants.add(starter.getUniqueId()); announcePublicJoin(start);
            }
            case "stop" -> { if (!canControl(sender)) return deny(sender); if (sender.hasPermission("wordchain.admin")) stopPublicGame(sender.getName()); else requestPublicStop(sender); }
            case "reset" -> { if (!canControl(sender)) return deny(sender); if (!dictionaryReady || importRunning.get()) { sender.sendMessage(message("<yellow>Đang nạp từ điển, vui lòng thử lại sau.")); return true; } requestPublicReset(sender); }
            case "vote" -> { if (!sender.hasPermission("wordchain.vote")) return deny(sender); castGameVote(sender, args); }
            case "menu" -> { if (!sender.hasPermission("wordchain.menu")) return deny(sender); if (sender instanceof Player p) openMenu(p); else sender.sendMessage("Lệnh này chỉ dành cho người chơi."); }
            case "top" -> { if (!sender.hasPermission("wordchain.top")) return deny(sender); showTop(sender); }
            case "wins" -> { if (!(sender.hasPermission("wordchain.admin") || sender.hasPermission("wordchain.stats"))) return deny(sender); if (args.length != 2) { sender.sendMessage(message("<yellow>Dùng: /wordchain wins <player>")); return true; } showPlayerStats(sender, args[1]); }
            case "rewards" -> { if (!(sender instanceof Player p) || !(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); openRewardsMenu(p); }
            case "reward" -> { if (!(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); editReward(sender, args); }
            case "difficulty" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); setDifficulty(sender, args); }
            case "duel" -> { if (!sender.hasPermission("wordchain.duel")) return deny(sender); createDuelInvite(sender,args); }
            case "accept" -> { if (!sender.hasPermission("wordchain.duel.respond")) return deny(sender); acceptDuel(sender,args); }
            case "join" -> { if (!(sender instanceof Player player) || !player.hasPermission("wordchain.play")) return deny(sender); joinPublicGame(player); }
            case "leave" -> { if (!(sender instanceof Player player)) { sender.sendMessage("Only players can leave a game."); return true; } leavePublicGame(player); }
            case "deny" -> { if (!sender.hasPermission("wordchain.duel.respond")) return deny(sender); denyDuel(sender,args); }
            case "add" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length < 2) { sender.sendMessage(message("<yellow>Dùng: /noitu add <từ gồm hai tiếng>")); return true; } String phrase = normalizePhrase(String.join(" ", Arrays.copyOfRange(args, 1, args.length))); addWordAsync(sender, phrase); }
            case "report" -> { if (!sender.hasPermission("wordchain.report")) return deny(sender); reportWord(sender, args); }
            case "reports" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); showReports(sender); }
            case "approve", "reject" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain " + sub + " <tiếng1 tiếng2>")); return true; } reviewReport(sender, sub.equals("approve"), normalizePhrase(args[1] + " " + args[2])); }
            case "remove" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain remove <tiếng1 tiếng2>")); return true; } removeWord(sender, normalizePhrase(args[1] + " " + args[2])); }
            case "import" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); scheduleImport(sender, true); }
            case "skip" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (game.isActive()) resetPublicRound(); else sender.sendMessage(message("<yellow>Hiện không có ván đang diễn ra.")); }
            case "reload" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); reloadConfig(); migrateConfigDefaults(); if (getConfig().getBoolean("dictionary.refresh-on-start", true)) scheduleImport(sender, true); else Bukkit.getScheduler().runTaskAsynchronously(this, this::loadDictionaryCache); }
            default -> sender.sendMessage(message("<yellow>Dùng: /wordchain <start|stop|reset|accept|join|menu|top|wins|rewards|reward|difficulty|import|reload>"));
        }
        return true;
    }

    private boolean canControl(CommandSender s) { return s.hasPermission("wordchain.admin") || (getConfig().getBoolean("game.members-can-control", true) && s.hasPermission("wordchain.play")); }
    boolean placeholderGameActive() { return game != null && game.isActive(); }
    String placeholderCurrentWord() { return game == null ? "" : game.getLastWord(); }
    int placeholderMoves() { return game == null ? 0 : game.getMoves(); }
    int placeholderDictionarySize() { return dictionary.size(); }
    private boolean deny(CommandSender s) { s.sendMessage(message("<red>Bạn không có quyền thực hiện thao tác này.")); return true; }
    private Component message(String s) { return MM.deserialize(s); }
    private static String escape(String value) { return MM.escapeTags(value); }
    private static String normalizePhrase(String input) { return VietnameseNormalizer.normalize(input); }
    private static final Pattern TWO_SYLLABLE_PHRASE = Pattern.compile("[\\p{L}\\p{M}]+ [\\p{L}\\p{M}]+");
    private static String normalizeCandidate(String input) {
        if (input == null || input.length() > 48 || input.indexOf(' ') < 0) return null;
        String normalized = normalizePhrase(input);
        return normalized.length() <= 48 && TWO_SYLLABLE_PHRASE.matcher(normalized).matches() ? normalized : null;
    }
    private static boolean isTwoSyllablePhrase(String input) { return normalizeCandidate(input) != null; }

    private boolean isValidWord(String phrase) {
        return phrase.length() <= getConfig().getInt("game.max-phrase-length", 48)
                && TWO_SYLLABLE_PHRASE.matcher(phrase).matches() && dictionary.contains(phrase);
    }

    private void loadDictionaryCache() {
        try (Connection reader = openReadConnection(); Statement s = reader.createStatement(); ResultSet rs = s.executeQuery("SELECT phrase FROM words")) { while (rs.next()) dictionary.add(rs.getString(1)); refreshWordIndex(); dictionaryReady = true; }
            catch (SQLException e) { getLogger().severe("Could not load word index: " + e.getMessage()); }
    }

    private Connection openReadConnection() throws SQLException {
        Connection reader = new org.sqlite.JDBC().connect(databaseUrl, new Properties());
        if (reader == null) throw new SQLException("SQLite JDBC driver rejected the read URL");
        try (Statement statement = reader.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA query_only=ON");
            return reader;
        } catch (SQLException e) {
            try { reader.close(); } catch (SQLException ignored) { }
            throw e;
        }
    }

    /** Reopen a closed writer connection before the next transaction. */
    private synchronized void ensureWriterConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(1)) {
            connection = new org.sqlite.JDBC().connect(databaseUrl, new Properties());
            if (connection == null) throw new SQLException("SQLite JDBC driver rejected the database URL");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA journal_mode=WAL");
            }
        }
    }

    private void refreshWordIndex() {
        List<String> words = List.copyOf(dictionary);
        Map<String,List<String>> grouped = new HashMap<>();
        for (String phrase : words) {
            int separator = phrase.indexOf(' '); if (separator < 1) continue;
            grouped.computeIfAbsent(phrase.substring(0, separator), ignored -> new ArrayList<>()).add(phrase);
        }
        Map<String,List<String>> immutable = new HashMap<>();
        grouped.forEach((syllable, phrases) -> immutable.put(syllable, List.copyOf(phrases)));
        dictionaryByFirstSyllable = Map.copyOf(immutable);
        dictionaryIndex = words;
    }

    private int initialHardRun(String phrase) {
        int threshold = getConfig().getInt("difficulty.hard-threshold", 4);
        return getConfig().getInt("difficulty.words." + phrase, 1) >= threshold ? 1 : 0;
    }
    static boolean mayUseWord(Map<String, Integer> usedWords, String phrase, int configuredLimit) {
        int limit = Math.max(1, Math.min(5, configuredLimit));
        return usedWords.getOrDefault(phrase, 0) < limit;
    }
    private boolean hasContinuation(String phrase, Map<String,Integer> uses, int hardRun) {
        return hasContinuation(phrase, uses, hardRun, null);
    }
    private boolean hasContinuation(String phrase, Map<String,Integer> uses, int hardRun, String previousPhrase) {
        int split = phrase.indexOf(' '); if (split < 0) return false;
        List<String> candidates = dictionaryByFirstSyllable.get(phrase.substring(split + 1)); if (candidates == null) return false;
        int limit = Math.max(1, Math.min(5, getConfig().getInt("anti-abuse.word-use-limit", 3)));
        int threshold = getConfig().getInt("difficulty.hard-threshold", 4);
        int maxHard = Math.max(1, getConfig().getInt("anti-abuse.max-hard-words-in-row", 2));
        for (String candidate : candidates) {
            // Prevent the immediate A -> B -> A reversal that makes common two-word loops tedious.
            if (!GameplayRules.allowsNextPhrase(candidate, previousPhrase)) continue;
            if (uses.getOrDefault(candidate, 0) >= limit) continue;
            if (getConfig().getInt("difficulty.words." + candidate, 1) >= threshold && hardRun >= maxHard) continue;
            return true;
        }
        return false;
    }
    private String randomStartingWord() {
        List<String> words = dictionaryIndex; if (words.isEmpty()) return null;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        int tries = Math.min(64, words.size());
        for (int i = 0; i < tries; i++) {
            String candidate = words.get(random.nextInt(words.size()));
            if (!recentStartingWords.contains(candidate) && hasContinuation(candidate, Map.of(candidate, 1), initialHardRun(candidate))) return candidate;
        }
        int start = random.nextInt(words.size());
        int scanLimit = Math.min(words.size(), 4096);
        for (int i = 0; i < scanLimit; i++) {
            String candidate = words.get((start + i) % words.size());
            if (!recentStartingWords.contains(candidate) && hasContinuation(candidate, Map.of(candidate, 1), initialHardRun(candidate))) return candidate;
        }
        for (int i = 0; i < scanLimit; i++) {
            String candidate = words.get((start + i) % words.size());
            if (hasContinuation(candidate, Map.of(candidate, 1), initialHardRun(candidate))) return candidate;
        }
        return null;
    }
    private void rememberStartingWord(String phrase) {
        recentStartingWords.add(phrase);
        int limit = Math.max(0, Math.min(1000, getConfig().getInt("game.recent-start-limit", 20)));
        synchronized (recentStartingWords) {
            while (recentStartingWords.size() > limit) recentStartingWords.remove(recentStartingWords.iterator().next());
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        ensureWriterConnection();
        boolean found = false;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) { while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) found = true; }
        if (!found) try (Statement s = connection.createStatement()) { s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition); }
    }

    private int importSources(List<String> urls, int timeout, int maxLength, boolean reportErrors, boolean skipImported) {
        int imported = 0;
        for (String spec : urls) {
            if (skipImported && wasSourceImported(spec)) continue;
        try {
                int beforeSource = imported;
                String[] source = spec.split("\\|", 2); String format = source.length == 2 ? source[0].trim().toLowerCase(Locale.ROOT) : "plain"; String url = source.length == 2 ? source[1].trim() : spec.trim();
                var conn = URI.create(url).toURL().openConnection(); conn.setConnectTimeout(timeout * 1000); conn.setReadTimeout(Math.max(timeout, format.equals("tu-dien-archive") ? 60 : timeout) * 1000); conn.setRequestProperty("User-Agent", "WordChain-Paper/1.3 (dictionary importer)");
                int sourceAdded;
                try (InputStream raw = conn.getInputStream()) {
                    if (format.equals("tu-dien-archive")) sourceAdded = importTuDienArchive(raw, maxLength);
                    else if (format.equals("json-array")) sourceAdded = importJsonPhraseArray(raw, maxLength);
                    else {
                        List<String> batch = new ArrayList<>(512); long charsRead = 0; int lineCount = 0;
                        try (BufferedReader lines = new BufferedReader(new InputStreamReader(url.endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw, StandardCharsets.UTF_8), 16 * 1024)) {
                            String line; while ((line = lines.readLine()) != null) {
                                charsRead += line.length(); if (++lineCount > 1_000_000 || charsRead > 256_000_000L) break;
                                if (line.length() > 16_384) continue;
                                String phrase = switch (format) {
                                    case "kaikki", "wiktionary-jsonl" -> extractVietnameseHeadword(line);
                                    case "tab", "tudientv" -> line.split("\\t", 2)[0];
                                    default -> line;
                                };
                                if (phrase != null) { String normalized = normalizePhrase(phrase.replaceFirst("^\\uFEFF", "")); if (isTwoSyllablePhrase(normalized) && normalized.length() <= maxLength && normalized.matches("[\\p{L}\\p{M} ]+")) batch.add(normalized); }
                                if (batch.size() >= 512) { imported += importBatch(batch); batch.clear(); }
                            }
                        }
                        if (!batch.isEmpty()) imported += importBatch(batch);
                        sourceAdded = imported - beforeSource;
                    }
                }
                markSourceImported(spec, sourceAdded);
                if (format.equals("tu-dien-archive") || format.equals("json-array")) imported += sourceAdded;
            } catch (Exception e) { if (reportErrors || spec.startsWith("tu-dien-archive|") || spec.startsWith("json-array|")) getLogger().warning("Dictionary source failed " + spec + ": " + e.getMessage()); }
        }
        refreshWordIndex();
        return imported;
    }

    private int importJsonPhraseArray(InputStream source, int maxLength) throws IOException {
        List<String> batch = new ArrayList<>(512);
        int[] added = {0};
        InputStream bounded = new BoundedInputStream(source, 32L * 1024 * 1024);
        try (Reader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8), 16 * 1024)) {
            JsonPhraseArrayImporter.importPhrases(reader, "text", maxLength, phrase -> {
                batch.add(phrase);
                if (batch.size() >= 512) { added[0] += importBatch(batch); batch.clear(); }
            });
        }
        if (!batch.isEmpty()) added[0] += importBatch(batch);
        getLogger().info("Imported " + added[0] + " new two-syllable phrases from the Vietnamese-dictionary-api JSON source.");
        return added[0];
    }

    private int importTuDienArchive(InputStream input, int maxLength) throws IOException {
        int imported = 0; int wordFiles = 0; long archiveBytes = 0;
        List<String> batch = new ArrayList<>(512);
        try (java.util.zip.GZIPInputStream tar = new java.util.zip.GZIPInputStream(new BufferedInputStream(input), 64 * 1024)) {
            byte[] header = new byte[512];
            while (readBlock(tar, header)) {
                if (isZeroBlock(header)) break;
                long size = parseTarOctal(header, 124, 12); archiveBytes += 512 + size + ((512 - size % 512) % 512);
                if (archiveBytes > 2L * 1024 * 1024 * 1024) throw new IOException("Tar archive exceeds 2 GiB unpacked limit");
                String name = tarName(header); boolean file = header[156] == 0 || header[156] == '0';
                boolean wordFile = file && name.matches("data/processed/words/[a-z]\\.json");
                if (wordFile) {
                    if (size > 64L * 1024 * 1024) throw new IOException("Word bucket exceeds 64 MiB: " + name);
                    wordFiles++;
                    BoundedInputStream bounded = new BoundedInputStream(tar, size);
                    try (Reader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8), 16 * 1024)) {
                        imported += importWordArray(reader, batch, maxLength);
                    }
                    if (!batch.isEmpty()) { imported += importBatch(batch); batch.clear(); }
                } else {
                    if (wordFiles > 0 && name.startsWith("data/processed/") && !name.startsWith("data/processed/words/")) break;
                    skipFully(tar, size);
                }
                long padding = (512 - size % 512) % 512; skipFully(tar, padding);
            }
        }
        if (!batch.isEmpty()) imported += importBatch(batch);
        if (wordFiles != 26) throw new IOException("Expected all 26 dictionary word buckets, found " + wordFiles);
        getLogger().info("Imported " + wordFiles + " Vietnamese dictionary word buckets from the tu-dien-tieng-viet release.");
        return imported;
    }

    private int importWordArray(Reader source, List<String> batch, int maxLength) throws IOException {
        PushbackReader reader = new PushbackReader(source, 2); int added = 0;
        expectJson(reader, '['); char next = nextJson(reader);
        while (next != ']') {
            if (next != '{') throw new IOException("Expected a dictionary word object");
            String word = null; char field = nextJson(reader);
            while (field != '}') {
                if (field != '"') throw new IOException("Expected a JSON field name");
                reader.unread(field); String key = readJsonString(reader); expectJson(reader, ':'); char valueStart = nextJson(reader); reader.unread(valueStart);
                if (key.equals("word") && valueStart == '"') word = readJsonString(reader); else skipJsonValue(reader);
                field = nextJson(reader);
                if (field == ',') field = nextJson(reader); else if (field != '}') throw new IOException("Expected ',' or '}' in dictionary word");
            }
            if (word != null) {
                String normalized = normalizePhrase(word);
                if (isTwoSyllablePhrase(normalized) && normalized.length() <= maxLength && normalized.matches("[\\p{L}\\p{M} ]+")) {
                    batch.add(normalized);
                    if (batch.size() >= 512) { added += importBatch(batch); batch.clear(); }
                }
            }
            next = nextJson(reader);
            if (next == ',') next = nextJson(reader); else if (next != ']') throw new IOException("Expected ',' or ']' in dictionary word array");
        }
        while (reader.read() != -1) { /* drain the bounded tar entry before reading its padding */ }
        return added;
    }

    private static char nextJson(PushbackReader reader) throws IOException {
        int value; do { value = reader.read(); } while (value >= 0 && Character.isWhitespace(value));
        if (value < 0) throw new IOException("Unexpected end of JSON dictionary bucket");
        return (char) value;
    }
    private static void expectJson(PushbackReader reader, char expected) throws IOException { char actual = nextJson(reader); if (actual != expected) throw new IOException("Expected '" + expected + "' in dictionary bucket"); }
    private static String readJsonString(PushbackReader reader) throws IOException {
        expectJson(reader, '"'); StringBuilder value = new StringBuilder(); int ch;
        while ((ch = reader.read()) >= 0) {
            if (ch == '"') return value.toString();
            if (ch != '\\') { if (value.length() >= 4096) throw new IOException("JSON string exceeds 4096 characters"); value.append((char) ch); continue; }
            int escaped = reader.read(); if (escaped < 0) break;
            switch (escaped) {
                case '"', '\\', '/' -> value.append((char) escaped);
                case 'b' -> value.append('\b'); case 'f' -> value.append('\f'); case 'n' -> value.append('\n'); case 'r' -> value.append('\r'); case 't' -> value.append('\t');
                case 'u' -> { int code = 0; for (int i = 0; i < 4; i++) { int digit = Character.digit(reader.read(), 16); if (digit < 0) throw new IOException("Invalid Unicode escape in dictionary bucket"); code = (code << 4) | digit; } if (value.length() >= 4096) throw new IOException("JSON string exceeds 4096 characters"); value.append((char) code); }
                default -> throw new IOException("Invalid JSON string escape in dictionary bucket");
            }
        }
        throw new IOException("Unterminated JSON string in dictionary bucket");
    }
    private static void skipJsonValue(PushbackReader reader) throws IOException {
        char first = nextJson(reader);
        if (first == '"') { skipJsonString(reader); return; }
        if (first == '{' || first == '[') {
            Deque<Character> closers = new ArrayDeque<>(); closers.push(first == '{' ? '}' : ']'); boolean quoted = false, escaped = false; int ch;
            while (!closers.isEmpty() && (ch = reader.read()) >= 0) {
                if (quoted) { if (escaped) escaped = false; else if (ch == '\\') escaped = true; else if (ch == '"') quoted = false; continue; }
                if (ch == '"') quoted = true; else if (ch == '{') closers.push('}'); else if (ch == '[') closers.push(']'); else if (ch == closers.peek()) closers.pop();
                if (closers.size() > 128) throw new IOException("JSON nesting exceeds 128 levels");
            }
            if (!closers.isEmpty()) throw new IOException("Unclosed JSON value in dictionary bucket");
            return;
        }
        int ch; while ((ch = reader.read()) >= 0) { if (ch == ',' || ch == '}' || ch == ']') { reader.unread(ch); return; } }
    }
    private static void skipJsonString(PushbackReader reader) throws IOException {
        boolean escaped = false; int ch;
        while ((ch = reader.read()) >= 0) {
            if (escaped) {
                if (ch == 'u') {
                    for (int i = 0; i < 4; i++) if (Character.digit(reader.read(), 16) < 0) throw new IOException("Invalid Unicode escape in dictionary bucket");
                } else if (ch != '"' && ch != '\\' && ch != '/' && ch != 'b' && ch != 'f' && ch != 'n' && ch != 'r' && ch != 't') {
                    throw new IOException("Invalid JSON string escape in dictionary bucket");
                }
                escaped = false;
            } else if (ch == '\\') escaped = true;
            else if (ch == '"') return;
        }
        throw new IOException("Unterminated JSON string in dictionary bucket");
    }
    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0; while (offset < block.length) { int count = input.read(block, offset, block.length - offset); if (count < 0) { if (offset == 0) return false; throw new IOException("Truncated tar header"); } offset += count; } return true;
    }
    private static boolean isZeroBlock(byte[] block) { for (byte b : block) if (b != 0) return false; return true; }
    private static String tarName(byte[] header) {
        int nameLength = 0; while (nameLength < 100 && header[nameLength] != 0) nameLength++;
        int prefixLength = 0; while (prefixLength < 155 && header[345 + prefixLength] != 0) prefixLength++;
        String name = new String(header, 0, nameLength, StandardCharsets.UTF_8);
        return prefixLength == 0 ? name : new String(header, 345, prefixLength, StandardCharsets.UTF_8) + "/" + name;
    }
    private static long parseTarOctal(byte[] header, int offset, int length) throws IOException {
        int end = offset + length; while (offset < end && header[offset] == ' ') offset++;
        long value = 0; boolean found = false;
        while (offset < end && header[offset] >= '0' && header[offset] <= '7') { found = true; int digit = header[offset++] - '0'; if (value > (Long.MAX_VALUE - digit) / 8) throw new IOException("Tar entry size overflows 64-bit integer"); value = (value << 3) + digit; }
        if (!found) throw new IOException("Invalid octal tar entry size"); return value;
    }
    private static void skipFully(InputStream input, long count) throws IOException {
        while (count > 0) { long skipped = input.skip(count); if (skipped > 0) count -= skipped; else { if (input.read() < 0) throw new IOException("Truncated tar entry"); count--; } }
    }
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        private BoundedInputStream(InputStream input, long remaining) { super(input); this.remaining = remaining; }
        @Override public int read() throws IOException { if (remaining <= 0) return -1; int value = in.read(); if (value >= 0) remaining--; return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { if (remaining <= 0) return -1; int count = in.read(bytes, offset, (int) Math.min(length, remaining)); if (count > 0) remaining -= count; return count; }
        @Override public void close() { /* The tar reader owns the underlying archive stream. */ }
    }

    private synchronized boolean wasSourceImported(String source) {
        try { ensureWriterConnection(); } catch (SQLException e) { getLogger().warning("Could not reopen database: " + e.getMessage()); return false; }
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM dictionary_sources WHERE source=?")) { ps.setString(1, source); try (ResultSet rs = ps.executeQuery()) { return rs.next(); } }
        catch (SQLException e) { getLogger().warning("Could not check dictionary source cache: " + e.getMessage()); return false; }
    }
    private synchronized void markSourceImported(String source, int added) throws SQLException {
        ensureWriterConnection();
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO dictionary_sources(source,imported_at,entries_added) VALUES(?,?,?) ON CONFLICT(source) DO UPDATE SET imported_at=excluded.imported_at,entries_added=excluded.entries_added")) { ps.setString(1, source); ps.setLong(2, System.currentTimeMillis()); ps.setInt(3, added); ps.executeUpdate(); }
    }

    private void scheduleImport(CommandSender sender, boolean reportErrors) {
        if (!importRunning.compareAndSet(false, true)) { if (sender != null) sender.sendMessage(message("<yellow>Một lần nhập dữ liệu đang chạy.")); return; }
        List<String> sources = List.copyOf(getConfig().getStringList("dictionary.sources"));
        int timeout = Math.max(2, getConfig().getInt("dictionary.request-timeout-seconds", 20));
        int maxLength = getConfig().getInt("game.max-phrase-length", 48);
        if (sender != null) sender.sendMessage(message("<yellow>Đang tải danh sách từ bất đồng bộ..."));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int imported = 0;
            try { if (!dictionaryReady) loadDictionaryCache(); imported = importSources(sources, timeout, maxLength, reportErrors, sender == null); }
            finally {
                importRunning.set(false); int result = imported;
                if (sender != null) Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message("<green>Đã nhập " + result + " mục từ.")));
            }
        });
    }

    private synchronized int importBatch(List<String> phrases) {
        int added = 0; boolean committed = false;
        try {
            ensureWriterConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO words(phrase,first_syllable,last_syllable) VALUES(?,?,?)")) {
                for (String phrase : phrases) { String[] parts = phrase.split(" "); ps.setString(1, phrase); ps.setString(2, parts[0]); ps.setString(3, parts[1]); ps.addBatch(); }
                for (int result : ps.executeBatch()) if (result > 0 || result == Statement.SUCCESS_NO_INFO) added++;
            }
            connection.commit();
            committed = true;
        } catch (SQLException e) { try { connection.rollback(); } catch (SQLException ignored) {} getLogger().warning("Dictionary batch import failed: " + e.getMessage()); }
        finally { try { connection.setAutoCommit(true); } catch (SQLException ignored) {} }
        if (committed) dictionary.addAll(phrases);
        return added;
    }

    private static final Pattern JSON_WORD = Pattern.compile("\"word\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern JSON_VI_CODE = Pattern.compile("\"lang_code\"\\s*:\\s*\"vi\"");
    private static final Pattern JSON_VI_NAME = Pattern.compile("\"lang\"\\s*:\\s*\"Vietnamese\"");
    private static final Pattern JSON_UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static String extractVietnameseHeadword(String json) {
        if (!(JSON_VI_CODE.matcher(json).find() || JSON_VI_NAME.matcher(json).find())) return null;
        Matcher matcher = JSON_WORD.matcher(json); if (!matcher.find()) return null;
        String raw = matcher.group(1); Matcher unicode = JSON_UNICODE_ESCAPE.matcher(raw); StringBuffer decoded = new StringBuffer();
        while (unicode.find()) { char c = (char) Integer.parseInt(unicode.group(1), 16); unicode.appendReplacement(decoded, Matcher.quoteReplacement(String.valueOf(c))); }
        unicode.appendTail(decoded);
        return decoded.toString().replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private synchronized boolean addWord(String phrase, int maxLength) {
        String[] parts = phrase.split(" "); if (!isTwoSyllablePhrase(phrase) || phrase.length() > maxLength || !phrase.matches("[\\p{L}\\p{M} ]+")) return false;
        try { ensureWriterConnection(); } catch (SQLException e) { getLogger().warning("Could not reopen database: " + e.getMessage()); return false; }
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO words(phrase,first_syllable,last_syllable) VALUES(?,?,?)")) {
            ps.setString(1, phrase); ps.setString(2, parts[0]); ps.setString(3, parts[parts.length - 1]); boolean added = ps.executeUpdate() > 0; if (added) { dictionary.add(phrase); refreshWordIndex(); } return added;
        } catch (SQLException e) { return false; }
    }
    private long countWords() { return dictionary.size(); }
    private void addWordAsync(CommandSender sender, String phrase) {
        int maxLength = getConfig().getInt("game.max-phrase-length", 48);
        if (!isTwoSyllablePhrase(phrase) || phrase.length() > maxLength) { sender.sendMessage(message("<red>Chỉ thêm cụm từ đúng hai tiếng.")); return; }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { boolean added = addWord(phrase, maxLength); Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(added ? "<green>Đã thêm: " + escape(phrase) : "<yellow>Từ đã tồn tại hoặc không hợp lệ."))); });
    }

    private void payReward(UUID id, int streak) {
        rewardService.reward(id, streak);
        Player p = Bukkit.getPlayer(id); if (p == null) return;
        double multiplier = rewardService.multiplier(streak);
        int maxPerMaterial = Math.max(1, getConfig().getInt("streak-rewards.max-items-per-type", 64));
        for (String spec : getConfig().getStringList("rewards.items")) {
            String[] bits = spec.split(":", 2); if (bits.length != 2) continue;
            Material type = Material.matchMaterial(bits[0].trim()); int amount;
            try { amount = Integer.parseInt(bits[1].trim()); } catch (NumberFormatException e) { continue; }
            if (type == null || !type.isItem() || amount <= 0) { getLogger().warning("Invalid reward entry: " + spec); continue; }
            amount = Math.min(maxPerMaterial, Math.max(1, (int) Math.floor(amount * multiplier)));
            int remaining = amount;
            while (remaining > 0) { int stack = Math.min(remaining, type.getMaxStackSize()); Map<Integer, ItemStack> overflow = p.getInventory().addItem(new ItemStack(type, stack)); overflow.values().forEach(item -> p.getWorld().dropItemNaturally(p.getLocation(), item)); remaining -= stack; }
        }
    }
    private void saveWin(UUID id, String name, boolean resetOtherStreaks, boolean duel, UUID duelOpponent) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { int streak = 1; synchronized (this) {
            try {
                ensureWriterConnection();
                connection.setAutoCommit(false);
                if (duel && duelOpponent != null) {
                    try (PreparedStatement reset = connection.prepareStatement("UPDATE wins SET current_streak=0 WHERE uuid=?")) { reset.setString(1, duelOpponent.toString()); reset.executeUpdate(); }
                } else if (resetOtherStreaks) {
                    try (PreparedStatement reset = connection.prepareStatement("UPDATE wins SET current_streak=0 WHERE uuid<>?")) { reset.setString(1, id.toString()); reset.executeUpdate(); }
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO wins(uuid,player_name,wins,current_streak,best_streak) VALUES(?,?,1,1,1) ON CONFLICT(uuid) DO UPDATE SET player_name=excluded.player_name,wins=wins+1,current_streak=current_streak+1,best_streak=MAX(best_streak,current_streak+1)")) { ps.setString(1, id.toString()); ps.setString(2, name); ps.executeUpdate(); }
                try (PreparedStatement ps = connection.prepareStatement("SELECT current_streak FROM wins WHERE uuid=?")) { ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) streak = rs.getInt(1); } }
                connection.commit();
            } catch (SQLException e) { try { connection.rollback(); } catch (SQLException ignored) {} getLogger().warning("Could not save win: " + e.getMessage()); }
            finally { try { connection.setAutoCommit(true); } catch (SQLException ignored) {} }
        } refreshLeaderboardCache(); int currentStreak = streak; Bukkit.getScheduler().runTask(this, () -> {
            payReward(id, currentStreak);
            Component streakMessage = message("<gold>Chuỗi thắng của <yellow>" + escape(name) + "</yellow>: <bold>" + currentStreak + "</bold>! Phần thưởng được nhân theo chuỗi.");
            if (duel) {
                Player winner = Bukkit.getPlayer(id); if (winner != null) winner.sendMessage(streakMessage);
                Player opponent = duelOpponent == null ? null : Bukkit.getPlayer(duelOpponent); if (opponent != null) opponent.sendMessage(streakMessage);
            } else Bukkit.broadcast(streakMessage);
            Bukkit.getPluginManager().callEvent(new WordChainWinEvent(id, name, currentStreak, duel));
        }); });
    }

    private void startNextMatch() {
        long delay = Math.max(0, getConfig().getLong("game.next-match-delay-seconds", 5)) * 20L;
        cancelNextMatch(); nextMatchTask=Bukkit.getScheduler().runTaskLater(this, () -> {
            nextMatchTask=null; if(game.isActive())return;
            String start = randomStartingWord(); if (start == null) { Bukkit.broadcast(message("<yellow>Chưa tìm được từ mở màn có nước nối hợp lệ.")); return; }
            rememberStartingWord(start);
            game.start(start); announcePublicJoin(start);
        }, delay);
    }
    private void cancelNextMatch(){if(nextMatchTask!=null){nextMatchTask.cancel();nextMatchTask=null;}}

    private void setDifficulty(CommandSender sender, String[] args) {
        if(args.length!=4){sender.sendMessage(message("<yellow>Dùng: /wordchain difficulty <tiếng1> <tiếng2> <1-5>"));return;}
        String phrase=normalizePhrase(args[1]+" "+args[2]);int level;try{level=Integer.parseInt(args[3]);}catch(NumberFormatException ex){sender.sendMessage(message("<red>Mức khó phải từ 1 đến 5."));return;}
        if(!isTwoSyllablePhrase(phrase)||level<1||level>5){sender.sendMessage(message("<red>Cụm từ phải có đúng hai tiếng và mức khó 1-5."));return;}
        getConfig().set("difficulty.words."+phrase,level);persistConfigAsync();sender.sendMessage(message("<green>Đã đặt độ khó "+level+" cho "+escape(phrase)+"."));
    }
    private void notifyInvalidMove(UUID id,String raw){long now=System.currentTimeMillis(),last=wrongMoveNotice.getOrDefault(id,0L);if(now-last<1500)return;wrongMoveNotice.put(id,now);Player p=Bukkit.getPlayer(id);if(p==null)return;String phrase=normalizePhrase(raw),required=game.requiredSyllable();String feedback=!phrase.startsWith(required+" ")?"<red>Cụm từ phải bắt đầu bằng <yellow>"+escape(required)+"</yellow>.":!dictionary.contains(phrase)?"<red>Từ chưa có trong từ điển. Dùng /wordchain report "+escape(phrase)+" để báo từ.":"<red>Cụm từ đã dùng quá giới hạn hoặc không hợp lệ.";p.sendMessage(message(feedback));}

    private void createDuelInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player challenger)) { sender.sendMessage("Only players can challenge a duel."); return; }
        if (args.length != 5 && args.length != 4) {
            sender.sendMessage(message("<yellow>/wordchain duel <player> <seconds|off> <vault|MATERIAL> <amount>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || target.equals(challenger) || duels.containsKey(target.getUniqueId()) || duels.containsKey(challenger.getUniqueId())) {
            sender.sendMessage(message("<red>Người chơi không hợp lệ hoặc đang đấu."));
            return;
        }
        int seconds;
        if (args[2].equalsIgnoreCase("off")) seconds = 0;
        else try { seconds = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) { sender.sendMessage(message("<red>Thời gian phải là số giây hoặc off.")); return; }
        if (seconds != 0 && (seconds < 5 || seconds > 300)) {
            sender.sendMessage(message("<red>Thời gian kết nối hợp lệ: off hoặc 5-300 giây.")); return;
        }
        String stakeType = args.length == 4 ? "vault" : args[3].toLowerCase(Locale.ROOT);
        String stakeText = args.length == 4 ? args[3] : args[4];
        double stake;
        try { stake = Double.parseDouble(stakeText); }
        catch (NumberFormatException e) { sender.sendMessage(message("<red>Số cược phải là số hợp lệ; dùng 0 để đấu vui.")); return; }
        if (!Double.isFinite(stake) || stake < 0) { sender.sendMessage(message("<red>Số cược không hợp lệ.")); return; }
        if (stakeType.equals("auto")) {
            String configuredType = getConfig().getString("duel.default-stake-type", "auto");
            stakeType = configuredType == null || configuredType.equalsIgnoreCase("auto")
                    ? (rewardService.isEconomyAvailable() ? "vault" : getConfig().getString("duel.fallback-item", "DIAMOND").toLowerCase(Locale.ROOT))
                    : configuredType.toLowerCase(Locale.ROOT);
        }
        if (!stakeType.equals("vault")) {
            Material selected = Material.matchMaterial(stakeType);
            if (selected == null || !selected.isItem() || selected.isAir()) {
                sender.sendMessage(message("<red>Loại cược phải là vault hoặc tên vật phẩm Bukkit hợp lệ.")); return;
            }
            stakeType = selected.name().toLowerCase(Locale.ROOT);
        }
        if (stake > 0 && stakeType.equals("vault")) {
            double max = Math.min(Double.MAX_VALUE / 2, Math.max(0, getConfig().getDouble("duel.max-wager", 1_000_000)));
            if (!GameplayRules.isValidWager(stake, max) || !rewardService.isEconomyAvailable()) {
                sender.sendMessage(message("<red>Cược Vault không hợp lệ hoặc Vault/economy chưa sẵn sàng.")); return;
            }
            if (!rewardService.hasFunds(challenger.getUniqueId(), stake)) { sender.sendMessage(message("<red>Bạn không đủ số dư Vault.")); return; }
        } else if (stake > 0) {
            Material material = Material.matchMaterial(stakeType);
            int max = Math.max(0, Math.min(Integer.MAX_VALUE / 2, getConfig().getInt("duel.max-item-stake", 64)));
            if (material == null || !material.isItem() || material.isAir() || stake != Math.rint(stake) || stake > max
                    || !hasItem(challenger, material, (int) stake)) {
                sender.sendMessage(message("<red>Vật cược phải là material hợp lệ, số nguyên tối đa " + max + ", và bạn phải có đủ vật phẩm.")); return;
            }
        }
        DuelInvite invite = new DuelInvite(challenger.getUniqueId(), challenger.getName(), target.getUniqueId(), seconds, stakeType, stake);
        duelInvites.put(target.getUniqueId(), invite);
        String wagerText = stake == 0 ? "đấu vui, không cược" : "mỗi bên cược " + formatStake(stakeType, stake);
        String turnText = seconds == 0 ? "không giới hạn" : seconds + " giây";
        target.sendMessage(message("<gold>" + escape(challenger.getName()) + " mời bạn đấu Nối Từ (" + wagerText
                + "). Dùng <yellow>/wordchain accept</yellow> để đồng ý mức cược hoặc <yellow>/wordchain deny</yellow> để từ chối. Thời gian nối: " + turnText + "."));
        challenger.sendMessage(message("<green>Đã gửi lời mời đấu tới " + escape(target.getName()) + " (" + wagerText + ")."));
        long expiryTicks = Math.max(20, getConfig().getLong("duel.invite-expiry-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> duelInvites.remove(target.getUniqueId(), invite), expiryTicks);
    }

    private void acceptDuel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player receiver)) { sender.sendMessage("Only players can accept duels."); return; }
        DuelInvite invite = duelInvites.get(receiver.getUniqueId());
        if (invite == null) { joinPublicGame(receiver); return; }
        Player challenger = Bukkit.getPlayer(invite.challenger());
        if (challenger == null || !dictionaryReady) {
            duelInvites.remove(receiver.getUniqueId(), invite);
            sender.sendMessage(message("<red>Người thách đấu đã rời mạng hoặc từ điển chưa sẵn sàng."));
            return;
        }
        if (duels.containsKey(challenger.getUniqueId()) || duels.containsKey(receiver.getUniqueId())) return;
        String start = randomStartingWord();
        if (start == null) { sender.sendMessage(message("<red>Từ điển chưa có từ mở màn khả dụng.")); return; }

        boolean escrowed = false;
        if (invite.stake() > 0) {
            Material item = invite.stakeType().equals("vault") ? null : Material.matchMaterial(invite.stakeType());
            if (item == null && invite.stakeType().equals("vault")) {
                if (!rewardService.isEconomyAvailable() || !rewardService.hasFunds(invite.challenger(), invite.stake())
                        || !rewardService.hasFunds(receiver.getUniqueId(), invite.stake())) {
                    cancelDuelInvite(receiver, challenger, invite, "<red>Vault/economy không sẵn sàng hoặc một bên không đủ tiền; duel bị hủy.", "<yellow>Không thể giữ đủ tiền cược; duel đã bị hủy."); return;
                }
            } else if (item == null || !hasItem(challenger, item, (int) invite.stake()) || !hasItem(receiver, item, (int) invite.stake())) {
                cancelDuelInvite(receiver, challenger, invite, "<red>Một bên không đủ vật phẩm cược; duel bị hủy.", "<yellow>Bạn hoặc đối thủ không đủ vật phẩm cược."); return;
            }
            if (!withdrawStake(challenger, invite.stakeType(), invite.stake())) {
                cancelDuelInvite(receiver, challenger, invite, "<red>Không thể giữ cược từ người thách đấu.", "<yellow>Không thể giữ cược; duel đã bị hủy."); return;
            }
            if (!withdrawStake(receiver, invite.stakeType(), invite.stake())) {
                boolean refunded = depositStake(challenger.getUniqueId(), invite.stakeType(), invite.stake());
                cancelDuelInvite(receiver, challenger, invite, "<red>Không thể giữ cược từ bạn.</red>", refunded
                        ? "<yellow>Không thể bắt đầu cược; tài sản của bạn đã được hoàn lại."
                        : "<red>Không thể hoàn tài sản tự động. Hãy báo admin.</red>"); return;
            }
            escrowed = true;
        }

        duelInvites.remove(receiver.getUniqueId(), invite);
        rememberStartingWord(start);
        DuelMatch match = new DuelMatch(challenger.getUniqueId(), challenger.getName(), receiver.getUniqueId(),
                receiver.getName(), invite.connectSeconds(), start, invite.stakeType(), invite.stake(), escrowed);
        duels.put(challenger.getUniqueId(), match);
        duels.put(receiver.getUniqueId(), match);
        long maxTicks = Math.max(60, Math.min(3600, getConfig().getLong("duel.max-match-seconds", 3600))) * 20L;
        match.maxTask = Bukkit.getScheduler().runTaskLater(this,
                () -> finishDuel(match, null, "Trận đấu tự dừng sau một giờ."), maxTicks).getTaskId();
        armDuelTurn(match);
        String wagerText = invite.stake() == 0 ? "Trận đấu này không cược."
                : "Mỗi bên đã cược " + formatStake(invite.stakeType(), invite.stake()) + "; người thắng nhận gấp đôi.";
        sendDuelMessage(match, "<gold>Đấu bắt đầu! <yellow>" + escape(challenger.getName()) + "</yellow> đấu với <yellow>"
                + escape(receiver.getName()) + "</yellow>. " + escape(wagerText) + " Từ khóa: <aqua>" + escape(start)
                + "</aqua>. Lượt đầu: " + escape(challenger.getName()) + ".");
    }

    private void cancelDuelInvite(Player receiver, Player challenger, DuelInvite invite, String receiverMessage, String challengerMessage) {
        duelInvites.remove(receiver.getUniqueId(), invite);
        receiver.sendMessage(message(receiverMessage));
        challenger.sendMessage(message(challengerMessage));
    }
    private void denyDuel(CommandSender sender,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("Only players can deny duels.");return;}DuelInvite invite=duelInvites.remove(p.getUniqueId());if(invite==null){sender.sendMessage(message("<red>Không có lời mời đang chờ."));return;}Player challenger=Bukkit.getPlayer(invite.challenger);if(challenger!=null)challenger.sendMessage(message("<yellow>"+escape(p.getName())+" đã từ chối lời mời."));sender.sendMessage(message("<gray>Đã từ chối lời mời."));}
    private void armDuelTurn(DuelMatch match){if(match.turnSeconds<=0)return;if(match.turnTask!=0)Bukkit.getScheduler().cancelTask(match.turnTask);match.turnTask=Bukkit.getScheduler().runTaskLater(this,()->finishDuel(match,otherPlayer(match,match.turn),"Hết thời gian nối từ."),match.turnSeconds*20L).getTaskId();}
    private UUID otherPlayer(DuelMatch m,UUID id){return m.first.equals(id)?m.second:m.first;}
    private void processDuelMove(DuelMatch m,UUID id,String name,String raw){
        if(!m.active)return;if(!m.turn.equals(id)){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<yellow>Chưa đến lượt bạn nối từ."));return;}
        if(!hasContinuation(m.last,m.uses,m.hardRun,m.previous)){resetDuelRound(m);return;}
        String phrase=raw;String required=m.last.split(" ")[1];if(!phrase.startsWith(required+" ")){notifyDuelError(id,"<red>Nối chưa đúng; tiếng đầu phải là <yellow>"+escape(required)+"</yellow>.");return;}if(!dictionary.contains(phrase)){notifyDuelError(id,"<red>Từ chưa có trong từ điển; hãy báo bằng /wordchain report "+escape(phrase)+".");return;}
        if(!GameplayRules.allowsNextPhrase(phrase,m.previous)){notifyDuelError(id,"<red>Không thể nối ngược ngay về cụm vừa dùng; hãy chọn cụm khác để tránh lặp vòng.");return;}
        int limit=Math.max(1,Math.min(5,getConfig().getInt("anti-abuse.word-use-limit",3)));if(m.uses.getOrDefault(phrase,0)>=limit){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<red>Cụm từ này đã đạt giới hạn sử dụng."));return;}
        int difficulty=getConfig().getInt("difficulty.words."+phrase,1),threshold=getConfig().getInt("difficulty.hard-threshold",4),maxHard=Math.max(1,getConfig().getInt("anti-abuse.max-hard-words-in-row",2));if(difficulty>=threshold&&m.hardRun>=maxHard){notifyDuelError(id,"<red>Đã đạt giới hạn cụm khó liên tiếp. Hãy chọn một cụm dễ hơn.");return;}m.hardRun=difficulty>=threshold?m.hardRun+1:0;
        m.uses.merge(phrase,1,Integer::sum);m.previous=m.last;m.last=phrase;m.moves++;m.turn=otherPlayer(m,id);armDuelTurn(m);sendDuelMessage(m,"<aqua>[ĐẤU]</aqua> <yellow>"+escape(name)+"</yellow>: <white>"+escape(phrase)+"</white>. Từ cần nối: <gold>"+escape(phrase.split(" ")[1])+"</gold>.");
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id,name,phrase));
        if(m.moves>=getConfig().getInt("duel.target-moves",10))finishDuel(m,id,"Đấu kết thúc!");
        else if(!hasContinuation(m.last,m.uses,m.hardRun,m.previous))resetDuelRound(m);
    }
    private void resetDuelRound(DuelMatch match) {
        String next = randomStartingWord();
        if (next == null) { finishDuel(match, null, "Không còn từ mở màn có nước nối."); return; }
        rememberStartingWord(next); match.last=next; match.previous=""; match.turn=match.first; match.uses.clear(); match.uses.put(next,1); match.hardRun=initialHardRun(next); match.moves=0; armDuelTurn(match);
        sendDuelMessage(match,"<yellow>Không còn nước nối hợp lệ; ván duel được đặt lại với từ khóa ngẫu nhiên <gold>"+escape(next)+"</gold>. Lượt đầu: "+escape(match.firstName)+".");
    }
    private void finishDuel(DuelMatch match, UUID winner, String reason) {
        if (!match.active) return;
        match.active = false;
        duels.remove(match.first, match);
        duels.remove(match.second, match);
        if (match.turnTask != 0) Bukkit.getScheduler().cancelTask(match.turnTask);
        if (match.maxTask != 0) Bukkit.getScheduler().cancelTask(match.maxTask);

        String payoutMessage = settleDuelWager(match, winner);
        if (winner == null) {
            sendDuelMessage(match, "<yellow>" + reason + " Không có người thắng." + payoutMessage);
            return;
        }
        String winnerName = match.first.equals(winner) ? match.firstName : match.secondName;
        sendDuelMessage(match, "<gold>" + reason + " Người thắng: <yellow>" + escape(winnerName) + "</yellow>!" + payoutMessage);
        saveWin(winner, winnerName, true, true, otherPlayer(match, winner));
    }

    private String settleDuelWager(DuelMatch match, UUID winner) {
        if (!match.wagerEscrowed) return "";
        match.wagerEscrowed = false;
        if (winner == null) {
            boolean firstRefund = depositStake(match.first, match.stakeType, match.stake);
            boolean secondRefund = depositStake(match.second, match.stakeType, match.stake);
            if (firstRefund && secondRefund) return " Tài sản cược đã được hoàn lại.";
            getLogger().severe("Could not refund duel escrow " + formatStake(match.stakeType, match.stake) + " for " + match.first + " and " + match.second);
            return " <red>Không hoàn đủ cược; hãy báo admin kiểm tra.</red>";
        }
        if (depositStake(winner, match.stakeType, match.stake * 2)) return " Người thắng nhận " + formatStake(match.stakeType, match.stake * 2) + ".";
        getLogger().severe("Could not pay duel escrow " + formatStake(match.stakeType, match.stake) + " to " + winner);
        return " <red>Không trả được cược; hãy báo admin kiểm tra.</red>";
    }

    private boolean hasItem(Player player, Material material, int amount) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && stack.getType() == material) count += stack.getAmount();
        return count >= amount;
    }

    private boolean withdrawStake(Player player, String type, double amount) {
        if (type.equals("vault")) return rewardService.withdrawWager(player.getUniqueId(), amount);
        Material material = Material.matchMaterial(type);
        if (material == null || amount != Math.rint(amount) || !hasItem(player, material, (int) amount)) return false;
        int remaining = (int) amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(stack.getAmount(), remaining);
            remaining -= take;
            if (take == stack.getAmount()) contents[i] = null;
            else stack.setAmount(stack.getAmount() - take);
        }
        player.getInventory().setStorageContents(contents);
        return remaining == 0;
    }

    private boolean depositStake(UUID playerId, String type, double amount) {
        if (type.equals("vault")) return rewardService.depositWager(playerId, amount);
        Player player = Bukkit.getPlayer(playerId);
        Material material = Material.matchMaterial(type);
        if (player == null || material == null || amount != Math.rint(amount) || amount <= 0) return false;
        int remaining = (int) amount;
        while (remaining > 0) {
            int count = Math.min(remaining, material.getMaxStackSize());
            ItemStack stack = new ItemStack(material, count);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            remaining -= count;
        }
        return true;
    }

    private String formatStake(String type, double amount) {
        return type.equals("vault") ? formatMoney(amount) + " Vault" : ((int) amount) + " " + type.toUpperCase(Locale.ROOT);
    }

    private void sendDuelMessage(DuelMatch match,String text){Component component=message(text);Player first=Bukkit.getPlayer(match.first),second=Bukkit.getPlayer(match.second);if(first!=null)first.sendMessage(component);if(second!=null)second.sendMessage(component);}
    private void notifyDuelError(UUID id,String text){long now=System.currentTimeMillis(),last=wrongMoveNotice.getOrDefault(id,0L);if(now-last<1500)return;wrongMoveNotice.put(id,now);Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message(text));}

    private record DuelInvite(UUID challenger,String challengerName,UUID target,int connectSeconds,String stakeType,double stake){}
    private final class DuelMatch {final UUID first,second;final String firstName,secondName;final int turnSeconds;final String stakeType;final double stake;final Map<String,Integer> uses=new HashMap<>();String last,previous="";UUID turn;int moves,turnTask,maxTask,hardRun;boolean active=true,wagerEscrowed;DuelMatch(UUID a,String an,UUID b,String bn,int seconds,String start,String stakeType,double stake,boolean escrowed){first=a;firstName=an;second=b;secondName=bn;turnSeconds=seconds;this.stakeType=stakeType;this.stake=stake;wagerEscrowed=escrowed;last=start;turn=a;uses.put(start,1);hardRun=initialHardRun(start);}}
    private static String formatMoney(double amount){return String.format(Locale.ROOT,"%.2f",amount);}
    private void showTop(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> rows = new ArrayList<>();
            try (Connection reader = openReadConnection(); Statement statement = reader.createStatement();
                 ResultSet result = statement.executeQuery("SELECT player_name,wins FROM wins ORDER BY wins DESC, player_name ASC LIMIT 10")) {
                while (result.next()) rows.add(result.getString(1) + ": " + result.getInt(2));
            } catch (SQLException e) { getLogger().warning("Could not read leaderboard: " + e.getMessage()); }
            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(message("<gold><bold>Bảng xếp hạng Nối Từ</bold>"));
                if (rows.isEmpty()) sender.sendMessage(message("<gray>Chưa có người chiến thắng."));
                for (int i = 0; i < rows.size(); i++) sender.sendMessage(message("<yellow>" + (i + 1) + ". <white>" + escape(rows.get(i))));
            });
        });
    }

    private java.util.concurrent.CompletionStage<Integer> lookupWins(UUID id) {
        java.util.concurrent.CompletableFuture<Integer> result = new java.util.concurrent.CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection reader = openReadConnection(); PreparedStatement statement = reader.prepareStatement("SELECT wins FROM wins WHERE uuid=?")) {
                statement.setString(1, id.toString());
                try (ResultSet rows = statement.executeQuery()) { result.complete(rows.next() ? rows.getInt(1) : 0); }
            } catch (SQLException e) { result.completeExceptionally(e); }
        });
        return result;
    }

    private java.util.concurrent.CompletionStage<List<WordChainApi.LeaderboardEntry>> lookupLeaderboard(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        java.util.concurrent.CompletableFuture<List<WordChainApi.LeaderboardEntry>> result = new java.util.concurrent.CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection reader = openReadConnection(); PreparedStatement statement = reader.prepareStatement(
                    "SELECT player_name,wins,current_streak,best_streak FROM wins ORDER BY wins DESC, player_name COLLATE NOCASE ASC LIMIT ?")) {
                statement.setInt(1, limit);
                List<WordChainApi.LeaderboardEntry> entries = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) entries.add(new WordChainApi.LeaderboardEntry(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getInt(4)));
                }
                result.complete(List.copyOf(entries));
            } catch (SQLException e) { result.completeExceptionally(e); }
        });
        return result;
    }

    private void refreshLeaderboardCache() {
        lookupLeaderboard(10).thenAccept(entries -> leaderboardCache = entries)
                .exceptionally(error -> { getLogger().warning("Could not refresh leaderboard cache: " + error.getMessage()); return null; });
    }

    String placeholderLeaderboard(int rank, String column) {
        List<WordChainApi.LeaderboardEntry> snapshot = leaderboardCache;
        if (rank < 1 || rank > snapshot.size()) return column.equals("name") ? "" : "0";
        WordChainApi.LeaderboardEntry entry = snapshot.get(rank - 1);
        return switch (column) {
            case "name" -> entry.playerName();
            case "wins" -> Integer.toString(entry.wins());
            case "streak" -> Integer.toString(entry.currentStreak());
            case "best_streak" -> Integer.toString(entry.bestStreak());
            default -> "";
        };
    }

    boolean placeholderParticipating(UUID playerId) { return playerId != null && publicParticipants.contains(playerId); }
    boolean placeholderInDuel(UUID playerId) { return playerId != null && duels.containsKey(playerId); }
    String placeholderDuelWager(UUID playerId) {
        DuelMatch match = playerId == null ? null : duels.get(playerId);
        return match == null || match.stake == 0 ? "0" : formatStake(match.stakeType, match.stake);
    }

    private void openPlayerMenu(Player player) {
        if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(this, () -> openPlayerMenu(player)); return; }
        if (!player.isOnline()) return;
        if (!player.hasPermission("wordchain.menu")) { deny(player); return; }
        openMenu(player);
    }

    private void showPlayerStats(CommandSender sender, String name) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String foundName = null; int wins = 0, streak = 0, best = 0;
            try (Connection reader = openReadConnection(); PreparedStatement statement = reader.prepareStatement(
                    "SELECT player_name,wins,current_streak,best_streak FROM wins WHERE player_name=? COLLATE NOCASE")) {
                statement.setString(1, name);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) { foundName = rows.getString(1); wins = rows.getInt(2); streak = rows.getInt(3); best = rows.getInt(4); }
                }
            } catch (SQLException e) { getLogger().warning("Could not read player stats: " + e.getMessage()); }
            String resultName = foundName; int resultWins = wins, resultStreak = streak, resultBest = best;
            Bukkit.getScheduler().runTask(this, () -> {
                if (resultName == null) sender.sendMessage(message("<red>Chưa có dữ liệu cho người chơi này."));
                else sender.sendMessage(message("<gold>Thống kê <yellow>" + escape(resultName) + "</yellow>: <white>" + resultWins
                        + " thắng, chuỗi hiện tại " + resultStreak + ", kỷ lục " + resultBest + "."));
            });
        });
    }
    private void openMenu(Player p) {
        boolean admin = p.hasPermission("wordchain.admin")||p.hasPermission("wordchain.rewards")||p.hasPermission("wordchain.stats"); MenuHolder holder = new MenuHolder(MenuMode.MAIN);
        Inventory inv = Bukkit.createInventory(holder, admin ? 45 : 27, message(getConfig().getString("menu.title", "<dark_green>Nối Từ</dark_green>"))); holder.inventory = inv;
        put(inv, 10, Material.LIME_WOOL, "<green><bold>Bắt đầu ván</bold>", "<gray>Từ mở màn được chọn ngẫu nhiên, tránh lặp gần đây");
        put(inv, 12, Material.BARRIER, "<red>Đề xuất dừng ván", "<gray>Người chơi mở vote; admin có thể dừng ngay");
        put(inv, 14, Material.SUNFLOWER, "<yellow>Đề xuất ván mới", "<gray>Người chơi khác bỏ phiếu; cần ít nhất 1/3 đồng ý");
        put(inv, 16, Material.GOLD_INGOT, "<gold>Bảng xếp hạng", "<gray>Xem 10 người thắng nhiều nhất");
        put(inv, 20, Material.LIME_DYE, "<green>Tham gia ván", "<gray>Chấp nhận lời mời hoặc vào ván công khai");
        put(inv, 24, Material.IRON_SWORD, "<aqua>Thách đấu duel", "<gray>Dùng /noitu duel <tên người chơi>");
        put(inv, 22, Material.BOOK, "<aqua>Thông tin", "<gray>Từ trong dữ liệu: " + countWords(), "<gray>Mục tiêu: " + getConfig().getInt("game.target-moves", 10) + " lượt");
        if (admin) { put(inv, 29, Material.HOPPER, "<gold>Nhập từ điển", "<gray>Tải các nguồn đã cấu hình"); put(inv, 31, Material.WRITABLE_BOOK, "<aqua>Báo cáo từ", "<gray>Xem các từ người chơi báo"); put(inv, 33, Material.COMPARATOR, "<yellow>Tải lại cấu hình", "<gray>Đọc lại config.yml"); put(inv, 37, Material.DIAMOND, "<gold>Chỉnh phần thưởng", "<gray>Điều chỉnh cấu hình quà thắng"); put(inv, 41, Material.PLAYER_HEAD, "<aqua>Thống kê người chơi", "<gray>Xem thắng và chuỗi thắng"); }
        p.openInventory(inv);
    }
    private void put(Inventory inv, int slot, Material material, String name, String... lore) { ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(message(name)); meta.lore(Arrays.stream(lore).map(this::message).toList()); item.setItemMeta(meta); inv.setItem(slot, item); }
    @EventHandler public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !(e.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        e.setCancelled(true); if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (holder.mode == MenuMode.REWARDS) { if (!hasRewardPermission(p)) return; String material = holder.targets.get(e.getRawSlot()); if (material != null) adjustReward(p, material, e.isRightClick() ? -1 : 1, e.isShiftClick()); return; }
        if (holder.mode == MenuMode.WINS) { if (!hasStatsPermission(p)) return; String target = holder.targets.get(e.getRawSlot()); if (target != null) showPlayerStats(p, target); return; }
        if (holder.mode == MenuMode.DUEL_TARGET) {
            String targetId = holder.targets.get(e.getRawSlot());
            if (targetId == null) return;
            Player target = Bukkit.getPlayer(UUID.fromString(targetId));
            if (target == null) { p.sendMessage(message("<yellow>Người chơi vừa offline.")); openDuelMenu(p); return; }
            openDuelWagerMenu(p, target); return;
        }
        if (holder.mode == MenuMode.DUEL_WAGER) {
            String wager = holder.targets.get(e.getRawSlot());
            Player target = holder.duelTarget == null ? null : Bukkit.getPlayer(UUID.fromString(holder.duelTarget));
            if (wager == null) return;
            if (target == null) { p.sendMessage(message("<yellow>Người chơi vừa offline.")); openDuelMenu(p); return; }
            p.closeInventory();
            String[] selectedStake = wager.split(" ", 2);
            createDuelInvite(p, new String[]{"duel", target.getName(), Integer.toString(getConfig().getInt("duel.default-connect-seconds", 30)), selectedStake[0], selectedStake[1]});
            return;
        }
        switch (e.getRawSlot()) { case 10 -> p.performCommand("wordchain start"); case 12 -> p.performCommand("wordchain stop"); case 14 -> p.performCommand("wordchain reset"); case 16 -> p.performCommand("wordchain top"); case 20 -> p.performCommand("wordchain accept"); case 24 -> openDuelMenu(p); case 29 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain import"); } case 31 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain reports"); } case 33 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain reload"); } case 37 -> { if (hasRewardPermission(p)) openRewardsMenu(p); } case 41 -> { if (hasStatsPermission(p)) openWinsMenu(p); } default -> {} }
    }
    @EventHandler public void onMenuDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true); }

    private void openDuelMenu(Player challenger) {
        if (!challenger.hasPermission("wordchain.duel")) { deny(challenger); return; }
        MenuHolder holder = new MenuHolder(MenuMode.DUEL_TARGET);
        Inventory inventory = Bukkit.createInventory(holder, 54, message("<dark_aqua>Chọn người chơi đấu duel</dark_aqua>"));
        holder.inventory = inventory;
        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(challenger))
                .filter(p -> !duels.containsKey(p.getUniqueId())).sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).toList()) {
            if (slot >= inventory.getSize()) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(target);
                meta.displayName(message("<yellow>" + escape(target.getName())));
                meta.lore(List.of(message("<gray>Nhấn để gửi lời mời duel")));
                head.setItemMeta(meta);
            }
            inventory.setItem(slot, head);
            holder.targets.put(slot, target.getUniqueId().toString());
            slot++;
        }
        if (slot == 0) { challenger.sendMessage(message("<yellow>Không có người chơi phù hợp để thách đấu.")); return; }
        challenger.openInventory(inventory);
    }

    private void openDuelWagerMenu(Player challenger, Player target) {
        MenuHolder holder = new MenuHolder(MenuMode.DUEL_WAGER);
        holder.duelTarget = target.getUniqueId().toString();
        Inventory inventory = Bukkit.createInventory(holder, 27, message("<dark_aqua>Choose duel stake</dark_aqua>"));
        holder.inventory = inventory;
        holder.targets.put(11, "auto 0");
        put(inventory, 11, Material.PAPER, "<green>Friendly duel", "<gray>No stake is collected.");
        boolean economyAvailable = rewardService.isEconomyAvailable();
        boolean hasStakeOptions = false;
        if (economyAvailable) {
            double configuredMax = getConfig().getDouble("duel.max-wager", 1_000_000);
            double max = Double.isFinite(configuredMax) ? Math.min(Double.MAX_VALUE / 2, Math.max(0, configuredMax)) : 1_000_000;
            for (int i = 0; i < 3; i++) {
                int amount = 10 * (i + 1);
                if (amount > max) continue;
                int slot = 13 + i * 2;
                holder.targets.put(slot, "vault " + amount);
                put(inventory, slot, Material.GOLD_INGOT, "<gold>Stake " + amount + " Vault", "<gray>Each player stakes " + amount + ".");
                hasStakeOptions = true;
            }
        } else {
            Material fallback = Material.matchMaterial(getConfig().getString("duel.fallback-item", "DIAMOND"));
            int max = Math.max(0, Math.min(Integer.MAX_VALUE / 2, getConfig().getInt("duel.max-item-stake", 64)));
            if (fallback != null && fallback.isItem() && !fallback.isAir() && max > 0) {
                for (int i = 0; i < 3; i++) {
                    int amount = Math.min(max, 3 * (i + 1));
                    int slot = 13 + i * 2;
                    holder.targets.put(slot, fallback.name() + " " + amount);
                    put(inventory, slot, fallback, "<gold>Stake " + amount + " " + fallback.name(), "<gray>Each player stakes " + amount + " items.");
                    hasStakeOptions = true;
                }
            }
        }
        if (!hasStakeOptions) put(inventory, 15, Material.BARRIER, "<red>No stakes configured", "<gray>Check duel limits and fallback-item.");
        challenger.openInventory(inventory);
    }

    private enum MenuMode { MAIN, REWARDS, WINS, DUEL_TARGET, DUEL_WAGER }
    private final class MenuHolder implements InventoryHolder {
        private final MenuMode mode;
        private final Map<Integer,String> targets = new HashMap<>();
        private String duelTarget;
        private Inventory inventory;
        private MenuHolder(MenuMode mode) { this.mode = mode; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private void openRewardsMenu(Player p) {
        if (!hasRewardPermission(p)) { deny(p); return; }
        MenuHolder holder = new MenuHolder(MenuMode.REWARDS); Inventory inv = Bukkit.createInventory(holder, 27, message("<dark_green>Chỉnh phần thưởng</dark_green>")); holder.inventory=inv;
        List<String> rewards = getConfig().getStringList("rewards.items"); int slot=10;
        for (String spec : rewards) { String[] pair=spec.split(":",2); if(pair.length!=2 || slot>16) continue; Material material=Material.matchMaterial(pair[0]); if(material==null || !material.isItem()) continue; holder.targets.put(slot,pair[0].toUpperCase(Locale.ROOT)); put(inv,slot,material,"<yellow>"+pair[0]+" x"+pair[1],"<gray>Chuột trái: +1; chuột phải: -1","<gray>Shift + chuột phải: xóa"); slot+=2; }
        put(inv,22,Material.ANVIL,"<aqua>Thêm phần thưởng","<gray>Dùng /wordchain reward add <material> <amount>"); p.openInventory(inv);
    }
    private void adjustReward(Player p, String material, int delta, boolean shift) {
        if (!hasRewardPermission(p)) return; List<String> rewards=new ArrayList<>(getConfig().getStringList("rewards.items"));
        for(int i=0;i<rewards.size();i++){String[] pair=rewards.get(i).split(":",2);if(pair.length!=2||!pair[0].equalsIgnoreCase(material))continue;int amount;try{amount=Integer.parseInt(pair[1]);}catch(NumberFormatException ex){return;}if(shift&&delta<0)rewards.remove(i);else{amount=Math.max(1,Math.min(64,amount+delta));rewards.set(i,material+":"+amount);}getConfig().set("rewards.items",rewards);persistConfigAsync();Bukkit.getScheduler().runTask(this,()->openRewardsMenu(p));return;}
    }
    private void editReward(CommandSender sender, String[] args) {
        if(args.length==4 && args[1].equalsIgnoreCase("add")){Material m=Material.matchMaterial(args[2]);int amount;try{amount=Integer.parseInt(args[3]);}catch(NumberFormatException e){sender.sendMessage(message("<red>Amount must be a number."));return;}if(m==null||!m.isItem()||amount<1||amount>64){sender.sendMessage(message("<red>Invalid item or amount (1-64)."));return;}List<String> list=new ArrayList<>(getConfig().getStringList("rewards.items"));list.removeIf(x->x.split(":",2)[0].equalsIgnoreCase(m.name()));list.add(m.name()+":"+amount);getConfig().set("rewards.items",list);persistConfigAsync();sender.sendMessage(message("<green>Reward saved."));return;}
        if(args.length==3&&args[1].equalsIgnoreCase("remove")){List<String> list=new ArrayList<>(getConfig().getStringList("rewards.items"));boolean removed=list.removeIf(x->x.split(":",2)[0].equalsIgnoreCase(args[2]));getConfig().set("rewards.items",list);persistConfigAsync();sender.sendMessage(message(removed?"<green>Reward removed.":"<red>Reward not found."));return;}
        sender.sendMessage(message("<yellow>Dùng: /wordchain reward add <material> <amount> | remove <material>"));
    }
    private boolean hasRewardPermission(Player p){return p.hasPermission("wordchain.admin")||p.hasPermission("wordchain.rewards");}
    private boolean hasStatsPermission(Player p){return p.hasPermission("wordchain.admin")||p.hasPermission("wordchain.stats");}
    private void persistConfigAsync(){String snapshot=getConfig().saveToString();long revision=++configRevision;java.nio.file.Path path=getDataFolder().toPath().resolve("config.yml");Bukkit.getScheduler().runTaskAsynchronously(this,()->{synchronized(configWriteLock){if(revision<writtenConfigRevision)return;try{java.nio.file.Files.writeString(path,snapshot,StandardCharsets.UTF_8);writtenConfigRevision=revision;}catch(IOException e){getLogger().warning("Could not save config: "+e.getMessage());}}});}
    private void flushConfigNow() {
        synchronized (configWriteLock) {
            if (configRevision <= writtenConfigRevision) return;
            try {
                java.nio.file.Files.writeString(getDataFolder().toPath().resolve("config.yml"), getConfig().saveToString(), StandardCharsets.UTF_8);
                writtenConfigRevision = configRevision;
            } catch (IOException e) { getLogger().warning("Could not flush config on shutdown: " + e.getMessage()); }
        }
    }

    private void openWinsMenu(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String[]> rows = new ArrayList<>();
            try (Connection reader = openReadConnection(); Statement statement = reader.createStatement();
                 ResultSet result = statement.executeQuery("SELECT player_name,wins,current_streak,best_streak FROM wins ORDER BY wins DESC LIMIT 18")) {
                while (result.next()) rows.add(new String[]{result.getString(1), "Thắng: " + result.getInt(2) + " | Chuỗi: " + result.getInt(3) + " | Kỷ lục: " + result.getInt(4)});
            } catch (SQLException e) { getLogger().warning("Could not load wins menu: " + e.getMessage()); }
            Bukkit.getScheduler().runTask(this, () -> {
                MenuHolder holder = new MenuHolder(MenuMode.WINS);
                Inventory inventory = Bukkit.createInventory(holder, 27, message("<dark_green>Thống kê người chơi</dark_green>"));
                holder.inventory = inventory;
                int slot = 0;
                for (String[] row : rows) {
                    holder.targets.put(slot, row[0]);
                    put(inventory, slot, Material.PAPER, "<yellow>" + escape(row[0]), "<gray>" + row[1], "<gray>Nhấp để xem chi tiết");
                    slot++;
                }
                p.openInventory(inventory);
            });
        });
    }
    private void reportWord(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can report words."); return; }
        if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain report <tiếng1 tiếng2>")); return; }
        String phrase = normalizePhrase(args[1] + " " + args[2]); if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ nhận báo cáo gồm đúng hai tiếng.")); return; }
        long now = System.currentTimeMillis(), wait = getConfig().getLong("reports.cooldown-seconds", 30) * 1000L;
        Long last = reportCooldown.get(player.getUniqueId()); if (last != null && now - last < wait) { sender.sendMessage(message("<red>Hãy đợi trước khi gửi báo cáo khác.")); return; }
        UUID reporterId = player.getUniqueId(); String reporterName = player.getName();
        reportCooldown.put(player.getUniqueId(), now);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { int result = 0; try { synchronized (this) { ensureWriterConnection(); try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO word_reports(phrase,reporter_uuid,reporter_name,created_at,status) VALUES(?,?,?,?, 'PENDING')")) { ps.setString(1, phrase); ps.setString(2, reporterId.toString()); ps.setString(3, reporterName); ps.setLong(4, now); result = ps.executeUpdate(); } } } catch (SQLException ex) { getLogger().warning(ex.getMessage()); }
            int rows = result; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(rows > 0 ? "<green>Đã gửi báo cáo từ: " + escape(phrase) : "<yellow>Từ này đã được báo cáo trước đó hoặc không thể lưu."))); });
    }
    private void showReports(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> rows = new ArrayList<>();
            try (Connection reader = openReadConnection(); Statement statement = reader.createStatement();
                 ResultSet result = statement.executeQuery("SELECT phrase,reporter_name FROM word_reports WHERE status='PENDING' ORDER BY created_at LIMIT 10")) {
                while (result.next()) rows.add(result.getString(1) + " — " + result.getString(2));
            } catch (SQLException e) { getLogger().warning("Could not load pending reports: " + e.getMessage()); }
            Bukkit.getScheduler().runTask(this, () -> {
                sender.sendMessage(message("<gold><bold>Báo cáo từ đang chờ</bold>"));
                if (rows.isEmpty()) { sender.sendMessage(message("<gray>Không có báo cáo chờ xử lý.")); return; }
                rows.forEach(row -> sender.sendMessage(message("<yellow>• <white>" + escape(row))));
                sender.sendMessage(message("<gray>Dùng /wordchain approve hoặc reject <tiếng1 tiếng2>."));
            });
        });
    }
    private void reviewReport(CommandSender sender, boolean approve, String phrase) {
        if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ xử lý cụm đúng hai tiếng.")); return; }
        int maxLength = getConfig().getInt("game.max-phrase-length", 48);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { boolean changed = false;
        synchronized (this) {
            try {
                ensureWriterConnection();
                if (approve) addWord(phrase, maxLength);
                try (PreparedStatement ps = connection.prepareStatement("UPDATE word_reports SET status=? WHERE phrase=? AND status='PENDING'")) { ps.setString(1, approve ? "APPROVED" : "REJECTED"); ps.setString(2, phrase); changed = ps.executeUpdate() > 0; }
            } catch (SQLException ex) { getLogger().warning(ex.getMessage()); }
        } boolean result = changed; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(!result ? "<red>Không tìm thấy báo cáo đang chờ." : approve ? "<green>Đã duyệt và thêm: " + escape(phrase) : "<yellow>Đã từ chối báo cáo: " + escape(phrase)))); });
    }
    private void removeWord(CommandSender sender, String phrase) {
        if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ xóa cụm đúng hai tiếng.")); return; }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { boolean removed = false; synchronized (this) { try { ensureWriterConnection(); try (PreparedStatement ps = connection.prepareStatement("DELETE FROM words WHERE phrase=?")) { ps.setString(1, phrase); removed = ps.executeUpdate() > 0; } } catch (SQLException ex) { getLogger().warning(ex.getMessage()); } } if (removed) { dictionary.remove(phrase); refreshWordIndex(); } boolean result = removed; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(result ? "<green>Đã xóa: " + escape(phrase) : "<red>Không tìm thấy từ."))); });
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("start", "stop", "reset", "vote", "menu", "top", "wins", "rewards", "reward", "difficulty", "duel", "accept", "join", "leave", "deny", "add", "remove", "report", "reports", "approve", "reject", "import", "reload", "skip").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("duel")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("duel")) return List.of("30", "60", "off").stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 4 && args[0].equalsIgnoreCase("duel")) return List.of("auto", "vault", "DIAMOND", "EMERALD").stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("vote")) return List.of("yes", "no").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void requestPublicReset(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            startFreshPublicGame(sender.getName());
            return;
        }
        if (gameVote != null) {
            player.sendMessage(message("<yellow>Đang có đề xuất " + voteActionName(gameVote.action()) + " từ " + escape(gameVote.requesterName()) + "."));
            return;
        }
        Set<UUID> online = new HashSet<>();
        Bukkit.getOnlinePlayers().forEach(onlinePlayer -> online.add(onlinePlayer.getUniqueId()));
        if (online.size() < 2) {
            player.sendMessage(message("<red>Cần ít nhất hai người chơi online để bỏ phiếu đặt lại."));
            return;
        }
        gameVote = new GameVote(GameVote.Action.RESET, player.getUniqueId(), player.getName(), online);
        int timeout = voteTimeoutSeconds();
        GameVote proposal = gameVote;
        gameVoteTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (gameVote != proposal) return;
            clearGameVote();
            Bukkit.broadcast(message("<yellow>Đề xuất " + voteActionName(proposal.action()) + " của " + escape(proposal.requesterName()) + " đã hết hạn vì chưa đủ phiếu."));
        }, timeout * 20L);
        Bukkit.broadcast(message("<gold>" + escape(player.getName()) + " đề xuất bắt đầu ván mới với từ khóa ngẫu nhiên. Người chơi khác dùng <yellow>/wordchain vote yes</yellow> hoặc <yellow>/wordchain vote no</yellow>. Cần " + proposal.requiredVotes() + " phiếu (ít nhất 1/3 người chơi đang online) trong " + timeout + " giây."));
    }

    private void requestPublicStop(CommandSender sender) {
        if (!game.isActive()) { sender.sendMessage(message("<yellow>Hiện không có ván công khai nào đang diễn ra.")); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(message("<red>Chỉ người chơi mới có thể mở phiếu dừng; admin có thể dừng trực tiếp.")); return; }
        if (gameVote != null) { player.sendMessage(message("<yellow>Đang có đề xuất " + voteActionName(gameVote.action()) + " từ " + escape(gameVote.requesterName()) + ".")); return; }
        Set<UUID> online = new HashSet<>();
        Bukkit.getOnlinePlayers().forEach(onlinePlayer -> online.add(onlinePlayer.getUniqueId()));
        if (online.size() < 2) { player.sendMessage(message("<red>Cần ít nhất hai người chơi online để bỏ phiếu dừng ván.")); return; }
        gameVote = new GameVote(GameVote.Action.STOP, player.getUniqueId(), player.getName(), online);
        int timeout = voteTimeoutSeconds();
        GameVote proposal = gameVote;
        gameVoteTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (gameVote != proposal) return;
            clearGameVote();
            Bukkit.broadcast(message("<yellow>Đề xuất dừng ván của " + escape(proposal.requesterName()) + " đã hết hạn vì chưa đủ phiếu."));
        }, timeout * 20L);
        Bukkit.broadcast(message("<gold>" + escape(player.getName()) + " đề xuất dừng ván Nối Từ. Người chơi khác dùng <yellow>/wordchain vote yes</yellow> hoặc <yellow>/wordchain vote no</yellow>. Cần " + proposal.requiredVotes() + " phiếu đồng ý (ít nhất 1/3 người chơi online) trong " + timeout + " giây. Admin có thể dùng /wordchain stop để dừng ngay."));
    }

    private void castGameVote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(message("<red>Chỉ người chơi mới bỏ phiếu được.")); return; }
        if (args.length != 2 || !(args[1].equalsIgnoreCase("yes") || args[1].equalsIgnoreCase("no"))) { sender.sendMessage(message("<yellow>Dùng: /wordchain vote <yes|no>")); return; }
        GameVote proposal = gameVote;
        if (proposal == null) { player.sendMessage(message("<yellow>Hiện không có đề xuất nào cần bỏ phiếu.")); return; }
        GameVote.Result result = proposal.cast(player.getUniqueId(), args[1].equalsIgnoreCase("yes"));
        String action = voteActionName(proposal.action());
        switch (result) {
            case INELIGIBLE -> player.sendMessage(message("<red>Người đề xuất không thể tự bỏ phiếu; chỉ người chơi đang online lúc mở phiếu được bỏ phiếu."));
            case DUPLICATE -> player.sendMessage(message("<yellow>Bạn đã bỏ phiếu rồi."));
            case RECORDED -> Bukkit.broadcast(message("<gray>Phiếu " + action + ": <green>" + proposal.yesVotes() + " đồng ý</green>, <red>" + proposal.noVotes() + " từ chối</red>; cần " + proposal.requiredVotes() + "."));
            case ACCEPTED -> {
                clearGameVote();
                if (proposal.action() == GameVote.Action.RESET) {
                    Bukkit.broadcast(message("<green>Đề xuất bắt đầu ván mới đã được thông qua."));
                    startFreshPublicGame(proposal.requesterName());
                } else stopPublicGame(proposal.requesterName());
            }
            case REJECTED -> {
                clearGameVote();
                Bukkit.broadcast(message("<yellow>Đề xuất " + action + " đã bị từ chối."));
            }
        }
    }

    private int voteTimeoutSeconds() { return Math.max(5, Math.min(120, getConfig().getInt("game.reset-vote-seconds", 30))); }
    private String voteActionName(GameVote.Action action) { return action == GameVote.Action.RESET ? "đặt lại ván" : "dừng ván"; }

    private void stopPublicGame(String requesterName) {
        cancelGameVote("<yellow>Đề xuất đã bị hủy vì admin dừng ván.");
        cancelNextMatch();
        cancelPublicIdleTimeout();
        game.stop();
        publicParticipants.clear();
        Bukkit.broadcast(message("<yellow>Trò Nối Từ đã dừng bởi " + escape(requesterName) + "."));
    }

    private void startFreshPublicGame(String requesterName) {
        String start = randomStartingWord();
        if (start == null) { Bukkit.broadcast(message("<red>Không thể đặt lại ván: từ điển chưa có từ mở màn hợp lệ.")); return; }
        cancelNextMatch();
        rememberStartingWord(start);
        game.start(start);
        Bukkit.broadcast(message("<green>Ván mới được " + escape(requesterName) + " yêu cầu bắt đầu!"));
        announcePublicJoin(start);
    }

    private void clearGameVote() {
        if (gameVoteTask != null) gameVoteTask.cancel();
        gameVoteTask = null;
        gameVote = null;
    }

    private void cancelGameVote(String reason) {
        if (gameVote == null) return;
        clearGameVote();
        Bukkit.broadcast(message(reason));
    }

    private final class WordGame {
        private String lastWord = "", previousWord = ""; private int moves; private boolean active;
        private UUID lastPlayer; private long lastMoveAt;
        private final Map<String,Integer> used = new HashMap<>(); private int hardRun;
        synchronized void start(String phrase) { used.clear(); lastWord = phrase; previousWord = ""; used.put(phrase,1); hardRun = initialHardRun(phrase); moves = 0; lastPlayer = null; lastMoveAt = System.currentTimeMillis(); active = true; if (displayService != null) displayService.start(phrase, moves); armPublicIdleTimeout(); }
        synchronized void reset() { used.clear(); lastWord = ""; moves = 0; active = false; lastPlayer = null; cancelPublicIdleTimeout(); }
        synchronized void stop() { active = false; lastPlayer = null; cancelPublicIdleTimeout(); if (displayService != null) displayService.stop(); }
        synchronized boolean isActive() { return active; }
        synchronized int getMoves() { return moves; }
        synchronized String getLastWord() { return lastWord; }
        synchronized UUID getLastPlayer() { return lastPlayer; }
        synchronized void clearLastPlayer(UUID player) { if (Objects.equals(lastPlayer, player)) lastPlayer = null; }
        synchronized boolean maySubmit(UUID player, long now, long minimumDelay, boolean antiSolo) { return active && (!antiSolo || lastPlayer == null || !lastPlayer.equals(player)) && now - lastMoveAt >= Math.max(0, minimumDelay); }
        synchronized void recordPlayer(UUID player, long now) { lastPlayer = player; lastMoveAt = now; }
        synchronized String requiredSyllable() { String[] parts = lastWord.split(" "); return parts[parts.length - 1]; }
        synchronized boolean hasLegalContinuation() { return active && hasContinuation(lastWord, used, hardRun, previousWord); }
        synchronized boolean tryMove(String input, java.util.function.Predicate<String> dictionary) {
            if (!active) return false; String phrase = input; String[] parts = phrase.split(" ");
            if (parts.length != 2 || !parts[0].equals(requiredSyllable()) || !dictionary.test(phrase)) return false;
            if (!GameplayRules.allowsNextPhrase(phrase, previousWord)) return false;
            if (!mayUseWord(used, phrase, getConfig().getInt("anti-abuse.word-use-limit", 3))) return false;
            int difficulty=getConfig().getInt("difficulty.words."+phrase,1), threshold=getConfig().getInt("difficulty.hard-threshold",4), maxHard=Math.max(1,getConfig().getInt("anti-abuse.max-hard-words-in-row",2));
            if(difficulty>=threshold&&hardRun>=maxHard)return false;
            if(difficulty>=threshold)hardRun++;else hardRun=0;
            used.merge(phrase,1,Integer::sum); previousWord = lastWord; lastWord = phrase; moves++; return true;
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();publicParticipants.remove(id);game.clearLastPlayer(id);GameVote vote=gameVote;if(vote!=null&&vote.requester().equals(id))cancelGameVote("<yellow>Đề xuất " + voteActionName(vote.action()) + " bị hủy vì người đề xuất đã rời máy chủ.");DuelMatch match=duels.get(id);if(match!=null)finishDuel(match,otherPlayer(match,id),"Đối thủ rời máy chủ.");duelInvites.remove(id);wrongMoveNotice.remove(id);reportCooldown.remove(id);}
    @EventHandler public void onJoin(PlayerJoinEvent event) { if (displayService != null) displayService.showTo(event.getPlayer()); }
}
