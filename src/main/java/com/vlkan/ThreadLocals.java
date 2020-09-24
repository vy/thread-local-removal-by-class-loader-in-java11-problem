package com.vlkan;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public enum ThreadLocals {;

    private static final Logger LOGGER = LogManager.getLogger(ThreadLocals.class);

    private static final Field THREAD_LOCALS_FIELD;

    private static final Field INHERITABLE_THREAD_LOCALS_FIELD;

    private static final Field TABLE_FIELD;

    private static final Field VALUE_FIELD;

    private static final Method REMOVE_METHOD;

    private static final Method EXPUNGE_STALE_ENTRIES_METHOD;

    static {
        try {

            THREAD_LOCALS_FIELD = Thread.class.getDeclaredField("threadLocals");
            THREAD_LOCALS_FIELD.setAccessible(true);

            INHERITABLE_THREAD_LOCALS_FIELD = Thread.class.getDeclaredField("inheritableThreadLocals");
            INHERITABLE_THREAD_LOCALS_FIELD.setAccessible(true);

            Class<?> threadLocalMapClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
            TABLE_FIELD = threadLocalMapClass.getDeclaredField("table");
            TABLE_FIELD.setAccessible(true);

            REMOVE_METHOD = threadLocalMapClass.getDeclaredMethod("remove", ThreadLocal.class);
            REMOVE_METHOD.setAccessible(true);

            EXPUNGE_STALE_ENTRIES_METHOD = threadLocalMapClass.getDeclaredMethod("expungeStaleEntries");
            EXPUNGE_STALE_ENTRIES_METHOD.setAccessible(true);

            Class<?> threadLocalMapEntryClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap$Entry");
            VALUE_FIELD = threadLocalMapEntryClass.getDeclaredField("value");
            VALUE_FIELD.setAccessible(true);

        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    /**
     * Remove thread locals whose referenced values were created by the given class loader.
     */
    public static void removeThreadLocalsForClassLoader(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        Thread[] threads = collectThreads();
        for (Thread thread : threads) {
            if (thread != null) {
                removeThreadLocalsForClassLoader(classLoader, thread);
            }
        }
    }

    static List<ThreadLocalEntry> collectThreadLocals(Thread thread) {
        try {

            // Collect thread locals.
            Object threadLocalMap = getThreadLocalMap(thread);
            List<ThreadLocalEntry> threadLocals = collectThreadLocals(threadLocalMap);

            // Collect inheritable thread locals.
            Object inheritableThreadLocalMap = getInheritableThreadLocalMap(thread);
            List<ThreadLocalEntry> inheritableThreadLocals = collectThreadLocals(inheritableThreadLocalMap);

            // Combine collected thread locals.
            if (threadLocals.isEmpty() && inheritableThreadLocals.isEmpty()) {
                return Collections.emptyList();
            } else if (threadLocals.isEmpty()) {
                return inheritableThreadLocals;
            } else if (inheritableThreadLocals.isEmpty()) {
                return threadLocals;
            } else {
                List<ThreadLocalEntry> combinedThreadLocals = new ArrayList<>(threadLocals);
                combinedThreadLocals.addAll(inheritableThreadLocals);
                return combinedThreadLocals;
            }

        } catch (Exception error) {
            throw new RuntimeException("failed collecting thread locals", error);
        }
    }

    private static void removeThreadLocalsForClassLoader(ClassLoader classLoader, Thread thread) {
        try {

            // Remove thread local map entries.
            Object threadLocalMap = getThreadLocalMap(thread);
            removeFromThreadLocalMap(classLoader, thread, threadLocalMap);

            // Remove inheritable thread local map entries.
            Object inheritableThreadLocalMap = getInheritableThreadLocalMap(thread);
            removeFromThreadLocalMap(classLoader, thread, inheritableThreadLocalMap);

        } catch (Exception error) {
            throw new RuntimeException("failed removing thread locals for the thread: " + thread, error);
        }
    }

    @Nullable
    private static Object getThreadLocalMap(Thread thread) throws IllegalAccessException {
        return THREAD_LOCALS_FIELD.get(thread);
    }

    @Nullable
    private static Object getInheritableThreadLocalMap(Thread thread) throws IllegalAccessException {
        return INHERITABLE_THREAD_LOCALS_FIELD.get(thread);
    }

    private static void removeFromThreadLocalMap(
            ClassLoader classLoader,
            Thread thread,
            @Nullable Object threadLocalMap
    ) throws IllegalAccessException, InvocationTargetException {

        // Short-circuit if the map is null.
        if (threadLocalMap == null) {
            return;
        }

        // Now  we will remove the leftovers.
        List<ThreadLocalEntry> threadLocalEntries = collectThreadLocals(threadLocalMap);
        for (ThreadLocalEntry threadLocalEntry : threadLocalEntries) {
            boolean valueLoadedByClassLoader = isValueLoadedByClassLoader(classLoader, threadLocalEntry.getValue());
            if (valueLoadedByClassLoader) {
                logThreadLocalRemoval(threadLocalEntry, thread);
                REMOVE_METHOD.invoke(threadLocalMap, threadLocalEntry.getThreadLocal());
            }
        }

    }

    private static void logThreadLocalRemoval(ThreadLocalEntry threadLocalEntry, Thread thread) {
        if (LOGGER.isDebugEnabled()) {
            Object value = threadLocalEntry.getValue();
            LOGGER.debug(
                    "removing thread local '{}' (type={}, valueType={}, value='{}') of thread '{}'",
                    stringify(threadLocalEntry.getThreadLocal()),                       // thread local
                    getPrettyClassName(threadLocalEntry.getThreadLocal().getClass()),   // thread local type
                    value != null ? getPrettyClassName(value.getClass()) : null,        // thread local value type
                    stringify(value),                                                   // thread local value
                    thread.getName());                                                  // thread
        }
    }

    private static List<ThreadLocalEntry> collectThreadLocals(
            @Nullable Object threadLocalMap
    ) throws IllegalAccessException, InvocationTargetException {

        // Short-circuit on null input.
        if (threadLocalMap == null) {
            return Collections.emptyList();
        }

        // First we make the `ThreadLocal` map do a cleanup sweep, so that we don't store stale entries.
        EXPUNGE_STALE_ENTRIES_METHOD.invoke(threadLocalMap);

        // Get a reference to the array holding the thread local variables inside the
        // `ThreadLocalMap` of the current thread.
        Object[] table = (Object[]) TABLE_FIELD.get(threadLocalMap);

        // Collect thread locals from the table entries.
        List<ThreadLocalEntry> threadLocals = new ArrayList<>();
        for (Object tableEntry : table) {
            if (tableEntry != null) {
                @SuppressWarnings("unchecked")
                ThreadLocal<?> threadLocal = ((Reference<ThreadLocal<?>>) tableEntry).get();
                if (threadLocal != null) {
                    Object value = VALUE_FIELD.get(tableEntry);
                    ThreadLocalEntry threadLocalEntry = new ThreadLocalEntry(threadLocal, value);
                    threadLocals.add(threadLocalEntry);
                }
            }
        }
        return threadLocals;

    }

    private static boolean isValueLoadedByClassLoader(ClassLoader classLoader, @Nullable Object value) {

        if (value == null) {
            return false;
        }

        Class<?> clazz = value instanceof Class
                ? (Class<?>) value
                : value.getClass();

        ClassLoader valueClassLoader = clazz.getClassLoader();
        while (valueClassLoader != null) {
            if (valueClassLoader == classLoader) {
                return true;
            }
            valueClassLoader = valueClassLoader.getParent();
        }
        return false;

    }

    /**
     * The list of threads accessible through the current thread group and its parents.
     */
    private static Thread[] collectThreads() {

        // During the execution of `ThreadGroup.enumerate` the number of active threads can increase. The `enumerate`
        // method can only copy as many threads into the array as long as it fits. Any Threads that don't fit will be
        // ignored. To reduce the change that we don't provide a big enough of array, we make the array a lot bigger.
        // Hopefully the `enumerate` method can then copy all threads in one go. If the first `enumerate` execution
        // can't copy everything into the array, because many new active threads where created in the meantime, then we
        // will generously double the array and try again.

        ThreadGroup threadGroup = findCurrentRootThreadGroup();
        int threadCountGuess = threadGroup.activeCount() + 50;
        Thread[] threads = new Thread[threadCountGuess];
        int threadCountActual = threadGroup.enumerate(threads);

        // If the actual number of threads is the same the guessed number of threads, there is a chance that we didn't
        // get all threads, so try again.
        while (threadCountActual == threadCountGuess) {
            threadCountGuess *= 2;
            threads = new Thread[threadCountGuess];
            threadCountActual = threadGroup.enumerate(threads);
        }
        return threads;

    }

    private static ThreadGroup findCurrentRootThreadGroup() {
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        while (true) {
            ThreadGroup parentThreadGroup = threadGroup.getParent();
            if (parentThreadGroup == null) {
                break;
            }
            threadGroup = parentThreadGroup;
        }
        return threadGroup;
    }

    private static String getPrettyClassName(@Nullable Class<?> clazz) {
        if (clazz == null) {
            return "null";
        }
        String name = clazz.getCanonicalName();
        if (name == null) {
            name = clazz.getName();
        }
        return name;
    }

    private static String stringify(@Nullable Object value) {
        try {
            return String.valueOf(value);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    static final class ThreadLocalEntry {

        private final ThreadLocal<?> threadLocal;

        @Nullable
        private final Object value;

        ThreadLocalEntry(ThreadLocal<?> threadLocal, @Nullable Object value) {
            this.threadLocal = threadLocal;
            this.value = value;
        }

        private ThreadLocal<?> getThreadLocal() {
            return threadLocal;
        }

        @Nullable
        Object getValue() {
            return value;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (object == null || getClass() != object.getClass()) return false;
            ThreadLocalEntry that = (ThreadLocalEntry) object;
            return threadLocal.equals(that.threadLocal) &&
                    Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(threadLocal, value);
        }

    }

}
