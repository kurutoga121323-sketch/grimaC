package jp.example.duelclans;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DuelClansPlugin extends JavaPlugin implements Listener {
  private final Map<String, Clan> clans = new ConcurrentHashMap<>();
  private final Map<UUID, String> membership = new ConcurrentHashMap<>();
  private final Map<UUID, String> invitations = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> ratings = new ConcurrentHashMap<>();
  private int nextDuelSpot;
  private BukkitTask pendingSave;

  @Override public void onEnable() {
    saveDefaultConfig(); loadData();
    DuelClansCommand commands = new DuelClansCommand(this);
    for (String command : List.of("duel", "setduel1", "setduel2", "clan", "clancolor")) getCommand(command).setExecutor(commands);
    getServer().getPluginManager().registerEvents(this, this);
    Bukkit.getOnlinePlayers().forEach(this::updateTab);
  }
  @Override public void onDisable() { if (pendingSave != null) pendingSave.cancel(); saveData(); }

  Location duelSpot(int index) { return getConfig().getLocation(index == 0 ? "duel.one" : "duel.two"); }
  void setDuelSpot(int index, Location location) { getConfig().set(index == 0 ? "duel.one" : "duel.two", location); saveConfig(); }
  Location nextDuelSpot() { Location result = duelSpot(Math.floorMod(nextDuelSpot++, 2)); return result; }
  int rating(UUID id) { return ratings.getOrDefault(id, 1500); }
  Clan clan(UUID id) { String name = membership.get(id); return name == null ? null : clans.get(name.toLowerCase(Locale.ROOT)); }
  Collection<Clan> clans() { return clans.values(); }
  String invitation(UUID id) { return invitations.get(id); }
  void invite(UUID id, Clan clan) { invitations.put(id, clan.name); }
  Clan accept(UUID id) { String name = invitations.remove(id); if (name == null || membership.containsKey(id)) return null; Clan clan = clans.get(name.toLowerCase(Locale.ROOT)); if (clan != null) { clan.members.add(id); membership.put(id, clan.name); queueSave(); } return clan; }
  Clan create(String name, UUID owner) {
    String key = name.toLowerCase(Locale.ROOT); if (clans.containsKey(key) || membership.containsKey(owner)) return null;
    Clan clan = new Clan(name); clan.members.add(owner); clans.put(key, clan); membership.put(owner, name); queueSave(); return clan;
  }
  boolean setColor(UUID member, String color) { Clan clan = clan(member); if (clan == null || clan.kills < 100 || colorFor(color) == null) return false; clan.color = color.toLowerCase(Locale.ROOT); queueSave(); refreshClan(clan); return true; }
  NamedTextColor colorFor(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "black", "黒" -> NamedTextColor.BLACK; case "dark_blue", "navy", "紺" -> NamedTextColor.DARK_BLUE;
      case "dark_green", "緑" -> NamedTextColor.DARK_GREEN; case "dark_aqua", "teal", "青緑" -> NamedTextColor.DARK_AQUA;
      case "dark_red", "赤" -> NamedTextColor.DARK_RED; case "dark_purple", "purple", "紫" -> NamedTextColor.DARK_PURPLE;
      case "gold", "orange", "金", "オレンジ" -> NamedTextColor.GOLD; case "gray", "grey", "灰" -> NamedTextColor.GRAY;
      case "dark_gray", "dark_grey", "濃灰" -> NamedTextColor.DARK_GRAY; case "blue", "青" -> NamedTextColor.BLUE;
      case "green", "lime", "黄緑" -> NamedTextColor.GREEN; case "aqua", "cyan", "水色" -> NamedTextColor.AQUA;
      case "red", "light_red", "明赤" -> NamedTextColor.RED; case "light_purple", "pink", "ピンク" -> NamedTextColor.LIGHT_PURPLE;
      case "yellow", "黄" -> NamedTextColor.YELLOW; case "white", "白" -> NamedTextColor.WHITE; default -> null;
    };
  }
  Component prefix(UUID id) { Clan clan = clan(id); if (clan == null) return Component.empty(); NamedTextColor color = colorFor(clan.color); return Component.text("[" + clan.name + "] ", color == null ? NamedTextColor.WHITE : color); }
  void updateTab(Player player) {
    player.playerListName(prefix(player.getUniqueId()).append(Component.text(player.getName())));
  }
  void refreshClan(Clan clan) { clan.members.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).forEach(this::updateTab); }

  @EventHandler public void onJoin(PlayerJoinEvent event) { updateTab(event.getPlayer()); }
  @EventHandler public void onChat(AsyncChatEvent event) {
    Component prefix = prefix(event.getPlayer().getUniqueId());
    event.renderer((source, sourceName, message, viewer) -> prefix.append(sourceName).append(Component.text(": ")).append(message));
  }
  @EventHandler public void onDeath(PlayerDeathEvent event) {
    Player victim = event.getPlayer(), killer = victim.getKiller(); if (killer == null || killer.equals(victim)) return;
    int winner = rating(killer.getUniqueId()), loser = rating(victim.getUniqueId());
    if (sameIp(killer, victim)) {
      event.deathMessage(ratedDeathMessage(victim, killer)
          .append(Component.text("  no rating (same IP)", NamedTextColor.DARK_GRAY)));
      return;
    }
    RatingChange change = ratingChange(winner, loser);
    int gain = change.gain, loss = change.loss;
    ratings.put(killer.getUniqueId(), winner + gain); ratings.put(victim.getUniqueId(), Math.max(0, loser - loss));
    Clan clan = clan(killer.getUniqueId()); if (clan != null) { clan.kills++; refreshClan(clan); }
    event.deathMessage(ratedDeathMessage(victim, killer)
        .append(Component.text("  +" + gain + " / -" + loss, NamedTextColor.DARK_GRAY)));
    queueSave();
  }

  private RatingChange ratingChange(int winner, int loser) {
    double expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (loser - winner) / 400.0));
    int delta = clamp((int) Math.round(32.0 * (1.0 - expectedWinner)), 1, 32);
    return new RatingChange(delta, Math.min(delta, 24));
  }

  private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

  private boolean sameIp(Player first, Player second) {
    if (first.getAddress() == null || second.getAddress() == null) return false;
    InetAddress firstAddress = first.getAddress().getAddress();
    InetAddress secondAddress = second.getAddress().getAddress();
    return firstAddress != null && firstAddress.equals(secondAddress);
  }

  private Component ratedDeathMessage(Player victim, Player killer) {
    return ratedName(victim, NamedTextColor.LIGHT_PURPLE, NamedTextColor.RED)
        .append(Component.text(" was killed by ", NamedTextColor.GRAY))
        .append(ratedName(killer, NamedTextColor.DARK_PURPLE, NamedTextColor.GREEN))
        .append(deathSuffix(victim));
  }

  private Component ratedName(Player player, NamedTextColor nameColor, NamedTextColor ratingColor) {
    return Component.text(player.getName(), nameColor)
        .append(Component.text("(" + rating(player.getUniqueId()) + ")", ratingColor));
  }

  private Component deathSuffix(Player victim) {
    if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage
        && damage.getDamager().getType() == EntityType.END_CRYSTAL) {
      return Component.text(" with an end crystal", NamedTextColor.AQUA);
    }
    return Component.empty();
  }
  private void loadData() {
    ConfigurationSection root = getConfig().getConfigurationSection("clans"); if (root != null) for (String key : root.getKeys(false)) {
      ConfigurationSection s = root.getConfigurationSection(key); if (s == null) continue; Clan c = new Clan(s.getString("name", key)); c.color = s.getString("color", "white"); c.kills = s.getInt("kills", 0);
      for (String raw : s.getStringList("members")) try { UUID id = UUID.fromString(raw); c.members.add(id); membership.put(id, c.name); } catch (IllegalArgumentException ignored) { }
      clans.put(key.toLowerCase(Locale.ROOT), c);
    }
    ConfigurationSection ratingRoot = getConfig().getConfigurationSection("ratings"); if (ratingRoot != null) for (String raw : ratingRoot.getKeys(false)) try { ratings.put(UUID.fromString(raw), ratingRoot.getInt(raw, 1500)); } catch (IllegalArgumentException ignored) { }
  }
  private void saveData() {
    getConfig().set("clans", null); getConfig().set("ratings", null);
    for (Clan c : clans.values()) { String path = "clans." + c.name.toLowerCase(Locale.ROOT); getConfig().set(path + ".name", c.name); getConfig().set(path + ".color", c.color); getConfig().set(path + ".kills", c.kills); getConfig().set(path + ".members", c.members.stream().map(UUID::toString).toList()); }
    ratings.forEach((id, value) -> getConfig().set("ratings." + id, value)); saveConfig();
  }
  private void queueSave() {
    if (pendingSave != null) return;
    pendingSave = Bukkit.getScheduler().runTaskLater(this, () -> { pendingSave = null; saveData(); }, 20L);
  }
  static final class Clan { final String name; volatile String color = "white"; int kills; final Set<UUID> members = ConcurrentHashMap.newKeySet(); Clan(String name) { this.name = name; } }
  private record RatingChange(int gain, int loss) { }
}
