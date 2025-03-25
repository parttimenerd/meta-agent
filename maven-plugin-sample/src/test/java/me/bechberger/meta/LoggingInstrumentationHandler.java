package me.bechberger.meta;

import me.bechberger.meta.runtime.CallbackAction;
import me.bechberger.meta.runtime.ClassArtifact;
import me.bechberger.meta.runtime.InstrumentationCallback;

import java.lang.instrument.ClassFileTransformer;

public class LoggingInstrumentationHandler implements InstrumentationCallback {
    @Override
    public CallbackAction onAddTransformer(ClassFileTransformer transformer) {
        commonOnAddTransformer(transformer);
        return CallbackAction.ALLOW;
    }

    @Override
    public void onExistingTransformer(ClassFileTransformer transformer) {
        commonOnAddTransformer(transformer);
    }

    void commonOnAddTransformer(ClassFileTransformer transformer) {
        if (!transformer.getClass().getName().startsWith("org.mockito")) {
            System.err.println("Unsupported transformer added " + transformer.getClass().getName());
            System.exit(1);
        }
    }
}
