package com.shortcutblur;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Reflect {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_CACHE = 512;

    private static final Map<MKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    private static final Map<String, Method> TYPED_CACHE = new ConcurrentHashMap<>();

    private static final Set<MKey> MISS_METHOD = ConcurrentHashMap.newKeySet();
    private static final Set<FKey> MISS_FIELD = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> MISS_CTOR = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISS_TYPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISS_CLASS = ConcurrentHashMap.newKeySet();

    private Reflect() {}

    private static final class MKey {
        final Class<?> c;
        final String name;
        final int argc;
        private final int h;

        MKey(Class<?> c, String name, int argc) {
            this.c = c;
            this.name = name;
            this.argc = argc;
            this.h = ((c.hashCode() * 31) + name.hashCode()) * 31 + argc;
        }

        @Override public int hashCode() { return h; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MKey)) return false;
            MKey k = (MKey) o;
            return k.argc == argc && k.c == c && k.name.equals(name);
        }
    }

    private static final class FKey {
        final Class<?> c;
        final String name;
        private final int h;

        FKey(Class<?> c, String name) {
            this.c = c;
            this.name = name;
            this.h = c.hashCode() * 31 + name.hashCode();
        }

        @Override public int hashCode() { return h; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FKey)) return false;
            FKey k = (FKey) o;
            return k.c == c && k.name.equals(name);
        }
    }

    private static <K, V> void put(Map<K, V> cache, K key, V value) {

        if (cache.size() >= MAX_CACHE) cache.clear();
        cache.put(key, value);
    }

    private static <K> void putMiss(Set<K> miss, K key) {
        if (miss.size() >= MAX_CACHE) miss.clear();
        miss.add(key);
    }

    public static Method method(Class<?> c, String name, int paramCount) {
        if (c == null || name == null) return null;
        MKey key = new MKey(c, name, paramCount);
        Method hit = METHOD_CACHE.get(key);
        if (hit != null) return hit;
        if (MISS_METHOD.contains(key)) return null;
        Class<?> k = c;
        int depth = 0;
        boolean aborted = false;
        while (k != null && depth++ < MAX_DEPTH) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                        m.setAccessible(true);
                        put(METHOD_CACHE, key, m);
                        return m;
                    }
                }
            } catch (Throwable t) {

                aborted = true;
            }
            k = k.getSuperclass();
        }
        if (!aborted) putMiss(MISS_METHOD, key);
        return null;
    }

    public static Method method(Class<?> c, String name, Class<?>... paramTypes) {
        if (c == null || name == null) return null;
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name).append('(');
        if (paramTypes != null) {
            for (Class<?> p : paramTypes) sb.append(p == null ? "?" : p.getName()).append(',');
        }
        String key = sb.append(')').toString();
        Method hit = TYPED_CACHE.get(key);
        if (hit != null) return hit;
        if (MISS_TYPED.contains(key)) return null;
        try {
            Method m = c.getMethod(name, paramTypes);
            m.setAccessible(true);
            put(TYPED_CACHE, key, m);
            return m;
        } catch (Throwable t) {
            putMiss(MISS_TYPED, key);
            return null;
        }
    }

    public static Field field(Class<?> c, String name) {
        if (c == null || name == null) return null;
        FKey key = new FKey(c, name);
        Field hit = FIELD_CACHE.get(key);
        if (hit != null) return hit;
        if (MISS_FIELD.contains(key)) return null;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                put(FIELD_CACHE, key, f);
                return f;
            } catch (NoSuchFieldException nsf) {
                k = k.getSuperclass();
            } catch (Throwable t) {

                return null;
            }
        }
        putMiss(MISS_FIELD, key);
        return null;
    }

    public static Object newInstance(Class<?> c) {
        if (c == null) return null;
        if (MISS_CTOR.contains(c)) return null;
        Constructor<?> ctor = CTOR_CACHE.get(c);
        try {
            if (ctor == null) {
                ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                put(CTOR_CACHE, c, ctor);
            }
            return ctor.newInstance();
        } catch (Throwable t) {
            if (ctor == null) putMiss(MISS_CTOR, c);
            return null;
        }
    }

    public static Object call(Object target, String name) {
        if (target == null) return null;
        Method m = method(target.getClass(), name, 0);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object call(Object target, String name, int paramCount, Object... args) {
        if (target == null) return null;
        Method m = method(target.getClass(), name, paramCount);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setAccessible(Executable e) {
        try {
            e.setAccessible(true);
        } catch (Throwable ignore) {
        }
    }

    public static Class<?> loadClass(String name, ClassLoader loader) {
        if (loader == null) return null;
        Class<?> cached = CLASS_CACHE.get(name);

        if (cached != null && cached.getClassLoader() == loader) return cached;
        String missKey = name + "@" + System.identityHashCode(loader);
        if (MISS_CLASS.contains(missKey)) return null;
        try {
            Class<?> c = Class.forName(name, false, loader);
            put(CLASS_CACHE, name, c);
            return c;
        } catch (Throwable t) {
            putMiss(MISS_CLASS, missKey);
            return null;
        }
    }

    public static Object readField(Object target, String name) {
        if (target == null) return null;
        Field f = field(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            try {
                return f.get(null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    public static int readStaticInt(Class<?> c, String name, int def) {
        if (c == null || name == null) return def;
        Field f = field(c, name);
        if (f == null) return def;
        try {
            return f.getInt(null);
        } catch (Throwable t) {
            try {
                return f.get(null) instanceof Integer ? (Integer) f.get(null) : def;
            } catch (Throwable t2) {
                return def;
            }
        }
    }

    public static Object callStatic(Class<?> c, String name) {
        if (c == null || name == null) return null;
        Method m = method(c, name, 0);
        if (m == null) return null;
        try {
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
