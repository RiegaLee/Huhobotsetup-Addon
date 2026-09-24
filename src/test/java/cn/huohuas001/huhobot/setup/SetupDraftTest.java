package cn.huohuas001.huhobot.setup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SetupDraftTest {
    @Test
    void trimsSubmittedValuesWithoutChangingBoolean() {
        SetupDraft draft = new SetupDraft(" 123 ", " secret ", " Bot ", false);
        draft.setAppId(draft.getAppId());
        draft.setSecret(draft.getSecret());
        draft.setBotName(draft.getBotName());

        assertEquals("123", draft.getAppId());
        assertEquals("secret", draft.getSecret());
        assertEquals("Bot", draft.getBotName());
        assertFalse(draft.isSuppressConsoleOutput());
    }
}
