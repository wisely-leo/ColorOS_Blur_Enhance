package com.shortcutblur;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class Reflect {

    private static final int MAX_DEPTH = 16;

    private static final int MAX_CACHE = 512;

    private static final Map<String, Method> METHOD_CACHE = newCache();
    private static final Map<String, Field> FIELD_CACHE = newCache();
    private static final Map<String, Constructor<?>> CTOR_CACHE = newCache();
    private static final Map<String, Class<?>> CLASS_CACHE = newCache();

    private Reflect() {}

    private static <V> Map<String, V> newCache() {
        return java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, V>(64, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                        return size() > MAX_CACHE;
                    }
                });
    }

    private static <V> V cacheGet(Map<String, V> cache, String key) {
        return cache.get(key);
    }

    private static <V> void cachePut(Map<String, V> cache, String key, V value) {
        cache.put(key, value);
    }

    public static Method method(Class<?> c, String name, int paramCount) {
        if (c == null || name == null) return null;
        String key = c.getName() + "#n:" + name + "/" + paramCount;
        Method hit = cacheGet(METHOD_CACHE, key);
        if (hit != null) return hit;
        Class<?> k = c;
        int depth = 0;
        while (k != null && depth++ < MAX_DEPTH) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) {
                        m.setAccessible(true);
                        cachePut(METHOD_CACHE, key, m);
                        return m;
                    }
                }
            } catch (Throwable ignored) { }
            k = k.getSuperclass();
        }
        return null;
    }

    public static Method method(Class<?> c, String name, Class<?>... paramTypes) {
        if (c == null || name == null) return null;
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name).append('(');
        if (paramTypes != null) {
            for (Class<?> p : paramTypes) sb.append(p == null ? "?" : p.getName()).append(',');
        }
        String key = sb.append(')').toString();
        Method hit = cacheGet(METHOD_CACHE, key);
        if (hit != null) return hit;
        try {
            Method m = c.getMethod(name, paramTypes);
            m.setAccessible(true);
            cachePut(METHOD_CACHE, key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Field field(Class<?> c, String name) {
        if (c == null || name == null) return null;
        String key = c.getName() + "#" + name;
        Field hit = cacheGet(FIELD_CACHE, key);
        if (hit != null) return hit;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                cachePut(FIELD_CACHE, key, f);
                return f;
            } catch (NoSuchFieldException nsf) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    public static Object newInstance(Class<?> c) {
        if (c == null) return null;
        String key = c.getName();
        Constructor<?> ctor = cacheGet(CTOR_CACHE, key);
        try {
            if (ctor == null) {
                ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                cachePut(CTOR_CACHE, key, ctor);
            }
            return ctor.newInstance();
        } catch (Throwable t) {
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
        String key = name + "@" + System.identityHashCode(loader);
        Class<?> cached = cacheGet(CLASS_CACHE, key);
        if (cached != null) return cached;
        try {
            Class<?> c = Class.forName(name, false, loader);
            cachePut(CLASS_CACHE, key, c);
            return c;
        } catch (Throwable t) {
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
}
