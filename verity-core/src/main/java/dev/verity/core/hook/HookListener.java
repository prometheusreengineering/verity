package dev.verity.core.hook;

/** Events a client reports through verity-api. Called on the connection's thread. */
public interface HookListener {
    default void onConnected(String javaVersion) {
    }

    default void onPass(String name) {
    }

    default void onFail(String name, String message, String detail) {
    }

    default void onLog(String message) {
    }

    default void onAttach(String name, byte[] data) {
    }

    default void onFinish() {
    }
}
