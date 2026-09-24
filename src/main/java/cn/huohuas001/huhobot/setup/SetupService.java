package cn.huohuas001.huhobot.setup;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

final class SetupService {
    enum ApplyResult {
        STARTED_NOW,
        RELOADED,
        RESTART_REQUIRED
    }

    private final JavaPlugin addon;
    private final JavaPlugin host;

    SetupService(JavaPlugin addon, JavaPlugin host) {
        this.addon = addon;
        this.host = host;
    }

    SetupDraft newDraft(boolean exposeExistingSecret) {
        FileConfiguration config = host.getConfig();
        return new SetupDraft(
            config.getString("bot.app-id", ""),
            exposeExistingSecret ? config.getString("bot.secret", "") : "",
            config.getString("bot.name", "HuHoBot"),
            config.getBoolean("bot.suppress-console-output", true)
        );
    }

    boolean hasStoredSecret() {
        return !host.getConfig().getString("bot.secret", "").trim().isEmpty();
    }

    boolean isConfigured() {
        return !host.getConfig().getString("bot.app-id", "").trim().isEmpty() && hasStoredSecret();
    }

    String validate(SetupDraft draft) {
        String appId = draft.getAppId().trim();
        String secret = effectiveSecret(draft);
        String botName = draft.getBotName().trim();

        if (appId.isEmpty()) return "AppID 不能为空";
        if (appId.length() > 64 || containsWhitespace(appId)) return "AppID 格式不正确";
        if (secret.isEmpty()) return "Secret 不能为空";
        if (secret.length() > 256 || containsWhitespace(secret)) return "Secret 格式不正确";
        if (botName.isEmpty() || botName.length() > 32) return "机器人名称应为 1～32 个字符";
        return null;
    }

    ApplyResult apply(SetupDraft draft) throws Exception {
        String validationError = validate(draft);
        if (validationError != null) throw new IllegalArgumentException(validationError);

        FileConfiguration config = host.getConfig();
        String oldAppId = config.getString("bot.app-id", "").trim();
        String oldSecret = config.getString("bot.secret", "").trim();
        String newSecret = effectiveSecret(draft);
        boolean hadCompleteCredentials = !oldAppId.isEmpty() && !oldSecret.isEmpty();
        boolean credentialsChanged = !oldAppId.equals(draft.getAppId()) || !oldSecret.equals(newSecret);

        backupConfig();
        config.set("bot.app-id", draft.getAppId());
        config.set("bot.secret", newSecret);
        config.set("bot.name", draft.getBotName());
        config.set("bot.suppress-console-output", draft.isSuppressConsoleOutput());
        OnlineListConfigurator.applyCompatibleDefaults(config);
        host.saveConfig();
        invokeNoArgs(host, "reloadPluginConfig");

        if (!hadCompleteCredentials) {
            invokeNoArgs(host, "launchQqClient");
            return ApplyResult.STARTED_NOW;
        }
        if (credentialsChanged) return ApplyResult.RESTART_REQUIRED;
        return ApplyResult.RELOADED;
    }

    void sendApplyResult(org.bukkit.command.CommandSender sender, ApplyResult result) {
        sender.sendMessage(ChatColor.GREEN + "HuHoBot 配置已保存。完整 Secret 未写入聊天或日志。");
        sender.sendMessage(ChatColor.GREEN + "/查在线 已就绪：未自定义时自动使用兼容文本回执，不需要填写 MOTD 接口。");
        if (result == ApplyResult.STARTED_NOW) {
            sender.sendMessage(ChatColor.AQUA + "QQ 机器人正在连接；连接成功后即可在目标群 @机器人完成接入。");
        } else if (result == ApplyResult.RESTART_REQUIRED) {
            sender.sendMessage(ChatColor.GOLD + "检测到机器人账号凭据发生变化，请重启服务器后应用新账号。");
        } else {
            sender.sendMessage(ChatColor.AQUA + "普通配置已重新载入。");
        }
    }

    JavaPlugin getHost() {
        return host;
    }

    private String effectiveSecret(SetupDraft draft) {
        String submitted = draft.getSecret().trim();
        return submitted.isEmpty() ? host.getConfig().getString("bot.secret", "").trim() : submitted;
    }

    private void backupConfig() {
        try {
            File source = new File(host.getDataFolder(), "config.yml");
            if (!source.isFile()) return;
            File backupDirectory = new File(host.getDataFolder(), "backups");
            if (!backupDirectory.isDirectory() && !backupDirectory.mkdirs()) {
                addon.getLogger().warning("无法创建 HuHoBot 配置备份目录: " + backupDirectory);
                return;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File target = new File(backupDirectory, "config-before-setup-" + stamp + ".yml");
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (Exception error) {
            addon.getLogger().warning("备份 HuHoBot 配置失败，将继续保存: " + error.getMessage());
        }
    }

    private static void invokeNoArgs(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.invoke(target);
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return true;
        }
        return false;
    }
}
