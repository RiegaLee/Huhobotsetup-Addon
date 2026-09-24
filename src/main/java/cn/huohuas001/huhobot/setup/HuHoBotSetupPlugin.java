package cn.huohuas001.huhobot.setup;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class HuHoBotSetupPlugin extends JavaPlugin {
    private AnvilSetupUi anvilUi;
    private PaperDialogSetupUi paperDialogUi;
    private SpigotDialogSetupUi spigotDialogUi;
    private EnrollmentService enrollment;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Plugin found = getServer().getPluginManager().getPlugin("HuHoBotPenguin");
        if (!(found instanceof JavaPlugin) || !found.isEnabled()) {
            getLogger().severe("未找到已启用的 HuHoBotPenguin，初始化 ADDON 无法启动。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        JavaPlugin host = (JavaPlugin) found;
        SetupService service = new SetupService(this, host);
        enrollment = new EnrollmentService(this, service);
        paperDialogUi = new PaperDialogSetupUi(this, service, enrollment);
        spigotDialogUi = new SpigotDialogSetupUi(this, service, enrollment);
        anvilUi = new AnvilSetupUi(this, service, enrollment);

        getServer().getPluginManager().registerEvents(anvilUi, this);
        boolean enrollmentReady = enrollment.initialize();

        PluginCommand command = getCommand("huhobotsetup");
        if (command == null) {
            getLogger().severe("plugin.yml 未注册 /huhobotsetup。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SetupCommand executor = new SetupCommand(
            service, paperDialogUi, spigotDialogUi, anvilUi, enrollment
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        registerAddonMetadata(host);

        String ui = paperDialogUi.isAvailable() ? paperDialogUi.implementationName()
            : spigotDialogUi.isAvailable() ? spigotDialogUi.implementationName() : "箱子菜单＋书本表单";
        getLogger().info("HuHoBot 初始化 ADDON 已就绪；界面：" + ui
            + "；群消息确认接入：" + (enrollmentReady ? "可用" : "不可用"));
    }

    @Override
    public void onDisable() {
        if (anvilUi != null) anvilUi.clear();
        if (paperDialogUi != null) paperDialogUi.clear();
        if (spigotDialogUi != null) spigotDialogUi.clear();
        if (enrollment != null) enrollment.clear();
    }

    private void registerAddonMetadata(JavaPlugin host) {
        try {
            ClassLoader loader = host.getClass().getClassLoader();
            Class<?> addonClass = Class.forName("cn.huohuas001.bot.addon.Addon", true, loader);
            Constructor<?> constructor = addonClass.getConstructor(
                String.class, String.class, String.class, String.class
            );
            Object addon = constructor.newInstance(
                "HuHoBotSetup",
                getDescription().getVersion(),
                "可视化初始化与游戏内确认式 QQ 群接入向导",
                "RiegaLee"
            );
            Method register = host.getClass().getMethod("registerAddon", addonClass);
            register.invoke(host, addon);
        } catch (Throwable error) {
            getLogger().warning("未能登记 HuHoBot ADDON 元数据，但初始化功能仍可使用: " + error.getMessage());
        }
    }
}
