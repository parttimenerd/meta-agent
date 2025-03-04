Meta-Agent Maven Plugin Sample Project
======================================

This shows how to use the [Meta-Agent Maven Plugin](../maven-plugin) in a sample project.

It is configured in it's pom to use the [LoggingInstrumentationHandler](src/test/java/me/bechberger/meta/LoggingInstrumentationHandler.java) as a callback handler and to start the meta-agent server.

The instrumentation handler is:

```java
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
        return CallbackAction.ALLOW;
    }
}
```

And logs all added and existing transformers.
This can be extended to e.g. fail for any unexpected transformers, by checking
the class name of the transformer.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger
and meta-agent agent contributors