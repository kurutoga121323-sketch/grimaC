package jp.example.uniquekitsbridge;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Plugin(id = "uniquekits-bridge", name = "UniqueKitsBridge", version = "1.1.2", authors = {"example"})
public final class UniqueKitsBridge {
  private static final MinecraftChannelIdentifier CREATOR_REQUEST = MinecraftChannelIdentifier.from("uniquekits:creator_request");
  private static final MinecraftChannelIdentifier CREATOR_TELEPORT = MinecraftChannelIdentifier.from("uniquekits:creator_teleport");
  private static final MinecraftChannelIdentifier CREATOR_SET = MinecraftChannelIdentifier.from("uniquekits:creator_set");
  private static final MinecraftChannelIdentifier MAIN_REQUEST = MinecraftChannelIdentifier.from("uniquekits:main_request");
  private static final MinecraftChannelIdentifier KITS_SYNC = MinecraftChannelIdentifier.from("uniquekits:kits_sync");

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
  private static final int STORE_MAGIC = 0x554B5332;
  private static final long BOOTSTRAP_WINDOW_MILLIS = 60_000L;
  private static final int MAX_ENTRY_BYTES = 1_000_000;
  private static final int MAX_KITS = 10_000;

  private final ProxyServer proxy;
  private final Logger logger = Logger.getLogger(UniqueKitsBridge.class.getName());
  private final Path dataDirectory;
  private final Map<String, byte[]> sharedKits = new LinkedHashMap<>();
  private final Map<String, ChunkAccumulator> incomingChunks = new LinkedHashMap<>();
  private volatile String creatorServer = "kitcreator";
  private byte[] legacySnapshot;
  private boolean initialized;
  private long bootstrapDeadline;

  @Inject
  public UniqueKitsBridge(ProxyServer proxy, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.dataDirectory = dataDirectory;
    load();
  }

  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    proxy.getChannelRegistrar().register(CREATOR_REQUEST, CREATOR_TELEPORT, CREATOR_SET, MAIN_REQUEST, KITS_SYNC);
  }

  @Subscribe
  public void messages(PluginMessageEvent event) {
    if (event.getIdentifier().equals(CREATOR_SET)) {
      event.setResult(PluginMessageEvent.ForwardResult.handled());
      if (event.getSource() instanceof ServerConnection source) {
        creatorServer = source.getServerInfo().getName();
        saveCreator();
      }
      return;
    }
    if (event.getIdentifier().equals(CREATOR_REQUEST)) {
      event.setResult(PluginMessageEvent.ForwardResult.handled());
      if (event.getSource() instanceof ServerConnection source) creatorRequest(source, event.getData());
      return;
    }
    if (event.getIdentifier().equals(MAIN_REQUEST)) {
      event.setResult(PluginMessageEvent.ForwardResult.handled());
      if (event.getSource() instanceof ServerConnection source) mainRequest(source, event.getData());
      return;
    }
    if (event.getIdentifier().equals(KITS_SYNC)) {
      event.setResult(PluginMessageEvent.ForwardResult.handled());
      if (event.getSource() instanceof ServerConnection source) sync(source, event.getData());
    }
  }

  private void creatorRequest(ServerConnection source, byte[] data) {
    try {
      UUID id = UUID.fromString(new String(data, StandardCharsets.UTF_8));
      Player player = proxy.getPlayer(id).orElse(null);
      RegisteredServer target = proxy.getServer(creatorServer).orElse(null);
      if (player == null || !source.getPlayer().getUniqueId().equals(id)) {
        logger.warning("Rejected a creator request whose player did not match its backend connection.");
        return;
      }
      if (target == null) {
        player.sendMessage(net.kyori.adventure.text.Component.text("Kit Creatorサーバー '" + creatorServer + "' が見つかりません。"));
        logger.warning("Creator backend is unavailable: " + creatorServer);
        return;
      }
      player.createConnectionRequest(target).connect().thenAccept(result -> {
        if (!result.isSuccessful()) {
          player.sendMessage(net.kyori.adventure.text.Component.text("Kit Creatorサーバーへ移動できませんでした: " + result.getStatus()));
          logger.warning("Creator connection failed for " + player.getUsername() + ": " + result.getStatus());
          return;
        }
        player.getCurrentServer().ifPresentOrElse(
            connection -> {
              boolean sent = connection.sendPluginMessage(CREATOR_TELEPORT, id.toString().getBytes(StandardCharsets.UTF_8));
              if (!sent) player.sendMessage(net.kyori.adventure.text.Component.text("移動先へのテレポート通知を送れませんでした。"));
            },
            () -> player.sendMessage(net.kyori.adventure.text.Component.text("移動先サーバーとの接続を確認できませんでした。")));
      }).exceptionally(error -> {
        logger.warning("Creator connection failed for " + player.getUsername() + ": " + error.getMessage());
        player.sendMessage(net.kyori.adventure.text.Component.text("Kit Creatorサーバーへの接続中にエラーが発生しました。"));
        return null;
      });
    } catch (IllegalArgumentException invalid) {
      logger.warning("Invalid creator request.");
    }
  }

  private void mainRequest(ServerConnection source, byte[] data) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      UUID id = UUID.fromString(in.readUTF());
      String serverName = in.readUTF();
      if (!serverName.matches("[A-Za-z0-9_.-]{1,64}")) throw new IOException("Invalid server name");
      Player player = proxy.getPlayer(id).orElse(null);
      if (player == null || !source.getPlayer().getUniqueId().equals(id)) {
        logger.warning("Rejected a main server request whose player did not match its backend connection.");
        return;
      }
      RegisteredServer target = proxy.getServer(serverName).orElse(null);
      if (target == null) {
        player.sendMessage(net.kyori.adventure.text.Component.text("戻り先サーバー '" + serverName + "' が見つかりません。"));
        logger.warning("Main backend is unavailable: " + serverName);
        return;
      }
      player.createConnectionRequest(target).connect().thenAccept(result -> {
        if (!result.isSuccessful()) {
          player.sendMessage(net.kyori.adventure.text.Component.text("戻り先サーバーへ移動できませんでした: " + result.getStatus()));
          logger.warning("Main connection failed for " + player.getUsername() + ": " + result.getStatus());
        }
      }).exceptionally(error -> {
        logger.warning("Main connection failed for " + player.getUsername() + ": " + error.getMessage());
        player.sendMessage(net.kyori.adventure.text.Component.text("戻り先サーバーへの接続中にエラーが発生しました。"));
        return null;
      });
    } catch (Exception invalid) {
      logger.warning("Invalid main server request: " + invalid.getMessage());
    }
  }

  private synchronized void sync(ServerConnection source, byte[] data) {
    if (data.length == 0) return;
    if (data[0] == CHUNK) {
      data = acceptChunk(source, data);
      if (data == null) return;
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      finishBootstrapIfDue();
      byte type = in.readByte();
      if (type == REQUEST_SNAPSHOT) {
        if (legacySnapshot != null) send(source, LEGACY_SNAPSHOT, out -> out.write(legacySnapshot));
        else if (!initialized) send(source, BOOTSTRAP_REQUEST, out -> { });
        else sendSnapshot(source);
      } else if (type == UPSERT_KIT) {
        String key = readKey(in);
        int length = in.readInt();
        if (length < 0 || length > MAX_ENTRY_BYTES || length > in.available()) throw new IOException("Invalid Kit payload size");
        sharedKits.put(key, in.readNBytes(length));
        saveKits();
      } else if (type == DELETE_KIT) {
        String key = readKey(in);
        sharedKits.remove(key);
        saveKits();
      } else if (type == BOOTSTRAP_COMPLETE) {
        legacySnapshot = null;
        if (!initialized && bootstrapDeadline == 0L) bootstrapDeadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MILLIS;
        saveKits();
      }
    } catch (Exception invalid) {
      logger.warning("Ignored invalid Kit synchronization message from " + source.getServerInfo().getName() + ": " + invalid.getMessage());
    }
  }

  private void finishBootstrapIfDue() throws IOException {
    if (!initialized && bootstrapDeadline > 0L && System.currentTimeMillis() >= bootstrapDeadline) {
      initialized = true;
      bootstrapDeadline = 0L;
      saveKits();
      logger.info("UniqueKits bootstrap completed with " + sharedKits.size() + " Kits.");
    }
  }

  private String readKey(DataInputStream in) throws IOException {
    String key = in.readUTF();
    if (!(key.matches("public/[A-Za-z0-9_]{1,32}") || key.matches("private/[0-9a-fA-F-]{36}/[A-Za-z0-9_]{1,32}"))) {
      throw new IOException("Invalid Kit key");
    }
    return key;
  }

  private void sendSnapshot(ServerConnection source) throws IOException {
    send(source, SNAPSHOT, out -> {
      out.writeInt(sharedKits.size());
      for (Map.Entry<String, byte[]> entry : sharedKits.entrySet()) {
        out.writeUTF(entry.getKey());
        out.writeInt(entry.getValue().length);
        out.write(entry.getValue());
      }
    });
  }

  private void send(ServerConnection source, byte type, PacketWriter writer) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(type);
      writer.write(out);
    }
    sendSyncPayload(source, bytes.toByteArray());
  }

  private void sendSyncPayload(ServerConnection source, byte[] payload) throws IOException {
    if (payload.length <= MAX_PLUGIN_MESSAGE_BYTES) {
      if (!source.sendPluginMessage(KITS_SYNC, payload)) {
        logger.warning("Could not send Kit synchronization data to " + source.getServerInfo().getName());
      }
      return;
    }
    String transferId = UUID.randomUUID().toString();
    int totalChunks = (payload.length + CHUNK_DATA_BYTES - 1) / CHUNK_DATA_BYTES;
    for (int index = 0; index < totalChunks; index++) {
      int offset = index * CHUNK_DATA_BYTES;
      int length = Math.min(CHUNK_DATA_BYTES, payload.length - offset);
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
      if (!source.sendPluginMessage(KITS_SYNC, chunk)) {
        logger.warning("Could not send chunked Kit synchronization data to " + source.getServerInfo().getName());
        return;
      }
    }
  }

  private byte[] acceptChunk(ServerConnection source, byte[] data) {
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
      String key = source.getServerInfo().getName() + ":" + transferId;
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
      logger.warning("Ignored invalid chunked Kit synchronization message from " + source.getServerInfo().getName() + ": " + invalid.getMessage());
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

  private void load() {
    try {
      Path serverFile = dataDirectory.resolve("creator-server.txt");
      if (Files.exists(serverFile)) {
        String saved = Files.readString(serverFile, StandardCharsets.UTF_8).trim();
        if (!saved.isBlank()) creatorServer = saved;
      }
      Path current = dataDirectory.resolve("kits-v2.bin");
      if (Files.exists(current)) {
        readStore(current);
        if (!initialized) bootstrapDeadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MILLIS;
      } else {
        Path legacy = dataDirectory.resolve("shared-kits.bin");
        if (!Files.exists(legacy)) legacy = Path.of("plugins", "UniqueKitsBridge", "shared-kits.bin");
        if (Files.exists(legacy)) legacySnapshot = Files.readAllBytes(legacy);
      }
    } catch (Exception error) {
      logger.warning("Could not load UniqueKits bridge data: " + error.getMessage());
    }
  }

  private void readStore(Path file) throws IOException {
    try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
      if (in.readInt() != STORE_MAGIC) throw new IOException("Unknown Kit store format");
      initialized = in.readBoolean();
      int count = in.readInt();
      if (count < 0 || count > MAX_KITS) throw new IOException("Invalid Kit count: " + count);
      for (int index = 0; index < count; index++) {
        String key = readKey(in);
        int length = in.readInt();
        if (length < 0 || length > MAX_ENTRY_BYTES) throw new IOException("Invalid Kit payload size");
        byte[] value = in.readNBytes(length);
        if (value.length != length) throw new IOException("Truncated Kit payload");
        sharedKits.put(key, value);
      }
    }
  }

  private void saveCreator() {
    try {
      Files.createDirectories(dataDirectory);
      Files.writeString(dataDirectory.resolve("creator-server.txt"), creatorServer, StandardCharsets.UTF_8);
    } catch (Exception error) {
      logger.warning("Could not save creator server: " + error.getMessage());
    }
  }

  private void saveKits() throws IOException {
    Files.createDirectories(dataDirectory);
    Path target = dataDirectory.resolve("kits-v2.bin");
    Path temporary = dataDirectory.resolve("kits-v2.bin.tmp");
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temporary))) {
      out.writeInt(STORE_MAGIC);
      out.writeBoolean(initialized);
      out.writeInt(sharedKits.size());
      for (Map.Entry<String, byte[]> entry : sharedKits.entrySet()) {
        out.writeUTF(entry.getKey());
        out.writeInt(entry.getValue().length);
        out.write(entry.getValue());
      }
    }
    try {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @FunctionalInterface
  private interface PacketWriter { void write(DataOutputStream out) throws IOException; }
}
