package jp.example.grimversion;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Plugin(id = "grimversion", name = "GrimVersion", version = "1.0.0", authors = {"sushi"})
public final class GrimVersion {
    private static final MinecraftChannelIdentifier BRAND_CHANNEL = MinecraftChannelIdentifier.from("minecraft:brand");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configPath;
    private volatile Settings settings = Settings.defaults();

    @Inject
    public GrimVersion(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configPath = dataDirectory.resolve("config.toml");
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            reloadConfig();
        } catch (Exception e) {
            logger.error("[GrimVersion] Failed to load config.toml. Using defaults.", e);
            settings = Settings.defaults();
        }

        proxy.getChannelRegistrar().register(BRAND_CHANNEL);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("grimversion").plugin(this).build();
        proxy.getCommandManager().register(meta, new GrimVersionCommand());

        logger.info("[GrimVersion] Enabled");
        logger.info("[GrimVersion] F3 Brand: {}", settings.plainBrand());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        Settings current = settings;
        if (!current.enabled() || !BRAND_CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        if (event.getSource() instanceof ServerConnection && event.getTarget() instanceof Player player) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            sendBrand(player, current);
            sendBrandLater(player, 250L);
            sendBrandLater(player, 1000L);
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        sendBrand(player, settings);
        sendBrandLater(player, 250L);
        sendBrandLater(player, 1000L);
    }

    private void reloadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        if (Files.notExists(configPath)) {
            Files.writeString(configPath, defaultConfig(), StandardCharsets.UTF_8);
        }

        try (CommentedFileConfig config = CommentedFileConfig.builder(configPath)
            .sync()
            .autosave()
            .preserveInsertionOrder()
            .build()) {
            config.load();


            boolean enabled = readBoolean(config, "brand.enabled", true);
            String prefix = readString(config, "brand.prefix", "Ez - ");
            String text = readString(config, "brand.text", "GrimHvH");
            String prefixColor = readColorCode(config, "brand.prefix-color", "f");
            String textColor = readColorCode(config, "brand.text-color", "c");

            config.clear();
            config.set("brand.enabled", enabled);
            config.set("brand.prefix", prefix);
            config.set("brand.text", text);
            config.set("brand.prefix-color", prefixColor);
            config.set("brand.text-color", textColor);
            config.save();

            settings = new Settings(enabled, prefix, text, prefixColor, textColor);
        }
    }

    private static String readString(CommentedFileConfig config, String path, String fallback) {
        Object value = config.get(path);
        if (value instanceof String string) {
            return string;
        }
        return fallback;
    }

    private static boolean readBoolean(CommentedFileConfig config, String path, boolean fallback) {
        Object value = config.get(path);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String readColorCode(CommentedFileConfig config, String path, String fallback) {
        String value = readString(config, path, fallback).trim().toLowerCase(Locale.ROOT);
        if (value.length() == 1 && "0123456789abcdef".contains(value)) {
            return value;
        }
        return fallback;
    }

    private void sendBrand(Player player, Settings current) {
        if (current.enabled() && player.isActive()) {
            player.sendPluginMessage(BRAND_CHANNEL, encodeMinecraftString(current.coloredBrand()));
        }
    }

    private void sendBrandLater(Player player, long delayMillis) {
        proxy.getScheduler()
            .buildTask(this, () -> sendBrand(player, settings))
            .delay(delayMillis, TimeUnit.MILLISECONDS)
            .schedule();
    }

    private static byte[] encodeMinecraftString(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(text.length + 5);
        writeVarInt(output, text.length);
        output.writeBytes(text);
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static String defaultConfig() {
        return """
            [brand]
            enabled = true
            prefix = "Ez - "
            text = "GrimHvH"
            prefix-color = "f"
            text-color = "c"
            """;
    }

    private final class GrimVersionCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            if (args.length == 0) {
                source.sendMessage(Component.text("Usage: /grimversion reload | brand"));
                return;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> reloadCommand(source);
                case "brand" -> brandCommand(source);
                default -> source.sendMessage(Component.text("Usage: /grimversion reload | brand"));
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length > 0 && args[0].equalsIgnoreCase("brand")) {
                return invocation.source().hasPermission("grimversion.brand");
            }
            return invocation.source().hasPermission("grimversion.reload");
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
                return List.of("reload", "brand").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
            }
            return List.of();
        }

        private void reloadCommand(CommandSource source) {
            try {
                reloadConfig();
                source.sendMessage(Component.text("GrimVersion configuration reloaded."));
                logger.info("[GrimVersion] F3 Brand: {}", settings.plainBrand());
            } catch (Exception e) {
                logger.error("[GrimVersion] Failed to reload config.toml", e);
                source.sendMessage(Component.text("Failed to reload GrimVersion configuration. Check the proxy console."));
            }
        }

        private void brandCommand(CommandSource source) {
            Settings current = settings;
            if (!current.enabled()) {
                source.sendMessage(Component.text("F3 brand is disabled."));
                return;
            }
            source.sendMessage(Component.text("Current brand: ")
                .append(Component.text(current.prefix()).color(legacyTextColor(current.prefixColor())))
                .append(Component.text(current.text()).color(legacyTextColor(current.textColor()))));
            source.sendMessage(Component.text("Current brand raw: " + current.coloredBrand().replace('§', '&')));
            if (source instanceof Player player) {
                sendBrand(player, current);
                sendBrandLater(player, 250L);
                source.sendMessage(Component.text("Brand packet resent to you."));
            }
        }
    }

    private static TextColor legacyTextColor(String code) {
        return switch (code) {
            case "0" -> NamedTextColor.BLACK;
            case "1" -> NamedTextColor.DARK_BLUE;
            case "2" -> NamedTextColor.DARK_GREEN;
            case "3" -> NamedTextColor.DARK_AQUA;
            case "4" -> NamedTextColor.DARK_RED;
            case "5" -> NamedTextColor.DARK_PURPLE;
            case "6" -> NamedTextColor.GOLD;
            case "7" -> NamedTextColor.GRAY;
            case "8" -> NamedTextColor.DARK_GRAY;
            case "9" -> NamedTextColor.BLUE;
            case "a" -> NamedTextColor.GREEN;
            case "b" -> NamedTextColor.AQUA;
            case "c" -> NamedTextColor.RED;
            case "d" -> NamedTextColor.LIGHT_PURPLE;
            case "e" -> NamedTextColor.YELLOW;
            default -> NamedTextColor.WHITE;
        };
    }

    private record Settings(boolean enabled, String prefix, String text, String prefixColor, String textColor) {
        static Settings defaults() {
            return new Settings(true, "Ez - ", "GrimHvH", "f", "c");
        }

        String coloredBrand() {
            return "§" + prefixColor + prefix + "§" + textColor + text;
        }

        String plainBrand() {
            return prefix + text;
        }
    }
}




