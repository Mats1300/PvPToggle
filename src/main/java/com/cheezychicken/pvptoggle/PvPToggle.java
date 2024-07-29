package com.cheezychicken.pvptoggle;

import com.nametagedit.plugin.NametagEdit;
import com.nametagedit.plugin.api.INametagApi;
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// ----------------------------------------------------------------------------------------------------------

/**
 * The main plugin class.
 */
public class PvPToggle extends JavaPlugin implements Listener {

    // ------------------------------------------------------------------------------------------------------
    /**
     * Stores the UUIDs of the players currently pvping.
     */
    private static final Set<UUID> ENABLED_PLAYERS = new HashSet<>();

    /**
     * Stores the UUIDs of the players with PvP persist enabled.
     */
    private static final Set<UUID> PERSISTING_PLAYERS = new HashSet<>();

    /**
     * Stores the Bukkit logger.
     */
    private final Logger logger = Bukkit.getLogger();

    // ------------------------------------------------------------------------------------------------------

    /**
     * @see JavaPlugin#onEnable().
     */
    @Override
    public void onEnable() {
        this.getServer().getPluginManager().registerEvents(this, this);

        Plugin nametagedit = getServer().getPluginManager().getPlugin("NametagEdit");

        if (nametagedit == null) {
            logger.log(Level.SEVERE, "NametagEdit is not installed! Unable to start PvPToggle.");
            getPluginLoader().disablePlugin(this);
        } else if (!nametagedit.isEnabled()) {
            logger.log(Level.SEVERE, "NametagEdit is not enabled! Unable to start PvPToggle.");
            getPluginLoader().disablePlugin(this);
        }
    }

    // ------------------------------------------------------------------------------------------------------

    @Override
    public void onDisable() {
        for (UUID uuid : ENABLED_PLAYERS) {
            if (Bukkit.getPlayer(uuid) != null) {
                NametagEdit.getApi().setPrefix(Bukkit.getPlayer(uuid), "");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Handles commands.
     *
     * @see JavaPlugin#onCommand(CommandSender, Command, String, String[]).
     */
    public boolean onCommand(CommandSender sender, Command command, String name, String[] args) {

        if (command.getName().equalsIgnoreCase("pvp")) {
            if (args.length == 0 || !sender.hasPermission("pvp.toggle")) {
                return false;
            } else {
                switch (args[0].toUpperCase()) {
                    case "ON":
                        if (args.length == 1) {
                            Player player = (Player) sender;
                            pvpStatusOn(player, "Player");
                            return true;
                        } else if (sender.hasPermission("pvp.others")) {
                            if (Bukkit.getPlayer(args[1]) == null) return false;
                            Player player = Bukkit.getPlayer(args[1]);
                            pvpStatusOn(player, "Admin");
                            return true;
                        } else {
                            return false;
                        }

                    case "OFF":
                        if (args.length == 1) {
                            Player player = (Player) sender;
                            pvpStatusOff(player, "Player");
                            return true;
                            // Admins can change other people's
                        } else if (sender.hasPermission("pvp.others")) {
                            if (Bukkit.getPlayer(args[1]) == null) return false;
                            Player player = Bukkit.getPlayer(args[1]);
                            pvpStatusOff(player, "Admin");
                            return true;
                        } else {
                            return false;
                        }

                    case "PERSIST":
                        PERSISTING_PLAYERS.add(((Player) sender).getUniqueId());
                        pvpStatusOn(((Player) sender), "Player");
                        return true;

                    case "LIST":
                        String playerList = "Players with PvP active: ";
                        if (ENABLED_PLAYERS.isEmpty()) {
                            playerList += "None!";
                        } else {
                            playerList += ENABLED_PLAYERS.stream()
                                    .map(getServer()::getPlayer)
                                    .map(Player::getName)
                                    .collect(Collectors.joining(", "));
                        }
                        sender.sendMessage(playerList);
                        return true;

                    default:
                        return false;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Turns the player's hunted status on
     *
     * @param player the player
     * @param reason Reason for change
     */
    private void pvpStatusOn(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        String msg = "";
        if (isActive(player)) {
            if (reason.equalsIgnoreCase("Player")) {
                player.sendMessage("PvP is already enabled for you!");
            }
        } else {
            if (reason.equalsIgnoreCase("Player")) {
                msg = ChatColor.RED + player.getName() + " has turned their PvP on." + ChatColor.RESET;
            } else if (reason.equalsIgnoreCase("Admin")) {
                msg = ChatColor.RED + player.getName() + " has had their PvP turned on." + ChatColor.RESET;
            }
            ENABLED_PLAYERS.add(uuid);
            if (!msg.equalsIgnoreCase("")) {
                getServer().broadcastMessage(msg);
            }
            checkPvPstate(player);
        }

    }
    // ------------------------------------------------------------------------------------------------------

    /**
     * Turns the player's hunted status off
     *
     * @param player the player
     * @param reason Reason for change
     */

    private void pvpStatusOff(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        String msg = "";
        if (isActive(player)) {
            if (reason == "Player") {
                msg = ChatColor.RED + player.getName() + " has turned their PvP off." + ChatColor.RESET;
            } else if (reason == "Admin") {
                msg = ChatColor.RED + player.getName() + " has had their PvP turned off." + ChatColor.RESET;
            }
            if(isPersisted(player)) {
                PERSISTING_PLAYERS.remove(uuid);
            }
            ENABLED_PLAYERS.remove(uuid);
            if (msg != "") {
                getServer().broadcastMessage(msg);
            }
            checkPvPstate(player);
        } else {
            if (reason == "Player") {
                player.sendMessage("PvP is already disabled for you!");
            }
        }

    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Sets the player's name colour depending on the state of their PvP toggle.
     *
     * @param player The player being checked
     */
    public static void checkPvPstate(Player player) {
        if (isActive(player)) {
            NametagEdit.getApi().setPrefix(player, "&c");
        } else {
            NametagEdit.getApi().setPrefix(player, "");
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Checks if the player has their PvP on.
     * @param player the player.
     * @return true if the player has pvp on
     */
    public static boolean isActive(Player player) {
        return ENABLED_PLAYERS.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Checks if the player has PvP persisting through deaths
     * @param player the player
     * @return true if the player has pvp persisting
     */
    public static boolean isPersisted(Player player) {
        return PERSISTING_PLAYERS.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Prevent WorldGuard from disabling PvP for two players with pvp on.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onPVPDamage(DisallowedPVPEvent event) {
        Player defender = event.getDefender();
        Player attacker = event.getAttacker();
        if (isActive(defender) && isActive(attacker)) {
            event.setCancelled(true);
        }
    }


    // ------------------------------------------------------------------------------------------------------

    /**
     * Turn off PvP for a player when they die.
     *
     * @param event The player death event
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (isActive(player) && !isPersisted(player)) {
            pvpStatusOff(player, "Death");
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Turn off PvP for a player when they log out.
     *
     * @param player the player
     */

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent player) {
        Player leaver = player.getPlayer();
        if (isActive(leaver)) {
            pvpStatusOff(leaver, "Leave");
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Turn off PvP for a player when they are kicked.
     *
     * @param player the player
     */

    @EventHandler
    public void onPlayerKick(PlayerKickEvent player) {
        Player leaver = player.getPlayer();
        if (isActive(leaver)) {
            pvpStatusOff(leaver, "Leave");
        }
    }
}
