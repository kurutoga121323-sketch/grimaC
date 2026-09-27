package jp.example.grimversionpaper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

public final class GrimVersionPaper extends JavaPlugin implements Listener, TabExecutor {
    private Settings settings = Settings.defaults();
    private ReflectionSender reflectionSender;
    private boolean warnedBrandSendFailure;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        reflectionSender = ReflectionSender.create(getLogger());

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("grimversion").setExecutor(this);
        getCommand("grimversion").setTabCompleter(this);

        getLogger().info("Enabled");
        getLogger().info("F3 Brand: " + settings.plainBrand());
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sendBrandLater(player, 20L);
        sendBrandLater(player, 60L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /grimversion reload | brand"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!sender.hasPermission("grimversion.reload")) {
                    sender.sendMessage(Component.text("You do not have permission."));
                    return true;
                }
                reloadConfig();
                reloadSettings();
                sender.sendMessage(Component.text("GrimVersion configuration reloaded."));
                getLogger().info("F3 Brand: " + settings.plainBrand());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    sendBrand(player);
                    sendBrandLater(player, 20L);
                }
                return true;
            }
            case "brand" -> {
                if (!sender.hasPermission("grimversion.brand")) {
                    sender.sendMessage(Component.text("You do not have permission."));
                    return true;
                }
                if (!settings.enabled()) {
                    sender.sendMessage(Component.text("F3 brand is disabled."));
                    return true;
                }
                sender.sendMessage(Component.text("Current brand: ")
                    .append(Component.text(settings.prefix()).color(legacyTextColor(settings.prefixColor())))
                    .append(Component.text(settings.text()).color(legacyTextColor(settings.textColor()))));
                sender.sendMessage(Component.text("Current brand raw: " + settings.coloredBrand().replace('§', '&')));
                if (sender instanceof Player player) {
                    sendBrand(player);
                    sendBrandLater(player, 20L);
                    sender.sendMessage(Component.text("Brand packet resent to you."));
                }
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /grimversion reload | brand"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "brand").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }
        return List.of();
    }

    private void reloadSettings() {
        FileConfiguration config = getConfig();
        boolean enabled = config.getBoolean("brand.enabled", true);
        String prefix = config.getString("brand.prefix", "Ez - ");
        String text = config.getString("brand.text", "GrimHvH");
        String prefixColor = readColorCode(config.getString("brand.prefix-color", "f"), "f");
        String textColor = readColorCode(config.getString("brand.text-color", "c"), "c");

        config.set("brand.enabled", enabled);
        config.set("brand.prefix", prefix);
        config.set("brand.text", text);
        config.set("brand.prefix-color", prefixColor);
        config.set("brand.text-color", textColor);
        saveConfig();

        settings = new Settings(enabled, prefix, text, prefixColor, textColor);
    }

    private static String readColorCode(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 1 && "0123456789abcdef".contains(value)) {
            return value;
        }
        return fallback;
    }

    private void sendBrandLater(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> sendBrand(player), delayTicks);
    }

    private void sendBrand(Player player) {
        Settings current = settings;
        if (!current.enabled() || !player.isOnline()) {
            return;
        }

        if (reflectionSender != null && reflectionSender.send(player, current.coloredBrand())) {
            return;
        }
        if (!warnedBrandSendFailure) {
            warnedBrandSendFailure = true;
            getLogger().warning("Failed to send F3 brand. This Paper/NMS version may need an updated GrimVersion build.");
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

    private record ReflectionSender(Constructor<?> brandPayloadConstructor, Constructor<?> packetConstructor, Method getHandleMethod,
                                    Field connectionField, Method sendMethod) {
        static ReflectionSender create(java.util.logging.Logger logger) {
            try {
                Class<?> brandPayloadClass = Class.forName("net.minecraft.network.protocol.common.custom.BrandPayload");
                Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket");
                Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
                Constructor<?> brandPayloadConstructor = brandPayloadClass.getConstructor(String.class);
                Constructor<?> packetConstructor = packetClass.getConstructor(Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload"));
                Method getHandleMethod = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
                Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
                Field connectionField = serverPlayerClass.getField("connection");
                Method sendMethod = connectionField.getType().getMethod("send", packetInterface);
                return new ReflectionSender(brandPayloadConstructor, packetConstructor, getHandleMethod, connectionField, sendMethod);
            } catch (ReflectiveOperationException ex) {
                logger.warning("NMS brand fallback is unavailable: " + ex.getMessage());
                return null;
            }
        }

        boolean send(Player player, String brand) {
            try {
                Object handle = getHandleMethod.invoke(player);
                Object connection = connectionField.get(handle);
                Object payload = brandPayloadConstructor.newInstance(brand);
                Object packet = packetConstructor.newInstance(payload);
                sendMethod.invoke(connection, packet);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }
}


