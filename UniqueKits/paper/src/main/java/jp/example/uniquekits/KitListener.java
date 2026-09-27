package jp.example.uniquekits;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class KitListener implements Listener {
  private final UniqueKitsPlugin plugin;

  KitListener(UniqueKitsPlugin plugin) { this.plugin = plugin; }

  @EventHandler
  public void menuClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)) return;
    ItemStack item = event.getCurrentItem();
    if (item == null || !item.hasItemMeta()) return;
    String action = item.getItemMeta().getPersistentDataContainer().get(plugin.actionKey(), PersistentDataType.STRING);
    if ("public".equals(action)) {
      openList(player, true);
      return;
    }
    if ("private".equals(action)) {
      openList(player, false);
      return;
    }
    String name = item.getItemMeta().getPersistentDataContainer().get(plugin.nameKey(), PersistentDataType.STRING);
    if (name == null) return;
    boolean publicKit = "public".equals(menu.type());
    player.closeInventory();
    plugin.store().find(player.getUniqueId(), name, publicKit)
        .ifPresentOrElse(kit -> KitCommand.apply(plugin, player, kit), () -> player.sendMessage(Component.text("Kitが見つかりません。")));
  }

  @EventHandler
  public void playerDeath(PlayerDeathEvent event) { plugin.clearKitLock(event.getPlayer()); }

  @EventHandler
  public void playerRespawn(PlayerRespawnEvent event) {
    if (!plugin.isCreatorServer()) return;
    Player player = event.getPlayer();
    Bukkit.getScheduler().runTask(plugin, () -> plugin.requestMainServer(player));
  }

  @EventHandler(ignoreCancelled = true)
  public void chooseContainer(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
    Player player = event.getPlayer();
    if (player.getInventory().getItemInMainHand().getType() != Material.WOODEN_PICKAXE) return;
    Block block = event.getClickedBlock();
    if (!(block.getState() instanceof Chest) && !(block.getState() instanceof Barrel)) return;
    if (!player.hasPermission("uniquekits.admin")) {
      player.sendMessage(Component.text("無限コンテナを設定する権限がありません。"));
      return;
    }
    if (plugin.store().creatorLocation().isEmpty()) {
      player.sendMessage(Component.text("先に /setkitcreator で作成場所を設定してください。"));
      return;
    }
    if (!atCreatorWorld(block.getWorld())) {
      player.sendMessage(Component.text("Kit Creatorワールド内で設定してください。"));
      return;
    }
    event.setCancelled(true);
    plugin.store().containerTemplate(key(block), new ItemStack(Material.AIR));
    player.sendMessage(Component.text("無限コンテナとして登録しました。中に補充するアイテムを1種類入れてください。"));
  }

  @EventHandler(ignoreCancelled = true)
  public void containerChanged(InventoryClickEvent event) {
    scheduleRefill(event.getClickedInventory());
    scheduleRefill(event.getView().getTopInventory());
  }

  @EventHandler(ignoreCancelled = true)
  public void containerDragged(InventoryDragEvent event) {
    scheduleRefill(event.getView().getTopInventory());
  }

  @EventHandler
  public void containerClosed(InventoryCloseEvent event) { scheduleRefill(event.getInventory()); }

  void openRoot(Player player) {
    Inventory inventory = Bukkit.createInventory(new Menu("root"), 9, Component.text("Kit"));
    button(inventory, 1, Material.CHEST, "public", "Public Kit");
    button(inventory, 7, Material.ENDER_CHEST, "private", "Private Kit");
    player.openInventory(inventory);
  }

  void openList(Player player, boolean publicKit) {
    Inventory inventory = Bukkit.createInventory(new Menu(publicKit ? "public" : "private"), 54,
        Component.text(publicKit ? "Public Kit" : "Private Kit"));
    int slot = 0;
    for (String name : publicKit ? plugin.store().publicNames() : plugin.store().privateNames(player.getUniqueId())) {
      if (slot >= inventory.getSize()) break;
      ItemStack icon = new ItemStack(Material.NAME_TAG);
      ItemMeta meta = icon.getItemMeta();
      meta.displayName(Component.text(name));
      meta.getPersistentDataContainer().set(plugin.nameKey(), PersistentDataType.STRING, name);
      icon.setItemMeta(meta);
      inventory.setItem(slot++, icon);
    }
    player.openInventory(inventory);
  }

  private void scheduleRefill(Inventory inventory) {
    if (inventory == null || (!(inventory.getHolder() instanceof Chest) && !(inventory.getHolder() instanceof Barrel))) return;
    if (inventory.getLocation() == null) return;
    String key = key(inventory.getLocation().getBlock());
    if (plugin.store().containerTemplate(key).isEmpty()) return;
    Bukkit.getScheduler().runTask(plugin, () -> refill(inventory, key));
  }

  private void refill(Inventory inventory, String key) {
    ItemStack template = plugin.store().containerTemplate(key).orElse(null);
    if (template == null || template.getType().isAir()) {
      template = findTemplate(inventory);
      if (template == null) return;
      ItemStack saved = template.clone();
      saved.setAmount(1);
      plugin.store().containerTemplate(key, saved);
    }
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack current = inventory.getItem(slot);
      if (current != null && !current.getType().isAir() && !current.isSimilar(template)) continue;
      ItemStack full = template.clone();
      full.setAmount(full.getMaxStackSize());
      inventory.setItem(slot, full);
    }
  }

  private ItemStack findTemplate(Inventory inventory) {
    ItemStack candidate = null;
    for (ItemStack item : inventory.getContents()) {
      if (item == null || item.getType().isAir()) continue;
      if (candidate == null) candidate = item;
      else if (!candidate.isSimilar(item)) return null;
    }
    return candidate;
  }

  private boolean atCreatorWorld(World world) {
    return plugin.store().creatorLocation().map(location -> location.getWorld() != null && location.getWorld().getUID().equals(world.getUID())).orElse(false);
  }

  private String key(Block block) {
    return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
  }

  private void button(Inventory inventory, int slot, Material material, String action, String title) {
    ItemStack icon = new ItemStack(material);
    ItemMeta meta = icon.getItemMeta();
    meta.displayName(Component.text(title));
    meta.getPersistentDataContainer().set(plugin.actionKey(), PersistentDataType.STRING, action);
    icon.setItemMeta(meta);
    inventory.setItem(slot, icon);
  }

  record Menu(String type) implements InventoryHolder {
    @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
  }
}
