package me.bechberger.meta;

public enum DiffSourceMode {
    JAVA(".java", "java", """
            <em>Decompiled bytecode using <a href="https://vineflower.org/">vineflower</a>, obtained
             when ever this page is loaded. Might contain errors, please check with <code>?mode=javap</code> too.</em>""", "java"),
    VERBOSE_BYTECODE(".bytecode", "javap", """
            <em>Decompiled bytecode using <code>javap</code></em>""", "javap"),
    ULTRA_VERBOSE_BYTECODE(".bytecode", "javap-verbose", """
            <em>Decompiled bytecode using <code>javap -v</code></em>
            """, "javap-verbose"),
    AI_JAVA(".md", "java-ai", """
            <em>Decompiled bytecode using <a href="https://vineflower.org/">vineflower</a> and then optimized using the locally running $MODEL with ollama, obtained
             when ever this page is loaded. Might contain errors, please check with <code>?mode=javap</code> too.</em>""".replace("$MODEL", Decompilation.AI_MODEL),
            "java-ai", "requires local ollama $MODEL model, is slow".replace("$MODEL", Decompilation.AI_MODEL));
    public final String suffix;
    public final String name;
    public final String description;
    public final String htmlHeader;
    public final String param;

    DiffSourceMode(String suffix, String name, String htmlHeader, String param, String description) {
        this.suffix = suffix;
        this.name = name;
        this.htmlHeader = htmlHeader;
        this.param = param;
        this.description = description;
    }

    DiffSourceMode(String suffix, String name, String htmlHeader, String param) {
        this(suffix, name, htmlHeader, param, null);
    }
}
