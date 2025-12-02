package me.bechberger.meta;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.scopedpool.ScopedClassPoolFactoryImpl;
import javassist.scopedpool.ScopedClassPoolRepositoryImpl;
import me.bechberger.meta.runtime.InstrumentationCallback;
import me.bechberger.meta.runtime.InstrumentationCallbacks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Replace every invocation of Instrumentation.addTransformer(...) with the InstrumentationHandler
 * version
 */
public class ClassTransformer implements ClassFileTransformer {
  private static final String BYTEBUDDY_AGENT_BUILDER_DEFAULT_DISPATCHER_CLASS_NAME = "net.bytebuddy.agent.builder.AgentBuilder$Default$Dispatcher";
  private final ScopedClassPoolFactoryImpl scopedClassPoolFactory =
            new ScopedClassPoolFactoryImpl();

    private final Set<String> instrumentationCallbackClasses;

    public ClassTransformer(Set<String> instrumentationCallbackClasses) {
        this.instrumentationCallbackClasses = instrumentationCallbackClasses;
    }

    private static boolean canTransformClass(String name) {
        return !name.startsWith("jdk/internal")
                && !name.startsWith("com/sun/");
    }

    public static boolean canTransformClass(Class<?> klass) {
        return canTransformClass(klass.getName().replace(".", "/"));
    }

    private boolean isInstrumentationHandlerClass(Class<?> klass) {
        return klass.getInterfaces().length > 0 && Arrays.asList(klass.getInterfaces()).contains(InstrumentationCallback.class);
    }

    private void handleInstrumentationHandlerClass(Class<?> klass) {
        try {
            InstrumentationCallbacks.addCallback((InstrumentationCallback) klass.getConstructor().newInstance());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException("Error instantiating the handler " + klass.getName(), e);
        }
    }

    private WeakHashMap<ClassLoader, Boolean> classLoaders = new WeakHashMap<>();
    private Set<String> foundInstrumentationCallbackClasses = new HashSet<>();

    private void tryToInitInstrumentationClassLoader() {
        if (foundInstrumentationCallbackClasses.size() == instrumentationCallbackClasses.size()) {
            return;
        }
        for (String className : instrumentationCallbackClasses) {
            if (foundInstrumentationCallbackClasses.contains(className)) {
                continue;
            }
            try {
                Class<?> klass = Class.forName(className);
                if (isInstrumentationHandlerClass(klass)) {
                    handleInstrumentationHandlerClass(klass);
                    foundInstrumentationCallbackClasses.add(className);
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!canTransformClass(className)) {
            return classfileBuffer;
        }

        if (loader != null && !classLoaders.containsKey(loader)) {
            tryToInitInstrumentationClassLoader();
            classLoaders.put(loader, true);
        }


        try {
            ClassPool cp =
                    scopedClassPoolFactory.create(
                            loader, ClassPool.getDefault(), ScopedClassPoolRepositoryImpl.getInstance());
            CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
            if (cc.isFrozen() || cc.isInterface()) {
                return classfileBuffer;
            }
            // classBeingRedefined is null if the class has not yet been defined
            transform(className, cc);
            return cc.toBytecode();
        } catch (CannotCompileException | IOException | RuntimeException e) {
            e.printStackTrace();
            return classfileBuffer;
        }
    }

    private boolean isAddTransformerMethod(MethodCall m) {
        return (m.getClassName().equals("java.lang.instrument.Instrumentation")
                || m.getClassName().equals("java.lang.instrument.InstrumentationImpl")
                // ByteBuddy's Dispatcher interface
                || m.getClassName().equals(BYTEBUDDY_AGENT_BUILDER_DEFAULT_DISPATCHER_CLASS_NAME))
                && m.getMethodName().equals("addTransformer");
    }

    private void transform(String className, CtClass cc)
            throws CannotCompileException {
        if (className.startsWith("me/bechberger/meta/runtime")) {
            return;
        }
        var exprEditor =
                new ExprEditor() {
                    @Override
                    public void edit(MethodCall m) throws CannotCompileException {
                        if (!isAddTransformerMethod(m)) {
                            return;
                        }
                        // check the number of arguments
                        int argCount = m.getSignature().contains("Z") ? 2 : 1;
                        // replace
                        if (m.getClassName().equals(BYTEBUDDY_AGENT_BUILDER_DEFAULT_DISPATCHER_CLASS_NAME)) {
                            // Dispatcher interface method call: addTransformer(Instrumentation, ClassFileTransformer, boolean)
                            m.replace(
                                    "me.bechberger.meta.runtime.InstrumentationHandler.addTransformer($1, $2, $3);");
                        } else if (argCount == 1) {
                            m.replace(
                                    "me.bechberger.meta.runtime.InstrumentationHandler.addTransformer($0, $1);");
                        } else {
                            m.replace(
                                    "me.bechberger.meta.runtime.InstrumentationHandler.addTransformer($0, $1, $2);");
                        }
                    }
                };
        for (CtConstructor constructor : cc.getDeclaredConstructors()) {
            constructor.instrument(exprEditor);
        }
        for (CtMethod method : cc.getDeclaredMethods()) {
            method.instrument(exprEditor);

            /*
            because:
            https://github.com/JetBrains/intellij-coverage/blob/master/instrumentation/src/com/intellij/rt/coverage/instrumentation/Instrumentator.java
            uses reflection
             */

            if (method.getName().equals("addTransformer") && className.endsWith("Instrumentator") && className.contains("intellij")) {
                method.instrument(
                        new ExprEditor() {
                            @Override
                            public void edit(MethodCall m) throws CannotCompileException {
                                if (m.getClassName().equals(Method.class.getName()) && m.getMethodName().equals("invoke")) {
                                    m.replace(
                                            "$_ = me.bechberger.meta.runtime.InstrumentationHandler.addTransformerIntelliJReflection($1, $2);");
                                }
                            }
                        });
            }
        }
    }
}