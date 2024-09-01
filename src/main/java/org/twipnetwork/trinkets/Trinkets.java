package org.twipnetwork.trinkets;

import io.th0rgal.oraxen.api.OraxenItems;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

public final class Trinkets extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private FileConfiguration config;
    private FileConfiguration messages;
    private Map<Integer, String> trinketSlots;
    private String trinketsMenuTitle;
    private final Map<UUID, Inventory> playerMenus = new HashMap<>();
    private final Map<UUID, BukkitRunnable> trinketEffectTasks = new HashMap<>();
    private String prefix;
    private ItemStack emptySlotItem;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("Oraxen") == null) {
            getLogger().severe("Oraxen is not installed! Disabling Trinkets plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                loadEmptySlotItem();
            }
        }.runTaskLater(this, 40L);

        saveDefaultConfig();
        loadMessages();
        config = getConfig();
        loadTrinketSlots();
        loadPrefix();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("trinkets").setExecutor(this);
        getCommand("trinkets").setTabCompleter(this);

        logMessage(prefix + "<dark_aqua>" + getName() + " " + getDescription().getVersion() + " is starting up.");
    }

    @Override
    public void onDisable() {
        logMessage(prefix + "<dark_aqua>Disabling " + getName() + " " + getDescription().getVersion() + ".");
        trinketEffectTasks.values().forEach(BukkitRunnable::cancel);
    }

    private void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadPrefix() {
        prefix = messages.getString("prefix", "<dark_purple>[<light_purple>ɴᴏᴛɪᴏɴ<dark_purple>] ");
    }

    private String getMessage(String key) {
        return prefix + messages.getString(key, "<dark_red>Message not found: " + key);
    }

    private void loadTrinketSlots() {
        reloadConfig();
        config = getConfig();

        trinketsMenuTitle = config.getString("trinkets_menu.title", "");
        trinketSlots = new HashMap<>();

        for (String key : config.getConfigurationSection("trinkets_menu.slots").getKeys(false)) {
            int slot = Integer.parseInt(key);
            String type = config.getString("trinkets_menu.slots." + key);
            trinketSlots.put(slot, type);
        }
    }

    private void loadEmptySlotItem() {
        String emptySlotConfig = config.getString("trinkets_menu.empty_slot", "LIGHT_GRAY_STAINED_GLASS_PANE");
        if (emptySlotConfig.startsWith("oraxen:")) {
            String oraxenId = emptySlotConfig.split(":")[1];
            emptySlotItem = OraxenItems.getItemById(oraxenId).build();
        } else {
            Material material = Material.matchMaterial(emptySlotConfig);
            if (material != null) {
                emptySlotItem = new ItemStack(material);
            } else {
                emptySlotItem = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            }
        }
    }

    public void openTrinketGUI(Player player) {
        Inventory gui = playerMenus.get(player.getUniqueId());
        if (gui == null) {
            gui = Bukkit.createInventory(player, 54, trinketsMenuTitle);
            playerMenus.put(player.getUniqueId(), gui);
        }
        fillUndefinedSlotsWithBlank(gui);
        syncArmorWithGUI(player, gui);
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();

        if (clickedInventory == null || !playerMenus.get(player.getUniqueId()).equals(clickedInventory)) return;

        int slot = event.getSlot();
        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();
        String slotType = trinketSlots.get(slot);

        if (clickedItem != null && clickedItem.isSimilar(emptySlotItem)) {
            event.setCancelled(true);
            return;
        }

        if (cursorItem != null && cursorItem.getType() != Material.AIR) {
            clickedInventory.setItem(slot, cursorItem);
            event.setCursor(new ItemStack(Material.AIR));
            if (OraxenItems.exists(cursorItem)) {
                applyTrinketEffects(player, slotType, OraxenItems.getIdByItem(cursorItem));
            }
        } else if (clickedItem != null && clickedItem.getType() != Material.AIR) {
            event.setCursor(clickedItem);
            clickedInventory.setItem(slot, new ItemStack(Material.AIR));
            if (OraxenItems.exists(clickedItem)) {
                removeTrinketEffects(player, slotType, OraxenItems.getIdByItem(clickedItem));
            }
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();

        if (playerMenus.get(player.getUniqueId()).equals(inventory)) {
            syncGUIWithArmor(player, inventory);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Inventory gui = playerMenus.get(player.getUniqueId());

        for (Map.Entry<Integer, String> entry : trinketSlots.entrySet()) {
            int slot = entry.getKey();
            String slotType = entry.getValue();
            ItemStack item = gui.getItem(slot);

            if (item != null && OraxenItems.exists(item)) {
                applyTrinketEffects(player, slotType, OraxenItems.getIdByItem(item));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Inventory inventory = playerMenus.get(playerId);

        if (inventory != null) {
            syncGUIWithArmor(event.getPlayer(), inventory);
        }
        trinketEffectTasks.remove(playerId);
    }

    private void fillUndefinedSlotsWithBlank(Inventory gui) {
        for (int slot = 0; slot < gui.getSize(); slot++) {
            if (!trinketSlots.containsKey(slot)) {
                gui.setItem(slot, emptySlotItem);
            }
        }
    }

    private boolean isValidItemForSlot(ItemStack item, String slotType) {
        if (item == null || item.getType() == Material.AIR) return true;

        switch (slotType) {
            case "helmet":
                return item.getType().name().endsWith("_HELMET");
            case "chestplate":
                return item.getType().name().endsWith("_CHESTPLATE");
            case "leggings":
                return item.getType().name().endsWith("_LEGGINGS");
            case "boots":
                return item.getType().name().endsWith("_BOOTS");
            default:
                return OraxenItems.exists(item) && isAllowedTrinket(slotType, OraxenItems.getIdByItem(item));
        }
    }

    private boolean isArmorSlot(String slotType) {
        return slotType.equals("helmet") || slotType.equals("chestplate") || slotType.equals("leggings") || slotType.equals("boots");
    }

    private boolean isAllowedTrinket(String slotType, String oraxenId) {
        return config.isSet(slotType + "_trinkets." + oraxenId);
    }

    private void syncArmorWithGUI(Player player, Inventory gui) {
        gui.setItem(10, player.getInventory().getHelmet());
        gui.setItem(19, player.getInventory().getChestplate());
        gui.setItem(28, player.getInventory().getLeggings());
        gui.setItem(37, player.getInventory().getBoots());
    }

    private void syncGUIWithArmor(Player player, Inventory gui) {
        player.getInventory().setHelmet(gui.getItem(10));
        player.getInventory().setChestplate(gui.getItem(19));
        player.getInventory().setLeggings(gui.getItem(28));
        player.getInventory().setBoots(gui.getItem(37));
    }

    private void applyTrinketEffects(Player player, String slotType, String oraxenId) {
        UUID playerId = player.getUniqueId();
        if (trinketEffectTasks.containsKey(playerId)) {
            trinketEffectTasks.get(playerId).cancel();
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && playerMenus.get(playerId).getItem(trinketSlots.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(slotType))
                        .map(Map.Entry::getKey)
                        .findFirst().orElse(-1)) != null) {

                    config.getConfigurationSection(slotType + "_trinkets." + oraxenId + ".effects")
                            .getKeys(false).forEach(effectTypeStr -> {
                                PotionEffectType effectType = PotionEffectType.getByName(effectTypeStr.toUpperCase());
                                if (effectType != null) {
                                    int amplifier = config.getInt(slotType + "_trinkets." + oraxenId + ".effects." + effectTypeStr + ".amplifier", 0);
                                    boolean ambient = config.getBoolean(slotType + "_trinkets." + oraxenId + ".effects." + effectTypeStr + ".ambient", true);
                                    boolean particles = config.getBoolean(slotType + "_trinkets." + oraxenId + ".effects." + effectTypeStr + ".particles", true);
                                    boolean hasIcon = config.getBoolean(slotType + "_trinkets." + oraxenId + ".effects." + effectTypeStr + ".has_icon", true);

                                    player.addPotionEffect(new PotionEffect(effectType, 100, amplifier, ambient, particles, hasIcon));
                                }
                            });
                } else {
                    cancel();
                }
            }
        };
        task.runTaskTimer(this, 0, 20);
        trinketEffectTasks.put(playerId, task);
    }

    private void removeTrinketEffects(Player player, String slotType, String oraxenId) {
        UUID playerId = player.getUniqueId();
        if (trinketEffectTasks.containsKey(playerId)) {
            trinketEffectTasks.get(playerId).cancel();
            trinketEffectTasks.remove(playerId);
        }
    }

    public void logMessage(String message) {
        Bukkit.getConsoleSender().sendMessage(MiniMessage.miniMessage().deserialize(message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(getMessage("command_usage")));
            return false;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("trinkets.reload")) {
                reloadConfig();
                config = getConfig();

                loadTrinketSlots();
                loadEmptySlotItem();
                loadPrefix();

                trinketsMenuTitle = config.getString("trinkets_menu.title", "");

                sender.sendMessage(MiniMessage.miniMessage().deserialize(getMessage("config_reloaded")));
                return true;
            } else {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(getMessage("no_permission")));
                return false;
            }
        }

        if (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("open")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                openTrinketGUI(player);
                return true;
            } else {
                sender.sendMessage(MiniMessage.miniMessage().deserialize(getMessage("player_only_command")));
                return false;
            }
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize(getMessage("command_usage")));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("trinkets.reload")) {
                completions.add("reload");
            }
            completions.add("gui");
            completions.add("open");
            return completions;
        }
        return null;
    }
}
