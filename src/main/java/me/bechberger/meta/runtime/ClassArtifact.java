package me.bechberger.meta.runtime;

import java.util.Map;

public record ClassArtifact(Class<?> klass, byte[] bytecode) {
    /**
     * Convert the bytecode to Java source code using vineflower, might be slow. Use {@link Decompilation#decompileClassesToJava(Map)}
     */
    public String toJava() {
        return Decompilation.decompileClasses(Map.of(klass, bytecode), DiffSourceMode.JAVA).get(klass);
    }
}
