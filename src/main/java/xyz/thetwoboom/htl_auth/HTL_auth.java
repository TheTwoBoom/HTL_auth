package xyz.thetwoboom.htl_auth;
/*

    HTLAuth: Easy Authentication for Minecraft via Microsoft Entra ID
    Copyright (C) 2024 TheTwoBoom

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

*/

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.apache.http.client.utils.URIBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Form;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;

import java.nio.file.Files;
import java.sql.*;
import java.time.Duration;
import java.util.Locale;
import net.kyori.adventure.text.event.ClickEvent;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.logging.Logger;

import net.luckperms.api.node.Node;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public class HTL_auth extends JavaPlugin implements Listener {
    LuckPerms api;
    private ConfigurationNode config;
    private ConfigurationNode playerDataConfig;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String organizationDomain;
    private Locale language;
    private boolean playerData;
    private boolean playerDataName;
    private int webPort;
    private OAuthWebServer webServer;
    private final HTL_auth plugin = this;
    private final Logger logger = getLogger();
    private World overworld;
    private String reminderMode;
    private String organizationName;
    private String contactMethod;
    private final Title title = Title.title(Component.translatable("verify.title").color(TextColor.color(55, 255, 55)), Component.translatable("verify.subtitle").color(TextColor.color(125,249,255)), Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3600), Duration.ofSeconds(3)));

    @Override
    public void onEnable() {
        // Copy config.yml from resources if it does not exist in the data folder
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            getDataFolder().mkdirs();
            try (java.io.InputStream in = getResource("config.yml");
                 java.io.OutputStream out = Files.newOutputStream(configFile.toPath())) {
                if (in == null) {
                    logger.severe("config.yml could not be loaded from resources!");
                } else {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    logger.info("config.yml was copied to the data folder.");
                }
            } catch (Exception e) {
                logger.severe("Error while copying config.yml: " + e.getMessage());
            }
        }
        api = LuckPermsProvider.get();
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        overworld = Bukkit.getWorld("world");
        // Configurate-Konfiguration laden
        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(new File(getDataFolder(), "config.yml").toPath())
                .build();
            config = loader.load();
            clientId = config.node("oauth").node("client_id").getString();
            clientSecret = config.node("oauth").node("client_secret").getString();
            redirectUri = config.node("oauth").node("redirect_uri").getString();
            organizationDomain = config.node("oauth").node("organization_domain").getString();
            language = new Locale(config.node("language").getString("en"));
            playerData = config.node("playerData").node("enabled").getBoolean();
            playerDataName = config.node("playerData").node("saveName").getBoolean();
            webPort = config.node("oauth").node("webPort").getInt();
            reminderMode = config.node("reminderMode").getString();
            organizationName = config.node("orgName").getString();
            contactMethod = config.node("contactMethodDialog").getString();
        } catch (IOException e) {
            logger.severe("Error while loading Configurate configuration: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        loadPlayerData();
        if (checkPermsGroup() == null) {
            logger.warning("The group 'verified' does not exist in LuckPerms.");
            logger.info("HTLAuth uses the group 'verified' to assign permissions to verified players.");
            logger.warning("Creating the group 'verified' with minimal required permissions.");
            createPermsGroup();
        } else if (Boolean.FALSE.equals(checkPermsGroup())) {
            logger.warning("The group 'verified' does not have the required permissions.");
            logger.info("HTLAuth uses the group 'verified' to assign permissions to verified players.");
            logger.warning("Please add the required permissions to the group 'verified' and restart the server.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        TranslationRegistry registry = TranslationRegistry.create(Key.key("htl_auth", "translations"));
        ResourceBundle bundle = ResourceBundle.getBundle("translations", language, new UTF8ResourceBundleControl());
        registry.registerAll(language, bundle, true);
        GlobalTranslator.translator().addSource(registry);
        webServer = new OAuthWebServer(webPort, this);
        try {
            if (webPort == 0) {
                logger.severe("Webserver port is not set in the config.");
                logger.severe("Please set the port in the config and restart the server.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            if (clientId == null || clientSecret == null || redirectUri == null || organizationDomain == null) {
                logger.severe("OAuth credentials are not set in the config.");
                logger.severe("Please set the credentials in the config and restart the server.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            webServer.start();
            logger.info("OAuth Webserver started on port " + webPort);
        } catch (Exception e) {
            logger.severe("Error while starting OAuth Webserver: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("verify")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (player.hasPermission("htlauth.join")) {
                    player.sendMessage(Component.translatable("verify.already").color(TextColor.color(255, 85, 85)));
                    return true;
                }
                String authLink = generateOAuthLink(player.getUniqueId());

                if (authLink != null) {
                    player.sendMessage(Component.translatable("verify.warning").color(TextColor.color(255, 225, 20)));
                    player.sendMessage(Component.translatable("verify.link", Component.text(authLink.substring(0, 50) + "...").clickEvent(ClickEvent.openUrl(authLink))).color(TextColor.color(170, 170, 170)));
                    logger.finer("Player " + player.getName() + " has requested verification.");
                } else {
                    player.sendMessage(Component.translatable("verify.link.error").color(TextColor.color(255, 85, 85)));
                    logger.severe("Error while generating OAuth link for player " + player.getName());
                }
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("lookup")) {
            if (!playerData) {
                sender.sendMessage(Component.translatable("lookup.disabled").color(TextColor.color(255, 85, 85)));
                return true;
            }
            if (args.length == 1) {
                String username = args[0].toLowerCase();
                Player target = Bukkit.getPlayer(username);
                if (target != null) {
                    Map<String, String> playerInfo = getPlayerInfo(target.getUniqueId());
                    if (playerInfo == null) {
                        sender.sendMessage(Component.translatable("lookup.error", Component.text(target.getName())).color(TextColor.color(255, 85, 85)));
                    } else if (playerInfo.isEmpty()) {
                        sender.sendMessage(Component.translatable("lookup.notfound", Component.text(target.getName())).color(TextColor.color(255, 85, 85)));
                    } else {
                        sender.sendMessage(Component.translatable("lookup.info", Component.text(target.getName()).color(TextColor.color(85, 255, 255))));
                        playerInfo.forEach((key, value) -> sender.sendMessage("§a" + key + ": §r" + value));
                    }
                } else {
                    sender.sendMessage(Component.translatable("lookup.notfound", Component.text(username)).color(TextColor.color(255, 85, 85)));
                }
            } else {
                sender.sendMessage(Component.translatable("lookup.usage").color(TextColor.color(85, 255, 255)));
            }
            return true;
        }
        if (command.getName().equalsIgnoreCase("htlauth")) {
            if (args.length >= 1) {
                if (args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    sender.sendMessage(Component.translatable("htlauth.reload").color(TextColor.color(85, 255, 85)));
                    return true;
                }
                if (args[0].equalsIgnoreCase("forceverify")) {
                    if (args.length == 2) {
                        Player player = Bukkit.getPlayer(args[1]);
                        assert player != null;
                        assignRank(player);
                        player.sendMessage(Component.translatable("verify.forceverify").color(TextColor.color(85, 255, 85)));
                        sender.sendMessage(Component.translatable("htlauth.success", Component.text("/forceverify")).color(TextColor.color(85, 255, 85)));
                        return true;
                    } else {
                        return false;
                    }
                }
                if (args[0].equalsIgnoreCase("reverify")) {
                    if (args.length == 2) {
                        Player player = Bukkit.getPlayer(args[1]);
                        assert player != null;
                        api.getUserManager().modifyUser(player.getUniqueId(), u -> u.data().remove(Node.builder("group.verified").build()));
                        player.kick(Component.translatable("verify.reverify").color(TextColor.color(85, 255, 85)));
                        sender.sendMessage(Component.translatable("htlauth.success", Component.text("/reverify")).color(TextColor.color(85, 255, 85)));
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            sender.sendMessage(Component.translatable("htlauth.wip").color(TextColor.color(85, 255, 255)));
            return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
            getLogger().info("OAuth Webserver stopped.");
        }
        savePlayerData();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("htlauth.join")) {
            event.setCancelled(true);
            if (reminderMode.equals("AGGRESSIVE")) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 10, 29);
                player.sendMessage(Component.translatable("verify.message").color(TextColor.color(255, 85, 85)));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("htlauth.join")) {
            if (reminderMode.equals("AGGRESSIVE")) {
                player.showTitle(title);
            } else if (reminderMode.equals("DIALOG")) {
                showVerifyDialog(player);
            }
            player.setGameMode(GameMode.SPECTATOR);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 255));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 10, 29);
            player.sendMessage(Component.translatable("verify.message").color(TextColor.color(255, 85, 85)));
            player.teleportAsync(overworld.getSpawnLocation());
        }
    }

    private void showVerifyDialog(Player player) {
        String authLink = generateOAuthLink(player.getUniqueId());
        if (authLink == null) {
            player.sendMessage(Component.translatable("verify.link.error").color(TextColor.color(255, 85, 85)));
            return;
        }
        // Dialog API Integration (PaperMC experimental)
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.translatable("verify.dialog.title").color(TextColor.color(55, 255, 55)))
                        .canCloseWithEscape(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.translatable("verify.dialog.explain1")),
                                DialogBody.plainMessage(Component.translatable("verify.dialog.explain2", Component.text(organizationName))),
                                DialogBody.plainMessage(Component.translatable("verify.dialog.explain3")),
                                DialogBody.plainMessage(Component.translatable("verify.dialog.explain4")),
                                DialogBody.plainMessage(Component.translatable("verify.dialog.explain5", Component.text(contactMethod)))
                        ))
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .build())
                .type(DialogType.multiAction(List.of(
                        ActionButton.create(Component.translatable("verify.button"), null, 200, DialogAction.staticAction(ClickEvent.openUrl(authLink)))
                )).build())
            );
        player.showDialog(dialog);
    }

    public void createPlayerData() {
        // Stelle sicher, dass das Datenverzeichnis existiert
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        String url = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/playerData.db";
        try (Connection conn = DriverManager.getConnection(url)) {
            if (conn != null) {
                DatabaseMetaData meta = conn.getMetaData();
                logger.info("The driver name is " + meta.getDriverName());
                logger.info("A new database has been created.");

                String createTableSQL = "CREATE TABLE IF NOT EXISTS player_data ("
                        + "uuid TEXT PRIMARY KEY,"
                        + "email TEXT NOT NULL,"
                        + "name TEXT"
                        + ");";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(createTableSQL);
                }
            }
        } catch (SQLException e) {
            logger.severe("Error while creating player data: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void loadPlayerData() {
        // Stelle sicher, dass das Datenverzeichnis existiert
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        String url = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/playerData.db";
        try (Connection conn = DriverManager.getConnection(url)) {
            if (conn != null) {
                String selectSQL = "SELECT * FROM player_data";
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery(selectSQL);
                    YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                        .path(new File(getDataFolder(), "playerData.yml").toPath())
                        .build();
                    playerDataConfig = loader.createNode();
                    while (rs.next()) {
                        String uuid = rs.getString("uuid");
                        String email = rs.getString("email");
                        String name = rs.getString("name");
                        try {
                            playerDataConfig.node(uuid).node("email").set(email);
                            playerDataConfig.node(uuid).node("name").set(name);
                        } catch (org.spongepowered.configurate.serialize.SerializationException se) {
                            logger.warning("Fehler beim Setzen der PlayerDataConfig: " + se.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    logger.warning("Error while loading player data: " + e.getMessage());
                    logger.warning("Creating new player data file.");
                    createPlayerData();
                    loadPlayerData();
                }
            }
        } catch (SQLException e) {
            logger.severe("Error while loading player data: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void savePlayerData() {
        String url = "jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/playerData.db";
        try (Connection conn = DriverManager.getConnection(url)) {
            if (conn != null) {
                String insertSQL = "INSERT OR REPLACE INTO player_data(uuid, email, name) VALUES(?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    for (Object uuidObj : playerDataConfig.childrenMap().keySet()) {
                        String uuid = uuidObj.toString();
                        String email = playerDataConfig.node(uuid).node("email").getString();
                        String name = playerDataConfig.node(uuid).node("name").getString();
                        pstmt.setString(1, uuid);
                        pstmt.setString(2, email);
                        pstmt.setString(3, name);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
            }
        } catch (SQLException e) {
            logger.severe("Error while saving player data: " + e.getMessage());
        }
    }

    private String generateOAuthLink(UUID playerUUID) {
        try {
            URIBuilder uriBuilder = new URIBuilder("https://login.microsoftonline.com/common/oauth2/v2.0/authorize");
            uriBuilder.addParameter("client_id", clientId);
            uriBuilder.addParameter("response_type", "code");
            uriBuilder.addParameter("redirect_uri", redirectUri);
            uriBuilder.addParameter("response_mode", "query");
            uriBuilder.addParameter("scope", "openid profile");
            uriBuilder.addParameter("state", playerUUID.toString());
            return uriBuilder.build().toString();
        } catch (URISyntaxException e) {
            logger.severe("Error while generating OAuth link: " + e.getMessage());
            return null;
        }
    }

    public void handleOAuthCallback(String code, UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null && !player.hasPermission("htlauth.join")) {
            try {
                String response = Request.Post("https://login.microsoftonline.com/common/oauth2/v2.0/token")
                        .bodyForm(Form.form()
                                .add("client_id", clientId)
                                .add("client_secret", clientSecret)
                                .add("code", code)
                                .add("redirect_uri", redirectUri)
                                .add("grant_type", "authorization_code")
                                .build())
                        .execute().returnContent().asString();
                Map<String, String> userInfo = extractUserInfoFromToken(response, player);
                if (userInfo != null) {
                    String email = userInfo.get("email");
                    String fullName = userInfo.get("name");

                    if (email != null && email.endsWith("@" + organizationDomain)) {
                        if (playerData) {
                            if (isEmailLinkedToAnotherAccount(email, playerUUID)) {
                                player.sendMessage(Component.translatable("verify.email.linked").color(TextColor.color(255, 85, 85)));
                                logger.info("Player" + player.getName() + " tried to verify with an email that is already linked to another account.");
                                return;
                            }
                            linkEmailToPlayer(email, fullName, playerUUID);
                        }
                        assignRank(Bukkit.getPlayer(playerUUID));
                        player.sendMessage(Component.translatable("verify.success").color(TextColor.color(85, 255, 85)));
                    } else if (email != null) {
                        player.sendMessage(Component.translatable("verify.email.error").color(TextColor.color(255, 85, 85)));
                        logger.warning("Player " + player.getName() + " tried to verify with an email that is not from the organization domain.");
                    }
                } else {
                    player.sendMessage(Component.translatable("verify.error").color(TextColor.color(255, 85, 85)));
                    logger.warning("Error while extracting user info for user " + player.getName() + " from token.");
                }
            } catch (IOException e) {
                logger.severe("Error while handling OAuth callback: " + e.getMessage());
            }
        } else if (player == null) {
            logger.warning("Invalid UUID tried to verify: " + playerUUID);
        }
    }

    private Map<String, String> extractUserInfoFromToken(String tokenResponse, Player player) {
        try {
            String[] tokenParts = tokenResponse.split("\\.");
            if (tokenParts.length < 2) {
                throw new IllegalArgumentException("Invalid Format: " + tokenResponse);
            }
            String payload = new String(Base64.getUrlDecoder().decode(tokenParts[1]));
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> payloadData = objectMapper.readValue(payload, Map.class);

            String email = null;
            String fullName = null;

            if (payloadData.containsKey("email")) {
                email = (String) payloadData.get("email");
            } else if (payloadData.containsKey("upn")) {
                email = (String) payloadData.get("upn");
            } else if (payloadData.containsKey("preferred_username")) {
                email = (String) payloadData.get("preferred_username");
            }
            if (email == null) {
                throw new IllegalArgumentException("Email not in Payload: " + payload);
            }
            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("email", email);
            if (playerDataName) {
                if (payloadData.containsKey("name")) {
                    fullName = (String) payloadData.get("name");
                }
            userInfo.put("name", fullName);
            } else {
                userInfo.put("name", player.getName());
            }
            return userInfo;

        } catch (Exception e) {
            logger.severe("Error while extracting user info from token: " + e.getMessage());
            player.sendMessage(Component.translatable("verify.error").color(TextColor.color(255, 85, 85)));
            return null;
        }
    }

    private void linkEmailToPlayer(String email, String fullName, UUID playerUUID) {
        if (!playerData) return;
        try {
            playerDataConfig.node(playerUUID.toString()).node("email").set(email);
            if (fullName != null && playerDataName) {
                playerDataConfig.node(playerUUID.toString()).node("name").set(fullName);
            }
        } catch (org.spongepowered.configurate.serialize.SerializationException se) {
            logger.warning("Fehler beim Setzen der PlayerDataConfig: " + se.getMessage());
        }
        savePlayerData();
    }

    private boolean isEmailLinkedToAnotherAccount(String email, UUID currentUUID) {
        if (!playerData) return false;
        for (Object uuidObj : playerDataConfig.childrenMap().keySet()) {
            String uuid = uuidObj.toString();
            String storedEmail = playerDataConfig.node(uuid).node("email").getString();
            if (storedEmail != null && storedEmail.equalsIgnoreCase(email) && !uuid.equals(currentUUID.toString())) {
                return true;
            }
        }
        return false;
    }

    public Map<String, String> getPlayerInfo(UUID playerUUID) {
        Map<String, String> playerInfo = new HashMap<>();
        String email = playerDataConfig.node(playerUUID.toString()).node("email").getString();
        String name = playerDataConfig.node(playerUUID.toString()).node("name").getString();
        if (email != null) {
            playerInfo.put("email", email);
        }
        if (!playerData) return playerInfo;
        if (name != null) {
            playerInfo.put("name", name);
        }
        return playerInfo;
    }

    private void assignRank(Player player) {
        if (player != null) {
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                Group group = api.getGroupManager().getGroup("verified");
                if (group != null) {
                    api.getUserManager().modifyUser(user.getUniqueId(), u -> u.data().add(Node.builder("group.verified").build()));
                    Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> player.removePotionEffect(PotionEffectType.BLINDNESS));
                    player.resetTitle();
                    if (reminderMode.equals("DIALOG")) {
                        player.closeDialog();
                    }
                    player.teleportAsync(player.getWorld().getSpawnLocation());
                    player.setGameMode(GameMode.SURVIVAL);
                    logger.fine("Player " + player.getName() + " has been verified.");
                }
            }
        }
    }

    private Boolean checkPermsGroup() {
        Group defaultGroup = api.getGroupManager().getGroup("default");
        Group verifiedGroup = api.getGroupManager().getGroup("verified");
        if (defaultGroup != null && verifiedGroup != null) {
            return verifiedGroup.getNodes().contains(Node.builder("htlauth.join").build());
        }
        return null;
    }

    private void createPermsGroup() {
        api.getGroupManager().createAndLoadGroup("verified");
        api.getGroupManager().modifyGroup("verified", verifiedGroup -> verifiedGroup.data().add(Node.builder("htlauth.join").build()));
        api.getGroupManager().modifyGroup("default", defaultGroup -> defaultGroup.data().add(Node.builder("*").value(false).build()));
    }
}
