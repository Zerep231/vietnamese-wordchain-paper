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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.io.*;
import java.net.URL;
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
    private final Map<UUID, Long> reportCooldown = new HashMap<>();
    private final Map<UUID, Long> wrongMoveNotice = new HashMap<>();
    private final Map<UUID,DuelInvite> duelInvites = new ConcurrentHashMap<>();
    private final Map<UUID,DuelMatch> duels = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> recentStartingWords = new LinkedHashSet<>();
    private final AtomicBoolean importRunning = new AtomicBoolean();
    private final Object configWriteLock = new Object();
    private long configRevision;
    private volatile long writtenConfigRevision;
    private final Set<String> dictionary = ConcurrentHashMap.newKeySet();
    private volatile List<String> dictionaryIndex = List.of();
    private volatile Map<String,List<String>> dictionaryByFirstSyllable = Map.of();
    private volatile boolean dictionaryReady;
    private WordChainApi api;
    private org.bukkit.scheduler.BukkitTask nextMatchTask;
    private org.bukkit.scheduler.BukkitTask resetVoteTask;
    private ResetVote resetVote;
    private Connection connection;
    private WordGame game;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateConfigDefaults();
        try {
            connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + getDataFolder().toPath().resolve("wordchain.db"), new Properties());
            if (connection == null) throw new SQLException("SQLite JDBC driver rejected the database URL");
            try (Statement s = connection.createStatement()) {
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
        api = new WordChainApi() {
            @Override public boolean isGameActive() { return game.isActive(); }
            @Override public String getCurrentKeyword() { return game.getLastWord(); }
            @Override public boolean isKnownPhrase(String phrase) { return dictionary.contains(normalizePhrase(phrase)); }
            @Override public java.util.concurrent.CompletionStage<Integer> getWins(UUID id) { return lookupWins(id); }
        };
        getServer().getServicesManager().register(WordChainApi.class, api, this, ServicePriority.Normal);
        Objects.requireNonNull(getCommand("wordchain")).setExecutor(this);
        getCommand("wordchain").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
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
        if (changed) saveConfig();
    }

    @Override public void onDisable() {
        if (api != null) getServer().getServicesManager().unregister(WordChainApi.class, api);
        cancelNextMatch();
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
            if (isTwoSyllablePhrase(input)) Bukkit.getScheduler().runTask(this, () -> processMove(id, name, input));
            else Bukkit.getScheduler().runTask(this, () -> notifyDuelError(id, "<red>Trong duel, hãy gửi đúng một cụm gồm hai tiếng."));
            return;
        }
        // Ignore ordinary conversation and long messages before scheduling any main-thread work.
        if (!game.isActive() || !isTwoSyllablePhrase(input)) return;
        event.setCancelled(true); // Accepted moves are announced once by processMove; invalid moves get private feedback.
        Bukkit.getScheduler().runTask(this, () -> processMove(id, name, input));
    }

    private void processMove(UUID id, String playerName, String raw) {
        DuelMatch duel=duels.get(id); if(duel!=null){ processDuelMove(duel,id,playerName,raw); return; }
        if (!game.isActive()) return;
        if (!game.tryMove(raw, this::isValidWord)) { if (!game.hasLegalContinuation()) resetPublicRound(); else notifyInvalidMove(id, raw); return; }
        String clean = normalizePhrase(raw);
        Bukkit.broadcast(message("<green>" + escape(playerName) + " nối thành công: <yellow>" + escape(clean) + "</yellow>. Tiếng tiếp theo: <gold>" + escape(game.requiredSyllable()) + "</gold>.</green>"));
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id, playerName, clean));
        if (game.getMoves() >= getConfig().getInt("game.target-moves", 10)) {
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
        if (next == null) { game.stop(); Bukkit.broadcast(message("<yellow>Ván đã được đặt lại vì không còn nước nối hợp lệ; chưa có từ mở màn phù hợp.")); }
        else { rememberStartingWord(next); game.start(next); Bukkit.broadcast(message("<yellow>Không còn nước nối hợp lệ. Ván mới bắt đầu với từ khóa ngẫu nhiên: <gold>" + escape(next) + "</gold>.")); }
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
                cancelResetVote("<yellow>Đề xuất đặt lại đã bị hủy vì ván mới được bắt đầu.");
                rememberStartingWord(start);
                cancelNextMatch(); game.start(start); Bukkit.broadcast(message("<green>Trò Nối Từ bắt đầu! Từ đầu tiên: <yellow>" + escape(start) + "</yellow>."));
            }
            case "stop" -> { if (!canControl(sender)) return deny(sender); cancelResetVote("<yellow>Đề xuất đặt lại đã bị hủy vì trò chơi đã dừng."); cancelNextMatch(); game.stop(); Bukkit.broadcast(message("<yellow>Trò Nối Từ đã dừng.")); }
            case "reset" -> { if (!canControl(sender)) return deny(sender); if (!dictionaryReady || importRunning.get()) { sender.sendMessage(message("<yellow>Đang nạp từ điển, vui lòng thử lại sau.")); return true; } requestPublicReset(sender); }
            case "vote" -> { if (!sender.hasPermission("wordchain.vote")) return deny(sender); castResetVote(sender, args); }
            case "menu" -> { if (!sender.hasPermission("wordchain.menu")) return deny(sender); if (sender instanceof Player p) openMenu(p); else sender.sendMessage("Lệnh này chỉ dành cho người chơi."); }
            case "top" -> { if (!sender.hasPermission("wordchain.top")) return deny(sender); showTop(sender); }
            case "wins" -> { if (!(sender.hasPermission("wordchain.admin") || sender.hasPermission("wordchain.stats"))) return deny(sender); if (args.length != 2) { sender.sendMessage(message("<yellow>Dùng: /wordchain wins <player>")); return true; } showPlayerStats(sender, args[1]); }
            case "rewards" -> { if (!(sender instanceof Player p) || !(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); openRewardsMenu(p); }
            case "reward" -> { if (!(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); editReward(sender, args); }
            case "difficulty" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); setDifficulty(sender, args); }
            case "duel" -> { if (!sender.hasPermission("wordchain.duel")) return deny(sender); createDuelInvite(sender,args); }
            case "accept" -> { if (!sender.hasPermission("wordchain.duel.respond")) return deny(sender); acceptDuel(sender,args); }
            case "deny" -> { if (!sender.hasPermission("wordchain.duel.respond")) return deny(sender); denyDuel(sender,args); }
            case "add" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain add <tiếng1 tiếng2>")); return true; } String phrase = normalizePhrase(args[1] + " " + args[2]); addWordAsync(sender, phrase); }
            case "report" -> { if (!sender.hasPermission("wordchain.report")) return deny(sender); reportWord(sender, args); }
            case "reports" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); showReports(sender); }
            case "approve", "reject" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain " + sub + " <tiếng1 tiếng2>")); return true; } reviewReport(sender, sub.equals("approve"), normalizePhrase(args[1] + " " + args[2])); }
            case "remove" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain remove <tiếng1 tiếng2>")); return true; } removeWord(sender, normalizePhrase(args[1] + " " + args[2])); }
            case "import" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); scheduleImport(sender, true); }
            case "reload" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); reloadConfig(); sender.sendMessage(message("<green>Đã tải lại cấu hình.")); }
            default -> sender.sendMessage(message("<yellow>Dùng: /wordchain <start|stop|reset|menu|top|wins|rewards|reward|difficulty|import|reload>"));
        }
        return true;
    }

    private boolean canControl(CommandSender s) { return s.hasPermission("wordchain.admin") || (getConfig().getBoolean("game.members-can-control", true) && s.hasPermission("wordchain.play")); }
    private boolean deny(CommandSender s) { s.sendMessage(message("<red>Bạn không có quyền thực hiện thao tác này.")); return true; }
    private Component message(String s) { return MM.deserialize(s); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String normalizePhrase(String input) { return Normalizer.normalize(input.trim().toLowerCase(Locale.forLanguageTag("vi-VN")).replaceAll("\\s+", " "), Normalizer.Form.NFC); }
    private static boolean isTwoSyllablePhrase(String input) { if(input==null||input.length()>48)return false;String n = normalizePhrase(input); return n.length() <= 48 && n.matches("[\\p{L}\\p{M}]+ [\\p{L}\\p{M}]+"); }

    private boolean isValidWord(String phrase) {
        String normalized = normalizePhrase(phrase);
        if (normalized.length() > getConfig().getInt("game.max-phrase-length", 48) || !isTwoSyllablePhrase(normalized)) return false;
        return dictionary.contains(normalized);
    }

    private void loadDictionaryCache() {
        synchronized (this) {
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT phrase FROM words")) { while (rs.next()) dictionary.add(rs.getString(1)); refreshWordIndex(); dictionaryReady = true; }
            catch (SQLException e) { getLogger().severe("Could not load word index: " + e.getMessage()); }
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
    private boolean hasContinuation(String phrase, Map<String,Integer> uses, int hardRun) {
        int split = phrase.indexOf(' '); if (split < 0) return false;
        List<String> candidates = dictionaryByFirstSyllable.get(phrase.substring(split + 1)); if (candidates == null) return false;
        int limit = Math.max(1, Math.min(5, getConfig().getInt("anti-abuse.word-use-limit", 3)));
        int threshold = getConfig().getInt("difficulty.hard-threshold", 4);
        int maxHard = Math.max(1, getConfig().getInt("anti-abuse.max-hard-words-in-row", 2));
        for (String candidate : candidates) {
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
        while (recentStartingWords.size() > limit) recentStartingWords.remove(recentStartingWords.iterator().next());
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
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
                var conn = new URL(url).openConnection(); conn.setConnectTimeout(timeout * 1000); conn.setReadTimeout(Math.max(timeout, format.equals("tu-dien-archive") ? 60 : timeout) * 1000); conn.setRequestProperty("User-Agent", "WordChain-Paper/1.3 (dictionary importer)");
                int sourceAdded;
                try (InputStream raw = conn.getInputStream()) {
                    if (format.equals("tu-dien-archive")) sourceAdded = importTuDienArchive(raw, maxLength);
                    else if (format.equals("json-array")) sourceAdded = importJsonPhraseArray(raw, maxLength);
                    else {
                        InputStream stream = url.endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw;
                        List<String> batch = new ArrayList<>(512); long charsRead = 0; int lineCount = 0;
                        try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024)) {
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
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM dictionary_sources WHERE source=?")) { ps.setString(1, source); try (ResultSet rs = ps.executeQuery()) { return rs.next(); } }
        catch (SQLException e) { getLogger().warning("Could not check dictionary source cache: " + e.getMessage()); return false; }
    }
    private synchronized void markSourceImported(String source, int added) throws SQLException {
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
        Player p = Bukkit.getPlayer(id); if (p == null) return;
        double rate = Math.max(0, getConfig().getDouble("streak-rewards.bonus-per-level", 0.35));
        double cap = Math.max(1.0, getConfig().getDouble("streak-rewards.max-multiplier", 3.0));
        double multiplier = Math.min(cap, 1.0 + rate * (Math.log(Math.max(1, streak)) / Math.log(2)));
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
        } int currentStreak = streak; Bukkit.getScheduler().runTask(this, () -> {
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
            game.start(start); Bukkit.broadcast(message("<green>Ván mới! Từ khóa: <yellow>" + escape(start) + "</yellow>."));
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

    private void createDuelInvite(CommandSender sender,String[] args){
        if(!(sender instanceof Player challenger)){sender.sendMessage("Only players can challenge a duel.");return;}if(args.length<2||args.length>3){sender.sendMessage(message("<yellow>/wordchain duel <player> [seconds|off]"));return;}
        Player target=Bukkit.getPlayerExact(args[1]);if(target==null||target.equals(challenger)||duels.containsKey(target.getUniqueId())||duels.containsKey(challenger.getUniqueId())){sender.sendMessage(message("<red>Người chơi không hợp lệ hoặc đang đấu."));return;}
        int seconds=getConfig().getInt("duel.default-connect-seconds",30);if(args.length==3){if(args[2].equalsIgnoreCase("off"))seconds=0;else try{seconds=Integer.parseInt(args[2]);}catch(NumberFormatException e){sender.sendMessage(message("<red>Thời gian phải là số giây hoặc off."));return;}}
        if(seconds!=0&&(seconds<5||seconds>300)){sender.sendMessage(message("<red>Thời gian kết nối hợp lệ: off hoặc 5-300 giây."));return;}
        DuelInvite invite=new DuelInvite(challenger.getUniqueId(),challenger.getName(),target.getUniqueId(),seconds);duelInvites.put(target.getUniqueId(),invite);
        target.sendMessage(message("<gold>"+escape(challenger.getName())+" thách đấu Nối Từ. Dùng <yellow>/wordchain accept</yellow> hoặc <yellow>/wordchain deny</yellow>. Thời gian nối: "+(seconds==0?"không giới hạn":seconds+" giây")+"."));challenger.sendMessage(message("<green>Đã gửi lời mời đấu tới "+escape(target.getName())+"."));
        Bukkit.getScheduler().runTaskLater(this,()->duelInvites.remove(target.getUniqueId(),invite),Math.max(20,getConfig().getLong("duel.invite-expiry-seconds",60))*20L);
    }
    private void acceptDuel(CommandSender sender,String[] args){if(!(sender instanceof Player receiver)){sender.sendMessage("Only players can accept duels.");return;}DuelInvite invite=duelInvites.remove(receiver.getUniqueId());if(invite==null){sender.sendMessage(message("<red>Không có lời mời đấu đang chờ."));return;}Player challenger=Bukkit.getPlayer(invite.challenger);if(challenger==null||!dictionaryReady){sender.sendMessage(message("<red>Người thách đấu đã rời mạng hoặc từ điển chưa sẵn sàng."));return;}if(duels.containsKey(challenger.getUniqueId())||duels.containsKey(receiver.getUniqueId()))return;
        String start=randomStartingWord();if(start==null){sender.sendMessage(message("<red>Từ điển chưa có từ mở màn khả dụng."));return;}rememberStartingWord(start);DuelMatch match=new DuelMatch(challenger.getUniqueId(),challenger.getName(),receiver.getUniqueId(),receiver.getName(),invite.connectSeconds,start);duels.put(challenger.getUniqueId(),match);duels.put(receiver.getUniqueId(),match);
        long maxTicks=Math.max(60,Math.min(3600,getConfig().getLong("duel.max-match-seconds",3600)))*20L;match.maxTask=Bukkit.getScheduler().runTaskLater(this,()->finishDuel(match,null,"Trận đấu tự dừng sau một giờ."),maxTicks).getTaskId();armDuelTurn(match);sendDuelMessage(match,"<gold>Đấu bắt đầu! <yellow>"+escape(challenger.getName())+"</yellow> đấu với <yellow>"+escape(receiver.getName())+"</yellow>. Từ khóa: <aqua>"+escape(start)+"</aqua>. Lượt đầu: "+escape(challenger.getName())+".");
    }
    private void denyDuel(CommandSender sender,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("Only players can deny duels.");return;}DuelInvite invite=duelInvites.remove(p.getUniqueId());if(invite==null){sender.sendMessage(message("<red>Không có lời mời đang chờ."));return;}Player challenger=Bukkit.getPlayer(invite.challenger);if(challenger!=null)challenger.sendMessage(message("<yellow>"+escape(p.getName())+" đã từ chối lời mời."));sender.sendMessage(message("<gray>Đã từ chối lời mời."));}
    private void armDuelTurn(DuelMatch match){if(match.turnSeconds<=0)return;if(match.turnTask!=0)Bukkit.getScheduler().cancelTask(match.turnTask);match.turnTask=Bukkit.getScheduler().runTaskLater(this,()->finishDuel(match,otherPlayer(match,match.turn),"Hết thời gian nối từ."),match.turnSeconds*20L).getTaskId();}
    private UUID otherPlayer(DuelMatch m,UUID id){return m.first.equals(id)?m.second:m.first;}
    private void processDuelMove(DuelMatch m,UUID id,String name,String raw){
        if(!m.active)return;if(!m.turn.equals(id)){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<yellow>Chưa đến lượt bạn nối từ."));return;}
        if(!hasContinuation(m.last,m.uses,m.hardRun)){resetDuelRound(m);return;}
        String phrase=normalizePhrase(raw);String required=m.last.split(" ")[1];if(!phrase.startsWith(required+" ")){notifyDuelError(id,"<red>Nối chưa đúng; tiếng đầu phải là <yellow>"+escape(required)+"</yellow>.");return;}if(!dictionary.contains(phrase)){notifyDuelError(id,"<red>Từ chưa có trong từ điển; hãy báo bằng /wordchain report "+escape(phrase)+".");return;}
        int limit=Math.max(1,Math.min(5,getConfig().getInt("anti-abuse.word-use-limit",3)));if(m.uses.getOrDefault(phrase,0)>=limit){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<red>Cụm từ này đã đạt giới hạn sử dụng."));return;}
        int difficulty=getConfig().getInt("difficulty.words."+phrase,1),threshold=getConfig().getInt("difficulty.hard-threshold",4),maxHard=Math.max(1,getConfig().getInt("anti-abuse.max-hard-words-in-row",2));if(difficulty>=threshold&&m.hardRun>=maxHard){notifyDuelError(id,"<red>Đã đạt giới hạn cụm khó liên tiếp. Hãy chọn một cụm dễ hơn.");return;}m.hardRun=difficulty>=threshold?m.hardRun+1:0;
        m.uses.merge(phrase,1,Integer::sum);m.last=phrase;m.moves++;m.turn=otherPlayer(m,id);armDuelTurn(m);sendDuelMessage(m,"<aqua>[ĐẤU]</aqua> <yellow>"+escape(name)+"</yellow>: <white>"+escape(phrase)+"</white>. Từ cần nối: <gold>"+escape(phrase.split(" ")[1])+"</gold>.");
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id,name,phrase));
        if(m.moves>=getConfig().getInt("duel.target-moves",10))finishDuel(m,id,"Đấu kết thúc!");
        else if(!hasContinuation(m.last,m.uses,m.hardRun))resetDuelRound(m);
    }
    private void resetDuelRound(DuelMatch match) {
        String next = randomStartingWord();
        if (next == null) { finishDuel(match, null, "Không còn từ mở màn có nước nối."); return; }
        rememberStartingWord(next); match.last=next; match.turn=match.first; match.uses.clear(); match.uses.put(next,1); match.hardRun=initialHardRun(next); match.moves=0; armDuelTurn(match);
        sendDuelMessage(match,"<yellow>Không còn nước nối hợp lệ; ván duel được đặt lại với từ khóa ngẫu nhiên <gold>"+escape(next)+"</gold>. Lượt đầu: "+escape(match.firstName)+".");
    }
    private void finishDuel(DuelMatch m,UUID winner,String reason){if(!m.active)return;m.active=false;duels.remove(m.first,m);duels.remove(m.second,m);if(m.turnTask!=0)Bukkit.getScheduler().cancelTask(m.turnTask);if(m.maxTask!=0)Bukkit.getScheduler().cancelTask(m.maxTask);if(winner==null){sendDuelMessage(m,"<yellow>"+reason+" Không có người thắng.");return;}String name=m.first.equals(winner)?m.firstName:m.secondName;sendDuelMessage(m,"<gold>"+reason+" Người thắng: <yellow>"+escape(name)+"</yellow>!");saveWin(winner,name,true,true,otherPlayer(m,winner));}
    private void sendDuelMessage(DuelMatch match,String text){Component component=message(text);Player first=Bukkit.getPlayer(match.first),second=Bukkit.getPlayer(match.second);if(first!=null)first.sendMessage(component);if(second!=null)second.sendMessage(component);}
    private void notifyDuelError(UUID id,String text){long now=System.currentTimeMillis(),last=wrongMoveNotice.getOrDefault(id,0L);if(now-last<1500)return;wrongMoveNotice.put(id,now);Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message(text));}

    private record DuelInvite(UUID challenger,String challengerName,UUID target,int connectSeconds){}
    private final class DuelMatch {final UUID first,second;final String firstName,secondName;final int turnSeconds;final Map<String,Integer> uses=new HashMap<>();String last;UUID turn;int moves,turnTask,maxTask,hardRun;boolean active=true;DuelMatch(UUID a,String an,UUID b,String bn,int seconds,String start){first=a;firstName=an;second=b;secondName=bn;turnSeconds=seconds;last=start;turn=a;uses.put(start,1);hardRun=initialHardRun(start);}}
    private void showTop(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String> rows = new ArrayList<>(); synchronized (this) {
                try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT player_name,wins FROM wins ORDER BY wins DESC, player_name ASC LIMIT 10")) { while (rs.next()) rows.add(rs.getString(1) + ": " + rs.getInt(2)); }
                catch (SQLException e) { getLogger().warning(e.getMessage()); }
            }
            Bukkit.getScheduler().runTask(this, () -> { sender.sendMessage(message("<gold><bold>Bảng xếp hạng Nối Từ</bold>")); if (rows.isEmpty()) sender.sendMessage(message("<gray>Chưa có người chiến thắng.")); for (int i=0;i<rows.size();i++) sender.sendMessage(message("<yellow>" + (i+1) + ". <white>" + escape(rows.get(i)))); });
        });
    }

    private java.util.concurrent.CompletionStage<Integer> lookupWins(UUID id) {
        java.util.concurrent.CompletableFuture<Integer> result = new java.util.concurrent.CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { int wins = 0; synchronized (this) { try (PreparedStatement ps = connection.prepareStatement("SELECT wins FROM wins WHERE uuid=?")) { ps.setString(1, id.toString()); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) wins = rs.getInt(1); } } catch (SQLException e) { result.completeExceptionally(e); return; } } result.complete(wins); });
        return result;
    }
    private void showPlayerStats(CommandSender sender, String name) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { String foundName = null; int wins = 0, streak = 0, best = 0; synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT player_name,wins,current_streak,best_streak FROM wins WHERE player_name=? COLLATE NOCASE")) { ps.setString(1, name); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { foundName=rs.getString(1); wins=rs.getInt(2); streak=rs.getInt(3); best=rs.getInt(4); } } }
            catch (SQLException e) { getLogger().warning("Could not read player stats: " + e.getMessage()); }
        } String n=foundName; int w=wins,s=streak,b=best; Bukkit.getScheduler().runTask(this, () -> { if (n==null) sender.sendMessage(message("<red>Chưa có dữ liệu cho người chơi này.")); else sender.sendMessage(message("<gold>Thống kê <yellow>"+escape(n)+"</yellow>: <white>"+w+" thắng, chuỗi hiện tại "+s+", kỷ lục "+b+".")); }); });
    }

    private void openMenu(Player p) {
        boolean admin = p.hasPermission("wordchain.admin")||p.hasPermission("wordchain.rewards")||p.hasPermission("wordchain.stats"); MenuHolder holder = new MenuHolder(MenuMode.MAIN);
        Inventory inv = Bukkit.createInventory(holder, admin ? 45 : 27, message(getConfig().getString("menu.title", "<dark_green>Nối Từ</dark_green>"))); holder.inventory = inv;
        put(inv, 10, Material.LIME_WOOL, "<green><bold>Bắt đầu ván</bold>", "<gray>Từ mở màn được chọn ngẫu nhiên, tránh lặp gần đây");
        put(inv, 12, Material.BARRIER, "<red>Dừng ván", "<gray>Dừng trò chơi hiện tại");
        put(inv, 14, Material.SUNFLOWER, "<yellow>Đề xuất ván mới", "<gray>Người chơi khác bỏ phiếu; cần ít nhất 1/3 đồng ý");
        put(inv, 16, Material.GOLD_INGOT, "<gold>Bảng xếp hạng", "<gray>Xem 10 người thắng nhiều nhất");
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
        switch (e.getRawSlot()) { case 10 -> p.performCommand("wordchain start"); case 12 -> p.performCommand("wordchain stop"); case 14 -> p.performCommand("wordchain reset"); case 16 -> p.performCommand("wordchain top"); case 29 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain import"); } case 31 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain reports"); } case 33 -> { if (p.hasPermission("wordchain.admin")) p.performCommand("wordchain reload"); } case 37 -> { if (hasRewardPermission(p)) openRewardsMenu(p); } case 41 -> { if (hasStatsPermission(p)) openWinsMenu(p); } default -> {} }
    }
    @EventHandler public void onMenuDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true); }

    private enum MenuMode { MAIN, REWARDS, WINS }
    private final class MenuHolder implements InventoryHolder {
        private final MenuMode mode;
        private final Map<Integer,String> targets = new HashMap<>();
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
    private void openWinsMenu(Player p){
        Bukkit.getScheduler().runTaskAsynchronously(this,()->{List<String[]> rows=new ArrayList<>();synchronized(this){try(Statement s=connection.createStatement();ResultSet rs=s.executeQuery("SELECT player_name,wins,current_streak,best_streak FROM wins ORDER BY wins DESC LIMIT 18")){while(rs.next())rows.add(new String[]{rs.getString(1),"Thắng: "+rs.getInt(2)+" | Chuỗi: "+rs.getInt(3)+" | Kỷ lục: "+rs.getInt(4)});}catch(SQLException e){getLogger().warning(e.getMessage());}}Bukkit.getScheduler().runTask(this,()->{MenuHolder holder=new MenuHolder(MenuMode.WINS);Inventory inv=Bukkit.createInventory(holder,27,message("<dark_green>Thống kê người chơi</dark_green>"));holder.inventory=inv;int slot=0;for(String[] row:rows){holder.targets.put(slot,row[0]);put(inv,slot,Material.PAPER,"<yellow>"+escape(row[0]),"<gray>"+row[1],"<gray>Nhấp để xem chi tiết");slot++;}p.openInventory(inv);});});
    }

    private void reportWord(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can report words."); return; }
        if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain report <tiếng1 tiếng2>")); return; }
        String phrase = normalizePhrase(args[1] + " " + args[2]); if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ nhận báo cáo gồm đúng hai tiếng.")); return; }
        long now = System.currentTimeMillis(), wait = getConfig().getLong("reports.cooldown-seconds", 30) * 1000L;
        Long last = reportCooldown.get(player.getUniqueId()); if (last != null && now - last < wait) { sender.sendMessage(message("<red>Hãy đợi trước khi gửi báo cáo khác.")); return; }
        UUID reporterId = player.getUniqueId(); String reporterName = player.getName();
        reportCooldown.put(player.getUniqueId(), now);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { int result = 0; try { synchronized (this) { try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO word_reports(phrase,reporter_uuid,reporter_name,created_at,status) VALUES(?,?,?,?, 'PENDING')")) { ps.setString(1, phrase); ps.setString(2, reporterId.toString()); ps.setString(3, reporterName); ps.setLong(4, now); result = ps.executeUpdate(); } } } catch (SQLException ex) { getLogger().warning(ex.getMessage()); }
            int rows = result; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(rows > 0 ? "<green>Đã gửi báo cáo từ: " + escape(phrase) : "<yellow>Từ này đã được báo cáo trước đó hoặc không thể lưu."))); });
    }
    private void showReports(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { List<String> rows = new ArrayList<>(); synchronized (this) {
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT phrase,reporter_name FROM word_reports WHERE status='PENDING' ORDER BY created_at LIMIT 10")) { while (rs.next()) rows.add(rs.getString(1) + " — " + rs.getString(2)); }
            catch (SQLException ex) { getLogger().warning(ex.getMessage()); }
        } Bukkit.getScheduler().runTask(this, () -> { sender.sendMessage(message("<gold><bold>Báo cáo từ đang chờ</bold>")); if (rows.isEmpty()) { sender.sendMessage(message("<gray>Không có báo cáo chờ xử lý.")); return; } rows.forEach(row -> sender.sendMessage(message("<yellow>• <white>" + escape(row)))); sender.sendMessage(message("<gray>Dùng /wordchain approve hoặc reject <tiếng1 tiếng2>.")); }); });
    }
    private void reviewReport(CommandSender sender, boolean approve, String phrase) {
        if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ xử lý cụm đúng hai tiếng.")); return; }
        int maxLength = getConfig().getInt("game.max-phrase-length", 48);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { boolean changed = false;
        synchronized (this) {
            try {
                if (approve) addWord(phrase, maxLength);
                try (PreparedStatement ps = connection.prepareStatement("UPDATE word_reports SET status=? WHERE phrase=? AND status='PENDING'")) { ps.setString(1, approve ? "APPROVED" : "REJECTED"); ps.setString(2, phrase); changed = ps.executeUpdate() > 0; }
            } catch (SQLException ex) { getLogger().warning(ex.getMessage()); }
        } boolean result = changed; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(!result ? "<red>Không tìm thấy báo cáo đang chờ." : approve ? "<green>Đã duyệt và thêm: " + escape(phrase) : "<yellow>Đã từ chối báo cáo: " + escape(phrase)))); });
    }
    private void removeWord(CommandSender sender, String phrase) {
        if (!isTwoSyllablePhrase(phrase)) { sender.sendMessage(message("<red>Chỉ xóa cụm đúng hai tiếng.")); return; }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> { boolean removed = false; synchronized (this) { try (PreparedStatement ps = connection.prepareStatement("DELETE FROM words WHERE phrase=?")) { ps.setString(1, phrase); removed = ps.executeUpdate() > 0; } catch (SQLException ex) { getLogger().warning(ex.getMessage()); } } if (removed) { dictionary.remove(phrase); refreshWordIndex(); } boolean result = removed; Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message(result ? "<green>Đã xóa: " + escape(phrase) : "<red>Không tìm thấy từ."))); });
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("start", "stop", "reset", "vote", "menu", "top", "wins", "rewards", "reward", "difficulty", "duel", "accept", "deny", "add", "remove", "report", "reports", "approve", "reject", "import", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("vote")) return List.of("yes", "no").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private void requestPublicReset(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            startFreshPublicGame(sender.getName());
            return;
        }
        if (resetVote != null) {
            player.sendMessage(message("<yellow>Đang có đề xuất đặt lại từ " + escape(resetVote.requesterName()) + "."));
            return;
        }
        Set<UUID> online = new HashSet<>();
        Bukkit.getOnlinePlayers().forEach(onlinePlayer -> online.add(onlinePlayer.getUniqueId()));
        if (online.size() < 2) {
            player.sendMessage(message("<red>Cần ít nhất hai người chơi online để bỏ phiếu đặt lại."));
            return;
        }
        resetVote = new ResetVote(player.getUniqueId(), player.getName(), online);
        int timeout = Math.max(5, Math.min(120, getConfig().getInt("game.reset-vote-seconds", 30)));
        ResetVote proposal = resetVote;
        resetVoteTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (resetVote != proposal) return;
            clearResetVote();
            Bukkit.broadcast(message("<yellow>Đề xuất đặt lại của " + escape(proposal.requesterName()) + " đã hết hạn vì chưa đủ phiếu."));
        }, timeout * 20L);
        Bukkit.broadcast(message("<gold>" + escape(player.getName()) + " đề xuất bắt đầu ván mới với từ khóa ngẫu nhiên. Người chơi khác dùng <yellow>/wordchain vote yes</yellow> hoặc <yellow>/wordchain vote no</yellow>. Cần " + proposal.requiredVotes() + " phiếu (ít nhất 1/3 người chơi đang online) trong " + timeout + " giây."));
    }

    private void castResetVote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(message("<red>Chỉ người chơi mới bỏ phiếu được.")); return; }
        if (args.length != 2 || !(args[1].equalsIgnoreCase("yes") || args[1].equalsIgnoreCase("no"))) { sender.sendMessage(message("<yellow>Dùng: /wordchain vote <yes|no>")); return; }
        ResetVote proposal = resetVote;
        if (proposal == null) { player.sendMessage(message("<yellow>Hiện không có đề xuất đặt lại nào.")); return; }
        ResetVote.Result result = proposal.cast(player.getUniqueId(), args[1].equalsIgnoreCase("yes"));
        switch (result) {
            case INELIGIBLE -> player.sendMessage(message("<red>Người đề xuất không thể tự bỏ phiếu; chỉ người chơi đang online lúc mở phiếu được bỏ phiếu."));
            case DUPLICATE -> player.sendMessage(message("<yellow>Bạn đã bỏ phiếu rồi."));
            case RECORDED -> Bukkit.broadcast(message("<gray>Phiếu đặt lại: <green>" + proposal.yesVotes() + " đồng ý</green>, <red>" + proposal.noVotes() + " từ chối</red>; cần " + proposal.requiredVotes() + "."));
            case ACCEPTED -> {
                clearResetVote();
                Bukkit.broadcast(message("<green>Đề xuất bắt đầu ván mới đã được thông qua."));
                startFreshPublicGame(proposal.requesterName());
            }
            case REJECTED -> {
                clearResetVote();
                Bukkit.broadcast(message("<yellow>Đề xuất bắt đầu ván mới đã bị từ chối."));
            }
        }
    }

    private void startFreshPublicGame(String requesterName) {
        String start = randomStartingWord();
        if (start == null) { Bukkit.broadcast(message("<red>Không thể đặt lại ván: từ điển chưa có từ mở màn hợp lệ.")); return; }
        cancelNextMatch();
        rememberStartingWord(start);
        game.start(start);
        Bukkit.broadcast(message("<green>Ván mới được " + escape(requesterName) + " yêu cầu bắt đầu! Từ khóa ngẫu nhiên: <yellow>" + escape(start) + "</yellow>."));
    }

    private void clearResetVote() {
        if (resetVoteTask != null) resetVoteTask.cancel();
        resetVoteTask = null;
        resetVote = null;
    }

    private void cancelResetVote(String reason) {
        if (resetVote == null) return;
        clearResetVote();
        Bukkit.broadcast(message(reason));
    }

    private final class WordGame {
        private String lastWord = ""; private int moves; private boolean active;
        private final Map<String,Integer> used = new HashMap<>(); private int hardRun;
        synchronized void start(String phrase) { used.clear(); lastWord = phrase; used.put(phrase,1); hardRun = initialHardRun(phrase); moves = 0; active = true; }
        synchronized void reset() { used.clear(); lastWord = ""; moves = 0; active = false; }
        synchronized void stop() { active = false; }
        synchronized boolean isActive() { return active; }
        synchronized int getMoves() { return moves; }
        synchronized String getLastWord() { return lastWord; }
        synchronized String requiredSyllable() { String[] parts = lastWord.split(" "); return parts[parts.length - 1]; }
        synchronized boolean hasLegalContinuation() { return active && hasContinuation(lastWord, used, hardRun); }
        synchronized boolean tryMove(String input, java.util.function.Predicate<String> dictionary) {
            if (!active) return false; String phrase = normalizePhrase(input); String[] parts = phrase.split(" ");
            if (parts.length != 2 || !parts[0].equals(requiredSyllable()) || !dictionary.test(phrase)) return false;
            int maxUses=Math.max(1,Math.min(5,getConfig().getInt("anti-abuse.word-use-limit",3)));
            if(used.getOrDefault(phrase,0)>=maxUses)return false;
            int difficulty=getConfig().getInt("difficulty.words."+phrase,1), threshold=getConfig().getInt("difficulty.hard-threshold",4), maxHard=Math.max(1,getConfig().getInt("anti-abuse.max-hard-words-in-row",2));
            if(difficulty>=threshold&&hardRun>=maxHard)return false;
            if(difficulty>=threshold)hardRun++;else hardRun=0;
            used.merge(phrase,1,Integer::sum); lastWord = phrase; moves++; return true;
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();if(resetVote!=null&&resetVote.requester().equals(id))cancelResetVote("<yellow>Đề xuất ván mới bị hủy vì người đề xuất đã rời máy chủ.");DuelMatch match=duels.get(id);if(match!=null)finishDuel(match,otherPlayer(match,id),"Đối thủ rời máy chủ.");duelInvites.remove(id);wrongMoveNotice.remove(id);reportCooldown.remove(id);}
}
