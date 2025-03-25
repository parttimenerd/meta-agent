package me.bechberger.meta.runtime;

public record Klass(String name, Class<?> klass) {
    public Klass {
        if (name == null || klass == null) {
            throw new IllegalArgumentException("Name and class must not be null!");
        }
    }

    public Klass(Class<?> klass) {
        this(klass.getName().replace('.', '/'), klass);
    }

    public Klass(String name) {
        this(name, null);
    }

    public boolean hasClass() {
        return klass != null;
    }

    public String getPackageName() {
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash == -1) {
            return "";
        }
        return name.substring(0, lastSlash).replace('/', '.');
    }

    public String getSimpleName() {
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash == -1) {
            return name;
        }
        return name.substring(lastSlash + 1);
    }

    public String getName() {
        return name.replace('/', '.');
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Klass klass = (Klass) obj;
        return name.equals(klass.name) || (this.klass != null && this.klass.equals(klass.klass));
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
