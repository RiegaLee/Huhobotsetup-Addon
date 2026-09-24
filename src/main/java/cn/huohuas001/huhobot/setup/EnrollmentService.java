package cn.huohuas001.huhobot.setup;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EnrollmentService {
    private static final String ADD_TO_GROUP_GUIDE = "/onboarding/add-to-group.png";
    private static final String PERMISSION_GUIDE = "/onboarding/permission-settings.png";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Pattern COMMAND = Pattern.compile(
        "^/?接入\\s*([A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2})\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MENTION = Pattern.compile("<@!?[^>]+>");

    private final JavaPlugin plugin;
    private final SetupService setupService;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, EnrollmentToken> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingGameReceipts = new ConcurrentHashMap<>();
    private final Object confirmationLock = new Object();
    private WaitingSession waitingSession;
    private ConfirmationRequest confirmationRequest;
    private final Listener eventListener = new Listener() { };
    private final Listener receiptListener = new ReceiptListener();

    EnrollmentService(JavaPlugin plugin, SetupService setupService) {
        this.plugin = plugin;
        this.setupService = setupService;
    }

    boolean initialize() {
        try {
            Class<?> eventType = Class.forName(
                "cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg",
                true,
                setupService.getHost().getClass().getClassLoader()
            );
            @SuppressWarnings("unchecked")
            Class<? extends Event> typedEvent = (Class<? extends Event>) eventType.asSubclass(Event.class);
            EventExecutor executor = (listener, event) -> handleMessage(event);
            Bukkit.getPluginManager().registerEvent(
                typedEvent, eventListener, EventPriority.LOWEST, executor, plugin, true
            );
            Bukkit.getPluginManager().registerEvents(receiptListener, plugin);
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("无法连接 HuHoBot 群消息事件，QQ 群确认接入功能不可用: " + rootMessage(error));
            return false;
        }
    }

    String issueCode(CommandSender sender) {
        cleanupExpired();
        String code;
        do {
            code = randomPart(4) + "-" + randomPart(4) + "-" + randomPart(4);
        } while (tokens.containsKey(code));
        long seconds = Math.max(60L, plugin.getConfig().getLong("enrollment.code-expiry-seconds", 600L));
        Player player = sender instanceof Player ? (Player) sender : null;
        tokens.put(code, new EnrollmentToken(
            System.currentTimeMillis() + seconds * 1000L,
            player == null ? null : player.getUniqueId()
        ));
        sender.sendMessage(ChatColor.GREEN + "一次性群接入码：" + ChatColor.AQUA + code);
        sender.sendMessage(ChatColor.GRAY + "请在目标 QQ 群中发送：@机器人 /接入" + code);
        sendCopyButton(sender, code);
        sender.sendMessage(ChatColor.GRAY + "无需等待机器人主动发言；默认仅接收 @ 消息也能完成接入。");
        sender.sendMessage(ChatColor.GRAY + "接入码 " + seconds / 60L + " 分钟内有效，成功后立即作废。");
        return code;
    }

    void beginConfirmation(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.YELLOW + "请由游戏内 OP 打开接入向导，以便在游戏中确认目标 QQ 群。");
            return;
        }

        Player player = (Player) sender;
        long seconds = Math.max(60L, plugin.getConfig().getLong("enrollment.waiting-expiry-seconds", 600L));
        synchronized (confirmationLock) {
            waitingSession = new WaitingSession(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
            confirmationRequest = null;
        }

        player.sendMessage(ChatColor.GREEN + "正在等待目标 QQ 群的 @ 消息……");
        player.sendMessage(ChatColor.GRAY + "请在要接入的群里发送：@机器人 这是我要接入的群");
        player.sendMessage(ChatColor.GRAY + "收到后，游戏会显示发送者昵称和消息内容，由你点击确认或取消。");
        player.sendMessage(ChatColor.GRAY + "等待时间为 " + seconds / 60L + " 分钟；期间只取第一条候选消息。");
    }

    void confirm(Player player, String requestId) {
        ConfirmationRequest request = takeConfirmation(player, requestId, true);
        if (request == null) return;

        JavaPlugin host = setupService.getHost();
        FileConfiguration config = host.getConfig();
        List<String> groups = new ArrayList<>(config.getStringList("bot.groups"));
        boolean newlyAdded = !groups.contains(request.groupOpenId);
        if (newlyAdded) {
            groups.add(request.groupOpenId);
            config.set("bot.groups", groups);
            host.saveConfig();
        }

        player.sendMessage(ChatColor.GREEN + (newlyAdded
            ? "已确认并接入这个 QQ 群。"
            : "已确认：这个 QQ 群原本就已经接入。"));
        try {
            replyWithPermissionGuide(
                request.event,
                permissionGuideText()
            );
        } catch (Throwable error) {
            plugin.getLogger().warning("确认群接入后发送教程失败: " + rootMessage(error));
            player.sendMessage(ChatColor.YELLOW + "群已记录，但 QQ 权限教程发送失败；可稍后重新执行添加群流程。");
        }
        plugin.getLogger().info("一个 QQ 群已由游戏内 OP 确认接入（群标识未写入日志）");
    }

    void reject(Player player, String requestId) {
        ConfirmationRequest request = takeConfirmation(player, requestId, false);
        if (request == null) return;
        player.sendMessage(ChatColor.YELLOW + "已取消这次 QQ 群接入，没有修改配置。");
        try {
            reply(request.event, "服务器管理员取消了这次接入，请确认群聊后重新在游戏内发起。");
        } catch (Throwable error) {
            plugin.getLogger().warning("发送取消接入回执失败: " + rootMessage(error));
        }
    }

    void cancelWaiting(Player player) {
        boolean cancelled = false;
        synchronized (confirmationLock) {
            if (waitingSession != null && waitingSession.playerId.equals(player.getUniqueId())) {
                waitingSession = null;
                confirmationRequest = null;
                cancelled = true;
            }
        }
        if (cancelled) player.sendMessage(ChatColor.YELLOW + "已停止等待 QQ 群接入消息。");
    }

    private void sendCopyButton(CommandSender sender, String code) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.GRAY + "可复制内容：/接入" + code);
            return;
        }

        String command = "/接入" + code;
        TextComponent button = new TextComponent("[点击复制接入指令]");
        button.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        button.setBold(true);
        button.setUnderlined(true);
        button.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, command));

        TextComponent hint = new TextComponent(" 复制后在 QQ 群中先 @机器人，再粘贴发送");
        hint.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        button.addExtra(hint);
        ((Player) sender).spigot().sendMessage(button);
    }

    void clear() {
        tokens.clear();
        pendingGameReceipts.clear();
        synchronized (confirmationLock) {
            waitingSession = null;
            confirmationRequest = null;
        }
    }

    private void handleMessage(Event event) {
        try {
            Object msgPack = event.getClass().getMethod("getMsgPack").invoke(event);
            String content = String.valueOf(msgPack.getClass().getMethod("getContent").invoke(msgPack));
            String code = parseEnrollmentCode(content);
            if (code == null) {
                boolean captured = handleConfirmationCandidate(event, msgPack, content);
                if (!captured && shouldBlockUntilFirstConfirmation()) cancel(event);
                return;
            }
            cleanupExpired();
            EnrollmentToken token = tokens.remove(code);
            if (token == null || token.expiresAt < System.currentTimeMillis()) {
                reply(event, "接入码无效或已经过期，请让服务器管理员重新生成。");
                cancel(event);
                return;
            }

            String groupOpenId = String.valueOf(msgPack.getClass().getMethod("getGroupOpenId").invoke(msgPack));
            JavaPlugin host = setupService.getHost();
            FileConfiguration config = host.getConfig();
            List<String> groups = new ArrayList<>(config.getStringList("bot.groups"));
            boolean newlyAdded = !groups.contains(groupOpenId);
            if (newlyAdded) {
                groups.add(groupOpenId);
                config.set("bot.groups", groups);
                host.saveConfig();
                notifyIssuer(token, "QQ 群接入成功，" + botName()
                    + " 已记录该群，并在 QQ 群发送了权限设置教程。");
                replyWithPermissionGuide(event, permissionGuideText());
            } else {
                notifyIssuer(token, "QQ 群验证成功：该群已经接入 " + botName()
                    + "，权限设置教程已重新发送。");
                replyWithPermissionGuide(event, permissionGuideText());
            }
            cancel(event);
            plugin.getLogger().info("一个 QQ 群已通过一次性接入码加入 HuHoBot（群标识已安全写入配置，不在日志显示）");
        } catch (Throwable error) {
            plugin.getLogger().warning("处理群接入消息失败: " + rootMessage(error));
        }
    }

    private boolean handleConfirmationCandidate(Event event, Object msgPack, String content) throws Exception {
        long now = System.currentTimeMillis();
        WaitingSession session;
        String requestId;
        String groupOpenId;
        String senderName;
        String message;
        synchronized (confirmationLock) {
            cleanupConfirmationState(now);
            session = waitingSession;
            if (session == null || confirmationRequest != null) return false;

            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                waitingSession = null;
                return false;
            }

            requestId = randomPart(8);
            groupOpenId = String.valueOf(msgPack.getClass().getMethod("getGroupOpenId").invoke(msgPack));
            if (setupService.getHost().getConfig().getStringList("bot.groups").contains(groupOpenId)) {
                return false;
            }
            senderName = readSenderName(msgPack);
            message = displayMentionedMessage(content);
            long confirmationSeconds = Math.max(
                30L,
                plugin.getConfig().getLong("enrollment.confirmation-expiry-seconds", 120L)
            );
            confirmationRequest = new ConfirmationRequest(
                requestId,
                session.playerId,
                groupOpenId,
                senderName,
                message,
                event,
                now + confirmationSeconds * 1000L
            );
        }

        cancel(event);
        try {
            reply(event, "已将接入请求发送到游戏内，请等待服务器管理员确认。");
        } catch (Throwable error) {
            // The in-game confirmation is the source of truth. A QQ acknowledgement
            // failure must not prevent the OP from seeing and confirming the request.
            plugin.getLogger().warning("发送 QQ 接入等待回执失败，将继续游戏内确认: " + rootMessage(error));
        }
        plugin.getLogger().info("已捕获一条未接入 QQ 群的候选消息，等待游戏内 OP 确认");
        Bukkit.getScheduler().runTask(plugin, () -> showConfirmation(session.playerId, requestId));
        return true;
    }

    private boolean shouldBlockUntilFirstConfirmation() {
        return plugin.getConfig().getBoolean("enrollment.block-unconfirmed-groups", true)
            && setupService.getHost().getConfig().getStringList("bot.groups").isEmpty();
    }

    private void showConfirmation(UUID playerId, String requestId) {
        ConfirmationRequest request;
        synchronized (confirmationLock) {
            cleanupConfirmationState(System.currentTimeMillis());
            request = confirmationRequest;
            if (request == null || !request.id.equals(requestId) || !request.playerId.equals(playerId)) return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        player.sendMessage("");
        player.sendMessage(ChatColor.AQUA + "收到一条 QQ 群接入请求");
        player.sendMessage(ChatColor.GRAY + "发送者：" + ChatColor.WHITE + request.senderName);
        player.sendMessage(ChatColor.GRAY + "消息内容：" + ChatColor.WHITE + request.message);
        player.sendMessage(ChatColor.YELLOW + "这是你要接入的 QQ 群吗？请及时选择：");

        TextComponent confirm = new TextComponent("[确认绑定]");
        confirm.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        confirm.setBold(true);
        confirm.setClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/huhobotsetup confirm " + request.id
        ));
        TextComponent separator = new TextComponent("    ");
        TextComponent reject = new TextComponent("[取消]");
        reject.setColor(net.md_5.bungee.api.ChatColor.RED);
        reject.setBold(true);
        reject.setClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/huhobotsetup reject " + request.id
        ));
        confirm.addExtra(separator);
        confirm.addExtra(reject);
        player.spigot().sendMessage(confirm);
    }

    private ConfirmationRequest takeConfirmation(Player player, String requestId, boolean confirming) {
        synchronized (confirmationLock) {
            cleanupConfirmationState(System.currentTimeMillis());
            ConfirmationRequest request = confirmationRequest;
            if (request == null || requestId == null || !request.id.equalsIgnoreCase(requestId)) {
                player.sendMessage(ChatColor.RED + "这条接入请求不存在或已经过期，请重新开始添加 QQ 群。");
                return null;
            }
            if (!request.playerId.equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "只有发起本次接入的 OP 才能确认。");
                return null;
            }
            confirmationRequest = null;
            waitingSession = null;
            if (!confirming) cancel(request.event);
            return request;
        }
    }

    private void cleanupConfirmationState(long now) {
        if (waitingSession != null && waitingSession.expiresAt < now) {
            waitingSession = null;
            confirmationRequest = null;
            return;
        }
        if (confirmationRequest != null && confirmationRequest.expiresAt < now) {
            confirmationRequest = null;
            waitingSession = null;
        }
    }

    static String readSenderName(Object msgPack) {
        try {
            Object sender = msgPack.getClass().getMethod("getSender").invoke(msgPack);
            Object username = sender.getClass().getMethod("getUsername").invoke(sender);
            return displayValue(String.valueOf(username), "QQ 群成员", 64);
        } catch (Throwable ignored) {
            return "QQ 群成员";
        }
    }

    static boolean hasMention(Object msgPack, String content) {
        if (content != null && MENTION.matcher(content).find()) return true;
        try {
            Object mentions = msgPack.getClass().getMethod("getMentions").invoke(msgPack);
            return mentions instanceof Collection && !((Collection<?>) mentions).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String displayMentionedMessage(String content) {
        String original = content == null ? "" : content;
        if (MENTION.matcher(original).find()) return displayMessage(original);
        String remainder = displayValue(
            original.replace('\r', ' ').replace('\n', ' ').trim(),
            "",
            170
        );
        return remainder.isEmpty() ? "@机器人" : "@机器人 " + remainder;
    }

    static String displayMessage(String content) {
        String friendly = MENTION.matcher(content == null ? "" : content).replaceAll("@机器人");
        friendly = friendly.replace('\r', ' ').replace('\n', ' ').trim();
        return displayValue(friendly, "@机器人", 180);
    }

    private static String displayValue(String value, String fallback, int maxLength) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        clean = ChatColor.stripColor(clean);
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean) || "unknown".equalsIgnoreCase(clean)) {
            return fallback;
        }
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 1) + "…";
    }

    private void reply(Event event, String text) throws Exception {
        Method reply = event.getClass().getMethod("replyText", String.class);
        reply.invoke(event, text);
    }

    private void replyWithPermissionGuide(Event event, String text) throws Exception {
        Method replyImage;
        try {
            replyImage = event.getClass().getMethod("replyImage", byte[].class, String.class);
        } catch (NoSuchMethodException ignored) {
            plugin.getLogger().warning("当前 HuHoBot 核心不支持被动图片回复，已退回纯文本提示");
            reply(event, text);
            return;
        }

        byte[] addToGroup = readResource(ADD_TO_GROUP_GUIDE);
        byte[] permissionSettings = readResource(PERMISSION_GUIDE);
        boolean firstSent = addToGroup != null && invokeImageReply(
            replyImage,
            event,
            addToGroup,
            "【本群设置】" + botName() + " 已接入。请点击图片右上角圈出的齿轮，进入机器人设置。"
        );
        boolean secondSent = permissionSettings != null && invokeImageReply(
            replyImage,
            event,
            permissionSettings,
            text
        );
        if (firstSent && secondSent) return;

        plugin.getLogger().warning("接入引导图未能完整发送，已补发纯文本提示");
        if (!firstSent) {
            reply(event, "【本群设置】" + botName()
                + " 已接入。请打开机器人资料页，点击右上角的齿轮进入设置。");
        }
        if (!secondSent) {
            reply(event, text);
        }
    }

    private boolean invokeImageReply(Method method, Event event, byte[] imageBytes, String text) {
        try {
            Object result = method.invoke(event, imageBytes, text);
            return !(result instanceof Boolean) || ((Boolean) result);
        } catch (Throwable error) {
            plugin.getLogger().warning("发送接入引导图失败: " + rootMessage(error));
            return false;
        }
    }

    static String permissionGuideText() {
        return "【本群设置】以下权限仅群主可设置，请群主按图开启："
            + "\n1. 获取群内全部消息"
            + "\n2. 机器人主动在群聊内发言"
            + "\n群管理员不能代为设置；如果你不是群主，请联系群主操作。"
            + "\n群主找不到选项时，请先更新手机 QQ。";
    }

    private String botName() {
        return displayValue(
            setupService.getHost().getConfig().getString("bot.name", ""),
            "机器人",
            32
        );
    }

    private byte[] readResource(String path) {
        try (InputStream input = EnrollmentService.class.getResourceAsStream(path)) {
            if (input == null) {
                plugin.getLogger().warning("找不到权限引导图资源: " + path);
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException error) {
            plugin.getLogger().warning("读取权限引导图失败: " + rootMessage(error));
            return null;
        }
    }

    private void cancel(Event event) {
        try {
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
        } catch (Exception ignored) {
            // Older HuHoBot event revisions may not be cancellable; enrollment still succeeds.
        }
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private void notifyIssuer(EnrollmentToken token, String message) {
        if (token.playerId == null) {
            plugin.getLogger().info(message);
            return;
        }
        Runnable notify = () -> {
            Player player = Bukkit.getPlayer(token.playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.GREEN + message);
            } else {
                pendingGameReceipts.put(token.playerId, message);
            }
        };
        if (Bukkit.isPrimaryThread()) notify.run();
        else Bukkit.getScheduler().runTask(plugin, notify);
    }

    private String randomPart(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    static String parseEnrollmentCode(String content) {
        if (content == null) return null;
        String withoutMention = MENTION.matcher(content).replaceAll("").trim();
        Matcher matcher = COMMAND.matcher(withoutMention);
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class ReceiptListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            String message = pendingGameReceipts.remove(event.getPlayer().getUniqueId());
            if (message == null) return;
            Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> event.getPlayer().sendMessage(ChatColor.GREEN + message),
                1L
            );
        }
    }

    private static final class EnrollmentToken {
        private final long expiresAt;
        private final UUID playerId;

        private EnrollmentToken(long expiresAt, UUID playerId) {
            this.expiresAt = expiresAt;
            this.playerId = playerId;
        }
    }

    private static final class WaitingSession {
        private final UUID playerId;
        private final long expiresAt;

        private WaitingSession(UUID playerId, long expiresAt) {
            this.playerId = playerId;
            this.expiresAt = expiresAt;
        }
    }

    private static final class ConfirmationRequest {
        private final String id;
        private final UUID playerId;
        private final String groupOpenId;
        private final String senderName;
        private final String message;
        private final Event event;
        private final long expiresAt;

        private ConfirmationRequest(
            String id,
            UUID playerId,
            String groupOpenId,
            String senderName,
            String message,
            Event event,
            long expiresAt
        ) {
            this.id = id;
            this.playerId = playerId;
            this.groupOpenId = groupOpenId;
            this.senderName = senderName;
            this.message = message;
            this.event = event;
            this.expiresAt = expiresAt;
        }
    }
}
