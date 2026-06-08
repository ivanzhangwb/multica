package ai.multica.server.auth;

public enum ActorSource {
    HUMAN(""),
    TASK_TOKEN("task_token"),
    CLOUD_PAT("cloud_pat");

    private final String headerValue;

    ActorSource(String headerValue) {
        this.headerValue = headerValue;
    }

    public String headerValue() {
        return headerValue;
    }

    public boolean isMachineCredential() {
        return this == TASK_TOKEN || this == CLOUD_PAT;
    }
}
