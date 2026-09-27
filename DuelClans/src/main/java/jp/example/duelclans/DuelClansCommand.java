package jp.example.duelclans;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

final class DuelClansCommand implements CommandExecutor {
  private final DuelClansPlugin plugin;
  DuelClansCommand(DuelClansPlugin plugin) { this.plugin = plugin; }
  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
    return switch (command.getName().toLowerCase()) {
      case "duel" -> duel(player); case "setduel1" -> setDuel(player, 0); case "setduel2" -> setDuel(player, 1);
      case "clan" -> clan(player, args); case "clancolor" -> color(player, args); default -> false;
    };
  }
  private boolean duel(Player p) { Location spot = plugin.nextDuelSpot(); if (spot == null) p.sendMessage(Component.text("Duel地点が未設定です。")); else p.teleportAsync(spot); return true; }
  private boolean setDuel(Player p, int index) { if (!p.hasPermission("duelclans.admin")) { p.sendMessage(Component.text("権限がありません。")); return true; } plugin.setDuelSpot(index, p.getLocation()); p.sendMessage(Component.text("Duel地点 " + (index + 1) + " を設定しました。")); return true; }
  private boolean clan(Player p, String[] a) {
    if (a.length == 2 && a[0].equalsIgnoreCase("create")) { if (!a[1].matches("[A-Za-z0-9_]{2,16}")) p.sendMessage(Component.text("Clan名は英数字と_で2〜16文字です。")); else if (plugin.create(a[1], p.getUniqueId()) == null) p.sendMessage(Component.text("既にClan所属か、その名前は使用済みです。")); else { plugin.updateTab(p); p.sendMessage(Component.text("Clan " + a[1] + " を作成しました。")); } return true; }
    if (a.length == 2 && a[0].equalsIgnoreCase("invite")) { DuelClansPlugin.Clan clan = plugin.clan(p.getUniqueId()); Player target = p.getServer().getPlayerExact(a[1]); if (clan == null) p.sendMessage(Component.text("Clanに所属していません。")); else if (target == null) p.sendMessage(Component.text("対象プレイヤーが見つかりません。")); else if (plugin.clan(target.getUniqueId()) != null) p.sendMessage(Component.text("そのプレイヤーは既にClan所属です。")); else { plugin.invite(target.getUniqueId(), clan); target.sendMessage(Component.text("[" + clan.name + "] から招待されました。/clan accept で承諾します。")); p.sendMessage(Component.text("招待を送りました。")); } return true; }
    if (a.length == 1 && a[0].equalsIgnoreCase("accept")) { DuelClansPlugin.Clan clan = plugin.accept(p.getUniqueId()); if (clan == null) p.sendMessage(Component.text("承諾できる招待がないか、既にClan所属です。")); else { plugin.updateTab(p); p.sendMessage(Component.text("[" + clan.name + "] に参加しました。")); } return true; }
    p.sendMessage(Component.text("/clan create <NAME> | /clan invite <Player> | /clan accept")); return true;
  }
  private boolean color(Player p, String[] a) { if (a.length != 1) { p.sendMessage(Component.text("/clancolor <色名>")); return true; } DuelClansPlugin.Clan c = plugin.clan(p.getUniqueId()); if (c == null) p.sendMessage(Component.text("Clanに所属していません。")); else if (c.kills < 100) p.sendMessage(Component.text("Clan合計100 killで色を変更できます。現在: " + c.kills)); else if (!plugin.setColor(p.getUniqueId(), a[0])) p.sendMessage(Component.text("色名が無効です。例: red, blue, green, gold, white")); else p.sendMessage(Component.text("Clan色を変更しました。")); return true; }
}
