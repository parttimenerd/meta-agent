package me.bechberger.meta.runtime;

public enum CallbackAction {
    /**
     * Allow the instrumentation or adding the transformer
     */
    ALLOW,
    /**
     * Ignore the instrumentation or don't add the transformer
     */
    IGNORE
}
