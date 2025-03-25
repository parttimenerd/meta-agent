package me.bechberger.meta.runtime;

public record ClassArtifact(Klass klass, byte[] bytecode) {

    public String toJava() {
        return SimpleDecompilation.decompileClass(this);
    }
}
