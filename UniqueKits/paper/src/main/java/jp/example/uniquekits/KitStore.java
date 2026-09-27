package jp.example.uniquekits;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class KitStore {
  private static final String PUBLIC_PREFIX = "public/";
  private static final String PRIVATE_PREFIX = "private/";
  private final UniqueKitsPlugin plugin;

  KitStore(UniqueKitsPlugin plugin) { this.plugin = plugin; }

  public Optional<Location> creatorLocation() {
    return Optional.ofNullable(plugin.getConfig().getLocation("creator-location"));
  }

  public void creatorLocation(Location location) {
    plugin.getConfig().set("creator-location", location);
    plugin.saveConfig();
  }

  public Optional<Kit> find(UUID owner, String name) {
    return find(owner, name, true).or(() -> find(owner, name, false));
  }

  public Optional<Kit> find(UUID owner, String name, boolean publicKit) {
    return Optional.ofNullable(read(path(owner, name, publicKit)));
  }

  public Set<String> publicNames() { return names("public-kits"); }
  public Set<String> privateNames(UUID owner) { return names("private-kits." + owner); }

  public void put(UUID owner, String name, Kit kit, boolean publicKit) {
    write(path(owner, name, publicKit), kit);
    plugin.saveConfig();
    plugin.publishKit(key(owner, name, publicKit), encode(kit));
  }

  public boolean remove(UUID owner, String name, boolean publicKit) {
    String path = path(owner, name, publicKit);
    if (!plugin.getConfig().isConfigurationSection(path)) return false;
    plugin.getConfig().set(path, null);
    plugin.saveConfig();
    plugin.deleteSharedKit(key(owner, name, publicKit));
    return true;
  }

  public Optional<ItemStack> containerTemplate(String key) {
    return Optional.ofNullable(plugin.getConfig().getItemStack("infinite-containers." + key));
  }

  public void containerTemplate(String key, ItemStack item) {
    plugin.getConfig().set("infinite-containers." + key, item);
    plugin.saveConfig();
  }

  Map<String, byte[]> exportAll() {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (String name : publicNames()) find(null, name, true).ifPresent(kit -> result.put(key(null, name, true), encode(kit)));
    ConfigurationSection owners = plugin.getConfig().getConfigurationSection("private-kits");
    if (owners != null) {
      for (String rawOwner : owners.getKeys(false)) {
        try {
          UUID owner = UUID.fromString(rawOwner);
          for (String name : privateNames(owner)) find(owner, name, false).ifPresent(kit -> result.put(key(owner, name, false), encode(kit)));
        } catch (IllegalArgumentException invalidOwner) {
          plugin.getLogger().warning("Ignored invalid private Kit owner: " + rawOwner);
        }
      }
    }
    return result;
  }

  void replaceAll(Map<String, byte[]> entries) {
    YamlConfiguration replacement = new YamlConfiguration();
    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
      String path = pathFromKey(entry.getKey());
      if (path == null) continue;
      try {
        write(replacement, path, decode(entry.getValue()));
      } catch (InvalidConfigurationException | RuntimeException invalid) {
        plugin.getLogger().warning("Ignored invalid synchronized Kit '" + entry.getKey() + "': " + invalid.getMessage());
      }
    }
    plugin.getConfig().set("public-kits", null);
    plugin.getConfig().set("private-kits", null);
    copySection(replacement, "public-kits");
    copySection(replacement, "private-kits");
    plugin.saveConfig();
  }

  void importLegacy(byte[] yaml) throws InvalidConfigurationException {
    YamlConfiguration legacy = new YamlConfiguration();
    legacy.loadFromString(new String(yaml, StandardCharsets.UTF_8));
    plugin.getConfig().set("public-kits", null);
    plugin.getConfig().set("private-kits", null);
    copySection(legacy, "public-kits");
    copySection(legacy, "private-kits");
    plugin.saveConfig();
  }

  static String key(UUID owner, String name, boolean publicKit) {
    return publicKit ? PUBLIC_PREFIX + name : PRIVATE_PREFIX + owner + "/" + name;
  }

  private String path(UUID owner, String name, boolean publicKit) {
    return publicKit ? "public-kits." + name : "private-kits." + owner + "." + name;
  }

  private String pathFromKey(String key) {
    if (key.startsWith(PUBLIC_PREFIX)) {
      String name = key.substring(PUBLIC_PREFIX.length());
      return validName(name) ? "public-kits." + name : null;
    }
    if (!key.startsWith(PRIVATE_PREFIX)) return null;
    String[] parts = key.substring(PRIVATE_PREFIX.length()).split("/", 2);
    if (parts.length != 2 || !validName(parts[1])) return null;
    try {
      return "private-kits." + UUID.fromString(parts[0]) + "." + parts[1];
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private boolean validName(String name) { return name.matches("[A-Za-z0-9_]{1,32}"); }

  private Set<String> names(String path) {
    ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
    return section == null ? Set.of() : new TreeSet<>(section.getKeys(false));
  }

  private Kit read(String path) {
    if (!plugin.getConfig().isConfigurationSection(path)) return null;
    return new Kit(items(plugin.getConfig(), path + ".storage", 36), items(plugin.getConfig(), path + ".armor", 4), plugin.getConfig().getItemStack(path + ".offhand"));
  }

  private byte[] encode(Kit kit) {
    YamlConfiguration yaml = new YamlConfiguration();
    write(yaml, "kit", kit);
    return yaml.saveToString().getBytes(StandardCharsets.UTF_8);
  }

  private Kit decode(byte[] data) throws InvalidConfigurationException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(new String(data, StandardCharsets.UTF_8));
    if (!yaml.isConfigurationSection("kit")) throw new InvalidConfigurationException("Missing kit section");
    return new Kit(items(yaml, "kit.storage", 36), items(yaml, "kit.armor", 4), yaml.getItemStack("kit.offhand"));
  }

  private ItemStack[] items(ConfigurationSection source, String path, int size) {
    ItemStack[] items = new ItemStack[size];
    for (int i = 0; i < size; i++) items[i] = source.getItemStack(path + "." + i);
    return items;
  }

  private void write(String path, Kit kit) { write(plugin.getConfig(), path, kit); }

  private void write(ConfigurationSection target, String path, Kit kit) {
    ItemStack[] storage = kit.storage();
    ItemStack[] armor = kit.armor();
    for (int i = 0; i < 36; i++) target.set(path + ".storage." + i, storage[i]);
    for (int i = 0; i < 4; i++) target.set(path + ".armor." + i, armor[i]);
    target.set(path + ".offhand", kit.offHand());
  }

  private void copySection(YamlConfiguration source, String root) {
    ConfigurationSection section = source.getConfigurationSection(root);
    if (section != null) section.getValues(true).forEach((key, value) -> plugin.getConfig().set(root + "." + key, value));
  }
}
