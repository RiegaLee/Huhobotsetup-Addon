package cn.huohuas001.huhobot.setup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy-version setup UI. The class name is retained for binary/source history,
 * but configuration input is now handled by one editable book instead of anvils.
 */
final class AnvilSetupUi implements Listener {
    private static final String APP_ID_LABEL = "填写QQ开放平台AppID";
    private static final String SECRET_LABEL = "填写Secret";
    private static final String BOT_NAME_LABEL = "填写机器人名称";

    private static final class Session {
        SetupDraft draft;
        boolean bookActive;
        int bookSlot = -1;
        ItemStack previousItem;

        Session(SetupDraft draft) {
            this.draft = draft;
        }
    }

    private final JavaPlugin plugin;
    private final SetupService service;
    private final EnrollmentService enrollment;
    private final NamespacedKey setupBookKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Inventory> dashboards = new HashMap<>();

    AnvilSetupUi(JavaPlugin plugin, SetupService service, EnrollmentService enrollment) {
        this.plugin = plugin;
        this.service = service;
        this.enrollment = enrollment;
        this.setupBookKey = new NamespacedKey(plugin, "setup-book");
    }

    void open(Player player) {
        cancel(player, false);
        openDashboard(player);
    }

    private void openDashboard(Player player) {
        Inventory menu = Bukkit.createInventory(null, 9, ChatColor.DARK_AQUA + "HuHoBot 初始化");
        menu.setItem(2, menuItem(
            Material.WRITABLE_BOOK,
            ChatColor.AQUA + "配置机器人",
            ChatColor.GRAY + "使用三页配置书一次填写全部项目"
        ));
        menu.setItem(4, menuItem(
            service.isConfigured() ? Material.ENDER_EYE : Material.GRAY_DYE,
            ChatColor.GREEN + "添加 QQ 群",
            service.isConfigured()
                ? ChatColor.GRAY + "等待目标群 @ 消息并在游戏内确认"
                : ChatColor.RED + "请先完成机器人配置"
        ));
        menu.setItem(6, menuItem(
            Material.BOOK,
            ChatColor.YELLOW + "查看状态",
            ChatColor.GRAY + "凭据：" + (service.isConfigured() ? "已配置" : "未配置"),
            ChatColor.GRAY + "已接入群：" + service.getHost().getConfig().getStringList("bot.groups").size()
        ));
        menu.setItem(8, menuItem(Material.BARRIER, ChatColor.RED + "关闭"));
        dashboards.put(player.getUniqueId(), menu);
        player.openInventory(menu);
    }

    private void openConfiguration(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            session = new Session(service.newDraft(false));
            sessions.put(player.getUniqueId(), session);
        }
        giveConfigurationBook(player, session, createConfigurationBook(session.draft));
    }

    void cancel(Player player, boolean notify) {
        Session removed = sessions.remove(player.getUniqueId());
        Inventory dashboard = dashboards.remove(player.getUniqueId());
        if (removed != null) restoreItem(player, removed);
        if (removed != null || dashboard != null) player.closeInventory();
        if (notify) player.sendMessage(ChatColor.YELLOW + "已取消 HuHoBot 配置向导。");
    }

    void clear() {
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) restoreItem(player, entry.getValue());
        }
        sessions.clear();
        dashboards.clear();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.bookActive) {
            if (isConfigurationBook(event.getCurrentItem()) || isConfigurationBook(event.getCursor())) {
                sessions.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> restoreItem(player, session));
            }
            return;
        }

        Inventory dashboard = dashboards.get(player.getUniqueId());
        if (dashboard == null || dashboard != event.getView().getTopInventory()) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= dashboard.getSize()) return;
        switch (event.getRawSlot()) {
            case 2:
                dashboards.remove(player.getUniqueId());
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> openConfiguration(player));
                return;
            case 4:
                if (!service.isConfigured()) {
                    player.sendMessage(ChatColor.RED + "请先点击“配置机器人”填写 AppID 和 Secret。");
                    return;
                }
                dashboards.remove(player.getUniqueId());
                sessions.remove(player.getUniqueId());
                player.closeInventory();
                enrollment.beginConfirmation(player);
                return;
            case 6:
                player.sendMessage(ChatColor.AQUA + "HuHoBot 状态："
                    + (service.isConfigured() ? "机器人凭据已配置" : "机器人凭据未配置")
                    + "，已接入 " + service.getHost().getConfig().getStringList("bot.groups").size() + " 个群。");
                return;
            case 8:
                cancel(player, false);
                return;
            default:
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        Inventory dashboard = dashboards.get(player.getUniqueId());
        if (dashboard == event.getInventory()) {
            dashboards.remove(player.getUniqueId());
            Session session = sessions.get(player.getUniqueId());
            if (session != null && !session.bookActive) sessions.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.bookActive || event.getSlot() != session.bookSlot) return;
        event.setCancelled(true);

        SetupDraft draft;
        try {
            draft = readDraft(event.getNewBookMeta(), session.draft);
        } catch (IllegalArgumentException error) {
            returnToDashboard(player, session, error.getMessage());
            return;
        }

        String validation = service.validate(draft);
        if (validation != null) {
            session.draft = draft;
            returnToDashboard(player, session, validation);
            return;
        }

        sessions.remove(player.getUniqueId());
        finishBookEdit(player, session);
        try {
            SetupService.ApplyResult result = service.apply(draft);
            service.sendApplyResult(player, result);
            if (result != SetupService.ApplyResult.RESTART_REQUIRED) enrollment.beginConfirmation(player);
        } catch (Exception error) {
            player.sendMessage(ChatColor.RED + "保存配置失败，请查看控制台日志。");
            plugin.getLogger().warning("书本配置向导保存失败: " + error.getMessage());
        }
    }

    @EventHandler
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session != null && session.bookActive) restoreItem(player, session);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session == null || !session.bookActive) return;

        // Perform the swap as if the temporary setup book were the player's
        // original held item, then discard the temporary book.
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack previous = session.previousItem == null ? null : session.previousItem.clone();
        event.setCancelled(true);
        player.getInventory().setItem(session.bookSlot, offHand == null ? null : offHand.clone());
        player.getInventory().setItemInOffHand(previous);
        finishSession(session);
        player.updateInventory();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!isConfigurationBook(event.getItemDrop().getItemStack())) return;
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        event.getItemDrop().remove();
        if (session != null && session.bookActive) restoreItem(player, session);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        dashboards.remove(event.getPlayer().getUniqueId());
        if (session != null) restoreItem(event.getPlayer(), session);
    }

    private void giveConfigurationBook(Player player, Session session, ItemStack book) {
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack previous = player.getInventory().getItem(slot);
        session.bookSlot = slot;
        session.previousItem = previous == null ? null : previous.clone();
        session.bookActive = true;
        player.getInventory().setItem(slot, book);
        player.updateInventory();
        player.sendMessage(ChatColor.AQUA + "HuHoBot 配置书已放到手中；填写内容不会进入聊天或聊天日志。");
        player.sendMessage(ChatColor.YELLOW + "请右键打开配置书，填写 3 页内容后点击“完成”。");
    }

    private ItemStack createConfigurationBook(SetupDraft current) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;
        meta.setDisplayName(ChatColor.AQUA + "HuHoBot 配置书（右键打开）");
        meta.setLore(Arrays.asList(
            ChatColor.GRAY + "第 1 页：QQ 开放平台 AppID",
            ChatColor.GRAY + "第 2 页：QQ 开放平台 Secret",
            ChatColor.GRAY + "第 3 页：自定义机器人名称"
        ));
        meta.getPersistentDataContainer().set(setupBookKey, PersistentDataType.BYTE, (byte) 1);
        meta.setPages(
            formPage(
                APP_ID_LABEL,
                current.getAppId()
            ),
            formPage(
                SECRET_LABEL,
                current.getSecret()
            ),
            formPage(
                BOT_NAME_LABEL,
                current.getBotName()
            )
        );
        book.setItemMeta(meta);
        return book;
    }

    private SetupDraft readDraft(BookMeta meta, SetupDraft current) {
        List<String> pages = meta.getPages();
        String appId = pageValue(pages, 0, APP_ID_LABEL);
        String secret = pageValue(pages, 1, SECRET_LABEL);
        String botName = pageValue(pages, 2, BOT_NAME_LABEL);
        return new SetupDraft(appId, secret, botName, current.isSuppressConsoleOutput());
    }

    static String formPage(String title, String value) {
        String clean = value == null ? "" : value.trim();
        return title + "\n\n" + clean;
    }

    static String pageValue(List<String> pages, int index, String label) {
        if (index >= pages.size()) return "";
        String text = pages.get(index) == null ? "" : pages.get(index).replace("\r", "").trim();
        if (text.equals(label)) return "";
        String prefix = label + "\n\n";
        return text.startsWith(prefix) ? text.substring(prefix.length()).trim() : text;
    }

    private void returnToDashboard(Player player, Session session, String problem) {
        finishBookEdit(player, session);
        player.sendMessage(ChatColor.RED + "还没有设置完成：" + problem + "。");
        player.sendMessage(ChatColor.YELLOW + "已返回设置菜单，点击“配置机器人”继续填写。");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) return;
            openDashboard(player);
        });
    }

    private void restoreItem(Player player, Session session) {
        if (!session.bookActive || session.bookSlot < 0) return;
        removeConfigurationBooks(player);

        ItemStack displaced = player.getInventory().getItem(session.bookSlot);
        if (displaced != null && displaced.getType() != Material.AIR) {
            player.getInventory().setItem(session.bookSlot, null);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(displaced);
            for (ItemStack item : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }

        player.getInventory().setItem(
            session.bookSlot,
            session.previousItem == null ? null : session.previousItem.clone()
        );
        player.updateInventory();
        finishSession(session);
    }

    /**
     * The client applies the edited book to the held slot after PlayerEditBookEvent
     * has returned. Restoring only inside the event can therefore be overwritten by
     * that late client sync. Restore immediately for responsiveness, then repeat on
     * the next server tick for the one inventory slot owned by this session.
     */
    private void finishBookEdit(Player player, Session session) {
        if (!session.bookActive || session.bookSlot < 0) return;
        final int slot = session.bookSlot;
        final ItemStack previous = session.previousItem == null ? null : session.previousItem.clone();
        restoreItem(player, session);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.getInventory().setItem(slot, previous == null ? null : previous.clone());
            player.updateInventory();
        });
    }

    private boolean isConfigurationBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(setupBookKey, PersistentDataType.BYTE);
    }

    private void removeConfigurationBooks(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isConfigurationBook(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (isConfigurationBook(player.getItemOnCursor())) player.setItemOnCursor(null);
    }

    private static void finishSession(Session session) {
        session.bookActive = false;
        session.bookSlot = -1;
        session.previousItem = null;
    }

    private static ItemStack menuItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
