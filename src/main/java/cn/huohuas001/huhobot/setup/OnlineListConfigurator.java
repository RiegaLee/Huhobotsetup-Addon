package cn.huohuas001.huhobot.setup;

import org.bukkit.configuration.file.FileConfiguration;

/** Supplies a safe zero-configuration fallback for HuHoBot's /查在线 command. */
final class OnlineListConfigurator {
    static final String DEFAULT_TEXT = "当前在线 {online} 人\n{players}";

    private OnlineListConfigurator() {
    }

    /**
     * Fills only an empty online-list response. Existing MOTD image or Markdown
     * configuration belongs to the server owner and must never be overwritten.
     *
     * @return true when a missing value was repaired
     */
    static boolean applyCompatibleDefaults(FileConfiguration config) {
        String text = config.getString("motd.text", "");
        if (text != null && !text.trim().isEmpty()) return false;

        config.set("motd.text", DEFAULT_TEXT);

        // Fresh HuHoBot configurations already default both switches to false.
        // Write them only when the keys are absent so custom choices are retained.
        if (!config.contains("motd.post-img")) {
            config.set("motd.post-img", false);
        }
        if (!config.contains("motd.use-markdown")) {
            config.set("motd.use-markdown", false);
        }
        return true;
    }
}
