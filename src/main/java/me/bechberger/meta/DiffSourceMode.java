package me.bechberger.meta;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public enum DiffSourceMode {
    JAVA(".java", "java", """
            <em>Decompiled bytecode using <a href="https://vineflower.org/">vineflower</a>, obtained
             when ever this page is loaded. Might contain errors, please check with <code>?mode=javap</code> too.</em>""", "java"),
    VERBOSE_BYTECODE(".bytecode", "javap", """
            <em>Decompiled bytecode using <code>javap</code></em>""", "javap"),
    ULTRA_VERBOSE_BYTECODE(".bytecode", "javap-verbose", """
            <em>Decompiled bytecode using <code>javap -v</code></em>
            """, "javap-verbose"),
    AI_JAVA(".java", "java-ai", """
            <em>Decompiled bytecode using <a href="https://vineflower.org/">vineflower</a> and then optimized using the locally running $MODEL with ollama, obtained
             when ever this page is loaded. Might contain errors, please check with <code>?mode=javap</code> too.</em>""".replace("$MODEL", Decompilation.AI_MODEL),
            "java-ai", "requires local ollama $MODEL model, is slow".replace("$MODEL", Decompilation.AI_MODEL),
            () -> Runtime.getRuntime().exec("ollama").waitFor() == 0);
    public final String suffix;
    public final String name;
    public final String description;
    public final String htmlHeader;
    public final String param;

    @FunctionalInterface
    private interface IsSupportedCallback {
        boolean get() throws Exception;
    }

    private IsSupportedCallback isSupported;
    private boolean isSupportedCache = false;
    private boolean isSupportedCacheSet = false;

    DiffSourceMode(String suffix, String name, String htmlHeader, String param, String description, IsSupportedCallback isSupported) {
        this.suffix = suffix;
        this.name = name;
        this.htmlHeader = htmlHeader;
        this.param = param;
        this.description = description;
        this.isSupported = isSupported;
    }

    DiffSourceMode(String suffix, String name, String htmlHeader, String param, String description) {
        this(suffix, name, htmlHeader, param, description, () -> true);
    }

    DiffSourceMode(String suffix, String name, String htmlHeader, String param) {
        this(suffix, name, htmlHeader, param, null);
    }

    public boolean isSupported() {
        if (!isSupportedCacheSet) {
            try {
                isSupportedCache = isSupported.get();
            } catch (Exception e) {
                isSupportedCache = false;
            }
            isSupportedCacheSet = true;
        }
        return isSupportedCache;
    }

    public static DiffSourceMode[] supportedValues() {
        return Arrays.stream(values()).filter(DiffSourceMode::isSupported).toArray(DiffSourceMode[]::new);
    }
}
