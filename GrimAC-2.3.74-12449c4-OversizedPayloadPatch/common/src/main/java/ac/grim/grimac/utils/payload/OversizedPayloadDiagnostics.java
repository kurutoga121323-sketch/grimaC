package ac.grim.grimac.utils.payload;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OversizedPayloadDiagnostics {
    public static final String OVERSIZED_PAYLOAD_MESSAGE = "Payload may not be larger than 32767 bytes";
    private static final long DUPLICATE_WINDOW_MS = 100;
    private static final long CACHE_TTL_MS = 1_000;
    private static final Map<String, Long> RECENT_LOGS = new ConcurrentHashMap<>();

    private OversizedPayloadDiagnostics() {
    }

    public static boolean isOversizedPluginMessage(RuntimeException ex) {
        return OVERSIZED_PAYLOAD_MESSAGE.equals(ex.getMessage());
    }

    public static void handleOversized(PacketReceiveEvent event, String listenerName) {
        Diagnostic diagnostic = readDiagnostic(event, listenerName);
        if (isDebugEnabled() && shouldLog(diagnostic)) {
            log(diagnostic);
        }
        if (isCancelEnabled() && diagnostic.hasReliableSize()) {
            event.setCancelled(true);
        }
    }

    private static Diagnostic readDiagnostic(PacketReceiveEvent event, String listenerName) {
        Diagnostic diagnostic = new Diagnostic(listenerName);
        try {
            User user = event.getUser();
            if (user != null) {
                diagnostic.player = safeString(user.getName());
                UUID uuid = user.getUUID();
                diagnostic.uuid = uuid == null ? "<unknown>" : uuid.toString();
            }
            diagnostic.state = stateName(event.getConnectionState());
            diagnostic.packet = packetName(event);
            diagnostic.clientVersion = safeString(event.getClientVersion());
            diagnostic.serverVersion = safeString(event.getServerVersion());

            Object buffer = event.getByteBuf();
            if (buffer == null) return diagnostic;

            Object duplicate = ByteBufHelper.duplicate(buffer);
            diagnostic.rawSize = ByteBufHelper.readableBytes(duplicate);
            diagnostic.reliableRawSize = true;

            String channel = readIdentifier(duplicate);
            if (channel != null) {
                diagnostic.channel = channel;
                diagnostic.payloadSize = ByteBufHelper.readableBytes(duplicate);
                diagnostic.reliablePayloadSize = true;
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must not interfere with normal packet handling.
        }
        return diagnostic;
    }

    private static String readIdentifier(Object buffer) {
        int length = ByteBufHelper.readVarInt(buffer);
        if (length < 0 || length > 32767 || ByteBufHelper.readableBytes(buffer) < length) {
            return null;
        }
        byte[] bytes = new byte[length];
        ByteBufHelper.readBytes(buffer, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean shouldLog(Diagnostic diagnostic) {
        long now = System.currentTimeMillis();
        RECENT_LOGS.entrySet().removeIf(entry -> now - entry.getValue() > CACHE_TTL_MS);
        String key = diagnostic.uuid + '|' + diagnostic.rawSize + '|' + diagnostic.payloadSize + '|' + diagnostic.channel;
        Long previous = RECENT_LOGS.put(key, now);
        return previous == null || now - previous > DUPLICATE_WINDOW_MS;
    }

    private static void log(Diagnostic diagnostic) {
        LogUtil.warn("[Grim OversizedPayload]\n"
                + "player=" + diagnostic.player + '\n'
                + "uuid=" + diagnostic.uuid + '\n'
                + "state=" + diagnostic.state + '\n'
                + "packet=" + diagnostic.packet + '\n'
                + "channel=" + diagnostic.channel + '\n'
                + "rawSize=" + sizeString(diagnostic.rawSize) + '\n'
                + "payloadSize=" + sizeString(diagnostic.payloadSize) + '\n'
                + "clientVersion=" + diagnostic.clientVersion + '\n'
                + "serverVersion=" + diagnostic.serverVersion + '\n'
                + "timestamp=" + Instant.ofEpochMilli(diagnostic.timestamp) + '\n'
                + "listener=" + diagnostic.listenerName);
    }

    private static boolean isDebugEnabled() {
        try {
            return GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("debug-oversized-plugin-message", true);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isCancelEnabled() {
        try {
            return GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("cancel-oversized-plugin-message", false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String stateName(ConnectionState state) {
        if (state == null) return "<unknown>";
        String name = state.name();
        if ("CONFIGURATION".equals(name) || "PLAY".equals(name)) return name;
        return name;
    }

    private static String packetName(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE || event.getPacketType() == PacketType.Configuration.Client.PLUGIN_MESSAGE) {
            return "PLUGIN_MESSAGE";
        }
        return safeString(event.getPacketType());
    }

    private static String safeString(Object value) {
        return value == null ? "<unknown>" : value.toString();
    }

    private static String sizeString(int size) {
        return size < 0 ? "<unavailable>" : Integer.toString(size);
    }

    private static final class Diagnostic {
        private final long timestamp = System.currentTimeMillis();
        private final String listenerName;
        private String player = "<unknown>";
        private String uuid = "<unknown>";
        private String state = "<unknown>";
        private String packet = "PLUGIN_MESSAGE";
        private String channel = "<unavailable>";
        private int rawSize = -1;
        private int payloadSize = -1;
        private String clientVersion = "<unknown>";
        private String serverVersion = "<unknown>";
        private boolean reliableRawSize;
        private boolean reliablePayloadSize;

        private Diagnostic(String listenerName) {
            this.listenerName = listenerName;
        }

        private boolean hasReliableSize() {
            return reliableRawSize && reliablePayloadSize && rawSize > 32767 && payloadSize > 32767;
        }
    }
}
