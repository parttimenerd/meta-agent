package me.bechberger.meta;

import me.bechberger.meta.runtime.CallbackAction;
import me.bechberger.meta.runtime.ClassArtifact;
import me.bechberger.meta.runtime.InstrumentationCallback;

import java.lang.instrument.ClassFileTransformer;

public class LoggingInstrumentationHandler implements InstrumentationCallback {
    @Override
    public CallbackAction onAddTransformer(ClassFileTransformer transformer) {
        System.err.println("New transformer " + transformer.getClass().getName());
        return CallbackAction.ALLOW;
    }

    @Override
    public void onExistingTransformer(ClassFileTransformer transformer) {
        System.err.println("Existing transformer " + transformer.getClass().getName());
    }

    @Override
    public CallbackAction onInstrumentation(ClassFileTransformer transformer, ClassArtifact before, ClassArtifact after) {
        System.err.println("Instrumenting " + before.klass().getName());
        return CallbackAction.ALLOW;
    }

}
