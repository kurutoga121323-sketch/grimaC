package jp.example.uniquekits;

import org.bukkit.inventory.ItemStack;

public record Kit(ItemStack[] storage, ItemStack[] armor, ItemStack offHand) {
  public Kit { storage = copy(storage); armor = copy(armor); offHand = offHand == null ? null : offHand.clone(); }
  @Override public ItemStack[] storage() { return copy(storage); }
  @Override public ItemStack[] armor() { return copy(armor); }
  @Override public ItemStack offHand() { return offHand == null ? null : offHand.clone(); }
  private static ItemStack[] copy(ItemStack[] source) { ItemStack[] result = new ItemStack[source.length]; for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? null : source[i].clone(); return result; }
}
