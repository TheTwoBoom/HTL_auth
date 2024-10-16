package xyz.thetwoboom.htl_auth;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.apache.http.client.utils.URIBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Form;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Base64;
import java.util.Map;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

public class HTL_auth extends JavaPlugin implements Listener {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String organizationDomain;
    private OAuthWebServer webServer;
    private final String title = ChatColor.RED + "Verify dich mit /verify!";
    private final String subtitle = ChatColor.GRAY + "Exklusiv für Schüler der HTL Rennweg!";

    private FileConfiguration playerDataConfig;
    private File playerDataFile;

    @Override
    public void onEnable() {
        Bukkit.getServer().getPluginManager().registerEvents(this, this);
        saveDefaultConfig();
        loadConfigValues();
        loadPlayerData();
        webServer = new OAuthWebServer(2500, this);
        try {
            webServer.start();
            getLogger().info("OAuth Web-Server gestartet auf Port 2500.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("verify")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (player.hasPermission("htlauth.join")) {
                    player.sendMessage(ChatColor.RED + "Du bist bereits verifiziert!");
                    return true;
                }
                String authLink = generateOAuthLink(player.getUniqueId());

                TextComponent message = new TextComponent(ChatColor.AQUA + authLink.substring(0, 50) + "...");
                message.setUnderlined(true);
                message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, authLink));

                player.sendMessage(ChatColor.GREEN + "Verifiziere dich hier: ");
                player.spigot().sendMessage(message);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
            getLogger().info("OAuth Web-Server gestoppt.");
        }
        savePlayerData();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Überprüfe, ob der Spieler die Permission hat
        if (!player.hasPermission("htlauth.join")) {
            // Teleportiere den Spieler zu seiner aktuellen Position (friert ihn ein)
            player.teleport(event.getFrom());
            player.sendMessage(ChatColor.RED + "Verifiziere dich mit /verify, um den Server zu nutzen!");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Überprüfe, ob der Spieler die Berechtigung hat
        if (!player.hasPermission("htlauth.join")) {
            player.sendTitle(title, subtitle, 10, 100000, 20);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1));
            player.sendMessage(ChatColor.RED + "Verifiziere dich mit /verify, um den Server zu nutzen!");
            player.teleport(new Location(Bukkit.getWorld("void"), 0, 65, 0));
        }
    }

    private void loadConfigValues() {
        FileConfiguration config = this.getConfig();
        clientId = config.getString("oauth.client_id");
        clientSecret = config.getString("oauth.client_secret");
        redirectUri = config.getString("oauth.redirect_uri");
        organizationDomain = config.getString("oauth.organization_domain");
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
            e.printStackTrace();
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
            e.printStackTrace();
            return null;
        }
    }

    public void handleOAuthCallback(String code, UUID playerUUID) {
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

            String email = extractEmailFromToken(response);
            if (email != null && email.endsWith("@" + organizationDomain)) {
                // Check if the email is already tied to another UUID
                if (isEmailLinkedToAnotherAccount(email, playerUUID)) {
                    Bukkit.getPlayer(playerUUID).sendMessage(ChatColor.RED + "Diese E-Mail-Adresse ist bereits mit einem anderen Minecraft-Account verknüpft.");
                } else {
                    assignRank(Bukkit.getPlayer(playerUUID));
                    linkEmailToPlayer(email, playerUUID); // Store the email-UUID link
                }
            } else {
                Bukkit.getPlayer(playerUUID).sendMessage("§cVerifizierung fehlgeschlagen. §rFalsche Organisation/Kein HTL Account");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String extractEmailFromToken(String tokenResponse) {
        try {
            String[] tokenParts = tokenResponse.split("\\.");
            if (tokenParts.length < 2) {
                throw new IllegalArgumentException("Ungültiges JWT-Token-Format.");
            }

            String payload = new String(Base64.getUrlDecoder().decode(tokenParts[1]));
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> payloadData = objectMapper.readValue(payload, Map.class);

            if (payloadData.containsKey("email")) {
                return (String) payloadData.get("email");
            } else if (payloadData.containsKey("upn")) {
                return (String) payloadData.get("upn");
            } else {
                throw new IllegalArgumentException("E-Mail-Feld nicht im Token vorhanden.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean isEmailLinkedToAnotherAccount(String email, UUID currentUUID) {
        for (String uuid : playerDataConfig.getKeys(false)) {
            String storedEmail = playerDataConfig.getString(uuid + ".email");
            if (storedEmail != null && storedEmail.equalsIgnoreCase(email) && !uuid.equals(currentUUID.toString())) {
                return true;
            }
        }
        return false;
    }

    private void linkEmailToPlayer(String email, UUID playerUUID) {
        playerDataConfig.set(playerUUID.toString() + ".email", email);
        savePlayerData();
    }

    private void assignRank(Player player) {
        if (player != null) {
            player.sendMessage("§aVerifizierung erfolgreich! §rJetzt kann es losgehen!");
            Bukkit.getScheduler().runTask(this, () -> {
                getServer().dispatchCommand(getServer().getConsoleSender(), "luckperms user " + player.getName() + " group add verified");
                getServer().dispatchCommand(getServer().getConsoleSender(), "spawn " + player.getName());
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.sendTitle("", "", 10, 20, 10);
            });
        }
    }
}
