package me.bechberger.meta.runtime;

public record ClassArtifact(Class<?> klass, byte[] bytecode) {

    public String toJava() {
        return SimpleDecompilation.decompileClass(this);
    }
}
