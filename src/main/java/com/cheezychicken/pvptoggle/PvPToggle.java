package com.cheezychicken.pvptoggle;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;

import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ----------------------------------------------------------------------------------------------------------

/**
 * The main plugin class for PvPToggle.
 * Handles PvP toggle logic, command processing, and player events.
 */
public class PvPToggle extends JavaPlugin implements Listener, TabCompleter, CommandExecutor {

    // ------------------------------------------------------------------------------------------------------
    /** Stores the UUIDs of players with PvP enabled. */
    private static final Set<UUID> ENABLED_PLAYERS = new HashSet<>();


    /** Stores the UUIDs of players with persistent PvP enabled. */
    private static final Set<UUID> PERSISTING_PLAYERS = new HashSet<>();

    /** Bukkit logger instance. */
    private final Logger logger = this.getLogger();

    /**
     * Audience provider used to send Adventure text components to players, console, etc.
     *<p>
     * Initialized in {@code onEnable()} via {@code BukkitAudiences.create(this)} and
     * must be closed in {@code onDisable()} to prevent memory leaks.
     */
    private  BukkitAudiences adventure;

    /**
     * MiniMessage instance used for deserializing Adventure mini-message strings
     * (e.g., <red>, <bold>, etc.) into formatted components.
     */
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // ------------------------------------------------------------------------------------------------------

    /**
     * Plugin enables lifecycle method.
     * Initializes Adventure, registers listeners, and ensures TAB plugin is loaded.
     */
    @Override
    public void onEnable() {
        this.adventure = BukkitAudiences.create(this);
        this.getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("pvp")).setExecutor(this);
        Objects.requireNonNull(getCommand("pvp")).setTabCompleter(this);

        Plugin tabPlugin = getServer().getPluginManager().getPlugin("TAB");
        if (tabPlugin == null || !tabPlugin.isEnabled()) {
            logger.log(Level.SEVERE, "TAB plugin is not installed or enabled! Disabling PvPToggle.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    // ------------------------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("pvp")) return List.of();

        if (args.length == 1) {
            return Stream.of("on", "off", "persist", "list")
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            if (sender.hasPermission("pvp.others")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Plugin disable lifecycle method.
     * Cleans up Adventure and resets TAB prefixes.
     */
    @Override
    public void onDisable() {
        if (adventure != null) {
            adventure.close();
        }

        TabAPI tabAPI = TabAPI.getInstance();
        NameTagManager nameTagManager = tabAPI.getNameTagManager();
        TabListFormatManager tabListManager = tabAPI.getTabListFormatManager();

        for (UUID uuid : ENABLED_PLAYERS) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                TabPlayer tabPlayer = tabAPI.getPlayer(player.getUniqueId());
                if (tabPlayer != null) {
                    if (nameTagManager != null) {
                        nameTagManager.setPrefix(tabPlayer, null);
                    }
                    if (tabListManager != null) {
                        tabListManager.setPrefix(tabPlayer, null);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Handles the /pvp command.
     *
     * @param sender  Command sender
     * @param command Command object
     * @param label   Command label
     * @param args    Command arguments
     * @return true if command was handled, false otherwise
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("pvp")) return false;
        if (args.length == 0 || !sender.hasPermission("pvp.toggle")) return false;

        switch (args[0].toUpperCase()) {
            case "ON" -> {
                if (args.length == 1) {
                    if (!(sender instanceof Player player)) return false;
                    pvpStatusOn(player, "Player");
                    return true;
                }
                if (sender.hasPermission("pvp.others")) {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) return false;
                    pvpStatusOn(target, "Admin");
                    return true;
                }
                return false;
            }
            case "OFF" -> {
                if (args.length == 1) {
                    if (!(sender instanceof Player player)) return false;
                    pvpStatusOff(player, "Player");
                    return true;
                }
                if (sender.hasPermission("pvp.others")) {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) return false;
                    pvpStatusOff(target, "Admin");
                    return true;
                }
                return false;
            }
            case "PERSIST" -> {
                if (!(sender instanceof Player player)) return false;

                UUID uuid = player.getUniqueId();
                if (isPersisted(player)) {
                    PERSISTING_PLAYERS.remove(uuid);
                    adventure.player(player).sendMessage(miniMessage.deserialize("<gray>Persistent PvP <red>disabled</red>.</gray>"));
                    pvpStatusOff(player, "Player");
                } else {
                    PERSISTING_PLAYERS.add(uuid);
                    adventure.player(player).sendMessage(miniMessage.deserialize("<gray>Persistent PvP <dark_red>enabled</dark_red>.</gray>"));
                    pvpStatusOn(player, "Player");
                }
                return true;
            }
            case "LIST" -> {
                String playerList = ENABLED_PLAYERS.isEmpty()
                        ? "<gray>Players with PvP active: None!</gray>"
                        : "<gray>Players with PvP active:</gray> <green>" +
                        ENABLED_PLAYERS.stream()
                                .map(getServer()::getPlayer)
                                .filter(Objects::nonNull)
                                .map(Player::getName)
                                .collect(Collectors.joining(", ")) +
                        "</green>";
                adventure.sender(sender).sendMessage(miniMessage.deserialize(playerList));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Enables PvP for a player.
     *
     * @param player The player
     * @param reason Reason for enabling
     */
    private void pvpStatusOn(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        if (isActive(player)) {
            if ("Player".equalsIgnoreCase(reason)) {
                adventure.player(player).sendMessage(miniMessage.deserialize("<red>PvP is already enabled for you!</red>"));
            }
            return;
        }
        String msg = switch (reason.toLowerCase()) {
            case "player" -> "<red>" + player.getName() + " has turned their PvP on.</red>";
            case "admin" -> "<red>" + player.getName() + " has had their PvP turned on.</red>";
            default -> "";
        };
        ENABLED_PLAYERS.add(uuid);
        if (!msg.isEmpty()) {
            adventure.all().sendMessage(miniMessage.deserialize(msg));
        }
        checkPvPstate(player);
    }
    // ------------------------------------------------------------------------------------------------------

    /**
     * Disables PvP for a player.
     *
     * @param player The player
     * @param reason Reason for disabling
     */

    private void pvpStatusOff(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        if (!isActive(player)) {
            if ("Player".equalsIgnoreCase(reason)) {
                adventure.player(player).sendMessage(miniMessage.deserialize("<gray>PvP is already disabled for you!</gray>"));
            }
            return;
        }
        String msg = switch (reason.toLowerCase()) {
            case "player" -> "<green>" + player.getName() + " has turned their PvP off.</green>";
            case "admin" -> "<gray>" + player.getName() + " has had their <color:#aa4700> PvP turned off.</color></gray>";
            default -> "";
        };
        if (isPersisted(player)) {
            PERSISTING_PLAYERS.remove(uuid);
        }
        ENABLED_PLAYERS.remove(uuid);
        if (!msg.isEmpty()) {
            adventure.all().sendMessage(miniMessage.deserialize(msg));
        }
        checkPvPstate(player);
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Updates the player's name color and tab prefix depending on their PvP state.
     *
     * @param player The player being checked
     */
    public static void checkPvPstate(Player player) {
        TabAPI tabAPI = TabAPI.getInstance();
        TabPlayer tabPlayer = tabAPI.getPlayer(player.getUniqueId());

        NameTagManager nameTagManager = tabAPI.getNameTagManager();
        TabListFormatManager tabListManager = tabAPI.getTabListFormatManager();

        if (tabPlayer != null) {
            String prefix = isActive(player) ? "<bold><dark_red>[PVP] </dark_red></bold>" : null;

            if (nameTagManager != null) {
                nameTagManager.setPrefix(tabPlayer, prefix);
            }

            if (tabListManager != null) {
                tabListManager.setPrefix(tabPlayer, prefix);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Checks if a player has PvP enabled.
     *
     * @param player The player
     * @return true if PvP is enabled
     */
    public static boolean isActive(Player player) {
        return ENABLED_PLAYERS.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Checks if a player has PvP persistence enabled.
     *
     * @param player The player
     * @return true if PvP persistence is enabled
     */
    public static boolean isPersisted(@NotNull Player player) {
        return PERSISTING_PLAYERS.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Prevents WorldGuard from blocking PvP between players who both have PvP enabled.
     *
     * @param event Disallowed PvP event from WorldGuard
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
     * Disables PvP when a player dies, unless persistence is enabled.
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
     * Disables PvP when a player quits.
     *
     * @param event The player quit event
     */

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        Player leaver = event.getPlayer();
        if (isActive(leaver)) {
            pvpStatusOff(leaver, "Leave");
        }
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * Disables PvP when a player is kicked.
     *
     * @param event The player kick event
     */

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        Player leaver = event.getPlayer();
        if (isActive(leaver)) {
            pvpStatusOff(leaver, "Leave");
        }
    }
}
