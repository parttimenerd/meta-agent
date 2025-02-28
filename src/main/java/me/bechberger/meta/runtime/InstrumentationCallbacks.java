package me.bechberger.meta.runtime;

import java.lang.instrument.ClassFileTransformer;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A call back called for every instrumentation
 */
public interface InstrumentationCallbacks {

    List<InstrumentationCallback> callbacks = new ArrayList<>(List.of(new DefaultCallback()));
    List<WeakReference<ClassFileTransformer>> transformers = new ArrayList<>();

    /**
     * Callback that captures all transformers
     */
    class DefaultCallback implements InstrumentationCallback {
        @Override
        public CallbackAction onAddTransformer(ClassFileTransformer transformer) {
            synchronized (transformers) {
                transformers.add(new WeakReference<>(transformer));
            }
            return CallbackAction.ALLOW;
        }
    }

    /**
     * Add a callback to be called for every instrumentation,
     * calls {@link InstrumentationCallback#onExistingTransformer(ClassFileTransformer)} for every existing transformer
     *
     * @param callback the callback to add
     */
    static void addCallback(InstrumentationCallback callback) {
        synchronized (callbacks) {
            callbacks.add(callback);
            synchronized (transformers) {
                transformers.stream().map(Reference::get).filter(Objects::nonNull).forEach(callback::onExistingTransformer);
            }
        }
    }

    /**
     * Remove a callback
     *
     * @param callback the callback to remove
     * @return true if the callback was removed
     */
    static boolean removeCallback(InstrumentationCallback callback) {
        synchronized (callbacks) {
            return callbacks.remove(callback);
        }
    }

    static CallbackAction addTransformer(ClassFileTransformer transformer) {
        synchronized (callbacks) {
            return callbacks.stream().map(c -> c.onAddTransformer(transformer)).filter(a -> a == CallbackAction.IGNORE).findFirst().orElse(CallbackAction.ALLOW);
        }
    }

    static CallbackAction processInstrumentation(ClassFileTransformer transformer, ClassArtifact before, ClassArtifact after) {
        synchronized (callbacks) {
            return callbacks.stream().map(c -> c.onInstrumentation(transformer, before, after)).filter(a -> a == CallbackAction.IGNORE).findFirst().orElse(CallbackAction.ALLOW);
        }
    }

    static byte[] transform(ClassFileTransformer transformer, ClassArtifact before, Function<byte[], byte[]> runnable) {
        synchronized (callbacks) {
            byte[] current = before.bytecode();

            Function<byte[], byte[]> curFunc = runnable;
            for (int i = callbacks.size() - 1; i >= 0; i--) {
                InstrumentationCallback callback = callbacks.get(i);
                Function<byte[], byte[]> finalCurFunc = curFunc;
                curFunc = (bytecode) -> callback.onTransform(transformer, before, finalCurFunc);
            }

            return current;
        }
        // return runnable.apply(before.bytecode);
    }
}
