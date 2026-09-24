package cn.huohuas001.huhobot.setup;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnvilSetupUiTest {
    @Test
    void readsValueBelowFieldTitle() {
        String page = AnvilSetupUi.formPage("填写QQ开放平台AppID", "123456");

        assertEquals("123456", AnvilSetupUi.pageValue(Collections.singletonList(page), 0, "填写QQ开放平台AppID"));
    }

    @Test
    void fieldCanRemainBlank() {
        String page = AnvilSetupUi.formPage("填写Secret", "");

        assertEquals("", AnvilSetupUi.pageValue(Collections.singletonList(page), 0, "填写Secret"));
    }

    @Test
    void supportsReplacingTheWholePageWithAValue() {
        assertEquals("123456", AnvilSetupUi.pageValue(Collections.singletonList("123456"), 0, "AppID"));
    }
}
