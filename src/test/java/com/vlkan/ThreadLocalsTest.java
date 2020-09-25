package com.vlkan;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class ThreadLocalsTest {

    @RepeatedTest(1_000)
    public void collectThreadLocals_should_work() throws Exception {
        runWithinThread(() -> {

            // Create the thread local.
            Object value = Collections.singleton(ThreadLocalRandom.current().nextInt());
            ThreadLocal<Object> threadLocal = new ThreadLocal<>();
            threadLocal.set(value);

            // Verify the collected thread locals.
            List<ThreadLocals.ThreadLocalEntry> entries =
                    ThreadLocals.collectThreadLocals(Thread.currentThread());
            ThreadLocals.ThreadLocalEntry expectedEntry =
                    new ThreadLocals.ThreadLocalEntry(threadLocal, value);
            Assertions.assertThat(entries).contains(expectedEntry);

        });
    }

    @RepeatedTest(20_000)
    public void removeThreadLocalsForClassLoader_should_work() throws Exception {

        // Populate class loaders and values.
        ClassLoader classLoader1 = new CustomClassLoader("custom-class-loader-1");
        Object value1 = CustomClass.ofClassLoader("custom-class-loader-1-value", classLoader1);
        ClassLoader classLoader2 = new CustomClassLoader("custom-class-loader-2");
        Object value2 = CustomClass.ofClassLoader("custom-class-loader-2-value", classLoader2);

        runWithinThread(() -> {

            // Create thread-local values.
            new ThreadLocal<>().set(value1);
            new ThreadLocal<>().set(value2);

            // Verify the initial state of thread-locals.
            verifyThreadLocals(
                    Stream.of(classLoader1, classLoader2).collect(Collectors.toSet()),
                    Stream.of(value1, value2).collect(Collectors.toSet()),
                    Collections.emptySet(),
                    Collections.emptySet());

            // Remove thread-locals belonging to the 1st class loader.
            ThreadLocals.removeThreadLocalsForClassLoader(classLoader1);

            // Verify the retained thread-locals.
            verifyThreadLocals(
                    Collections.singleton(classLoader2),
                    Collections.singleton(value2),
                    Collections.singleton(classLoader1),
                    Collections.singleton(value1));

        });

    }

    private static void runWithinThread(Runnable body) throws InterruptedException {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                errorRef.set(error);
            }
        });
        thread.start();
        thread.join();
        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException(error);
        }
    }

    private static void verifyThreadLocals(
            Set<ClassLoader> expectedClassLoaders,
            Set<Object> expectedValues,
            Set<ClassLoader> unexpectedClassLoaders,
            Set<Object> unexpectedValues) {

        // Verify the thread-local values.
        Set<Object> values = ThreadLocals
                .collectThreadLocals(Thread.currentThread())
                .stream()
                .map(ThreadLocals.ThreadLocalEntry::getValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Assertions
                .assertThat(values)
                .containsAll(expectedValues);
        if (!unexpectedValues.isEmpty()) {
            Assertions
                    .assertThat(values)
                    .doesNotContainAnyElementsOf(unexpectedValues);
        }

        // Verify the class loaders of thread-local values.
        Set<ClassLoader> classLoaders = values
                .stream()
                .map(Object::getClass)
                .map(Class::getClassLoader)
                .collect(Collectors.toSet());
        Assertions
                .assertThat(classLoaders)
                .containsAll(expectedClassLoaders);
        if (!unexpectedClassLoaders.isEmpty()) {
            Assertions
                    .assertThat(classLoaders)
                    .doesNotContainAnyElementsOf(unexpectedClassLoaders);
        }

    }

    // This class must be `public` to be accessible by `Class.getDeclaredConstructor()#newInstance()`.
    public static final class CustomClass {

        private final String name;

        // This ctor must be `public` to be accessible by `Class.getDeclaredConstructor()#newInstance()`.
        public CustomClass(final String name) {
            this.name = name;
        }

        // To work around `ClassCastException`s due to module mismatch, returning a plain Object.
        private static Object ofClassLoader(String name, ClassLoader classLoader) {
            try {
                @SuppressWarnings("unchecked")
                Class<CustomClass> clazz = (Class<CustomClass>) Class.forName(CustomClass.class.getName(), true, classLoader);
                return clazz.getDeclaredConstructor(String.class).newInstance(name);
            } catch (Exception error) {
                throw new RuntimeException("failed creating value: " + name, error);
            }
        }

        @Override
        public String toString() {
            return name;
        }

    }

    private static final class CustomClassLoader extends ClassLoader {

        private static final String CUSTOM_CLASS_NAME = CustomClass.class.getName();

        private static final String CUSTOM_CLASS_RESOURCE_NAME = File.separator + CUSTOM_CLASS_NAME.replace('.', File.separatorChar) + ".class";

        private final String name;

        private CustomClassLoader(String name) {
            super(CustomClassLoader.class.getClassLoader());
            this.name = name;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            return CUSTOM_CLASS_NAME.equals(name)
                    ? loadCustomClass()
                    : super.loadClass(name);
        }

        private Class<CustomClass> loadCustomClass() {
            byte[] classBytes = loadCustomClassBytes();
            @SuppressWarnings("unchecked")
            Class<CustomClass> clazz = (Class<CustomClass>) defineClass(CustomClass.class.getName(), classBytes, 0, classBytes.length);
            return clazz;
        }

        private byte[] loadCustomClassBytes() {
            try (InputStream inputStream = CustomClassLoader.class.getResourceAsStream(CUSTOM_CLASS_RESOURCE_NAME)) {
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                int readByteCount;
                while ((readByteCount = inputStream.read()) != -1) {
                    byteStream.write(readByteCount);
                }
                return byteStream.toByteArray();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public String toString() {
            return name;
        }

    }

}
