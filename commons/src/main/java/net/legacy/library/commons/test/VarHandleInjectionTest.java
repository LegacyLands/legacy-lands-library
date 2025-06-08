package net.legacy.library.commons.test;

import net.legacy.library.commons.injector.VarHandleReflectionInjector;
import net.legacy.library.commons.injector.annotation.VarHandleAutoInjection;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.lang.invoke.VarHandle;

/**
 * Test class for VarHandle reflection injector, validating core injection logic and exception handling.
 *
 * <p>This test class focuses on the critical VarHandle injection mechanism in
 * {@link VarHandleReflectionInjector}, including field access validation, annotation processing,
 * and error handling for various edge cases like invalid field names and security constraints.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@ModuleTest(
        testName = "varhandle-injection-test",
        description = "Tests VarHandle reflection injector core injection logic and exception handling",
        tags = {"commons", "injection", "varhandle", "reflection"},
        priority = 1,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class VarHandleInjectionTest {
    /**
     * Test static field for VarHandle injection verification
     */
    private static String testField = "initial";

    /**
     * VarHandle automatically injected through annotation
     */
    @VarHandleAutoInjection(fieldName = "testField")
    private static VarHandle testFieldHandle;

    /**
     * Another test field
     */
    private static int numberField = 42;

    /**
     * VarHandle automatically injected through annotation (for int field)
     */
    @VarHandleAutoInjection(fieldName = "numberField")
    private static VarHandle numberFieldHandle;

    /**
     * Tests basic VarHandle injection functionality for both String and primitive types.
     */
    public static boolean testBasicInjection() {
        try {
            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            // Execute injection
            injector.inject(VarHandleInjectionTest.class);

            // Verify VarHandle injection success
            boolean stringHandleInjected = testFieldHandle != null;
            boolean intHandleInjected = numberFieldHandle != null;

            if (!stringHandleInjected || !intHandleInjected) {
                TestLogger.logFailure("commons",
                        "Basic injection failed: stringHandle=" + stringHandleInjected + ", intHandle=" + intHandleInjected);
                return false;
            }

            // Verify VarHandle field access functionality
            String originalValue = (String) testFieldHandle.get();
            boolean correctInitialValue = "initial".equals(originalValue);

            // Test value modification and retrieval
            testFieldHandle.set("modified");
            String newValue = (String) testFieldHandle.get();
            boolean modificationWorked = "modified".equals(newValue);

            // Restore original value for other tests
            testFieldHandle.set("initial");

            TestLogger.logInfo("commons",
                    "Basic injection test: initialValue=" + correctInitialValue + ", modification=" + modificationWorked);

            return correctInitialValue && modificationWorked;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Basic injection test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests VarHandle operations on primitive int fields including compareAndSet functionality.
     */
    public static boolean testIntFieldInjection() {
        try {
            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            // Execute injection
            injector.inject(VarHandleInjectionTest.class);

            // Verify int field VarHandle injection
            boolean handleExists = numberFieldHandle != null;
            if (!handleExists) {
                TestLogger.logFailure("commons", "Int field injection failed: handle is null");
                return false;
            }

            // Test initial value retrieval
            int originalValue = (int) numberFieldHandle.get();
            boolean correctInitialValue = originalValue == 42;

            // Test value modification
            numberFieldHandle.set(100);
            int newValue = (int) numberFieldHandle.get();
            boolean setOperationWorked = newValue == 100;

            // Test atomic compareAndSet operation
            boolean casSuccess = numberFieldHandle.compareAndSet(100, 200);
            int finalValue = (int) numberFieldHandle.get();
            boolean casWorked = casSuccess && finalValue == 200;

            // Restore original value for other tests
            numberFieldHandle.set(42);

            TestLogger.logInfo("commons",
                    "Int field injection test: initialValue=" + correctInitialValue + ", setValue=" + setOperationWorked + ", compareAndSet=" + casWorked);

            return correctInitialValue && setOperationWorked && casWorked;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Int field injection test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests exception handling for invalid field names that don't exist in the target class.
     */
    public static boolean testInvalidFieldName() {
        try {
            // Create a test class with invalid field name annotation
            class InvalidFieldTest {
                @VarHandleAutoInjection(fieldName = "nonExistentField")
                private static VarHandle invalidHandle;
            }

            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            try {
                injector.inject(InvalidFieldTest.class);
                TestLogger.logFailure("commons", "Invalid field name test failed: expected IllegalStateException");
                return false; // Should throw exception
            } catch (IllegalStateException expected) {
                // Verify exception contains expected message
                boolean correctExceptionMessage = expected.getMessage().contains("Failed to inject VarHandle for field");

                TestLogger.logInfo("commons",
                        "Invalid field name test: caught expected exception=" + correctExceptionMessage + ", message=" + expected.getMessage());

                return correctExceptionMessage;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Invalid field name test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that fields without VarHandleAutoInjection annotations are properly ignored during injection.
     */
    public static boolean testNoAnnotationIgnored() {
        try {
            // Create a test class without annotations
            class NoAnnotationTest {
                private static VarHandle shouldBeNull = null;
                private static String normalField = "unchanged";
            }

            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            // Store original values for verification
            VarHandle originalHandle = NoAnnotationTest.shouldBeNull;
            String originalField = NoAnnotationTest.normalField;

            // Execute injection - should not affect non-annotated fields
            injector.inject(NoAnnotationTest.class);

            // Verify fields remain unchanged
            boolean handleUnchanged = NoAnnotationTest.shouldBeNull == originalHandle;
            boolean fieldUnchanged = "unchanged".equals(NoAnnotationTest.normalField);

            TestLogger.logInfo("commons",
                    "No annotation ignored test: handleUnchanged=" + handleUnchanged + ", fieldUnchanged=" + fieldUnchanged);

            return handleUnchanged && fieldUnchanged;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "No annotation ignored test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests VarHandle injection and access on private fields to verify security boundary handling.
     */
    public static boolean testPrivateFieldInjection() {
        try {
            // Create test class with private field
            class PrivateFieldTest {
                private static String privateField = "private";

                @VarHandleAutoInjection(fieldName = "privateField")
                private static VarHandle privateFieldHandle;
            }

            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            // Execute injection on private field
            injector.inject(PrivateFieldTest.class);

            // Verify private field VarHandle injection success
            boolean handleInjected = PrivateFieldTest.privateFieldHandle != null;
            if (!handleInjected) {
                TestLogger.logFailure("commons", "Private field injection failed: handle is null");
                return false;
            }

            // Verify private field access through VarHandle
            String retrievedValue = (String) PrivateFieldTest.privateFieldHandle.get();
            boolean correctValue = "private".equals(retrievedValue);

            // Test private field modification
            PrivateFieldTest.privateFieldHandle.set("modified-private");
            String modifiedValue = (String) PrivateFieldTest.privateFieldHandle.get();
            boolean modificationWorked = "modified-private".equals(modifiedValue);

            TestLogger.logInfo("commons",
                    "Private field injection test: injection=" + true + ", access=" + correctValue + ", modification=" + modificationWorked);

            return correctValue && modificationWorked;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Private field injection test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests idempotency of multiple consecutive injections on the same class.
     */
    public static boolean testMultipleInjectionIdempotency() {
        try {
            VarHandleReflectionInjector injector = new VarHandleReflectionInjector();

            // Reset field to null for clean test state
            testFieldHandle = null;
            numberFieldHandle = null;

            // First injection
            injector.inject(VarHandleInjectionTest.class);
            VarHandle firstStringHandle = testFieldHandle;
            VarHandle firstIntHandle = numberFieldHandle;

            // Second injection on same class
            injector.inject(VarHandleInjectionTest.class);
            VarHandle secondStringHandle = testFieldHandle;
            VarHandle secondIntHandle = numberFieldHandle;

            // Verify all injections produced valid handles
            boolean firstInjectionValid = firstStringHandle != null && firstIntHandle != null;
            boolean secondInjectionValid = secondStringHandle != null && secondIntHandle != null;

            if (!firstInjectionValid || !secondInjectionValid) {
                TestLogger.logFailure("commons",
                        "Multiple injection idempotency failed: first=" + firstInjectionValid + ", second=" + secondInjectionValid);
                return false;
            }

            // Verify functionality still works after multiple injections
            String stringValue = (String) testFieldHandle.get();
            int intValue = (int) numberFieldHandle.get();
            boolean functionalityWorks = "initial".equals(stringValue) && intValue == 42;

            TestLogger.logInfo("commons",
                    "Multiple injection idempotency test: functionality=" + functionalityWorks + ", stringValue=" + stringValue + ", intValue=" + intValue);

            return functionalityWorks;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Multiple injection idempotency test failed: " + exception.getMessage());
            return false;
        }
    }
}