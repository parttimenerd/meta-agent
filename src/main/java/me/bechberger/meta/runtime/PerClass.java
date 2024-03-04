package me.bechberger.meta.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Class that holds the diffs for a single class */
public class PerClass {

  private final Class<?> clazz;

  private final List<BytecodeDiff> diffs = Collections.synchronizedList(new ArrayList<>());

  public PerClass(Class<?> clazz) {
    this.clazz = clazz;
  }

  void addDiff(Instrumentator instrumentator, Class<?> clazz, byte[] old, byte[] current) {
    diffs.add(new BytecodeDiff(instrumentator, clazz, old, current));
  }

  public List<BytecodeDiff> getDiffs() {
    return Collections.unmodifiableList(diffs);
  }
}
