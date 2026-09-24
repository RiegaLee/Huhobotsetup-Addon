package cn.huohuas001.huhobot.setup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class SpigotDialogSetupUi implements DialogSetupUi {
    private static final String ACTION_SAVE_AND_GROUP = "huhobotsetup:save_and_group";
    private static final int TEXT_INPUT_WIDTH = 300;

    private final JavaPlugin plugin;
    private final SetupService service;
    private final EnrollmentService enrollment;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Listener eventListener = new Listener() { };
    private boolean available;

    SpigotDialogSetupUi(JavaPlugin plugin, SetupService service, EnrollmentService enrollment) {
        this.plugin = plugin;
        this.service = service;
        this.enrollment = enrollment;
        this.available = initialize();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String implementationName() {
        return "Spigot 原生 Dialog";
    }

    @Override
    public boolean open(Player player) {
        if (!available) return false;
        try {
            Class<?> dialogClass = Class.forName("net.md_5.bungee.api.dialog.Dialog");
            Object dialog = buildDialog(service.newDraft(false));
            Player.class.getMethod("showDialog", dialogClass).invoke(player, dialog);
            active.add(player.getUniqueId());
            return true;
        } catch (Throwable error) {
            available = false;
            plugin.getLogger().warning("打开 Spigot Dialog 失败，后续改用箱子菜单和配置书: " + rootMessage(error));
            return false;
        }
    }

    void clear() {
        active.clear();
    }

    private boolean initialize() {
        try {
            Class<?> eventType = Class.forName("org.bukkit.event.player.PlayerCustomClickEvent");
            Class.forName("net.md_5.bungee.api.dialog.Dialog");
            Player.class.getMethod("showDialog", Class.forName("net.md_5.bungee.api.dialog.Dialog"));
            buildDialog(service.newDraft(false));
            @SuppressWarnings("unchecked")
            Class<? extends Event> typedEvent = (Class<? extends Event>) eventType.asSubclass(Event.class);
            EventExecutor executor = (listener, event) -> handleClick(event);
            Bukkit.getPluginManager().registerEvent(
                typedEvent, eventListener, EventPriority.NORMAL, executor, plugin, true
            );
            return true;
        } catch (Throwable error) {
            plugin.getLogger().warning("Spigot Dialog 接口检测失败: " + rootMessage(error));
            return false;
        }
    }

    private void handleClick(Event event) {
        try {
            Object id = event.getClass().getMethod("getId").invoke(event);
            String key = String.valueOf(id);
            if (!ACTION_SAVE_AND_GROUP.equals(key)) return;
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!active.remove(player.getUniqueId())) return;
            Object data = event.getClass().getMethod("getData").invoke(event);
            if (data == null) return;
            Runnable apply = () -> applyResponse(player, data);
            if (event.isAsynchronous()) Bukkit.getScheduler().runTask(plugin, apply);
            else apply.run();
        } catch (Throwable error) {
            plugin.getLogger().warning("读取 Spigot Dialog 表单失败: " + rootMessage(error));
        }
    }

    private void applyResponse(Player player, Object json) {
        try {
            SetupDraft draft = new SetupDraft(
                jsonString(json, "app_id"),
                jsonString(json, "secret"),
                jsonString(json, "bot_name"),
                service.newDraft(false).isSuppressConsoleOutput()
            );
            String error = service.validate(draft);
            if (error != null) {
                player.sendMessage(ChatColor.RED + error + "，请重新打开 /huhobotsetup。");
                return;
            }
            SetupService.ApplyResult result = service.apply(draft);
            service.sendApplyResult(player, result);
            if (result != SetupService.ApplyResult.RESTART_REQUIRED) {
                enrollment.beginConfirmation(player);
            }
        } catch (Throwable error) {
            player.sendMessage(ChatColor.RED + "保存配置失败，请查看控制台日志。");
            plugin.getLogger().warning("保存 Spigot Dialog 配置失败: " + rootMessage(error));
        }
    }

    private Object buildDialog(SetupDraft current) throws Exception {
        Class<?> baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
        Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
        Constructor<?> textConstructor = textComponent.getConstructor(String.class);
        Class<?> dialogInput = Class.forName("net.md_5.bungee.api.dialog.input.DialogInput");
        Class<?> textInput = Class.forName("net.md_5.bungee.api.dialog.input.TextInput");

        List<Object> inputs = new ArrayList<>();
        inputs.add(spigotTextInput(textConstructor, textInput, "app_id", "QQ 开放平台 AppID", current.getAppId(), 64));
        inputs.add(spigotTextInput(textConstructor, textInput, "secret", service.hasStoredSecret()
            ? "Secret（留空则保留现有密钥）" : "Secret", "", 256));
        inputs.add(spigotTextInput(textConstructor, textInput, "bot_name", "机器人名称", current.getBotName(), 32));
        Class<?> dialogBase = Class.forName("net.md_5.bungee.api.dialog.DialogBase");
        Object base = dialogBase.getConstructor(baseComponent).newInstance(textConstructor.newInstance("HuHoBot 初始化"));
        dialogBase.getMethod("inputs", List.class).invoke(base, inputs);
        dialogBase.getMethod("canCloseWithEscape", Boolean.class).invoke(base, true);

        Class<?> action = Class.forName("net.md_5.bungee.api.dialog.action.Action");
        Class<?> actionButton = Class.forName("net.md_5.bungee.api.dialog.action.ActionButton");
        Object saveAndGroup = spigotActionButton(
            textConstructor, baseComponent, action, actionButton, "保存并继续", ACTION_SAVE_AND_GROUP
        );
        Class<?> multiAction = Class.forName("net.md_5.bungee.api.dialog.MultiActionDialog");
        Object actions = Array.newInstance(actionButton, 1);
        Array.set(actions, 0, saveAndGroup);
        return multiAction.getConstructor(dialogBase, actions.getClass()).newInstance(base, actions);
    }

    private static Object spigotActionButton(
        Constructor<?> textConstructor,
        Class<?> baseComponent,
        Class<?> action,
        Class<?> actionButton,
        String label,
        String key
    ) throws Exception {
        Class<?> customAction = Class.forName("net.md_5.bungee.api.dialog.action.CustomClickAction");
        Object click = customAction.getConstructor(String.class).newInstance(key);
        return actionButton.getConstructor(baseComponent, action)
            .newInstance(textConstructor.newInstance(label), click);
    }

    private static Object spigotTextInput(
        Constructor<?> textConstructor,
        Class<?> textInput,
        String key,
        String label,
        String initial,
        int maxLength
    ) throws Exception {
        Class<?> baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
        Object input = textInput.getConstructor(String.class, baseComponent)
            .newInstance(key, textConstructor.newInstance(label));
        textInput.getMethod("width", Integer.class).invoke(input, TEXT_INPUT_WIDTH);
        textInput.getMethod("initial", String.class).invoke(input, initial == null ? "" : initial);
        textInput.getMethod("maxLength", Integer.class).invoke(input, maxLength);
        return input;
    }

    private static String jsonString(Object json, String key) throws Exception {
        Object element = jsonElement(json, key);
        return element == null ? "" : String.valueOf(element.getClass().getMethod("getAsString").invoke(element));
    }

    private static Object jsonElement(Object json, String key) throws Exception {
        Object object = json.getClass().getMethod("getAsJsonObject").invoke(json);
        return object.getClass().getMethod("get", String.class).invoke(object, key);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
