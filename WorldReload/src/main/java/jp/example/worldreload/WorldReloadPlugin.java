package jp.example.worldreload;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldReloadPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final String WAITING_WORLD = "worldreload_waiting";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private BukkitTask restoreTask;
    private BukkitTask warningTask;
    private long intervalTicks;

    @Override public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("worldreload")).setExecutor(this);
        Objects.requireNonNull(getCommand("worldreload")).setTabCompleter(this);
        try {
            Files.createDirectories(backups());
            Files.createDirectories(workDir());
            Files.createDirectories(recoveryDir());
        } catch (IOException e) {
            getLogger().severe("WorldReload folders could not be created: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        long saved = getConfig().getLong("interval-seconds", 0);
        if (saved > 0 && backupExists(activeBackup())) {
            intervalTicks = Math.multiplyExact(saved, 20L);
            schedule();
            getLogger().info("Periodic restore resumed: every " + saved + " seconds using '" + activeBackup() + "'.");
        }
    }

    @Override public void onDisable() { cancelTasks(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("worldreload.admin")) { sender.sendMessage(ChatColor.RED + "権限がありません。"); return true; }
        if (args.length == 0) { help(sender); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "save" -> {
                    requireArgs(args, 2); String name = safeName(args[1]);
                    if (backupExists(name)) throw new IllegalArgumentException("同名バックアップは既にあります。既存データ保護のため上書きしません。");
                    saveBackupAsync(name, sender);
                }
                case "use" -> {
                    requireArgs(args, 2); String name = safeName(args[1]); validateBackup(name);
                    getConfig().set("active-backup", name); saveConfig(); sender.sendMessage(ChatColor.GREEN + "使用バックアップ: " + name);
                }
                case "time" -> {
                    requireArgs(args, 2); validateBackup(activeBackup());
                    long seconds = parseDuration(args[1]); intervalTicks = Math.multiplyExact(seconds, 20L);
                    getConfig().set("interval-seconds", seconds); saveConfig(); schedule();
                    sender.sendMessage(ChatColor.GREEN + formatDuration(seconds) + " ごとに復元します。プレイヤーは切断されません。");
                }
                case "restore" -> { String name = args.length >= 2 ? safeName(args[1]) : activeBackup(); validateBackup(name); startRestore(name, sender); }
                case "list" -> { sender.sendMessage(ChatColor.AQUA + "バックアップ: " + listBackups()); sender.sendMessage(ChatColor.AQUA + "選択中: " + activeBackup() + (restoreTask == null ? " / 定期復元なし" : " / 定期復元あり")); }
                case "status" -> status(sender);
                case "cancel" -> { cancelTasks(); getConfig().set("interval-seconds", 0); saveConfig(); sender.sendMessage(ChatColor.YELLOW + "定期復元を停止しました。"); }
                case "import" -> { requireArgs(args, 3); importBackupAsync(safeName(args[1]), Paths.get(joinArgs(args, 2)), sender); }
                default -> help(sender);
            }
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "失敗: " + e.getMessage());
            getLogger().warning("Command failed: " + e.getMessage());
        }
        return true;
    }

    private void schedule() {
        cancelTasks();
        long warningTicks = Math.min(intervalTicks - 1, Math.max(0, getConfig().getLong("warning-seconds", 30) * 20));
        if (warningTicks > 0) warningTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.broadcastMessage(ChatColor.YELLOW + "[WorldReload] " + Math.max(1, warningTicks / 20) + "秒後にワールドを復元します。"),
                intervalTicks - warningTicks, intervalTicks);
        restoreTask = Bukkit.getScheduler().runTaskTimer(this, () -> startRestore(activeBackup(), Bukkit.getConsoleSender()), intervalTicks, intervalTicks);
    }

    private void cancelTasks() {
        if (restoreTask != null) restoreTask.cancel();
        if (warningTask != null) warningTask.cancel();
        restoreTask = warningTask = null;
    }

    private void startRestore(String name, CommandSender sender) {
        if (!busy.compareAndSet(false, true)) { sender.sendMessage(ChatColor.YELLOW + "別の保存・復元処理が進行中です。"); return; }
        String operation = STAMP.format(LocalDateTime.now());
        Path staging = workDir().resolve("restore-" + operation);
        sender.sendMessage(ChatColor.YELLOW + "バックアップを準備しています: " + name);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                prepareStaging(name, staging);
                Bukkit.getScheduler().runTask(this, () -> applyRestore(name, operation, staging, sender));
            } catch (Exception e) {
                busy.set(false); getLogger().severe("Restore preparation failed: " + e);
                send(sender, ChatColor.RED + "復元準備に失敗しました: " + e.getMessage());
                deleteTreeQuietly(staging);
            }
        });
    }

    private void prepareStaging(String name, Path staging) throws IOException {
        Files.createDirectories(staging);
        for (String worldName : worlds()) copy(resolveBackupWorld(name, worldName), staging.resolve(worldName), true);
    }

    private void applyRestore(String name, String operation, Path staging, CommandSender sender) {
        Map<UUID, PlayerPosition> players = new LinkedHashMap<>();
        List<Swap> swapped = new ArrayList<>();
        try {
            List<World> targets = new ArrayList<>();
            List<World> liveTerrainTargets = new ArrayList<>();
            for (String worldName : worlds()) {
                World world = requireWorld(worldName);
                if (isPrimaryWorld(world)) {
                    if (!getConfig().getBoolean("live-terrain-primary-worlds", true)) throw new IllegalStateException("メインワールド '" + worldName + "' は稼働中に安全にアンロードできません。live-terrain-primary-worlds を有効にするか、復元対象に別ワールドを指定してください。");
                    liveTerrainTargets.add(world);
                    continue;
                }
                targets.add(world);
                for (Player player : world.getPlayers()) {
                    Location location = player.getLocation();
                    players.put(player.getUniqueId(), new PlayerPosition(worldName, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
                }
            }
            World waiting = players.isEmpty() ? null : waitingWorld();
            for (UUID id : players.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && !player.teleport(Objects.requireNonNull(waiting).getSpawnLocation())) throw new IOException(player.getName() + " を待機ワールドへ移動できません。");
            }
            for (World world : targets) {
                String worldName = world.getName();
                Path live = world.getWorldFolder().toPath().toAbsolutePath().normalize();
                Path recovery = recoveryDir().resolve(operation).resolve(worldName).toAbsolutePath().normalize();
                if (!Bukkit.unloadWorld(world, false)) throw new IOException(worldName + " をアンロードできません。別プラグインの強制ロード設定も確認してください。");
                Swap swap = new Swap(worldName, world.getEnvironment(), live, recovery);
                swapped.add(swap);
                Files.createDirectories(recovery.getParent());
                moveDirectory(live, recovery);
                moveDirectory(staging.resolve(worldName).toAbsolutePath().normalize(), live);
                if (Bukkit.createWorld(new WorldCreator(worldName).environment(world.getEnvironment())) == null) throw new IOException(worldName + " の再ロードに失敗しました。");
            }
            if (!liveTerrainTargets.isEmpty()) {
                startLiveTerrainRestore(name, operation, staging, liveTerrainTargets, players, sender);
                return;
            }
            returnPlayers(players);
            Bukkit.broadcastMessage(ChatColor.GREEN + "[WorldReload] '" + name + "' への復元が完了しました。プレイヤー接続は維持されています。");
            sender.sendMessage(ChatColor.GREEN + "復元完了。直前のデータは plugins/WorldReload/recovery/" + operation + " に保護しました。");
        } catch (Exception failure) {
            getLogger().severe("Restore failed; rolling back: " + failure);
            rollback(swapped); returnPlayers(players);
            sender.sendMessage(ChatColor.RED + "復元に失敗したため元のワールドへ戻しました: " + failure.getMessage());
        } finally {
            busy.set(false);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteTreeQuietly(staging));
        }
    }

    private void startLiveTerrainRestore(String name, String operation, Path staging, List<World> targets, Map<UUID, PlayerPosition> players, CommandSender sender) throws IOException {
        List<LiveWorld> liveWorlds = new ArrayList<>();
        try {
            LiveBounds bounds = liveBounds();
            for (World target : targets) liveWorlds.add(loadLiveSource(operation, staging.resolve(target.getName()), target));
            List<ChunkPair> chunks = new ArrayList<>();
            for (LiveWorld live : liveWorlds) for (ChunkCoord chunk : regionChunks(live.folder().resolve("region"), bounds)) chunks.add(new ChunkPair(live, chunk));
            if (chunks.isEmpty()) throw new IOException("バックアップに復元できるregion chunkがありません。");
            sender.sendMessage(ChatColor.YELLOW + "ライブ地形復元を開始します: " + chunks.size() + " chunks" + (bounds.enabled() ? " / bounded" : ""));
            final int perTick = Math.max(1, getConfig().getInt("live-terrain-chunks-per-tick", 1));
            final int total = chunks.size();
            final int[] index = {0};
            final BukkitTask[] task = new BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    for (int copied = 0; copied < perTick && index[0] < total; copied++) {
                        ChunkPair pair = chunks.get(index[0]++);
                        copyChunkTerrain(pair.live().source(), pair.live().target(), pair.chunk());
                    }
                    if (index[0] % Math.max(1, total / 10) == 0 || index[0] >= total) {
                        getLogger().info("Live terrain restore progress: " + index[0] + "/" + total + " chunks");
                    }
                    if (index[0] >= total) {
                        task[0].cancel();
                        finishLiveTerrainRestore(name, liveWorlds, players, staging, sender, true, null);
                    }
                } catch (Exception failure) {
                    task[0].cancel();
                    finishLiveTerrainRestore(name, liveWorlds, players, staging, sender, false, failure);
                }
            }, 1L, 1L);
        } catch (Exception failure) {
            finishLiveTerrainRestore(name, liveWorlds, players, staging, sender, false, failure);
        }
    }

    private LiveWorld loadLiveSource(String operation, Path source, World target) throws IOException {
        String tempName = tempWorldName(operation, target.getName());
        Path folder = Bukkit.getWorldContainer().toPath().resolve(tempName).toAbsolutePath().normalize();
        deleteTreeQuietly(folder);
        moveDirectory(source.toAbsolutePath().normalize(), folder);
        Files.deleteIfExists(folder.resolve("uid.dat"));
        Files.deleteIfExists(folder.resolve("session.lock"));
        World loaded = Bukkit.createWorld(new WorldCreator(tempName).environment(target.getEnvironment()));
        if (loaded == null) throw new IOException("一時復元ワールドをロードできません: " + tempName);
        loaded.setAutoSave(false);
        return new LiveWorld(target, loaded, folder);
    }

    private String tempWorldName(String operation, String worldName) {
        String raw = "__worldreload_source_" + Math.abs(Objects.hash(operation, worldName)) + "_" + worldName;
        return raw.length() <= 60 ? raw : raw.substring(0, 60);
    }

    private LiveBounds liveBounds() {
        boolean enabled = getConfig().getBoolean("live-terrain-bounds.enabled", false);
        int centerX = getConfig().getInt("live-terrain-bounds.center-x", 0);
        int centerZ = getConfig().getInt("live-terrain-bounds.center-z", 0);
        int radiusBlocks = Math.max(1, getConfig().getInt("live-terrain-bounds.radius-blocks", 300));
        int minChunkX = Math.floorDiv(centerX - radiusBlocks, 16);
        int maxChunkX = Math.floorDiv(centerX + radiusBlocks, 16);
        int minChunkZ = Math.floorDiv(centerZ - radiusBlocks, 16);
        int maxChunkZ = Math.floorDiv(centerZ + radiusBlocks, 16);
        return new LiveBounds(enabled, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }

    private List<ChunkCoord> regionChunks(Path regionDir, LiveBounds bounds) throws IOException {
        if (!Files.isDirectory(regionDir)) return List.of();
        List<ChunkCoord> chunks = new ArrayList<>();
        try (var stream = Files.list(regionDir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList()) {
                String[] parts = file.getFileName().toString().split("\\.");
                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);
                if (!bounds.mayContain(regionX, regionZ)) continue;
                byte[] header = new byte[4096];
                try (InputStream input = Files.newInputStream(file)) {
                    int read = input.read(header);
                    if (read < header.length) continue;
                }
                for (int index = 0; index < 1024; index++) {
                    int offset = ((header[index * 4] & 0xff) << 16) | ((header[index * 4 + 1] & 0xff) << 8) | (header[index * 4 + 2] & 0xff);
                    if (offset == 0) continue;
                    ChunkCoord chunk = new ChunkCoord(regionX * 32 + index % 32, regionZ * 32 + index / 32);
                    if (!bounds.contains(chunk)) continue;
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    private void copyChunkTerrain(World source, World target, ChunkCoord coord) {
        Chunk sourceChunk = source.getChunkAt(coord.x(), coord.z());
        Chunk targetChunk = target.getChunkAt(coord.x(), coord.z());
        int minY = Math.max(source.getMinHeight(), target.getMinHeight());
        int maxY = Math.min(source.getMaxHeight(), target.getMaxHeight());
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < maxY; y++) {
            targetChunk.getBlock(x, y, z).setBlockData(sourceChunk.getBlock(x, y, z).getBlockData(), false);
        }
    }

    private void finishLiveTerrainRestore(String name, List<LiveWorld> liveWorlds, Map<UUID, PlayerPosition> players, Path staging, CommandSender sender, boolean success, Exception failure) {
        for (LiveWorld live : liveWorlds) {
            Bukkit.unloadWorld(live.source(), false);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteTreeQuietly(live.folder()));
        }
        returnPlayers(players);
        busy.set(false);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteTreeQuietly(staging));
        if (success) {
            Bukkit.broadcastMessage(ChatColor.GREEN + "[WorldReload] '" + name + "' へのライブ地形復元が完了しました。");
            sender.sendMessage(ChatColor.GREEN + "ライブ地形復元完了。プレイヤー接続は維持されています。");
        } else {
            String message = failure == null ? "unknown" : failure.getMessage();
            sender.sendMessage(ChatColor.RED + "ライブ地形復元に失敗しました: " + message);
            getLogger().severe("Live terrain restore failed: " + failure);
        }
    }

    private void rollback(List<Swap> swapped) {
        Collections.reverse(swapped);
        for (Swap swap : swapped) {
            try {
                World current = Bukkit.getWorld(swap.name());
                if (current != null) Bukkit.unloadWorld(current, false);
                Path failed = recoveryDir().resolve("failed-" + STAMP.format(LocalDateTime.now())).resolve(swap.name());
                Files.createDirectories(failed.getParent());
                if (Files.exists(swap.recovery())) {
                    if (Files.exists(swap.live())) moveDirectory(swap.live(), failed);
                    moveDirectory(swap.recovery(), swap.live());
                }
                Bukkit.createWorld(new WorldCreator(swap.name()).environment(swap.environment()));
            } catch (Exception e) { getLogger().severe("Rollback failed for " + swap.name() + ": " + e); }
        }
    }

    private void returnPlayers(Map<UUID, PlayerPosition> players) {
        for (var entry : players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey()); PlayerPosition position = entry.getValue(); World world = Bukkit.getWorld(position.world());
            if (player != null && world != null) player.teleport(new Location(world, position.x(), position.y(), position.z(), position.yaw(), position.pitch()));
        }
    }

    private World waitingWorld() {
        String configured = getConfig().getString("evacuation-world", WAITING_WORLD);
        if (configured == null || configured.isBlank() || worlds().contains(configured)) configured = WAITING_WORLD;
        World world = Bukkit.getWorld(configured);
        if (world == null) world = Bukkit.createWorld(new WorldCreator(configured).type(WorldType.FLAT).generateStructures(false));
        if (world == null) throw new IllegalStateException("待機ワールドを作成できません: " + configured);
        return world;
    }

    private void saveBackupAsync(String name, CommandSender sender) {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("別の保存・復元処理が進行中です。");
        Map<String, Path> sources = new LinkedHashMap<>();
        for (String worldName : worlds()) { World w = requireWorld(worldName); w.save(); sources.put(worldName, w.getWorldFolder().toPath()); }
        sender.sendMessage(ChatColor.YELLOW + "バックアップを保存しています: " + name);
        Path temporary = workDir().resolve("save-" + name + "-" + STAMP.format(LocalDateTime.now()));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(temporary);
                for (var entry : sources.entrySet()) copy(entry.getValue(), temporary.resolve(entry.getKey()), false);
                moveDirectory(temporary, backups().resolve(name));
                send(sender, ChatColor.GREEN + "バックアップ '" + name + "' を保存しました。");
            } catch (Exception e) { send(sender, ChatColor.RED + "保存に失敗しました: " + e.getMessage()); getLogger().severe("Backup failed: " + e); }
            finally { deleteTreeQuietly(temporary); busy.set(false); }
        });
    }

    private void importBackupAsync(String name, Path source, CommandSender sender) {
        if (backupExists(name)) throw new IllegalArgumentException("同名バックアップは既にあります。既存データ保護のため上書きしません。");
        if (!Files.isDirectory(source)) throw new IllegalArgumentException("フォルダが見つかりません: " + source);
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("別の保存・復元処理が進行中です。");
        sender.sendMessage(ChatColor.YELLOW + "既存バックアップを取り込んでいます: " + name);
        List<String> configuredWorlds = List.copyOf(worlds());
        Path temporary = workDir().resolve("import-" + name + "-" + STAMP.format(LocalDateTime.now()));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(temporary);
                if (configuredWorlds.size() == 1 && Files.isRegularFile(source.resolve("level.dat"))) copy(source, temporary.resolve(configuredWorlds.getFirst()), false);
                else for (String world : configuredWorlds) {
                    Path from = source.resolve(world);
                    if (!Files.isDirectory(from)) throw new IllegalArgumentException("取り込み元に " + world + " フォルダがありません。");
                    copy(from, temporary.resolve(world), false);
                }
                moveDirectory(temporary, backups().resolve(name));
                send(sender, ChatColor.GREEN + "取り込み完了: " + name);
            } catch (Exception e) { send(sender, ChatColor.RED + "取り込みに失敗しました: " + e.getMessage()); getLogger().severe("Import failed: " + e); }
            finally { deleteTreeQuietly(temporary); busy.set(false); }
        });
    }

    private void validateBackup(String name) {
        if (!backupExists(name)) throw new IllegalArgumentException("バックアップ '" + name + "' がありません。");
        for (String world : worlds()) {
            Path source = resolveBackupWorld(name, world);
            if (!Files.isRegularFile(source.resolve("level.dat"))) throw new IllegalArgumentException("バックアップの " + world + " に level.dat がありません。");
        }
    }

    private Path resolveBackupWorld(String name, String world) {
        Path root = backups().resolve(name); Path nested = root.resolve(world);
        if (Files.isDirectory(nested)) return nested;
        if (worlds().size() == 1 && Files.isRegularFile(root.resolve("level.dat"))) return root;
        return nested;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "WorldReload v" + getPluginMeta().getVersion() + " / 処理中: " + (busy.get() ? "はい" : "いいえ"));
        sender.sendMessage(ChatColor.AQUA + "対象: " + String.join(", ", worlds()) + " / バックアップ: " + activeBackup());
        for (String name : worlds()) {
            World world = Bukkit.getWorld(name);
            sender.sendMessage((world == null ? ChatColor.RED : ChatColor.GREEN) + "- " + name + ": " + (world == null ? "未ロード" : isPrimaryWorld(world) ? getConfig().getBoolean("live-terrain-primary-worlds", true) ? "メインワールド（ライブ地形復元）" : "メインワールド（オンライン復元不可）" : "オンライン復元可能"));
        }
    }

    private boolean isPrimaryWorld(World world) { return !Bukkit.getWorlds().isEmpty() && Bukkit.getWorlds().getFirst().getUID().equals(world.getUID()); }
    private Path backups() { return getDataFolder().toPath().resolve("backups"); }
    private Path workDir() { return getDataFolder().toPath().resolve("work"); }
    private Path recoveryDir() { return getDataFolder().toPath().resolve("recovery"); }
    private String activeBackup() { return getConfig().getString("active-backup", "default"); }
    private List<String> worlds() { List<String> list = getConfig().getStringList("worlds"); if (list.isEmpty()) throw new IllegalStateException("config.yml の worlds が空です。"); return list; }
    private boolean backupExists(String name) { return Files.isDirectory(backups().resolve(name)); }
    private World requireWorld(String name) { World w = Bukkit.getWorld(name); if (w == null) throw new IllegalArgumentException("ワールドがロードされていません: " + name); return w; }
    private static void moveDirectory(Path from, Path to) throws IOException { try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(from, to); } }
    private static void copy(Path from, Path to, boolean skipSessionLock) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException { Files.createDirectories(to.resolve(from.relativize(d))); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (skipSessionLock && f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                Files.copy(f, to.resolve(from.relativize(f)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES); return FileVisitResult.CONTINUE;
            }
        });
    }
    private void send(CommandSender sender, String message) {
        if (Bukkit.isPrimaryThread()) sender.sendMessage(message);
        else Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message));
    }
    private void deleteTreeQuietly(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException { if (failure != null) throw failure; Files.deleteIfExists(directory); return FileVisitResult.CONTINUE; }
            });
        } catch (IOException error) { getLogger().warning("Could not clean work directory '" + root.getFileName() + "': " + error.getMessage()); }
    }
    private static long parseDuration(String text) { var m = java.util.regex.Pattern.compile("^(\\d+)([smhd])$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text); if (!m.matches()) throw new IllegalArgumentException("時間は 30m, 2h, 1d の形式です。"); long n = Long.parseLong(m.group(1)); if (n < 1) throw new IllegalArgumentException("時間は1以上にしてください。"); return Math.multiplyExact(n, switch (Character.toLowerCase(m.group(2).charAt(0))) { case 's' -> 1; case 'm' -> 60; case 'h' -> 3600; default -> 86400; }); }
    private static String formatDuration(long seconds) { if (seconds % 86400 == 0) return seconds / 86400 + "日"; if (seconds % 3600 == 0) return seconds / 3600 + "時間"; if (seconds % 60 == 0) return seconds / 60 + "分"; return seconds + "秒"; }
    private static String safeName(String n) { if (!n.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("名前は英数字、_、- のみ使えます。"); return n; }
    private static void requireArgs(String[] a, int n) { if (a.length < n) throw new IllegalArgumentException("引数が足りません。"); }
    private static String joinArgs(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private String listBackups() throws IOException { try (var s = Files.list(backups())) { String result = String.join(", ", s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList()); return result.isBlank() ? "なし" : result; } }
    private void help(CommandSender s) { s.sendMessage(ChatColor.AQUA + "/worldreload save <name> | use <name> | time <30m|2h|1d> | restore [name]"); s.sendMessage(ChatColor.AQUA + "/worldreload list | status | cancel | import <name> <フォルダ>"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("save", "use", "time", "restore", "list", "status", "cancel", "import").stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        try { if (args.length == 2 && (args[0].equalsIgnoreCase("use") || args[0].equalsIgnoreCase("restore"))) try (var s = Files.list(backups())) { return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(x -> x.startsWith(args[1])).toList(); } } catch (IOException ignored) {}
        return List.of();
    }

  private record Swap(String name, World.Environment environment, Path live, Path recovery) {}
  private record PlayerPosition(String world, double x, double y, double z, float yaw, float pitch) {}
  private record LiveWorld(World target, World source, Path folder) {}
  private record ChunkCoord(int x, int z) {}
  private record ChunkPair(LiveWorld live, ChunkCoord chunk) {}
  private record LiveBounds(boolean enabled, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
      boolean contains(ChunkCoord chunk) {
          return !enabled || (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ);
      }
      boolean mayContain(int regionX, int regionZ) {
          if (!enabled) return true;
          int minX = regionX * 32;
          int maxX = minX + 31;
          int minZ = regionZ * 32;
          int maxZ = minZ + 31;
          return maxX >= minChunkX && minX <= maxChunkX && maxZ >= minChunkZ && minZ <= maxChunkZ;
      }
  }
}
