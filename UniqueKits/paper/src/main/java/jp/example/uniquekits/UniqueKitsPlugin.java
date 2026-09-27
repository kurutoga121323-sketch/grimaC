package jp.example.uniquekits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.projectiles.ProjectileSource;

public final class UniqueKitsPlugin extends JavaPlugin implements PluginMessageListener {
  static final String CREATOR_REQUEST_CHANNEL = "uniquekits:creator_request";
  static final String CREATOR_TELEPORT_CHANNEL = "uniquekits:creator_teleport";
  static final String CREATOR_SET_CHANNEL = "uniquekits:creator_set";
  static final String MAIN_REQUEST_CHANNEL = "uniquekits:main_request";
  static final String KITS_SYNC_CHANNEL = "uniquekits:kits_sync";

  private static final byte REQUEST_SNAPSHOT = 0;
  private static final byte UPSERT_KIT = 1;
  private static final byte DELETE_KIT = 2;
  private static final byte SNAPSHOT = 3;
  private static final byte LEGACY_SNAPSHOT = 4;
  private static final byte BOOTSTRAP_REQUEST = 5;
  private static final byte BOOTSTRAP_COMPLETE = 6;
  private static final byte CHUNK = 7;
  private static final int MAX_PLUGIN_MESSAGE_BYTES = 30_000;
  private static final int CHUNK_DATA_BYTES = 28_000;
  private static final int MAX_SYNC_PACKET_BYTES = 2_000_000;
  private static final long CHUNK_TTL_MILLIS = 60_000L;
  private static final int MAX_ENTRY_BYTES = 1_000_000;
  private static final int MAX_KITS = 10_000;

  private KitStore store;
  private NamespacedKey actionKey;
  private NamespacedKey nameKey;
  private final Set<UUID> kitLocked = new java.util.HashSet<>();
  private final Map<String, ChunkAccumulator> incomingChunks = new HashMap<>();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    store = new KitStore(this);
    actionKey = new NamespacedKey(this, "action");
    nameKey = new NamespacedKey(this, "kit_name");
    KitCommand commands = new KitCommand(this);
    for (String name : Set.of("kit", "kitcreator", "setkitcreator", "createukit", "deleteukit")) {
      Objects.requireNonNull(getCommand(name), "Missing command in plugin.yml: " + name).setExecutor(commands);
    }
    getServer().getPluginManager().registerEvents(new KitListener(this), this);
    getServer().getPluginManager().registerEvents(new CombatShulkerLimit(), this);
    getServer().getMessenger().registerOutgoingPluginChannel(this, CREATOR_REQUEST_CHANNEL);
    getServer().getMessenger().registerOutgoingPluginChannel(this, CREATOR_SET_CHANNEL);
    getServer().getMessenger().registerOutgoingPluginChannel(this, MAIN_REQUEST_CHANNEL);
    getServer().getMessenger().registerOutgoingPluginChannel(this, KITS_SYNC_CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this, CREATOR_TELEPORT_CHANNEL, this);
    getServer().getMessenger().registerIncomingPluginChannel(this, KITS_SYNC_CHANNEL, this);
    getServer().getScheduler().runTaskTimer(this, this::requestSharedKits, 40L, 600L);
  }

  @Override
  public void onDisable() {
    getServer().getMessenger().unregisterIncomingPluginChannel(this);
    getServer().getMessenger().unregisterOutgoingPluginChannel(this);
  }

  public KitStore store() { return store; }
  public NamespacedKey actionKey() { return actionKey; }
  public NamespacedKey nameKey() { return nameKey; }
  public boolean canApplyKit(Player player) { return !kitLocked.contains(player.getUniqueId()); }
  public void lockKitUntilDeath(Player player) { kitLocked.add(player.getUniqueId()); }

  public void clearKitLock(Player player) { kitLocked.remove(player.getUniqueId()); }

  public boolean isCreatorServer() {
    String server = getConfig().getString("server-name", "");
    return !server.isBlank() && server.equalsIgnoreCase(creatorServer());
  }

  public String creatorServer() { return getConfig().getString("creator-server", "kitcreator"); }
  public String mainServer() { return getConfig().getString("main-server", "main"); }

  public void configureCurrentServerAsCreator(Player player) {
    getConfig().set("server-name", creatorServer());
    saveConfig();
    player.sendPluginMessage(this, CREATOR_SET_CHANNEL, new byte[0]);
  }

  public void requestCreatorTeleport(Player player) {
    player.sendPluginMessage(this, CREATOR_REQUEST_CHANNEL, player.getUniqueId().toString().getBytes(StandardCharsets.UTF_8));
  }

  public void requestMainServer(Player player) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeUTF(player.getUniqueId().toString());
        out.writeUTF(mainServer());
      }
      player.sendPluginMessage(this, MAIN_REQUEST_CHANNEL, bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Could not encode main server request", impossible);
    }
  }

  void publishKit(String key, byte[] value) {
    sendSharedPacket(UPSERT_KIT, out -> {
      out.writeUTF(key);
      out.writeInt(value.length);
      out.write(value);
    });
  }

  void deleteSharedKit(String key) {
    sendSharedPacket(DELETE_KIT, out -> out.writeUTF(key));
  }

  private void requestSharedKits() { sendSharedPacket(REQUEST_SNAPSHOT, out -> { }); }

  private void publishAllKits() {
    for (Map.Entry<String, byte[]> entry : store.exportAll().entrySet()) publishKit(entry.getKey(), entry.getValue());
    sendSharedPacket(BOOTSTRAP_COMPLETE, out -> { });
  }

  private void sendSharedPacket(byte type, PacketWriter writer) {
    Player carrier = getServer().getOnlinePlayers().stream().findFirst().orElse(null);
    if (carrier == null) return;
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        out.writeByte(type);
        writer.write(out);
      }
      sendSyncPayload(carrier, bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Could not encode Kit synchronization packet", impossible);
    }
  }

  private void sendSyncPayload(Player carrier, byte[] payload) {
    if (payload.length <= MAX_PLUGIN_MESSAGE_BYTES) {
      carrier.sendPluginMessage(this, KITS_SYNC_CHANNEL, payload);
      return;
    }
    String transferId = UUID.randomUUID().toString();
    int totalChunks = (payload.length + CHUNK_DATA_BYTES - 1) / CHUNK_DATA_BYTES;
    for (int index = 0; index < totalChunks; index++) {
      int offset = index * CHUNK_DATA_BYTES;
      int length = Math.min(CHUNK_DATA_BYTES, payload.length - offset);
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
          out.writeByte(CHUNK);
          out.writeUTF(transferId);
          out.writeInt(totalChunks);
          out.writeInt(index);
          out.writeInt(payload.length);
          out.writeInt(length);
          out.write(payload, offset, length);
        }
        byte[] chunk = bytes.toByteArray();
        if (chunk.length > MAX_PLUGIN_MESSAGE_BYTES) throw new IOException("Chunk is too large: " + chunk.length);
        carrier.sendPluginMessage(this, KITS_SYNC_CHANNEL, chunk);
      } catch (IOException impossible) {
        throw new IllegalStateException("Could not encode chunked Kit synchronization packet", impossible);
      }
    }
  }

  private byte[] acceptChunk(Player player, byte[] data) {
    cleanupChunks();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      if (in.readByte() != CHUNK) return data;
      String transferId = in.readUTF();
      int totalChunks = in.readInt();
      int index = in.readInt();
      int totalLength = in.readInt();
      int chunkLength = in.readInt();
      if (totalChunks <= 0 || totalChunks > 1024 || index < 0 || index >= totalChunks) throw new IOException("Invalid chunk index");
      if (totalLength < 0 || totalLength > MAX_SYNC_PACKET_BYTES) throw new IOException("Invalid chunked payload size");
      if (chunkLength < 0 || chunkLength > CHUNK_DATA_BYTES || chunkLength > in.available()) throw new IOException("Invalid chunk size");
      byte[] chunk = in.readNBytes(chunkLength);
      String key = player.getUniqueId() + ":" + transferId;
      ChunkAccumulator accumulator = incomingChunks.get(key);
      if (accumulator == null || accumulator.totalChunks != totalChunks || accumulator.totalLength != totalLength) {
        accumulator = new ChunkAccumulator(totalChunks, totalLength);
        incomingChunks.put(key, accumulator);
      }
      if (accumulator.chunks[index] == null) {
        accumulator.chunks[index] = chunk;
        accumulator.receivedChunks++;
        accumulator.receivedBytes += chunk.length;
      }
      if (accumulator.receivedChunks != totalChunks) return null;
      if (accumulator.receivedBytes != totalLength) throw new IOException("Chunked payload size mismatch");
      ByteArrayOutputStream full = new ByteArrayOutputStream(totalLength);
      for (byte[] part : accumulator.chunks) full.write(part);
      incomingChunks.remove(key);
      return full.toByteArray();
    } catch (Exception invalid) {
      getLogger().warning("Ignored invalid chunked Kit synchronization packet: " + invalid.getMessage());
      return null;
    }
  }

  private void cleanupChunks() {
    long now = System.currentTimeMillis();
    incomingChunks.entrySet().removeIf(entry -> now - entry.getValue().createdAt > CHUNK_TTL_MILLIS);
  }

  private static final class ChunkAccumulator {
    private final long createdAt = System.currentTimeMillis();
    private final int totalChunks;
    private final int totalLength;
    private final byte[][] chunks;
    private int receivedChunks;
    private int receivedBytes;

    private ChunkAccumulator(int totalChunks, int totalLength) {
      this.totalChunks = totalChunks;
      this.totalLength = totalLength;
      this.chunks = new byte[totalChunks][];
    }
  }

  @Override
  public void onPluginMessageReceived(String channel, Player player, byte[] data) {
    if (KITS_SYNC_CHANNEL.equals(channel)) {
      handleSharedPacket(player, data);
      return;
    }
    if (!CREATOR_TELEPORT_CHANNEL.equals(channel)) return;
    try {
      UUID playerId = UUID.fromString(new String(data, StandardCharsets.UTF_8));
      if (!playerId.equals(player.getUniqueId())) return;
      store.creatorLocation().ifPresentOrElse(player::teleportAsync,
          () -> player.sendMessage("Kit Creatorの移動先が設定されていません。"));
    } catch (IllegalArgumentException invalid) {
      getLogger().warning("Ignored invalid creator teleport message: " + invalid.getMessage());
    }
  }

  private void handleSharedPacket(Player player, byte[] data) {
    if (data.length == 0) return;
    if (data[0] == CHUNK) {
      data = acceptChunk(player, data);
      if (data == null) return;
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      byte type = in.readByte();
      if (type == SNAPSHOT) {
        int count = in.readInt();
        if (count < 0 || count > MAX_KITS) throw new IOException("Invalid Kit count: " + count);
        Map<String, byte[]> entries = new HashMap<>();
        for (int index = 0; index < count; index++) {
          String key = in.readUTF();
          int length = in.readInt();
          if (length < 0 || length > MAX_ENTRY_BYTES || length > in.available()) throw new IOException("Invalid Kit payload size");
          entries.put(key, in.readNBytes(length));
        }
        store.replaceAll(entries);
      } else if (type == LEGACY_SNAPSHOT) {
        store.importLegacy(in.readAllBytes());
        publishAllKits();
      } else if (type == BOOTSTRAP_REQUEST) {
        publishAllKits();
      }
    } catch (Exception invalid) {
      getLogger().warning("Ignored invalid Kit synchronization packet: " + invalid.getMessage());
    }
  }

  @FunctionalInterface
  private interface PacketWriter { void write(DataOutputStream out) throws IOException; }

  private final class CombatShulkerLimit implements Listener {
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
      Player attacker = playerSource(event.getDamager());
      if (attacker == null || shulkerCount(attacker) <= 1) return;
      event.setCancelled(true);
      long now = System.currentTimeMillis();
      if (now - lastWarning.getOrDefault(attacker.getUniqueId(), 0L) >= 2_000L) {
        attacker.sendMessage("戦闘に持ち込めるシュルカーボックスは1個までです。余分な分を保管してから攻撃してください。");
        lastWarning.put(attacker.getUniqueId(), now);
      }
    }

    private Player playerSource(org.bukkit.entity.Entity damager) {
      if (damager instanceof Player player) return player;
      if (damager instanceof Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Player player) return player;
      }
      return null;
    }

    private int shulkerCount(Player player) {
      int total = 0;
      for (ItemStack item : player.getInventory().getContents()) {
        if (item != null && item.getType().name().endsWith("SHULKER_BOX")) total += item.getAmount();
      }
      return total;
    }
  }
}
