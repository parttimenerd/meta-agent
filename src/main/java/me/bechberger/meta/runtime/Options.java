package me.bechberger.meta.runtime;

import java.util.HashSet;
import java.util.Set;

public class Options {
    public boolean server = false;
    public boolean _native = true;
    public int port = 7071;
    public boolean help = false;
    public Set<String> callbackClasses = new HashSet<>();
}
