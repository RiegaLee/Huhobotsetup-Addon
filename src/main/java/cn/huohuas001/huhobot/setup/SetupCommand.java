package cn.huohuas001.huhobot.setup;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class SetupCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = Arrays.asList("status", "cancel", "ui");
    private static final List<String> UI_MODES = Arrays.asList("auto", "dialog", "menu");

    private enum UiMode {
        AUTO,
        DIALOG,
        MENU
    }

    private final SetupService service;
    private final DialogSetupUi dialogUi;
    private final DialogSetupUi secondaryDialogUi;
    private final AnvilSetupUi anvilUi;
    private final EnrollmentService enrollment;
    private UiMode uiMode = UiMode.AUTO;

    SetupCommand(
        SetupService service,
        DialogSetupUi dialogUi,
        DialogSetupUi secondaryDialogUi,
        AnvilSetupUi anvilUi,
        EnrollmentService enrollment
    ) {
        this.service = service;
        this.dialogUi = dialogUi;
        this.secondaryDialogUi = secondaryDialogUi;
        this.anvilUi = anvilUi;
        this.enrollment = enrollment;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "setup" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setup":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.YELLOW + "游戏内玩家可执行 /" + label + " 打开可视化向导。");
                    sender.sendMessage(ChatColor.GRAY + "控制台请编辑 " + service.getHost().getDataFolder() + "\\config.yml。");
                    return true;
                }
                Player player = (Player) sender;
                openSetup(player);
                return true;
            case "ui":
                switchUi(sender, label, args);
                return true;
            case "group-code":
                enrollment.issueCode(sender);
                return true;
            case "confirm":
                if (!(sender instanceof Player) || args.length < 2) return true;
                enrollment.confirm((Player) sender, args[1]);
                return true;
            case "reject":
                if (!(sender instanceof Player) || args.length < 2) return true;
                enrollment.reject((Player) sender, args[1]);
                return true;
            case "status":
                sendStatus(sender);
                return true;
            case "cancel":
                if (sender instanceof Player) {
                    anvilUi.cancel((Player) sender, true);
                    enrollment.cancelWaiting((Player) sender);
                }
                else sender.sendMessage(ChatColor.YELLOW + "控制台没有正在进行的界面会话。");
                return true;
            default:
                sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " [status|cancel|ui <auto|dialog|menu>]");
                return true;
        }
    }

    private void switchUi(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " ui <auto|dialog|menu>");
            sender.sendMessage(ChatColor.GRAY + "当前模式：" + uiModeName());
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "auto":
                uiMode = UiMode.AUTO;
                break;
            case "dialog":
                if (!dialogUi.isAvailable() && !secondaryDialogUi.isAvailable()) {
                    sender.sendMessage(ChatColor.RED + "当前服务端不支持 Dialog，无法切换到该模式。");
                    return;
                }
                uiMode = UiMode.DIALOG;
                break;
            case "menu":
                uiMode = UiMode.MENU;
                break;
            default:
                sender.sendMessage(ChatColor.YELLOW + "可选模式：auto、dialog、menu。");
                return;
        }
        sender.sendMessage(ChatColor.GREEN + "界面模式已切换为：" + uiModeName() + ChatColor.GRAY + "（重启后恢复自动）");
        if (sender instanceof Player) openSetup((Player) sender);
    }

    private void openSetup(Player player) {
        if (uiMode == UiMode.MENU) {
            anvilUi.open(player);
            return;
        }
        if (dialogUi.isAvailable() && dialogUi.open(player)) return;
        if (secondaryDialogUi.isAvailable() && secondaryDialogUi.open(player)) return;
        if (uiMode == UiMode.DIALOG) {
            player.sendMessage(ChatColor.RED + "Dialog 打开失败，请切回 /huhobotsetup ui auto 或 menu。");
            return;
        }
        anvilUi.open(player);
    }

    private void sendStatus(CommandSender sender) {
        SetupDraft current = service.newDraft(false);
        sender.sendMessage(ChatColor.AQUA + "HuHoBot 初始化状态");
        sender.sendMessage(ChatColor.GRAY + "AppID：" + mask(current.getAppId()));
        sender.sendMessage(ChatColor.GRAY + "Secret：" + (service.hasStoredSecret() ? "已设置" : "未设置"));
        sender.sendMessage(ChatColor.GRAY + "机器人名称：" + current.getBotName());
        sender.sendMessage(ChatColor.GRAY + "已接入群：" + service.getHost().getConfig().getStringList("bot.groups").size());
        sender.sendMessage(ChatColor.GRAY + "界面模式：" + uiModeName());
        sender.sendMessage(ChatColor.GRAY + "当前界面：" + resolvedUiName());
    }

    private String uiModeName() {
        switch (uiMode) {
            case DIALOG:
                return "强制 Dialog";
            case MENU:
                return "强制箱子菜单";
            default:
                return "自动选择";
        }
    }

    private String resolvedUiName() {
        if (uiMode == UiMode.MENU) return "箱子菜单＋书本表单";
        if (dialogUi.isAvailable()) return dialogUi.implementationName();
        if (secondaryDialogUi.isAvailable()) return secondaryDialogUi.implementationName();
        return uiMode == UiMode.DIALOG ? "Dialog（当前不可用）" : "箱子菜单＋书本表单";
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "未设置";
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "…" + value.substring(value.length() - 2);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values;
        String prefix;
        if (args.length == 1) {
            values = SUBCOMMANDS;
            prefix = args[0].toLowerCase(Locale.ROOT);
        } else if (args.length == 2 && "ui".equalsIgnoreCase(args[0])) {
            values = UI_MODES;
            prefix = args[1].toLowerCase(Locale.ROOT);
        } else {
            return Collections.emptyList();
        }
        List<String> matches = new ArrayList<>();
        for (String value : values) if (value.startsWith(prefix)) matches.add(value);
        return matches;
    }
}
