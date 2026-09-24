package cn.huohuas001.huhobot.setup;

import org.bukkit.entity.Player;

interface DialogSetupUi {
    boolean isAvailable();

    boolean open(Player player);

    String implementationName();
}
