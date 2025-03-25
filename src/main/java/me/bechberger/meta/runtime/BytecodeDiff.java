package me.bechberger.meta.runtime;

public record BytecodeDiff(
        Instrumentator instrumentator, Klass klass, byte[] old, byte[] current) {
}
