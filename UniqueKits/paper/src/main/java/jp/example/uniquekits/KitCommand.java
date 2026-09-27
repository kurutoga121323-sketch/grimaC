package jp.example.uniquekits;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

final class KitCommand implements CommandExecutor {
  private final UniqueKitsPlugin plugin;
  KitCommand(UniqueKitsPlugin plugin) { this.plugin = plugin; }
  @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
    return switch (command.getName().toLowerCase()) {
      case "kit" -> kit(player, args); case "kitcreator" -> creator(player); case "setkitcreator" -> setCreator(player);
      case "createukit" -> create(player, args); case "deleteukit" -> delete(player, args); default -> false;
    };
  }
  private boolean creator(Player player) {
    if (!plugin.isCreatorServer()) { plugin.requestCreatorTeleport(player); player.sendMessage(Component.text("Kit Creatorサーバーへ移動しています。")); return true; }
    plugin.store().creatorLocation().ifPresentOrElse(location -> { player.teleportAsync(location); player.setGameMode(GameMode.SURVIVAL); }, () -> player.sendMessage(Component.text("KIT作成場所は未設定です。"))); return true;
  }
  private boolean setCreator(Player player) {
    if (!player.hasPermission("uniquekits.admin")) { player.sendMessage(Component.text("権限がありません。")); return true; }
    plugin.store().creatorLocation(player.getLocation()); plugin.configureCurrentServerAsCreator(player); player.sendMessage(Component.text("KIT作成場所とサーバーを保存しました。")); return true;
  }
  private boolean create(Player player, String[] args) {
    boolean pub = args.length == 2 && args[0].equalsIgnoreCase("public"); String name = pub ? args[1] : args.length == 1 ? args[0] : "";
    if (!name.matches("[A-Za-z0-9_]{1,32}")) { player.sendMessage(Component.text("使用法: /createukit [public] <Kit名>")); return true; }
    if (pub && !player.isOp()) { player.sendMessage(Component.text("Public Kitを作成できるのはOPのみです。")); return true; }
    PlayerInventory inv = player.getInventory(); plugin.store().put(player.getUniqueId(), name, new Kit(inv.getStorageContents(), inv.getArmorContents(), inv.getItemInOffHand()), pub);
    player.sendMessage(Component.text((pub ? "Public" : "Private") + " Kit " + name + " を保存しました。")); return true;
  }
  private boolean delete(Player player, String[] args) {
    boolean pub = args.length == 2 && args[0].equalsIgnoreCase("public"); String name = pub ? args[1] : args.length == 1 ? args[0] : "";
    if (!name.matches("[A-Za-z0-9_]{1,32}")) { player.sendMessage(Component.text("使用法: /deleteukit [public] <Kit名>")); return true; }
    if (pub && !player.isOp()) { player.sendMessage(Component.text("Public Kitを削除できるのはOPのみです。")); return true; }
    player.sendMessage(Component.text(plugin.store().remove(player.getUniqueId(), name, pub) ? "Kitを削除しました。" : "Kitが見つかりません。")); return true;
  }
  private boolean kit(Player player, String[] args) {
    if (args.length == 0) { new KitListener(plugin).openRoot(player); return true; }
    if (args.length == 2 && (args[0].equalsIgnoreCase("public") || args[0].equalsIgnoreCase("private"))) {
      boolean publicKit = args[0].equalsIgnoreCase("public");
      plugin.store().find(player.getUniqueId(), args[1], publicKit)
          .ifPresentOrElse(kit -> apply(plugin, player, kit), () -> player.sendMessage(Component.text("Kitが見つかりません。")));
      return true;
    }
    if (args.length != 1) {
      player.sendMessage(Component.text("使用法: /kit [public|private] <Kit名>"));
      return true;
    }
    plugin.store().find(player.getUniqueId(), args[0])
        .ifPresentOrElse(kit -> apply(plugin, player, kit), () -> player.sendMessage(Component.text("Kitが見つかりません。")));
    return true;
  }
  static void apply(UniqueKitsPlugin plugin, Player player, Kit kit) {
    if (!plugin.canApplyKit(player)) { player.sendMessage(Component.text("死亡するまでKitを再選択できません。")); return; }
    PlayerInventory inv = player.getInventory(); inv.setStorageContents(new org.bukkit.inventory.ItemStack[36]); inv.setArmorContents(new org.bukkit.inventory.ItemStack[4]); inv.setItemInOffHand(null);
    inv.setStorageContents(kit.storage()); inv.setArmorContents(kit.armor()); inv.setItemInOffHand(kit.offHand()); player.updateInventory(); plugin.lockKitUntilDeath(player); player.sendMessage(Component.text("Kitを適用しました。死亡までKit変更不可です。"));
  }
}
