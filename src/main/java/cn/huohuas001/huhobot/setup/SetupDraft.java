package cn.huohuas001.huhobot.setup;

final class SetupDraft {
    private String appId;
    private String secret;
    private String botName;
    private boolean suppressConsoleOutput;

    SetupDraft(String appId, String secret, String botName, boolean suppressConsoleOutput) {
        this.appId = appId == null ? "" : appId;
        this.secret = secret == null ? "" : secret;
        this.botName = botName == null ? "HuHoBot" : botName;
        this.suppressConsoleOutput = suppressConsoleOutput;
    }

    String getAppId() {
        return appId;
    }

    void setAppId(String appId) {
        this.appId = appId == null ? "" : appId.trim();
    }

    String getSecret() {
        return secret;
    }

    void setSecret(String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    String getBotName() {
        return botName;
    }

    void setBotName(String botName) {
        this.botName = botName == null ? "" : botName.trim();
    }

    boolean isSuppressConsoleOutput() {
        return suppressConsoleOutput;
    }

    void setSuppressConsoleOutput(boolean suppressConsoleOutput) {
        this.suppressConsoleOutput = suppressConsoleOutput;
    }
}
