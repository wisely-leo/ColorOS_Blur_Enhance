package com.shortcutblur;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射工具：统一收敛裸反射，并带缓存。
 *
 * <p>本类位于「安装期」与「每次弹窗 / 每帧动画」两类调用路径上，故缓存实现必须
 * 满足两个硬性要求：<b>读路径无锁</b>、<b>未命中不重复全量扫描</b>。
 */
public final class Reflect {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_CACHE = 512;

    // 发热优化：原实现用 Collections.synchronizedMap(new LinkedHashMap(..., accessOrder=true))。
    // accessOrder=true 会让每次 get() 都变成一次结构修改（命中项移到队尾），于是所有反射查找
    // 都被串行化在同一把锁上；而本类在热路径（每帧动画 / 每次弹窗 / 每秒内容变化）里被反复调用，
    // 会和其它线程互相阻塞。改为 ConcurrentHashMap：读路径彻底无锁。
    private static final Map<MKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    /** 带参数类型的方法查找（低频路径，保留字符串 key 语义）。 */
    private static final Map<String, Method> TYPED_CACHE = new ConcurrentHashMap<>();

    // 发热优化：负缓存。原实现「找不到」时直接 return null 且不记录，于是每次调用都要重新
    // 沿继承链做 MAX_DEPTH 次 getDeclaredMethods()（每次都返回新数组）全扫描。
    // 在方法与字段真的不存在（跨 ROM / 版本差异）时，这是热路径上最坏的一类开销。
    private static final Set<MKey> MISS_METHOD = ConcurrentHashMap.newKeySet();
    private static final Set<FKey> MISS_FIELD = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> MISS_CTOR = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISS_TYPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISS_CLASS = ConcurrentHashMap.newKeySet();

    private Reflect() {}

    /** 方法缓存键：避免每次查找都拼 "类名#方法名/参数个数" 字符串（每次调用都产生临时 String）。 */
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

    /** 字段缓存键（同上，避免字符串 key）。 */
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
        // 容量控制：读路径无锁，因此不做逐条淘汰（那需要额外状态），
        // 只在超过上限时整体清空一次——重建成本远低于持续膨胀。
        if (cache.size() >= MAX_CACHE) cache.clear();
        cache.put(key, value);
    }

    private static <K> void putMiss(Set<K> miss, K key) {
        if (miss.size() >= MAX_CACHE) miss.clear();
        miss.add(key);
    }

    /** 按「方法名 + 参数个数」查找（沿继承链最多 {@link #MAX_DEPTH} 层）。 */
    public static Method method(Class<?> c, String name, int paramCount) {
        if (c == null || name == null) return null;
        MKey key = new MKey(c, name, paramCount);
        Method hit = METHOD_CACHE.get(key);
        if (hit != null) return hit;
        if (MISS_METHOD.contains(key)) return null;   // 负缓存命中：快速失败，不再扫继承链
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
                // 反射受限（极少见）：不记负缓存，留待下次重试
                aborted = true;
            }
            k = k.getSuperclass();
        }
        if (!aborted) putMiss(MISS_METHOD, key);
        return null;
    }

    /** 按「方法名 + 参数类型」查找（走 {@code getMethod} 语义）。 */
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

    /** 按字段名查找（沿继承链上溯到 Object 之前）。 */
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
                // 反射受限：不记负缓存，留待下次重试
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
            if (ctor == null) putMiss(MISS_CTOR, c);   // 只有「没有无参构造」才记负缓存
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
        // 校验 loader：同一进程里同名类一般唯一，但插件场景下可能有多份，
        // 命中后必须确认来自期望的 loader，避免串类。
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

    /**
     * 读取静态字段的 int 值；失败返回 {@code def}。
     * 用于替代各 Hook 内重复的 {@code Class.forName(...).getField(...).getInt(null)} 样板。
     */
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

    /**
     * 调用无参静态方法（如 {@code ActivityThread.currentApplication()}）；失败返回 null。
     */
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