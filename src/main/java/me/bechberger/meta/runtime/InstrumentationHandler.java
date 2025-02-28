package me.bechberger.meta.runtime;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains the wrappers for the {@link Instrumentation#addTransformer(ClassFileTransformer)} and
 * {@link Instrumentation#addTransformer(ClassFileTransformer, boolean)} methods and stores the
 * differences between the old and new bytecode for each transformer invocation.
 */
public class InstrumentationHandler {

    private static final Map<Instrumentator, PerInstrumentator> diffs = new ConcurrentHashMap<>();
    private static final Map<Class<?>, PerClass> classDiffs = new ConcurrentHashMap<>();

    static void addDiff(Instrumentator instrumentator, Class<?> clazz, byte[] old, byte[] current) {
        if (Arrays.equals(old, current) || current == null) {
            return;
        }
        diffs.computeIfAbsent(instrumentator, PerInstrumentator::new).addDiff(clazz, old, current);
        classDiffs.computeIfAbsent(clazz, c -> new PerClass()).addDiff(instrumentator, clazz, old, current);
    }

    public static Map<Instrumentator, PerInstrumentator> getDiffs() {
        return Collections.unmodifiableMap(diffs);
    }

    public static Map<Class<?>, PerClass> getClassDiffs() {
        return Collections.unmodifiableMap(classDiffs);
    }

    public static void addTransformer(
            Instrumentation inst, ClassFileTransformer transformer, boolean canRetransform) {
        if (InstrumentationCallbacks.addTransformer(transformer) == CallbackAction.IGNORE) {
            return;
        }
        System.out.println("Adding transformer " + transformer + " with retransform " + canRetransform);
        inst.addTransformer(
                new ClassFileTransformer() {
                    @Override
                    public byte[] transform(
                            Module module,
                            ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer)
                            throws IllegalClassFormatException {

                        // System.out.println("Transforming " + className + " with " + transformer + " and
                        // retransform " + canRetransform);
                        byte[] old = classfileBuffer.clone();

                        byte[] current = InstrumentationCallbacks.transform(
                                transformer,
                                new ClassArtifact(classBeingRedefined, classfileBuffer),
                                b -> {
                                    try {
                                        return transformer.transform(
                                                module,
                                                loader,
                                                className,
                                                classBeingRedefined,
                                                protectionDomain,
                                                b);
                                    } catch (IllegalClassFormatException e) {
                                        throw new RuntimeException(e);
                                    }
                                });

                        if (InstrumentationCallbacks.processInstrumentation(
                                transformer,
                                new ClassArtifact(classBeingRedefined, old),
                                new ClassArtifact(classBeingRedefined, current))
                                == CallbackAction.IGNORE) {
                            return old;
                        }

                        addDiff(
                                new Instrumentator(transformer.getClass().getName()),
                                classBeingRedefined,
                                old,
                                current);
                        return current;
                    }

                    @Override
                    public byte[] transform(
                            ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer)
                            throws IllegalClassFormatException {
                        // System.out.println("Transforming " + className + " with " + transformer + " and
                        // retransform " + canRetransform);
                        byte[] old = classfileBuffer.clone();
                        byte[] current =
                                transformer.transform(
                                        loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
                        addDiff(
                                new Instrumentator(transformer.getClass().getName()),
                                classBeingRedefined,
                                old,
                                current);
                        return current;
                    }
                },
                canRetransform);
    }

    public static void addTransformer(Instrumentation inst, ClassFileTransformer transformer) {
        addTransformer(inst, transformer, false);
    }

    public static byte[] getCurrentBytecode(Class<?> clazz) {
        return classDiffs.get(clazz).getDiffs().get(0).current();
    }

    public static List<String> getInstrumentatorNames(Pattern pattern) {
        return getDiffs().keySet().stream()
                .filter(instrumentator -> pattern.matcher(instrumentator.name()).matches())
                .sorted()
                .map(Instrumentator::name)
                .collect(Collectors.toList());
    }

    public static PerInstrumentator getInstrumentatorDiffs(String instrumentator) {
        return diffs.keySet().stream()
                .filter(i -> i.name().equals(instrumentator))
                .findFirst()
                .map(diffs::get)
                .orElseThrow();
    }

    public static boolean isInstrumented(Class<?> clazz) {
        return classDiffs.containsKey(clazz);
    }
}
