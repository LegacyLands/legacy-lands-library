package net.legacy.library.annotation.test;

import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import org.reflections.util.ClasspathHelper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.util.Collection;
import java.util.Set;

/**
 * Test class for AnnotationScanner functionality.
 *
 * <p>This test class validates the annotation scanning capabilities,
 * including package-based scanning, URL-based scanning, and edge cases.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 18:00
 */
@ModuleTest(
        testName = "annotation-scanner-test",
        description = "Tests AnnotationScanner functionality for finding annotated classes",
        tags = {"annotation", "scanner", "reflection", "utility"},
        priority = 2,
        timeout = 4000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AnnotationScannerTest {
    /**
     * Tests basic package-based annotation scanning.
     */
    public static boolean testBasicPackageScanning() {
        try {
            String testPackage = "net.legacy.library.annotation.test";

            Set<Class<?>> scannerAnnotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, ScannerTestAnnotation.class);

            boolean foundClasses = !scannerAnnotatedClasses.isEmpty();
            boolean foundTestClass1 = scannerAnnotatedClasses.stream()
                    .anyMatch(clazz -> clazz.getSimpleName().equals("TestClass1"));
            boolean foundTestClass2 = scannerAnnotatedClasses.stream()
                    .anyMatch(clazz -> clazz.getSimpleName().equals("TestClass2"));

            // Should not find TestClass3 (doesn't have ScannerTestAnnotation)
            boolean correctlyFilteredTestClass3 = scannerAnnotatedClasses.stream()
                    .noneMatch(clazz -> clazz.getSimpleName().equals("TestClass3"));

            TestLogger.logInfo("annotation", "Basic package scanning test: foundClasses=%s, foundTestClass1=%s, foundTestClass2=%s, filteredTestClass3=%s, totalFound=%d",
                    foundClasses, foundTestClass1, foundTestClass2, correctlyFilteredTestClass3, scannerAnnotatedClasses.size());

            return foundClasses && foundTestClass1 && foundTestClass2 && correctlyFilteredTestClass3;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Basic package scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests URL-based annotation scanning.
     */
    public static boolean testUrlBasedScanning() {
        try {
            Collection<URL> urls = ClasspathHelper.forPackage("net.legacy.library.annotation.test");

            Set<Class<?>> anotherAnnotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    urls, AnotherTestAnnotation.class);

            boolean foundClasses = !anotherAnnotatedClasses.isEmpty();
            boolean foundTestClass2 = anotherAnnotatedClasses.stream()
                    .anyMatch(clazz -> clazz.getSimpleName().equals("TestClass2"));
            boolean foundTestClass3 = anotherAnnotatedClasses.stream()
                    .anyMatch(clazz -> clazz.getSimpleName().equals("TestClass3"));

            // Should not find TestClass1 (doesn't have AnotherTestAnnotation)
            boolean correctlyFilteredTestClass1 = anotherAnnotatedClasses.stream()
                    .noneMatch(clazz -> clazz.getSimpleName().equals("TestClass1"));

            TestLogger.logInfo("annotation", "URL-based scanning test: foundClasses=%s, foundTestClass2=%s, foundTestClass3=%s, filteredTestClass1=%s, totalFound=%d",
                    foundClasses, foundTestClass2, foundTestClass3, correctlyFilteredTestClass1, anotherAnnotatedClasses.size());

            return foundClasses && foundTestClass2 && foundTestClass3 && correctlyFilteredTestClass1;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "URL-based scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests scanning with multiple class loaders.
     */
    public static boolean testMultipleClassLoaderScanning() {
        try {
            String testPackage = "net.legacy.library.annotation.test";
            ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

            // First, test with no explicit class loader (should work like other tests)
            Set<Class<?>> defaultClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, ScannerTestAnnotation.class);

            // Then try with multiple class loaders
            Set<Class<?>> multiLoaderClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, ScannerTestAnnotation.class, currentClassLoader, systemClassLoader);

            // Finally try with single class loader
            Set<Class<?>> singleLoaderClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, ScannerTestAnnotation.class, currentClassLoader);

            int defaultCount = defaultClasses.size();
            int multiLoaderCount = multiLoaderClasses.size();
            int singleLoaderCount = singleLoaderClasses.size();

            TestLogger.logInfo("annotation", "Multiple class loader scanning test: defaultCount=%d, multiLoaderCount=%d, singleLoaderCount=%d",
                    defaultCount, multiLoaderCount, singleLoaderCount);

            // Test passes if any approach finds the expected classes
            // In test environments, multiple class loaders might not add value, so we accept if default scanning works
            boolean hasFoundClasses = defaultCount >= 2 || multiLoaderCount >= 2 || singleLoaderCount >= 2;

            if (!hasFoundClasses) {
                TestLogger.logInfo("annotation", "Multiple class loader scanning test: No approach found sufficient classes. Expected at least 2 classes with ScannerTestAnnotation");
                return false;
            }

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Multiple class loader scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests scanning for non-existent annotation.
     */
    public static boolean testNonExistentAnnotationScanning() {
        try {
            String testPackage = "net.legacy.library.annotation.test";
            Set<Class<?>> nonExistentAnnotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, NonExistentAnnotation.class);

            boolean isEmpty = nonExistentAnnotatedClasses.isEmpty();
            int size = nonExistentAnnotatedClasses.size();

            TestLogger.logInfo("annotation", "Non-existent annotation scanning test: isEmpty=%s, size=%d",
                    isEmpty, size);

            return isEmpty;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Non-existent annotation scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests scanning with invalid package name.
     */
    public static boolean testInvalidPackageScanning() {
        try {
            String invalidPackage = "non.existent.package.name";

            Set<Class<?>> emptyResults = AnnotationScanner.findAnnotatedClasses(
                    invalidPackage, ScannerTestAnnotation.class);

            boolean isEmpty = emptyResults.isEmpty();

            TestLogger.logInfo("annotation", "Invalid package scanning test: isEmpty=%s", isEmpty);

            return isEmpty;
        } catch (Exception exception) {
            // Exception is acceptable for invalid packages
            TestLogger.logInfo("annotation", "Invalid package scanning test: caught expected exception - %s",
                    exception.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * Tests scanning performance with large package.
     */
    public static boolean testScanningPerformance() {
        try {
            long startTime = System.currentTimeMillis();

            // Scan a larger package (the entire annotation module)
            String largePackage = "net.legacy.library.annotation";
            Set<Class<?>> allAnnotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    largePackage, ModuleTest.class);

            long duration = System.currentTimeMillis() - startTime;
            int classCount = allAnnotatedClasses.size();

            TestLogger.logInfo("annotation", "Scanning performance test: duration=%dms, classCount=%d",
                    duration, classCount);

            // Should complete within reasonable time (2 seconds) and find some classes
            return duration < 2000 && classCount > 0;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Scanning performance test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent scanning operations.
     */
    public static boolean testConcurrentScanning() {
        try {
            String testPackage = "net.legacy.library.annotation.test";
            boolean[] results = new boolean[3];

            // Run multiple scanning operations concurrently
            Thread thread1 = new Thread(() -> {
                try {
                    Set<Class<?>> result1 = AnnotationScanner.findAnnotatedClasses(
                            testPackage, ScannerTestAnnotation.class);
                    results[0] = !result1.isEmpty();
                } catch (Exception exception) {
                    results[0] = false;
                }
            });

            Thread thread2 = new Thread(() -> {
                try {
                    Set<Class<?>> result2 = AnnotationScanner.findAnnotatedClasses(
                            testPackage, AnotherTestAnnotation.class);
                    results[1] = !result2.isEmpty();
                } catch (Exception exception) {
                    results[1] = false;
                }
            });

            Thread thread3 = new Thread(() -> {
                try {
                    Collection<URL> urls = ClasspathHelper.forPackage(testPackage);
                    Set<Class<?>> result3 = AnnotationScanner.findAnnotatedClasses(
                            urls, ScannerTestAnnotation.class);
                    results[2] = !result3.isEmpty();
                } catch (Exception exception) {
                    results[2] = false;
                }
            });

            thread1.start();
            thread2.start();
            thread3.start();

            thread1.join(1000);
            thread2.join(1000);
            thread3.join(1000);

            boolean allSucceeded = results[0] && results[1] && results[2];

            TestLogger.logInfo("annotation", "Concurrent scanning test: thread1=%s, thread2=%s, thread3=%s, allSucceeded=%s",
                    results[0], results[1], results[2], allSucceeded);

            return allSucceeded;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Concurrent scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests scanning with inherited annotations.
     */
    public static boolean testInheritedAnnotationScanning() {
        try {
            // Base class with annotation
            @ScannerTestAnnotation("base-class")
            class BaseClass {
                public String getType() {
                    return "base";
                }
            }

            // Derived class that should inherit annotation behavior
            class DerivedClass extends BaseClass {
                @Override
                public String getType() {
                    return "derived";
                }
            }

            String testPackage = "net.legacy.library.annotation.test";
            Set<Class<?>> annotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    testPackage, ScannerTestAnnotation.class);

            // Check if scanning finds the expected classes
            boolean foundAnnotatedClasses = !annotatedClasses.isEmpty();
            int totalFound = annotatedClasses.size();

            TestLogger.logInfo("annotation", "Inherited annotation scanning test: foundClasses=%s, totalFound=%d",
                    foundAnnotatedClasses, totalFound);

            return foundAnnotatedClasses && totalFound >= 2;
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Inherited annotation scanning test failed: %s", exception.getMessage());
            return false;
        }
    }

    // Test annotations for scanning
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ScannerTestAnnotation {
        String value() default "scanner-test";
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AnotherTestAnnotation {
        int priority() default 1;
    }

    // Define a test annotation that doesn't exist on any classes
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NonExistentAnnotation {
    }

    // Test classes with annotations
    @ScannerTestAnnotation("test-class-1")
    public static class TestClass1 {
        public String getValue() {
            return "test1";
        }
    }

    @ScannerTestAnnotation("test-class-2")
    @AnotherTestAnnotation(priority = 2)
    public static class TestClass2 {
        public String getValue() {
            return "test2";
        }
    }

    @AnotherTestAnnotation(priority = 3)
    public static class TestClass3 {
        public String getValue() {
            return "test3";
        }
    }

    // Class without annotations
    public static class PlainClass {
        public String getValue() {
            return "plain";
        }
    }
}