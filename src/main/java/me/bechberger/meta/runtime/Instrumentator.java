package me.bechberger.meta.runtime;

public record Instrumentator(String name) implements Comparable<Instrumentator> {
    @Override
    public int compareTo(Instrumentator o) {
        return this.name.compareTo(o.name);
    }
}