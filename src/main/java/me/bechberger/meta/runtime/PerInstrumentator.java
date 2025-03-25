package me.bechberger.meta.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that holds the diffs for a single instrumentator
 */
public class PerInstrumentator {

    private final Instrumentator instrumentator;

    private final Map<Klass, List<BytecodeDiff>> diffs = new ConcurrentHashMap<>();

    public PerInstrumentator(Instrumentator instrumentator) {
        this.instrumentator = instrumentator;
    }

    void addDiff(Klass clazz, byte[] old, byte[] current) {
        diffs
                .computeIfAbsent(clazz, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new BytecodeDiff(instrumentator, clazz, old, current));
    }

    public Map<Klass, List<BytecodeDiff>> getDiffs() {
        return Collections.unmodifiableMap(diffs);
    }

    public Instrumentator getInstrumentator() {
        return instrumentator;
    }
}
