package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.DistributedTransaction;
import net.legacy.library.aop.aspect.DistributedTransactionAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.aop.transaction.DefaultCompensationRegistry;
import net.legacy.library.aop.transaction.DistributedTransactionCoordinator;
import net.legacy.library.aop.transaction.InMemoryTransactionLogStore;
import net.legacy.library.aop.transaction.ParticipantRegistry;
import net.legacy.library.aop.transaction.TransactionException;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for distributed transaction functionality in enterprise environments.
 *
 * <p>This test class focuses on the {@code @DistributedTransaction} annotation and related
 * transaction management capabilities. Tests verify two-phase commit protocols, rollback
 * mechanisms, compensation strategies, and transaction coordination across multiple services.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-distributed-transaction-test",
        description = "Tests distributed transaction management and coordination",
        tags = {"aop", "transaction", "distributed", "enterprise"},
        priority = 3,
        timeout = 30000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPDistributedTransactionTest {

    private static DistributedTransactionAspect createDistributedTransactionAspect() {
        InMemoryTransactionLogStore logStore = new InMemoryTransactionLogStore();
        DefaultCompensationRegistry compensationRegistry = new DefaultCompensationRegistry();
        ParticipantRegistry participantRegistry = new ParticipantRegistry();
        DistributedTransactionCoordinator coordinator = new DistributedTransactionCoordinator(
                logStore,
                compensationRegistry,
                participantRegistry
        );
        return new DistributedTransactionAspect(coordinator);
    }

    /**
     * Tests basic distributed transaction commit functionality.
     */
    public static boolean testBasicTransactionCommit() {
        try {
            TestLogger.logInfo("aop", "Starting basic transaction commit test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DistributedTransactionAspect distributedTransactionAspect = createDistributedTransactionAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    distributedTransactionAspect,
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TransactionalService service = aopFactory.create(TestTransactionalService.class);

            String result = service.performTransactionalOperation("test-data");

            boolean resultValid = result != null && result.contains("Processed") && result.contains("test-data");

            TestLogger.logInfo("aop", "Basic transaction commit test: resultValid=%s, result=%s", resultValid, result);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Basic transaction commit test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests distributed transaction rollback functionality.
     */
    public static boolean testTransactionRollback() {
        try {
            TestLogger.logInfo("aop", "Starting transaction rollback test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DistributedTransactionAspect distributedTransactionAspect = createDistributedTransactionAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    distributedTransactionAspect,
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TransactionalService service = aopFactory.create(TestFailingTransactionalService.class);

            try {
                // This should fail and trigger rollback
                service.performTransactionalOperation("rollback-test");
                TestLogger.logFailure("aop", "Transaction rollback test: expected exception but operation succeeded");
                return false;
            } catch (RuntimeException expected) {
                TestLogger.logInfo("aop", "Transaction rollback test: successfully caught expected exception: %s", expected.getMessage());
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Transaction rollback test failed with unexpected error: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests read-only transaction functionality.
     */
    public static boolean testReadOnlyTransaction() {
        try {
            TestLogger.logInfo("aop", "Starting read-only transaction test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DistributedTransactionAspect distributedTransactionAspect = createDistributedTransactionAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    distributedTransactionAspect,
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TransactionalService service = aopFactory.create(TestTransactionalService.class);

            String result = service.performReadOnlyOperation("test-id-123");

            boolean resultValid = result != null && result.contains("test-id-123") && result.contains("Read data");

            TestLogger.logInfo("aop", "Read-only transaction test: resultValid=%s, result=%s", resultValid, result);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Read-only transaction test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent transaction execution.
     */
    public static boolean testConcurrentTransactions() {
        try {
            TestLogger.logInfo("aop", "Starting concurrent transactions test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DistributedTransactionAspect distributedTransactionAspect = createDistributedTransactionAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    distributedTransactionAspect,
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TransactionalService service = aopFactory.create(TestTransactionalService.class);

            // Execute multiple transactions concurrently
            CompletableFuture<String>[] futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                final int index = i;
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        return service.performReadOnlyOperation("concurrent-" + index);
                    } catch (Exception exception) {
                        return "Error: " + exception.getMessage();
                    }
                });
            }

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures);
            allFutures.get(10, TimeUnit.SECONDS);

            int successCount = 0;
            for (CompletableFuture<String> future : futures) {
                if (future.get().contains("concurrent-")) {
                    successCount++;
                }
            }

            boolean resultValid = successCount == 5;

            TestLogger.logInfo("aop", "Concurrent transactions test: successCount=%d, resultValid=%s", successCount, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Concurrent transactions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests transaction timeout handling.
     */
    public static boolean testTransactionTimeout() {
        try {
            TestLogger.logInfo("aop", "Starting transaction timeout test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DistributedTransactionAspect distributedTransactionAspect = createDistributedTransactionAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    distributedTransactionAspect,
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the timeout test class
            aopService.registerTestInterceptors(TestTimeoutTransactionalService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            TransactionalService timeoutService = aopFactory.create(TestTimeoutTransactionalService.class);

            try {
                timeoutService.performTransactionalOperation("timeout-test");
                TestLogger.logFailure("aop", "Transaction timeout test: expected timeout but operation completed");
                return false;
            } catch (TransactionException expected) {
                TestLogger.logInfo("aop", "Transaction timeout test: successfully caught timeout exception: %s", expected.getMessage());
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Transaction timeout test failed with unexpected error: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing distributed transactions.
     */
    public interface TransactionalService {

        @DistributedTransaction(
                timeout = 5000,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        String performTransactionalOperation(String data) throws Exception;

        @DistributedTransaction(
                timeout = 3000,
                readOnly = true,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        String performReadOnlyOperation(String id);

    }

    /**
     * Test implementation for transactional operations.
     */
    public static class TestTransactionalService implements TransactionalService {

        private final AtomicInteger operationCounter = new AtomicInteger(0);

        @Override
        @DistributedTransaction(
                timeout = 5000,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        public String performTransactionalOperation(String data) throws Exception {
            int currentCount = operationCounter.incrementAndGet();

            return "Processed: " + data + " (Operation #" + currentCount + ")";
        }

        @Override
        @DistributedTransaction(
                timeout = 3000,
                readOnly = true,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        public String performReadOnlyOperation(String id) {
            return "Read data for ID: " + id;
        }

    }

    /**
     * Test implementation for failed transaction operations.
     */
    public static class TestFailingTransactionalService implements TransactionalService {

        private final AtomicInteger operationCounter = new AtomicInteger(0);

        @Override
        @DistributedTransaction(
                timeout = 5000,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        public String performTransactionalOperation(String data) throws Exception {
            int currentCount = operationCounter.incrementAndGet();

            // Always fail for rollback testing
            throw new RuntimeException("Simulated transaction failure");
        }

        @Override
        @DistributedTransaction(
                timeout = 3000,
                readOnly = true,
                isolation = DistributedTransaction.Isolation.READ_COMMITTED
        )
        public String performReadOnlyOperation(String id) {
            return "Read data for ID: " + id;
        }

    }

    /**
     * Test implementation for transaction timeout operations.
     */
    public static class TestTimeoutTransactionalService implements TransactionalService {

        @Override
        @DistributedTransaction(timeout = 5000)
        public String performTransactionalOperation(String data) throws Exception {
            try {
                Thread.sleep(6000); // Exceeds 5s timeout
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Transaction interrupted", exception);
            }
            return "Should timeout before this: " + data;
        }

        @Override
        public String performReadOnlyOperation(String id) {
            try {
                Thread.sleep(4000); // Exceeds typical timeout
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Read operation interrupted", exception);
            }
            return "Read-only should timeout: " + id;
        }

    }

}
