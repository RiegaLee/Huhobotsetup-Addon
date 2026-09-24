package cn.huohuas001.huhobot.setup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class PaperDialogSetupUi implements DialogSetupUi {
    private static final String ACTION_SAVE_AND_GROUP = "huhobotsetup:save_and_group";
    private static final int TEXT_INPUT_WIDTH = 300;

    private final JavaPlugin plugin;
    private final SetupService service;
    private final EnrollmentService enrollment;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Listener eventListener = new Listener() { };
    private boolean available;

    PaperDialogSetupUi(JavaPlugin plugin, SetupService service, EnrollmentService enrollment) {
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
        return "Paper 原生 Dialog";
    }

    @Override
    public boolean open(Player player) {
        if (!available) return false;
        try {
            Object dialog = buildDialog(service.newDraft(false));
            Class<?> dialogLike = Class.forName("net.kyori.adventure.dialog.DialogLike");
            Class<?> audience = Class.forName("net.kyori.adventure.audience.Audience");
            audience.getMethod("showDialog", dialogLike).invoke(player, dialog);
            active.add(player.getUniqueId());
            return true;
        } catch (Throwable error) {
            available = false;
            plugin.getLogger().warning("打开 Paper Dialog 失败，后续改用箱子菜单和配置书: " + rootMessage(error));
            return false;
        }
    }

    void clear() {
        active.clear();
    }

    private boolean initialize() {
        try {
            Class<?> eventType = Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
            Class.forName("io.papermc.paper.dialog.Dialog");
            Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
            buildDialog(service.newDraft(false));

            @SuppressWarnings("unchecked")
            Class<? extends Event> typedEvent = (Class<? extends Event>) eventType.asSubclass(Event.class);
            PluginManager manager = Bukkit.getPluginManager();
            EventExecutor executor = (listener, event) -> handleClick(event);
            manager.registerEvent(typedEvent, eventListener, EventPriority.NORMAL, executor, plugin, true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void handleClick(Event event) {
        try {
            Class<?> eventApi = Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent");
            Object identifier = eventApi.getMethod("getIdentifier").invoke(event);
            Class<?> keyApi = Class.forName("net.kyori.adventure.key.Key");
            String key = String.valueOf(keyApi.getMethod("asString").invoke(identifier));
            if (!ACTION_SAVE_AND_GROUP.equals(key)) return;

            Object connection = eventApi.getMethod("getCommonConnection").invoke(event);
            Class<?> gameConnection = Class.forName("io.papermc.paper.connection.PlayerGameConnection");
            if (!gameConnection.isInstance(connection)) return;
            Method getPlayer = gameConnection.getMethod("getPlayer");
            Player player = (Player) getPlayer.invoke(connection);
            if (!active.remove(player.getUniqueId())) return;

            Object view = eventApi.getMethod("getDialogResponseView").invoke(event);
            if (view == null) return;
            Runnable apply = () -> applyResponse(player, view);
            if (event.isAsynchronous()) Bukkit.getScheduler().runTask(plugin, apply);
            else apply.run();
        } catch (Throwable error) {
            plugin.getLogger().warning("读取 Paper Dialog 表单失败: " + rootMessage(error));
        }
    }

    private void applyResponse(Player player, Object view) {
        try {
            Class<?> responseView = Class.forName("io.papermc.paper.dialog.DialogResponseView");
            Method getText = responseView.getMethod("getText", String.class);
            SetupDraft draft = new SetupDraft(
                stringValue(getText.invoke(view, "app_id")),
                stringValue(getText.invoke(view, "secret")),
                stringValue(getText.invoke(view, "bot_name")),
                service.newDraft(false).isSuppressConsoleOutput()
            );
            String error = service.validate(draft);
            if (error != null) {
                player.sendMessage(ChatColor.RED + error + "，请重新打开 /huhobotsetup。 ");
                return;
            }
            SetupService.ApplyResult result = service.apply(draft);
            service.sendApplyResult(player, result);
            if (result != SetupService.ApplyResult.RESTART_REQUIRED) {
                enrollment.beginConfirmation(player);
            }
        } catch (IllegalArgumentException error) {
            player.sendMessage(ChatColor.RED + error.getMessage());
        } catch (Throwable error) {
            player.sendMessage(ChatColor.RED + "保存配置失败，请查看控制台日志。");
            plugin.getLogger().warning("保存 Dialog 配置失败: " + rootMessage(error));
        }
    }

    private Object buildDialog(SetupDraft current) throws Exception {
        Class<?> component = Class.forName("net.kyori.adventure.text.Component");
        Class<?> dialogInput = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput");
        Class<?> textBuilder = Class.forName("io.papermc.paper.registry.data.dialog.input.TextDialogInput$Builder");
        List<Object> inputs = new ArrayList<>();

        inputs.add(textInput(component, dialogInput, textBuilder, "app_id", "QQ 开放平台 AppID", current.getAppId(), 64));
        inputs.add(textInput(component, dialogInput, textBuilder, "secret", service.hasStoredSecret()
            ? "Secret（留空则保留现有密钥）" : "Secret", "", 256));
        inputs.add(textInput(component, dialogInput, textBuilder, "bot_name", "机器人名称", current.getBotName(), 32));

        Class<?> dialogBase = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
        Class<?> baseBuilder = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase$Builder");
        Object base = dialogBase.getMethod("builder", component).invoke(null, text(component, "HuHoBot 初始化"));
        baseBuilder.getMethod("canCloseWithEscape", boolean.class).invoke(base, true);
        baseBuilder.getMethod("inputs", List.class).invoke(base, inputs);
        Object builtBase = baseBuilder.getMethod("build").invoke(base);

        Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
        Class<?> binaryTag = Class.forName("net.kyori.adventure.nbt.api.BinaryTagHolder");
        Class<?> dialogAction = Class.forName("io.papermc.paper.registry.data.dialog.action.DialogAction");
        Class<?> actionButton = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton");
        Class<?> actionButtonBuilder = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton$Builder");

        Object builtSaveAndGroup = actionButton(
            component, keyClass, binaryTag, dialogAction, actionButton, actionButtonBuilder,
            "保存并继续", ACTION_SAVE_AND_GROUP
        );
        Object cancel = actionButton.getMethod("builder", component).invoke(null, text(component, "取消"));
        Object builtCancel = actionButtonBuilder.getMethod("build").invoke(cancel);

        Class<?> dialogType = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
        Object multiAction = dialogType.getMethod("multiAction", List.class, actionButton, int.class)
            .invoke(null, java.util.Collections.singletonList(builtSaveAndGroup), builtCancel, 1);

        Class<?> dialog = Class.forName("io.papermc.paper.dialog.Dialog");
        Class<?> registryFactory = Class.forName("io.papermc.paper.registry.RegistryBuilderFactory");
        Class<?> entryBuilder = Class.forName("io.papermc.paper.registry.data.dialog.DialogRegistryEntry$Builder");
        Class<?> dialogTypeInterface = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType");
        Consumer<Object> consumer = factory -> {
            try {
                Object builder = registryFactory.getMethod("empty").invoke(factory);
                entryBuilder.getMethod("base", dialogBase).invoke(builder, builtBase);
                entryBuilder.getMethod("type", dialogTypeInterface).invoke(builder, multiAction);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        return dialog.getMethod("create", Consumer.class).invoke(null, consumer);
    }

    private static Object actionButton(
        Class<?> component,
        Class<?> keyClass,
        Class<?> binaryTag,
        Class<?> dialogAction,
        Class<?> actionButton,
        Class<?> actionButtonBuilder,
        String label,
        String actionKey
    ) throws Exception {
        Object key = keyClass.getMethod("key", String.class).invoke(null, actionKey);
        Object action = dialogAction.getMethod("customClick", keyClass, binaryTag).invoke(null, key, null);
        Object builder = actionButton.getMethod("builder", component).invoke(null, text(component, label));
        actionButtonBuilder.getMethod("action", dialogAction).invoke(builder, action);
        return actionButtonBuilder.getMethod("build").invoke(builder);
    }

    private static Object textInput(
        Class<?> component,
        Class<?> dialogInput,
        Class<?> textBuilder,
        String key,
        String label,
        String initial,
        int maxLength
    ) throws Exception {
        Object builder = dialogInput.getMethod("text", String.class, component)
            .invoke(null, key, text(component, label));
        textBuilder.getMethod("width", int.class).invoke(builder, TEXT_INPUT_WIDTH);
        textBuilder.getMethod("initial", String.class).invoke(builder, initial == null ? "" : initial);
        textBuilder.getMethod("maxLength", int.class).invoke(builder, maxLength);
        return textBuilder.getMethod("build").invoke(builder);
    }

    private static Object text(Class<?> component, String value) throws Exception {
        return component.getMethod("text", String.class).invoke(null, value);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
