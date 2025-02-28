package me.bechberger.meta.runtime;

import java.lang.instrument.ClassFileTransformer;
import java.util.function.Function;

/**
 * A call back called for every instrumentation
 */
public interface InstrumentationCallback {

    /**
     * Called when a new transformer is added
     */
    default CallbackAction onAddTransformer(ClassFileTransformer transformer) {
        return CallbackAction.ALLOW;
    }

    /**
     * Called for every existing transformer
     */
    default void onExistingTransformer(ClassFileTransformer transformer) {
    }

    default byte[] onTransform(ClassFileTransformer transformer, ClassArtifact before, Function<byte[], byte[]> runnable) {
        return runnable.apply(before.bytecode());
    }

    /**
     * Called for every instrumentation
     */
    default CallbackAction onInstrumentation(ClassFileTransformer transformer, ClassArtifact before, ClassArtifact after) {
        return CallbackAction.ALLOW;
    }
}
