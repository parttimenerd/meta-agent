package me.bechberger.meta.runtime;

public record BytecodeDiff(
    Instrumentator instrumentator, Class<?> klass, byte[] old, byte[] current) {}
