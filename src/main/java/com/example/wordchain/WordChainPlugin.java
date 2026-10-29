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
    private final Map<UUID, Long> reportCooldown = new HashMap<>();
    private final Map<UUID, Long> wrongMoveNotice = new HashMap<>();
    private final Map<UUID,DuelInvite> duelInvites = new ConcurrentHashMap<>();
    private final Map<UUID,DuelMatch> duels = new ConcurrentHashMap<>();
    private final AtomicBoolean importRunning = new AtomicBoolean();
    private final Object configWriteLock = new Object();
    private long configRevision;
    private volatile long writtenConfigRevision;
    private final Set<String> dictionary = ConcurrentHashMap.newKeySet();
    private volatile List<String> dictionaryIndex = List.of();
    private volatile boolean dictionaryReady;
    private WordChainApi api;
    private int nextMatchTask;
    private Connection connection;
    private WordGame game;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder().toPath().resolve("wordchain.db"));
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS words (phrase TEXT PRIMARY KEY, first_syllable TEXT NOT NULL, last_syllable TEXT NOT NULL)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_words_first ON words(first_syllable)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS wins (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, wins INTEGER NOT NULL DEFAULT 0, current_streak INTEGER NOT NULL DEFAULT 0, best_streak INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS word_reports (phrase TEXT PRIMARY KEY, reporter_uuid TEXT NOT NULL, reporter_name TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING')");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_status ON word_reports(status, created_at)");
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

    @Override public void onDisable() {
        if (api != null) getServer().getServicesManager().unregister(WordChainApi.class, api);
        try { if (connection != null && !connection.isClosed()) connection.close(); } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }

    // Async chat is handed to the main thread before touching game state or Bukkit player state.
    @EventHandler public void onChat(AsyncChatEvent event) {
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()); UUID id = event.getPlayer().getUniqueId(); String name = event.getPlayer().getName();
        DuelMatch duel = duels.get(id);
        if (duel != null) {
            event.setCancelled(true); // Duel chat is private; never publish duel messages to the server chat.
            if (isTwoSyllablePhrase(input)) Bukkit.getScheduler().runTask(this, () -> processMove(id, name, input));
            return;
        }
        // Ignore ordinary conversation and long messages before scheduling any main-thread work.
        if (!game.isActive() || !isTwoSyllablePhrase(input)) return;
        event.setCancelled(true); // Accepted moves are announced once by processMove; invalid moves get private feedback.
        Bukkit.getScheduler().runTask(this, () -> processMove(id, name, input));
    }

    private void processMove(UUID id, String playerName, String raw) {
        DuelMatch duel=duels.get(id); if(duel!=null){ processDuelMove(duel,id,playerName,raw); return; }
        if (!game.tryMove(raw, this::isValidWord)) { notifyInvalidMove(id, raw); return; }
        String clean = normalizePhrase(raw);
        Bukkit.broadcast(message("<green>" + escape(playerName) + " nối thành công: <yellow>" + escape(clean) + "</yellow>. Tiếng tiếp theo: <gold>" + escape(game.requiredSyllable()) + "</gold>.</green>"));
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id, playerName, clean));
        if (game.getMoves() >= getConfig().getInt("game.target-moves", 10)) {
            game.stop();
            saveWin(id, playerName, true, false, null);
            Bukkit.broadcast(message("<gold><bold>" + escape(playerName) + " đã chiến thắng trò Nối Từ!"));
            startNextMatch();
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                if (!canControl(sender)) return deny(sender);
                if (!dictionaryReady || importRunning.get()) { sender.sendMessage(message("<yellow>Đang nạp từ điển, vui lòng thử lại sau.")); return true; }
                String start = args.length > 1 ? normalizePhrase(String.join(" ", Arrays.copyOfRange(args, 1, args.length))) : normalizePhrase(getConfig().getString("game.starting-word", "phong cảnh"));
                if (!isTwoSyllablePhrase(start) || !isValidWord(start)) { sender.sendMessage(message("<red>Từ bắt đầu phải là cụm đúng hai tiếng có trong từ điển.")); return true; }
                cancelNextMatch(); game.start(start); Bukkit.broadcast(message("<green>Trò Nối Từ bắt đầu! Từ đầu tiên: <yellow>" + escape(start) + "</yellow>."));
            }
            case "stop" -> { if (!canControl(sender)) return deny(sender); cancelNextMatch(); game.stop(); Bukkit.broadcast(message("<yellow>Trò Nối Từ đã dừng.")); }
            case "reset" -> { if (!canControl(sender)) return deny(sender); cancelNextMatch(); game.reset(); Bukkit.broadcast(message("<yellow>Ván Nối Từ đã được đặt lại.")); }
            case "menu" -> { if (sender instanceof Player p) openMenu(p); else sender.sendMessage("Lệnh này chỉ dành cho người chơi."); }
            case "top" -> showTop(sender);
            case "wins" -> { if (!(sender.hasPermission("wordchain.admin") || sender.hasPermission("wordchain.stats"))) return deny(sender); if (args.length != 2) { sender.sendMessage(message("<yellow>Dùng: /wordchain wins <player>")); return true; } showPlayerStats(sender, args[1]); }
            case "rewards" -> { if (!(sender instanceof Player p) || !(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); openRewardsMenu(p); }
            case "reward" -> { if (!(sender.hasPermission("wordchain.admin")||sender.hasPermission("wordchain.rewards"))) return deny(sender); editReward(sender, args); }
            case "difficulty" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); setDifficulty(sender, args); }
            case "duel" -> createDuelInvite(sender,args);
            case "accept" -> acceptDuel(sender,args);
            case "deny" -> denyDuel(sender,args);
            case "add" -> { if (!sender.hasPermission("wordchain.admin")) return deny(sender); if (args.length != 3) { sender.sendMessage(message("<yellow>Dùng: /wordchain add <tiếng1 tiếng2>")); return true; } String phrase = normalizePhrase(args[1] + " " + args[2]); addWordAsync(sender, phrase); }
            case "report" -> reportWord(sender, args);
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

    private void refreshWordIndex() { dictionaryIndex = List.copyOf(dictionary); }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        boolean found = false;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) { while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) found = true; }
        if (!found) try (Statement s = connection.createStatement()) { s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition); }
    }

    private int importSources(List<String> urls, int timeout, int maxLength, boolean reportErrors) {
        int imported = 0;
        for (String spec : urls) {
            try {
                String[] source = spec.split("\\|", 2); String format = source.length == 2 ? source[0].trim().toLowerCase(Locale.ROOT) : "plain"; String url = source.length == 2 ? source[1].trim() : spec.trim();
                var conn = new URL(url).openConnection(); conn.setConnectTimeout(timeout * 1000); conn.setReadTimeout(timeout * 1000); conn.setRequestProperty("User-Agent", "WordChain-Paper/1.1 (dictionary importer)");
                InputStream stream = conn.getInputStream(); if (url.endsWith(".gz")) stream = new java.util.zip.GZIPInputStream(stream);
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
            } catch (Exception e) { if (reportErrors) getLogger().warning("Dictionary source failed " + spec + ": " + e.getMessage()); }
        }
        refreshWordIndex();
        return imported;
    }

    private void scheduleImport(CommandSender sender, boolean reportErrors) {
        if (!importRunning.compareAndSet(false, true)) { if (sender != null) sender.sendMessage(message("<yellow>Một lần nhập dữ liệu đang chạy.")); return; }
        List<String> sources = List.copyOf(getConfig().getStringList("dictionary.sources"));
        int timeout = Math.max(2, getConfig().getInt("dictionary.request-timeout-seconds", 20));
        int maxLength = getConfig().getInt("game.max-phrase-length", 48);
        if (sender != null) sender.sendMessage(message("<yellow>Đang tải danh sách từ bất đồng bộ..."));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            int imported = 0;
            try { if (!dictionaryReady) loadDictionaryCache(); imported = importSources(sources, timeout, maxLength, reportErrors); }
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
                if (resetOtherStreaks) try (PreparedStatement reset = connection.prepareStatement("UPDATE wins SET current_streak=0 WHERE uuid<>?")) { reset.setString(1, id.toString()); reset.executeUpdate(); }
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
            nextMatchTask=0; if(game.isActive())return;
            if (dictionary.isEmpty()) return;
            List<String> words = dictionaryIndex; if(words.isEmpty())return; String start = words.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(words.size()));
            game.start(start); Bukkit.broadcast(message("<green>Ván mới! Từ khóa: <yellow>" + escape(start) + "</yellow>."));
        }, delay);
    }
    private void cancelNextMatch(){if(nextMatchTask!=0){Bukkit.getScheduler().cancelTask(nextMatchTask);nextMatchTask=0;}}

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
        String start=randomKeyword();if(start==null){sender.sendMessage(message("<red>Từ điển chưa có từ phù hợp."));return;}DuelMatch match=new DuelMatch(challenger.getUniqueId(),challenger.getName(),receiver.getUniqueId(),receiver.getName(),invite.connectSeconds,start);duels.put(challenger.getUniqueId(),match);duels.put(receiver.getUniqueId(),match);
        long maxTicks=Math.max(60,Math.min(3600,getConfig().getLong("duel.max-match-seconds",3600)))*20L;match.maxTask=Bukkit.getScheduler().runTaskLater(this,()->finishDuel(match,null,"Trận đấu tự dừng sau một giờ."),maxTicks).getTaskId();armDuelTurn(match);sendDuelMessage(match,"<gold>Đấu bắt đầu! <yellow>"+escape(challenger.getName())+"</yellow> đấu với <yellow>"+escape(receiver.getName())+"</yellow>. Từ khóa: <aqua>"+escape(start)+"</aqua>. Lượt đầu: "+escape(challenger.getName())+".");
    }
    private void denyDuel(CommandSender sender,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("Only players can deny duels.");return;}DuelInvite invite=duelInvites.remove(p.getUniqueId());if(invite==null){sender.sendMessage(message("<red>Không có lời mời đang chờ."));return;}Player challenger=Bukkit.getPlayer(invite.challenger);if(challenger!=null)challenger.sendMessage(message("<yellow>"+escape(p.getName())+" đã từ chối lời mời."));sender.sendMessage(message("<gray>Đã từ chối lời mời."));}
    private String randomKeyword(){List<String> values=dictionaryIndex;if(values.isEmpty())return null;return values.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(values.size()));}
    private void armDuelTurn(DuelMatch match){if(match.turnSeconds<=0)return;if(match.turnTask!=0)Bukkit.getScheduler().cancelTask(match.turnTask);match.turnTask=Bukkit.getScheduler().runTaskLater(this,()->finishDuel(match,otherPlayer(match,match.turn),"Hết thời gian nối từ."),match.turnSeconds*20L).getTaskId();}
    private UUID otherPlayer(DuelMatch m,UUID id){return m.first.equals(id)?m.second:m.first;}
    private void processDuelMove(DuelMatch m,UUID id,String name,String raw){
        if(!m.active)return;if(!m.turn.equals(id)){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<yellow>Chưa đến lượt bạn nối từ."));return;}
        String phrase=normalizePhrase(raw);String required=m.last.split(" ")[1];if(!phrase.startsWith(required+" ")){notifyDuelError(id,"<red>Nối chưa đúng; tiếng đầu phải là <yellow>"+escape(required)+"</yellow>.");return;}if(!dictionary.contains(phrase)){notifyDuelError(id,"<red>Từ chưa có trong từ điển; hãy báo bằng /wordchain report "+escape(phrase)+".");return;}
        int limit=Math.max(1,Math.min(5,getConfig().getInt("anti-abuse.word-use-limit",3)));if(m.uses.getOrDefault(phrase,0)>=limit){Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message("<red>Cụm từ này đã đạt giới hạn sử dụng."));return;}
        int difficulty=getConfig().getInt("difficulty.words."+phrase,1),threshold=getConfig().getInt("difficulty.hard-threshold",4),maxHard=Math.max(1,getConfig().getInt("anti-abuse.max-hard-words-in-row",2));if(difficulty>=threshold&&m.hardRun>=maxHard){notifyDuelError(id,"<red>Đã đạt giới hạn cụm khó liên tiếp. Hãy chọn một cụm dễ hơn.");return;}m.hardRun=difficulty>=threshold?m.hardRun+1:0;
        m.uses.merge(phrase,1,Integer::sum);m.last=phrase;m.moves++;m.turn=otherPlayer(m,id);armDuelTurn(m);sendDuelMessage(m,"<aqua>[ĐẤU]</aqua> <yellow>"+escape(name)+"</yellow>: <white>"+escape(phrase)+"</white>. Từ cần nối: <gold>"+escape(phrase.split(" ")[1])+"</gold>.");
        Bukkit.getPluginManager().callEvent(new WordChainMoveEvent(id,name,phrase));
        if(m.moves>=getConfig().getInt("duel.target-moves",10))finishDuel(m,id,"Đấu kết thúc!");
    }
    private void finishDuel(DuelMatch m,UUID winner,String reason){if(!m.active)return;m.active=false;duels.remove(m.first,m);duels.remove(m.second,m);if(m.turnTask!=0)Bukkit.getScheduler().cancelTask(m.turnTask);if(m.maxTask!=0)Bukkit.getScheduler().cancelTask(m.maxTask);if(winner==null){sendDuelMessage(m,"<yellow>"+reason+" Không có người thắng.");return;}String name=m.first.equals(winner)?m.firstName:m.secondName;sendDuelMessage(m,"<gold>"+reason+" Người thắng: <yellow>"+escape(name)+"</yellow>!");saveWin(winner,name,true,true,otherPlayer(m,winner));}
    private void sendDuelMessage(DuelMatch match,String text){Component component=message(text);Player first=Bukkit.getPlayer(match.first),second=Bukkit.getPlayer(match.second);if(first!=null)first.sendMessage(component);if(second!=null)second.sendMessage(component);}
    private void notifyDuelError(UUID id,String text){long now=System.currentTimeMillis(),last=wrongMoveNotice.getOrDefault(id,0L);if(now-last<1500)return;wrongMoveNotice.put(id,now);Player p=Bukkit.getPlayer(id);if(p!=null)p.sendMessage(message(text));}

    private record DuelInvite(UUID challenger,String challengerName,UUID target,int connectSeconds){}
    private final class DuelMatch {final UUID first,second;final String firstName,secondName;final int turnSeconds;final Map<String,Integer> uses=new HashMap<>();String last;UUID turn;int moves,turnTask,maxTask,hardRun;boolean active=true;DuelMatch(UUID a,String an,UUID b,String bn,int seconds,String start){first=a;firstName=an;second=b;secondName=bn;turnSeconds=seconds;last=start;turn=a;uses.put(start,1);}}
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
        put(inv, 10, Material.LIME_WOOL, "<green><bold>Bắt đầu ván</bold>", "<gray>Bắt đầu từ: " + escape(getConfig().getString("game.starting-word", "phong cảnh")));
        put(inv, 12, Material.BARRIER, "<red>Dừng ván", "<gray>Dừng trò chơi hiện tại");
        put(inv, 14, Material.SUNFLOWER, "<yellow>Đặt lại", "<gray>Xóa trạng thái ván hiện tại");
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
        if (args.length == 1) return List.of("start", "stop", "reset", "menu", "top", "wins", "rewards", "reward", "difficulty", "duel", "accept", "deny", "add", "remove", "report", "reports", "approve", "reject", "import", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }

    private final class WordGame {
        private String lastWord = ""; private int moves; private boolean active;
        private final Map<String,Integer> used = new HashMap<>(); private int hardRun;
        synchronized void start(String phrase) { used.clear(); lastWord = phrase; used.put(phrase,1); hardRun = 0; moves = 0; active = true; }
        synchronized void reset() { used.clear(); lastWord = ""; moves = 0; active = false; }
        synchronized void stop() { active = false; }
        synchronized boolean isActive() { return active; }
        synchronized int getMoves() { return moves; }
        synchronized String getLastWord() { return lastWord; }
        synchronized String requiredSyllable() { String[] parts = lastWord.split(" "); return parts[parts.length - 1]; }
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
    @EventHandler public void onQuit(PlayerQuitEvent e){UUID id=e.getPlayer().getUniqueId();DuelMatch match=duels.get(id);if(match!=null)finishDuel(match,null,"Người chơi rời máy chủ.");duelInvites.remove(id);wrongMoveNotice.remove(id);reportCooldown.remove(id);}
}
