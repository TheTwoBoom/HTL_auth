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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Form;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;

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

public class HTL_auth extends JavaPlugin implements Listener {

    private final FileConfiguration config = getConfig();
    private final String clientId = config.getString("oauth.client_id");
    private final String clientSecret = config.getString("oauth.client_secret");
    private final String redirectUri = config.getString("oauth.redirect_uri");
    private final String organizationDomain = config.getString("oauth.organization_domain");
    private final Locale language = new Locale(Objects.requireNonNull(config.getString("language")));
    private final boolean savePlayers = config.getBoolean("playerData.enabled");
    private final boolean savePlayersName = config.getBoolean("playerData.saveName");
    private final int webPort = config.getInt("oauth.webPort");
    private OAuthWebServer webServer;

    private FileConfiguration playerDataConfig;
    private File playerDataFile;
    private final Logger logger = getLogger();
    private World overworld;
    private final Title title = Title.title(Component.translatable("verify.title").color(TextColor.color(55, 255, 55)), Component.translatable("verify.subtitle").color(TextColor.color(125,249,255)), Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(3600), Duration.ofSeconds(3)));

    @Override
    public void onEnable() {
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        overworld = Bukkit.getWorld("world");
        loadPlayerData();
        getConfig().options().copyDefaults(true);
        saveConfig();
        TranslationRegistry registry = TranslationRegistry.create(Key.key("htl_auth", "translations"));
        ResourceBundle bundle = ResourceBundle.getBundle("translations", language, new UTF8ResourceBundleControl());
        registry.registerAll(language, bundle, true);
        GlobalTranslator.translator().addSource(registry);
        webServer = new OAuthWebServer(webPort, this);
        try {
            if (webPort == 0) {
                logger.severe("Webserver Port is not set in the config.");
                logger.severe("Please set the port in the config and restart the server.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            webServer.start();
            logger.info("OAuth Webserver started on Port " + webPort);
        } catch (Exception e) {
            logger.severe("Error while starting OAuth Webserver" + e.getMessage());
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
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!savePlayers) {
                    player.sendMessage(Component.translatable("lookup.disabled").color(TextColor.color(255, 85, 85)));
                    return true;
                }
                if (args.length == 1) {
                    String username = args[0].toLowerCase();
                    Player target = Bukkit.getPlayer(username);
                    if (target != null) {
                        Map<String, String> playerInfo = getPlayerInfo(target.getUniqueId());
                        if (playerInfo == null) {
                            player.sendMessage(Component.translatable("lookup.error", Component.text(target.getName())).color(TextColor.color(255, 85, 85)));
                        } else if (playerInfo.isEmpty()) {
                            player.sendMessage(Component.translatable("lookup.notfound", Component.text(target.getName())).color(TextColor.color(255, 85, 85)));
                        } else {
                            player.sendMessage(Component.translatable("lookup.info", Component.text(target.getName()).color(TextColor.color(85, 255, 255))));
                            playerInfo.forEach((key, value) -> player.sendMessage("§a" + key + ": §r" + value));
                        }
                    } else {
                        player.sendMessage(Component.translatable("lookup.notfound", Component.text(username)).color(TextColor.color(255, 85, 85)));
                    }
                } else {
                    player.sendMessage(Component.translatable("lookup.usage").color(TextColor.color(85, 255, 255)));
                }
                return true;
            }
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
            player.sendMessage(Component.translatable("verify.message").color(TextColor.color(255, 85, 85)));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!player.hasPermission("htlauth.join")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("htlauth.join")) {
            Random random = new Random();
            int PlayerY = random.nextInt(256);
            int PlayerX = random.nextInt(256);
            player.showTitle(title);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 10, 29);
            player.sendMessage(Component.translatable("verify.message").color(TextColor.color(255, 85, 85)));
            Location location = new Location(overworld, PlayerX, 199, PlayerY);
            Bukkit.getRegionScheduler().run(this, location, task -> location.getBlock().setType(Material.BARRIER));
            player.teleportAsync(new Location(overworld, PlayerX, 200, PlayerY));
        }
    }

    private void loadPlayerData() {
        playerDataFile = new File(getDataFolder(), "playerData.yml");
        if (!playerDataFile.exists()) {
            saveResource("playerData.yml", false);
        }
        playerDataConfig = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void savePlayerData() {
        try {
            playerDataConfig.save(playerDataFile);
        } catch (IOException e) {
            logger.severe("Error while saving player data" + e.getMessage());
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
                        if (savePlayers) {
                            if (isEmailLinkedToAnotherAccount(email, playerUUID)) {
                                player.sendMessage(Component.translatable("verify.email.linked").color(TextColor.color(255, 85, 85)));
                                logger.info("Player" + player.getName() + " tried to verify with an email that is already linked to another account.");
                                return;
                            }
                            linkEmailToPlayer(email, fullName, playerUUID);
                        }
                        assignRank(Bukkit.getPlayer(playerUUID));
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
            Map payloadData = objectMapper.readValue(payload, Map.class);

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
            if (savePlayersName) {
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
        if (!savePlayers) return;
        playerDataConfig.set(playerUUID.toString() + ".email", email);
        if (fullName != null && savePlayersName) {
            playerDataConfig.set(playerUUID + ".name", fullName);
        }
        savePlayerData();
    }

    private boolean isEmailLinkedToAnotherAccount(String email, UUID currentUUID) {
        if (!savePlayers) return false;
        for (String uuid : playerDataConfig.getKeys(false)) {
            String storedEmail = playerDataConfig.getString(uuid + ".email");
            if (storedEmail != null && storedEmail.equalsIgnoreCase(email) && !uuid.equals(currentUUID.toString())) {
                return true;
            }
        }
        return false;
    }

    public Map<String, String> getPlayerInfo(UUID playerUUID) {
        Map<String, String> playerInfo = new HashMap<>();
        String email = playerDataConfig.getString(playerUUID.toString() + ".email");
        String name = playerDataConfig.getString(playerUUID + ".name");

        if (email != null) {
            playerInfo.put("email", email);
        }
        if (!savePlayers || !savePlayersName) return playerInfo;
        if (name != null) {
            playerInfo.put("name", name);
        }

        return playerInfo;
    }

    private void assignRank(Player player) {
        LuckPerms api = LuckPermsProvider.get();
        if (player != null) {
            player.sendMessage(Component.translatable("verify.success").color(TextColor.color(85, 255, 85)));
            User user = api.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                Group group = api.getGroupManager().getGroup("verified");
                if (group != null) {
                    api.getUserManager().modifyUser(user.getUniqueId(), u -> u.data().add(Node.builder("group.verified").build()));
                    Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> player.removePotionEffect(PotionEffectType.BLINDNESS));
                    player.resetTitle();
                }
            }
            player.teleportAsync(player.getWorld().getSpawnLocation());
            logger.fine("Player " + player.getName() + " has been verified.");
        }
    }
}
