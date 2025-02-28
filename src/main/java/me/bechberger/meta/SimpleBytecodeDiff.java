package me.bechberger.meta;

/**
 * Diff class that mirrors the runtime.BytecodeDiff class to prevent conversion issues
 */
public record SimpleBytecodeDiff(byte[] old, byte[] current) {
}
