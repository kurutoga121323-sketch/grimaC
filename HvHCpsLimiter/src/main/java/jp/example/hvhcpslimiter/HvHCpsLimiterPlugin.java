package jp.example.hvhcpslimiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class HvHCpsLimiterPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
  private final Map<BlockKey, BlockState> states = new HashMap<>();
  private final Map<UUID, Long> lastMessage = new HashMap<>();
  private Settings settings;
  private long tick;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    loadSettings();
    getServer().getPluginManager().registerEvents(this, this);
    getServer().getScheduler().runTaskTimer(this, () -> {
      tick++;
      if (tick % 200L == 0L) cleanup();
    }, 1L, 1L);
    getCommand("hvhcps").setExecutor(this);
    getCommand("hvhcps").setTabCompleter(this);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    if (!settings.enabled || !shouldTrack(event.getBlock())) return;
    BlockKey key = BlockKey.of(event.getBlock());
    BlockState state = states.computeIfAbsent(key, ignored -> new BlockState());
    state.lastBreakTick = tick;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (!settings.enabled) return;
    if (settings.ignoreCreative && event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
    if (!shouldTrack(event.getBlockPlaced())) return;

    BlockKey key = BlockKey.of(event.getBlockPlaced());
    BlockState state = states.get(key);
    if (state == null) return;

    if (tick <= state.blockedUntilTick) {
      cancel(event, state);
      return;
    }
    if (tick - state.lastBreakTick > settings.breakWindowTicks) return;

    double chance = chance(state);
    if (ThreadLocalRandom.current().nextDouble() < chance) {
      state.blockedUntilTick = tick + settings.gapTicks;
      state.interrupts++;
      cancel(event, state);
      return;
    }

    recordCycle(state);
  }

  private void cancel(BlockPlaceEvent event, BlockState state) {
    event.setCancelled(true);
    recordCycle(state);
    if (!settings.sendActionbar) return;
    Player player = event.getPlayer();
    long last = lastMessage.getOrDefault(player.getUniqueId(), 0L);
    if (tick - last < settings.messageCooldownTicks) return;
    lastMessage.put(player.getUniqueId(), tick);
    player.sendActionBar(Component.text("Surround replacement delayed", NamedTextColor.DARK_GRAY));
  }

  private double chance(BlockState state) {
    double chance = state.recentCycles >= settings.repeatThreshold ? settings.repeatChance : settings.baseChance;
    return Math.max(0.0, Math.min(settings.maxChance, chance));
  }

  private void recordCycle(BlockState state) {
    if (tick - state.lastCycleTick <= settings.repeatWindowTicks) state.recentCycles++;
    else state.recentCycles = 1;
    state.lastCycleTick = tick;
  }

  private boolean shouldTrack(Block block) {
    return settings.blocks.contains(block.getType()) && enabledWorld(block.getWorld());
  }

  private boolean enabledWorld(World world) {
    return settings.worlds.isEmpty() || settings.worlds.contains(world.getName().toLowerCase(Locale.ROOT));
  }

  private void cleanup() {
    long expire = Math.max(settings.repeatWindowTicks, settings.breakWindowTicks) + settings.gapTicks + 200L;
    states.entrySet().removeIf(entry -> tick - Math.max(entry.getValue().lastBreakTick, entry.getValue().lastCycleTick) > expire);
    lastMessage.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
  }

  private void loadSettings() {
    reloadConfig();
    settings = new Settings();
    settings.enabled = getConfig().getBoolean("enabled", true);
    settings.worlds = lowerSet(getConfig().getStringList("worlds"));
    settings.blocks = materials(getConfig().getStringList("limited-blocks"));
    settings.breakWindowTicks = Math.max(1L, getConfig().getLong("break-window-ticks", 20L));
    settings.gapTicks = Math.max(1L, getConfig().getLong("gap-ticks", 5L));
    settings.baseChance = chanceValue("base-chance", 0.18);
    settings.repeatChance = chanceValue("repeat-chance", 0.35);
    settings.repeatWindowTicks = Math.max(1L, getConfig().getLong("repeat-window-seconds", 4L) * 20L);
    settings.repeatThreshold = Math.max(1, getConfig().getInt("repeat-threshold", 2));
    settings.maxChance = chanceValue("max-chance", 0.55);
    settings.ignoreCreative = getConfig().getBoolean("ignore-creative", true);
    settings.sendActionbar = getConfig().getBoolean("send-actionbar", true);
    settings.messageCooldownTicks = Math.max(0L, getConfig().getLong("message-cooldown-ticks", 40L));
  }

  private double chanceValue(String path, double fallback) {
    return Math.max(0.0, Math.min(1.0, getConfig().getDouble(path, fallback)));
  }

  private Set<String> lowerSet(List<String> values) {
    Set<String> result = new HashSet<>();
    for (String value : values) if (value != null && !value.isBlank()) result.add(value.toLowerCase(Locale.ROOT));
    return result;
  }

  private Set<Material> materials(List<String> values) {
    Set<Material> result = new HashSet<>();
    for (String value : values) {
      Material material = Material.matchMaterial(value);
      if (material == null || !material.isBlock()) getLogger().warning("Ignored invalid limited-blocks entry: " + value);
      else result.add(material);
    }
    if (result.isEmpty()) result.add(Material.OBSIDIAN);
    return result;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
      loadSettings();
      states.clear();
      sender.sendMessage(Component.text("HvHCpsLimiter config reloaded.", NamedTextColor.GREEN));
      return true;
    }
    if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
      sender.sendMessage(Component.text("enabled=" + settings.enabled
          + " blocks=" + settings.blocks
          + " base=" + settings.baseChance
          + " repeat=" + settings.repeatChance
          + " gapTicks=" + settings.gapTicks
          + " tracked=" + states.size(), NamedTextColor.AQUA));
      return true;
    }
    sender.sendMessage(Component.text("/hvhcps reload | /hvhcps status", NamedTextColor.AQUA));
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length != 1) return List.of();
    List<String> result = new ArrayList<>();
    for (String option : List.of("reload", "status")) if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
    return result;
  }

  private static final class Settings {
    boolean enabled;
    Set<String> worlds;
    Set<Material> blocks;
    long breakWindowTicks;
    long gapTicks;
    double baseChance;
    double repeatChance;
    long repeatWindowTicks;
    int repeatThreshold;
    double maxChance;
    boolean ignoreCreative;
    boolean sendActionbar;
    long messageCooldownTicks;
  }

  private static final class BlockState {
    long lastBreakTick;
    long blockedUntilTick;
    long lastCycleTick;
    int recentCycles;
    int interrupts;
  }

  private record BlockKey(UUID world, int x, int y, int z) {
    static BlockKey of(Block block) {
      return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
  }
}
