package me.bechberger.meta;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;

import javassist.*;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import javassist.scopedpool.ScopedClassPoolFactoryImpl;
import javassist.scopedpool.ScopedClassPoolRepositoryImpl;

/**
 * Replace every invocation of Instrumentation.addTransformer(...) with the InstrumentationHandler version
 */
public class ClassTransformer implements ClassFileTransformer {
  private final ScopedClassPoolFactoryImpl scopedClassPoolFactory =
      new ScopedClassPoolFactoryImpl();

  private boolean canTransformClass(String name) {
    return !name.startsWith("java/") && !name.startsWith("jdk/internal") && !name.startsWith("com/sun/") && !name.startsWith("me/bechberger/meta");
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
    } catch (CannotCompileException | IOException | RuntimeException | NotFoundException e) {
      e.printStackTrace();
      return classfileBuffer;
    }
  }

  private boolean isAddTransformerMethod(MethodCall m) {
    return (m.getClassName().equals("java.lang.instrument.Instrumentation") ||
            m.getClassName().equals("java.lang.instrument.InstrumentationImpl"))
        && m.getMethodName().equals("addTransformer");
  }

  private void transform(String className, CtClass cc)
      throws CannotCompileException, NotFoundException {
    var exprEditor = new ExprEditor() {
      @Override
      public void edit(MethodCall m) throws CannotCompileException {
        if (!isAddTransformerMethod(m)) {
          return;
        }
        // check the number of arguments
        int argCount = m.getSignature().contains("Z") ? 2 : 1;
        // replace
        if (argCount == 1) {
          m.replace("me.bechberger.meta.runtime.InstrumentationHandler.addTransformer($0, $1);");
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
    }
  }
}
