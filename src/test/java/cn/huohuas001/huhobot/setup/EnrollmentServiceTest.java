package cn.huohuas001.huhobot.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentServiceTest {
    @Test
    void acceptsCompactAndSpacedEnrollmentCommandsAfterMention() {
        assertEquals(
            "ABCD-EFGH-JKLM",
            EnrollmentService.parseEnrollmentCode("<@BOT_OPEN_ID> /接入ABCD-EFGH-JKLM")
        );
        assertEquals(
            "ABCD-EFGH-JKLM",
            EnrollmentService.parseEnrollmentCode("<@!BOT_OPEN_ID> /接入 abcd-efgh-jklm")
        );
    }

    @Test
    void ignoresOrdinaryConversationAndMalformedCodes() {
        assertNull(EnrollmentService.parseEnrollmentCode("接入新成员"));
        assertNull(EnrollmentService.parseEnrollmentCode("/接入123456"));
        assertNull(EnrollmentService.parseEnrollmentCode("今天接入ABCD-EFGH-JKLM了吗"));
    }

    @Test
    void formatsMentionAndMessageForInGameConfirmation() {
        assertEquals(
            "@机器人 这是我要接入的群",
            EnrollmentService.displayMessage("<@BOT_OPEN_ID> 这是我要接入的群")
        );
        assertEquals("@机器人", EnrollmentService.displayMessage("<@!BOT_OPEN_ID>"));
        assertEquals("@机器人 111", EnrollmentService.displayMentionedMessage(" 111"));
        assertEquals("@机器人", EnrollmentService.displayMentionedMessage(""));
    }

    @Test
    void detectsMentionFromSnapshotWhenSdkRemovesItFromContent() {
        assertTrue(EnrollmentService.hasMention(new FakeMessagePack("ExampleUser", true), " 111"));
        assertTrue(EnrollmentService.hasMention(new FakeMessagePack("ExampleUser", true), ""));
        assertTrue(EnrollmentService.hasMention(new FakeMessagePack("ExampleUser", false), "<@BOT> hello"));
        assertFalse(EnrollmentService.hasMention(new FakeMessagePack("ExampleUser", false), "hello"));
    }

    @Test
    void readsQqNicknameFromMessageSnapshotWithoutExposingOpenId() {
        assertEquals("测试群昵称", EnrollmentService.readSenderName(new FakeMessagePack("测试群昵称")));
        assertEquals("QQ 群成员", EnrollmentService.readSenderName(new FakeMessagePack("unknown")));
    }

    @Test
    void bundlesPermissionGuideImage() throws IOException {
        try (InputStream input = EnrollmentServiceTest.class.getResourceAsStream(
            "/onboarding/permission-settings.png"
        )) {
            assertNotNull(input);
            assertTrue(input.available() > 1000);
        }
    }

    public static final class FakeMessagePack {
        private final FakeSender sender;
        private final java.util.List<String> mentions;

        FakeMessagePack(String username) {
            this(username, false);
        }

        FakeMessagePack(String username, boolean mentioned) {
            sender = new FakeSender(username);
            mentions = mentioned ? Collections.singletonList("BOT") : Collections.emptyList();
        }

        public FakeSender getSender() {
            return sender;
        }

        public java.util.List<String> getMentions() {
            return mentions;
        }
    }

    public static final class FakeSender {
        private final String username;

        FakeSender(String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }
    }
}
