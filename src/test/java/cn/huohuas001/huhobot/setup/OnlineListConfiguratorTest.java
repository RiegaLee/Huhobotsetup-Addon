package cn.huohuas001.huhobot.setup;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineListConfiguratorTest {
    @Test
    void fillsEmptyOnlineListWithPermissionCompatibleTextMode() {
        YamlConfiguration config = new YamlConfiguration();

        assertTrue(OnlineListConfigurator.applyCompatibleDefaults(config));
        assertEquals(OnlineListConfigurator.DEFAULT_TEXT, config.getString("motd.text"));
        assertFalse(config.getBoolean("motd.post-img"));
        assertFalse(config.getBoolean("motd.use-markdown"));
    }

    @Test
    void keepsExistingCustomMotdConfigurationUntouched() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("motd.text", "自定义：{online}");
        config.set("motd.post-img", true);
        config.set("motd.use-markdown", true);
        config.set("motd.api", "https://example.invalid/{ip}/{port}");

        assertFalse(OnlineListConfigurator.applyCompatibleDefaults(config));
        assertEquals("自定义：{online}", config.getString("motd.text"));
        assertTrue(config.getBoolean("motd.post-img"));
        assertTrue(config.getBoolean("motd.use-markdown"));
        assertEquals("https://example.invalid/{ip}/{port}", config.getString("motd.api"));
    }

    @Test
    void repairsBlankTextWithoutChangingExplicitFeatureSwitches() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("motd.text", "   ");
        config.set("motd.post-img", true);
        config.set("motd.use-markdown", false);

        assertTrue(OnlineListConfigurator.applyCompatibleDefaults(config));
        assertEquals(OnlineListConfigurator.DEFAULT_TEXT, config.getString("motd.text"));
        assertTrue(config.getBoolean("motd.post-img"));
        assertFalse(config.getBoolean("motd.use-markdown"));
    }
}
