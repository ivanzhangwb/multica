package ai.multica.server.daemon;

enum DaemonTaskStatus {
    QUEUED("queued"),
    DISPATCHED("dispatched"),
    WAITING_LOCAL_DIRECTORY("waiting_local_directory"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireName;

    DaemonTaskStatus(String wireName) {
        this.wireName = wireName;
    }

    String wireName() {
        return wireName;
    }
}
