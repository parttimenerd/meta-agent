package me.bechberger.meta.runtime;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains the wrappers for the {@link Instrumentation#addTransformer(ClassFileTransformer)} and
 * {@link Instrumentation#addTransformer(ClassFileTransformer, boolean)} methods and stores the
 * differences between the old and new bytecode for each transformer invocation.
 */
public class InstrumentationHandler {

    private static final Map<String, Instrumentator> instrumentatorCache = new ConcurrentHashMap<>();
    private static final Map<Instrumentator, PerInstrumentator> diffs = new ConcurrentHashMap<>();
    private static final Map<Klass, PerClass> classDiffs = new ConcurrentHashMap<>();

    static void addDiff(Instrumentator instrumentator, Klass klass, byte[] old, byte[] current) {
        instrumentatorCache.put(instrumentator.name(), instrumentator);
        if (Arrays.equals(old, current) || current == null) {
            return;
        }
        diffs.computeIfAbsent(instrumentator, PerInstrumentator::new).addDiff(klass, old, current);
        classDiffs.computeIfAbsent(klass, c -> new PerClass()).addDiff(instrumentator, klass, old, current);
    }

    static void addDiff(Instrumentator instrumentator, Class<?> clazz, byte[] old, byte[] current) {
        addDiff(instrumentator, new Klass(clazz), old, current);
    }

    public static void addDiff(String instrumentator, String clazz, byte[] old, byte[] current) {
        var instr = instrumentatorCache.computeIfAbsent(instrumentator, Instrumentator::new);
        addDiff(instr, new Klass(clazz), old, current);
    }

    public static Map<Instrumentator, PerInstrumentator> getDiffs() {
        return Collections.unmodifiableMap(diffs);
    }

    public static Map<Klass, PerClass> getClassDiffs() {
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
                            byte[] classfileBuffer) {

                        byte[] old = classfileBuffer.clone();

                        byte[] current = InstrumentationCallbacks.transform(
                                transformer,
                                new ClassArtifact(new Klass(className, classBeingRedefined), classfileBuffer),
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
                                new ClassArtifact(new Klass(className, classBeingRedefined), old),
                                new ClassArtifact(new Klass(className, classBeingRedefined), current))
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
                            byte[] classfileBuffer) {
                        return transform(
                                null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
                    }
                },
                canRetransform);
    }

    public static void addTransformer(Instrumentation inst, ClassFileTransformer transformer) {
        addTransformer(inst, transformer, false);
    }

    public static Object addTransformerIntelliJReflection(Object inst, Object[] args) {
        addTransformer((Instrumentation) inst, (ClassFileTransformer) args[0], (Boolean) args[1]);
        return null;
    }

    public static byte[] getCurrentBytecode(Klass clazz) {
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

    public static boolean isInstrumented(Klass clazz) {
        return classDiffs.containsKey(clazz);
    }
}